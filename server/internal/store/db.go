// Package store 封装 SQLite 存储层。
//
// 选型说明：用 modernc.org/sqlite（纯 Go 实现，无需 cgo），
// 这样交叉编译到 x86_64 / aarch64 时不需要目标平台的 C 工具链，
// 是 fpk 单文件分发的前提。
package store

import (
	"context"
	"database/sql"
	"fmt"
	"log/slog"
	"strings"
	"time"

	_ "modernc.org/sqlite"
)

// Store 是数据访问入口。
type Store struct {
	db *sql.DB
}

// Open 打开（或创建）数据库并执行建表。
func Open(path string) (*Store, error) { return openWithBusyTimeout(path, 8000) }

// openWithBusyTimeout 与 Open 相同，但可以指定 busy_timeout。
//
// 只给测试用。把 busy_timeout 压到 100ms 才能断言"导入期间其它写不会被
// 长时间卡住"——用默认的 8 秒，一个真的会把写锁独占几秒的实现
// 也会在超时前成功，那条测试就是假绿。
func openWithBusyTimeout(path string, busyMS int) (*Store, error) {
	if busyMS <= 0 {
		busyMS = 8000
	}
	// WAL 提升并发读性能；busy_timeout 避免瞬时锁冲突直接报错。
	dsn := fmt.Sprintf("file:%s?_pragma=journal_mode(WAL)&_pragma=busy_timeout(%d)&_pragma=foreign_keys(1)&_pragma=synchronous(NORMAL)", path, busyMS)
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, fmt.Errorf("打开数据库失败: %w", err)
	}
	// SQLite 写入是串行的，连接数不宜过大。
	db.SetMaxOpenConns(8)
	db.SetMaxIdleConns(4)
	db.SetConnMaxLifetime(time.Hour)

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := db.PingContext(ctx); err != nil {
		return nil, fmt.Errorf("连接数据库失败: %w", err)
	}

	s := &Store{db: db}
	if err := s.migrate(ctx); err != nil {
		return nil, err
	}
	return s, nil
}

// Close 关闭数据库。
func (s *Store) Close() error { return s.db.Close() }

// DB 暴露底层连接（仅内部/测试使用）。
func (s *Store) DB() *sql.DB { return s.db }

