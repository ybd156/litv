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
	"time"

	_ "modernc.org/sqlite"
)

// Store 是数据访问入口。
type Store struct {
	db *sql.DB
}

// Open 打开（或创建）数据库并执行建表。
func Open(path string) (*Store, error) {
	// WAL 提升并发读性能；busy_timeout 避免瞬时锁冲突直接报错。
	dsn := fmt.Sprintf("file:%s?_pragma=journal_mode(WAL)&_pragma=busy_timeout(8000)&_pragma=foreign_keys(1)&_pragma=synchronous(NORMAL)", path)
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
  created_at INTEGER NOT NULL
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
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_play_events_time ON play_events(created_at);
CREATE INDEX IF NOT EXISTS idx_play_events_ch ON play_events(channel_id, created_at);

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
	return nil
}

// Now 返回 Unix 秒，统一时间口径。
func Now() int64 { return time.Now().Unix() }