const schema = `
CREATE TABLE IF NOT EXISTS users (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  fnos_uid    TEXT UNIQUE,
  username    TEXT NOT NULL DEFAULT '',
  is_admin    INTEGER NOT NULL DEFAULT 0,
  created_at  INTEGER NOT NULL,
  last_seen   INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS sources (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  name          TEXT NOT NULL,
  type          TEXT NOT NULL DEFAULT 'm3u',   -- m3u | txt | tvbox | json
  url           TEXT NOT NULL,
  enabled       INTEGER NOT NULL DEFAULT 1,
  last_fetch_at INTEGER NOT NULL DEFAULT 0,
  last_error    TEXT NOT NULL DEFAULT '',
  channel_count INTEGER NOT NULL DEFAULT 0,
  user_agent    TEXT NOT NULL DEFAULT '',
  created_at    INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS channels (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  number     INTEGER NOT NULL DEFAULT 0,
  name       TEXT NOT NULL,
  group_name TEXT NOT NULL DEFAULT '未分组',
  logo       TEXT NOT NULL DEFAULT '',
  tvg_id     TEXT NOT NULL DEFAULT '',
  epg_id     TEXT NOT NULL DEFAULT '',
  enabled    INTEGER NOT NULL DEFAULT 1,
  sort       INTEGER NOT NULL DEFAULT 0,
  source_id  INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  -- A1（第二批）：上游已不再提供这个频道。
  --
  -- **必须独立于 enabled**：enabled=0 是「用户手动停用」的唯一表达，
  -- 两个状态共用同一个 0 就永久不可区分——用户手动启用一个已被上游移除的台
  -- 会把它重新激活，而管理台也没法把"我关掉的"和"上游没的"分开显示。
  src_missing INTEGER NOT NULL DEFAULT 0,
  -- 第一次发现上游移除的时间（Unix 秒，0 = 上游仍然有它）。
  -- 只用于展示"多久前没的"，状态真值以 src_missing 为准。
  missing_since INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_channels_group ON channels(group_name);
CREATE INDEX IF NOT EXISTS idx_channels_sort  ON channels(sort);

CREATE TABLE IF NOT EXISTS streams (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  channel_id   INTEGER NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
  url          TEXT NOT NULL,
  quality      TEXT NOT NULL DEFAULT '',
  priority     INTEGER NOT NULL DEFAULT 0,
  headers_json TEXT NOT NULL DEFAULT '{}',
  health       INTEGER NOT NULL DEFAULT 1,   -- 1 健康 / 0 失效 / -1 未探测
  latency_ms   INTEGER NOT NULL DEFAULT 0,
  last_check   INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_streams_channel ON streams(channel_id);

CREATE TABLE IF NOT EXISTS devices (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  device_key  TEXT UNIQUE NOT NULL,          -- 客户端生成的稳定标识
  token_hash  TEXT NOT NULL,                 -- sha256(token)
  name        TEXT NOT NULL DEFAULT '',
  platform    TEXT NOT NULL DEFAULT 'android',
  model       TEXT NOT NULL DEFAULT '',
  status      TEXT NOT NULL DEFAULT 'pending', -- pending | bound | revoked
  config_json TEXT NOT NULL DEFAULT '{}',
  bound_at    INTEGER NOT NULL DEFAULT 0,
  last_seen   INTEGER NOT NULL DEFAULT 0,
  created_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_devices_status ON devices(status);

-- Password-login administrator sessions have a separate privilege namespace.
-- Only the hash is persisted. Device tokens remain explicitly revocable/long-lived.
CREATE TABLE IF NOT EXISTS admin_sessions (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  token_hash TEXT UNIQUE NOT NULL CHECK(length(token_hash)=64),
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL CHECK(expires_at>created_at)
);
CREATE INDEX IF NOT EXISTS idx_admin_sessions_expiry ON admin_sessions(expires_at);

CREATE TABLE IF NOT EXISTS bind_codes (
  code       TEXT PRIMARY KEY,
  device_id  INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  used       INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS epg_sources (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  name       TEXT NOT NULL,
  url        TEXT NOT NULL,
  enabled    INTEGER NOT NULL DEFAULT 1,
  last_sync  INTEGER NOT NULL DEFAULT 0,
  last_error TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS epg_channels (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  source_id    INTEGER NOT NULL DEFAULT 0,
  xmltv_id     TEXT NOT NULL,
  display_name TEXT NOT NULL DEFAULT '',
  icon         TEXT NOT NULL DEFAULT ''
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_epgch_xmltv ON epg_channels(xmltv_id);

CREATE TABLE IF NOT EXISTS epg_programs (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  epg_channel_id INTEGER NOT NULL REFERENCES epg_channels(id) ON DELETE CASCADE,
  start_at       INTEGER NOT NULL,
  end_at         INTEGER NOT NULL,
  title          TEXT NOT NULL DEFAULT '',
  description    TEXT NOT NULL DEFAULT '',
  category       TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_epgprog_ch_start ON epg_programs(epg_channel_id, start_at);

-- 节目开播提醒（C5）。按设备存：同一档节目电视和手机可以各自设提醒。
CREATE TABLE IF NOT EXISTS epg_reminders (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id    INTEGER NOT NULL,
  channel_id   INTEGER NOT NULL,
  channel_name TEXT NOT NULL DEFAULT '',
  title        TEXT NOT NULL DEFAULT '',
  start_at     INTEGER NOT NULL,
  fired        INTEGER NOT NULL DEFAULT 0,
  created_at   INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_reminders_due ON epg_reminders(fired, start_at);
CREATE INDEX IF NOT EXISTS idx_reminders_device ON epg_reminders(device_id, start_at);

CREATE TABLE IF NOT EXISTS favorites (
  device_id  INTEGER NOT NULL,
  channel_id INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (device_id, channel_id)
);

CREATE TABLE IF NOT EXISTS audit_logs (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id    INTEGER NOT NULL DEFAULT 0,
  action     TEXT NOT NULL,
  target     TEXT NOT NULL DEFAULT '',
  detail     TEXT NOT NULL DEFAULT '',
  ip         TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_audit_time ON audit_logs(created_at);

-- 播放质量事件：起播/卡顿/失败。
-- 这是"IPTV 体验好不好"的唯一客观依据——只看频道数没用，
-- 用户真正在意的是"起播多快、卡不卡、哪个台老失败"。
CREATE TABLE IF NOT EXISTS play_events (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id  INTEGER NOT NULL DEFAULT 0,
  channel_id INTEGER NOT NULL DEFAULT 0,
  event      TEXT NOT NULL,                 -- start | stall | error | stop
  startup_ms INTEGER NOT NULL DEFAULT 0,    -- 起播耗时（仅 start 事件有意义）
  detail     TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL,
  -- A3（第二批）：QoE 埋点。没有这些列，"卡顿"就只能靠用户嘴描述，
  -- 修完了也没法验收。
  stall_ms   INTEGER NOT NULL DEFAULT 0,    -- 本次卡顿持续时长（stall 事件）
  watch_ms   INTEGER NOT NULL DEFAULT 0,    -- 本次观看时长（stop 事件），算 rebuffer_ratio 的分母
  path       TEXT NOT NULL DEFAULT '',      -- ts | hls | remux | direct
  engine     TEXT NOT NULL DEFAULT ''       -- exo | vlc | mpv | avplayer
);
CREATE INDEX IF NOT EXISTS idx_play_events_time ON play_events(created_at);
CREATE INDEX IF NOT EXISTS idx_play_events_ch ON play_events(channel_id, created_at);
CREATE INDEX IF NOT EXISTS idx_play_events_path ON play_events(path, created_at);

-- 跨端续播：记住每台设备最后看到哪、看到第几秒。
-- 家庭场景下这是刚需——客厅看一半去卧室，不该重新找台。
CREATE TABLE IF NOT EXISTS watch_progress (
  device_id    INTEGER NOT NULL,
  channel_id   INTEGER NOT NULL,
  position_sec INTEGER NOT NULL DEFAULT 0,
  updated_at   INTEGER NOT NULL,
  PRIMARY KEY (device_id, channel_id)
);
CREATE INDEX IF NOT EXISTS idx_watch_progress_time ON watch_progress(updated_at);

-- 录制中心：立即录制与预约录制共用一张表
CREATE TABLE IF NOT EXISTS recordings (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  channel_id INTEGER NOT NULL DEFAULT 0,
  name       TEXT NOT NULL DEFAULT '',
  start_at   INTEGER NOT NULL,
  end_at     INTEGER NOT NULL,
  status     TEXT NOT NULL DEFAULT 'scheduled', -- scheduled|recording|finished|failed|canceled
  path       TEXT NOT NULL DEFAULT '',
  size       INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_recordings_start ON recordings(start_at);
CREATE INDEX IF NOT EXISTS idx_recordings_status ON recordings(status, start_at);

CREATE TABLE IF NOT EXISTS kv (
  k TEXT PRIMARY KEY,
  v TEXT NOT NULL
);
`

func (s *Store) migrate(ctx context.Context) error {
	if _, err := s.db.ExecContext(ctx, schema); err != nil {
		return fmt.Errorf("建表失败: %w", err)
	}
	// 增量列：老库不会因为 CREATE TABLE IF NOT EXISTS 拿到新字段，
	// 这里补一遍。重复执行会报 "duplicate column name"，忽略即可。
	for _, stmt := range []string{
		`ALTER TABLE sources ADD COLUMN user_agent TEXT NOT NULL DEFAULT ''`,
		// R17：设备配置版本。必须独立于 bound_at——
		// 以前用 bound_at 当版本号，改配置时它不变，
		// 终端永远不知道"配置该重新拉了"，管理台改了参数等于没改。
		`ALTER TABLE devices ADD COLUMN config_version INTEGER NOT NULL DEFAULT 0`,
		// R15：设备会话到期（滑动续期）。
		// 0 表示"从未使用过"，此时以 bound_at 为起点计算。
		// 正常使用会不断续期；只有长期不用才真正过期，
		// 避免 IPTV 场景下用户每天被迫重新绑定。
		`ALTER TABLE devices ADD COLUMN session_expires_at INTEGER NOT NULL DEFAULT 0`,
		// EPG 同步的「上次尝试」时间，与「上次成功」分开记。
		// 以前只有一个 last_sync，失败时也写，界面分不清成功还是失败。
		`ALTER TABLE epg_sources ADD COLUMN last_attempt INTEGER NOT NULL DEFAULT 0`,
		// A5/A7/A8：源优先级、防盗链请求头、本地文件源。
		`ALTER TABLE epg_sources ADD COLUMN priority INTEGER NOT NULL DEFAULT 0`,
		`ALTER TABLE epg_sources ADD COLUMN user_agent TEXT NOT NULL DEFAULT ''`,
		`ALTER TABLE epg_sources ADD COLUMN referer TEXT NOT NULL DEFAULT ''`,
		`ALTER TABLE epg_sources ADD COLUMN kind TEXT NOT NULL DEFAULT 'url'`,
		// D4：连续失败次数。成功清零，失败累加；到阈值就发告警。
		`ALTER TABLE epg_sources ADD COLUMN fail_streak INTEGER NOT NULL DEFAULT 0`,
		// 管理台设备列表要显示「IP」和「版本」，之前这两列根本不存在，
		// handler 里硬编码返回空串，界面上永远显示「—」。
		`ALTER TABLE devices ADD COLUMN ip TEXT NOT NULL DEFAULT ''`,
		`ALTER TABLE devices ADD COLUMN app_version TEXT NOT NULL DEFAULT ''`,
		// A1（第二批）：源刷新对齐上游。
		//
		// src_missing：上游已不再提供这个频道。**必须独立于 enabled**——
		// enabled=0 是"用户手动停用"的唯一表达，两者共用一个 0 就永久不可区分。
		// missing_since：第一次发现缺失的时间，只用于展示"多久前没的"。
		`ALTER TABLE channels ADD COLUMN src_missing INTEGER NOT NULL DEFAULT 0`,
		`ALTER TABLE channels ADD COLUMN missing_since INTEGER NOT NULL DEFAULT 0`,
		// K-6：channel_count 与 parsed_count 必须分开。
		//
		// 以前只有一个 channel_count，写的是"本次解析条数"，而管理台
		// 拿它当"删源会连带删掉几个频道"用在破坏性确认文案里。
		// 对齐语义落地后两者必然分叉（解析 50、库里 40），
		// 于是确认文案里的数字是错的——破坏性操作说错影响范围，不可接受。
		//   channel_count = 库里真实存在、会被一并删除的频道数
		//   parsed_count  = 上一次解析拿到的条目数
		`ALTER TABLE sources ADD COLUMN parsed_count INTEGER NOT NULL DEFAULT 0`,
		// K-2：上一次刷新的结果（五键 JSON）。
		// 放库里而不是只放内存：管理台刷新页面、甚至服务重启之后，
		// 用户问的"上游到底更新了吗"仍然要有答案。
		`ALTER TABLE sources ADD COLUMN last_result_json TEXT NOT NULL DEFAULT ''`,
		// A3（第二批）：QoE 埋点。老库里没有这些列，补上后老数据全是 0/空串，
		// 统计侧用 "> 0" 过滤即可，不需要回填。
		`ALTER TABLE play_events ADD COLUMN stall_ms INTEGER NOT NULL DEFAULT 0`,
		`ALTER TABLE play_events ADD COLUMN watch_ms INTEGER NOT NULL DEFAULT 0`,
		`ALTER TABLE play_events ADD COLUMN path TEXT NOT NULL DEFAULT ''`,
		`ALTER TABLE play_events ADD COLUMN engine TEXT NOT NULL DEFAULT ''`,
	} {
		if _, err := s.db.ExecContext(ctx, stmt); err != nil &&
			!strings.Contains(strings.ToLower(err.Error()), "duplicate column") {
			// 不当作致命错误：建表已经成功，缺列最多是功能降级
			slog.Warn("增量迁移失败（可忽略）", "stmt", stmt, "err", err)
		}
	}
	if err := s.migrateAdminSessions(ctx); err != nil {
		return fmt.Errorf("管理员会话迁移失败: %w", err)
	}
	return nil
}

// Now 返回 Unix 秒，统一时间口径。
func Now() int64 { return time.Now().Unix() }
