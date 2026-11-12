-- 边缘版最小 litvd 的存储。
--
-- 核心设计：把「订阅生成的数据」和「手工改的数据」分开，否则每天重建
-- 都会把用户在管理台里的改动冲掉。
--
--   channels.stable_key  跨重建稳定的标识（tvg-id 或归一化频道名）
--   channels.origin      m3u = 订阅生成（每次重建删除重建）
--                        manual = 管理台手工添加（永不被重建删除）
--   streams.origin       同上
--   streams.channel_key  让手工线路在重建后还能挂回正确的频道
--   channel_overrides    对订阅频道的改名/改分组/隐藏，按 stable_key 存
--
-- 重建顺序见 scripts/build.mjs 生成的 seed.sql：
--   删 m3u 线路 → 删 m3u 频道 → 插新频道 → 手工线路重新挂 id → 插新线路

CREATE TABLE IF NOT EXISTS channels (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  stable_key TEXT    NOT NULL UNIQUE,
  number     INTEGER NOT NULL DEFAULT 0,
  name       TEXT    NOT NULL,
  "group"    TEXT    NOT NULL DEFAULT '未分组',
  logo       TEXT    NOT NULL DEFAULT '',
  tvg_id     TEXT    NOT NULL DEFAULT '',
  epg_id     TEXT    NOT NULL DEFAULT '',
  enabled    INTEGER NOT NULL DEFAULT 1,
  sort       INTEGER NOT NULL DEFAULT 0,
  origin     TEXT    NOT NULL DEFAULT 'm3u'
);

CREATE INDEX IF NOT EXISTS idx_channels_order ON channels (enabled, sort, number, id);
CREATE INDEX IF NOT EXISTS idx_channels_origin ON channels (origin);

CREATE TABLE IF NOT EXISTS streams (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  channel_id  INTEGER NOT NULL,
  channel_key TEXT    NOT NULL DEFAULT '',
  label       TEXT    NOT NULL DEFAULT '',
  quality     TEXT    NOT NULL DEFAULT '',
  priority    INTEGER NOT NULL DEFAULT 0,
  health      INTEGER NOT NULL DEFAULT -1,
  latency_ms  INTEGER NOT NULL DEFAULT 0,
  url         TEXT    NOT NULL,
  ua          TEXT    NOT NULL DEFAULT '',
  headers     TEXT    NOT NULL DEFAULT '{}',
  origin      TEXT    NOT NULL DEFAULT 'm3u'
);

CREATE INDEX IF NOT EXISTS idx_streams_channel ON streams (channel_id, priority);
CREATE INDEX IF NOT EXISTS idx_streams_origin ON streams (origin);

-- 对订阅频道的人工修改。NULL 表示不覆盖。
CREATE TABLE IF NOT EXISTS channel_overrides (
  stable_key TEXT PRIMARY KEY,
  name       TEXT,
  group_name TEXT,
  logo       TEXT,
  enabled    INTEGER,
  updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS devices (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  device_key  TEXT NOT NULL UNIQUE,
  token_hash  TEXT NOT NULL,
  platform    TEXT NOT NULL DEFAULT '',
  name        TEXT NOT NULL DEFAULT '',
  created_at  INTEGER NOT NULL,
  last_seen   INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_devices_token ON devices (token_hash);

-- 订阅源。放在 D1 而不是 GitHub Secret，这样能在管理台里增删改、
-- 每个源带自己的 UA / Referer（有些面板不认默认 UA 就 403）。
--
-- 注意：url 里通常带账号密码，这是管理台数据，不是日志，不会外泄。
-- last_* 三个字段由 CI 每次重建后回写，用来在管理台显示"这个源还活着吗"。
CREATE TABLE IF NOT EXISTS sources (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  name          TEXT    NOT NULL DEFAULT '',
  type          TEXT    NOT NULL DEFAULT 'm3u',
  url           TEXT    NOT NULL,
  ua            TEXT    NOT NULL DEFAULT '',
  referer       TEXT    NOT NULL DEFAULT '',
  enabled       INTEGER NOT NULL DEFAULT 1,
  sort          INTEGER NOT NULL DEFAULT 0,
  created_at    INTEGER NOT NULL,
  last_fetch_at INTEGER NOT NULL DEFAULT 0,
  last_status   TEXT    NOT NULL DEFAULT '',
  last_count    INTEGER NOT NULL DEFAULT 0
);

-- 管理台会话。绑定码只有 6 位数字，所以它只在登录那一刻用，
-- 之后走随机 token，避免把弱口令当长期凭据使。
CREATE TABLE IF NOT EXISTS admin_sessions (
  token_hash TEXT PRIMARY KEY,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);

-- 绑定码/登录的失败限速，按 IP 记。
CREATE TABLE IF NOT EXISTS bind_attempts (
  ip           TEXT PRIMARY KEY,
  fails        INTEGER NOT NULL DEFAULT 0,
  window_start INTEGER NOT NULL
);

-- 杂项键值。目前只存 last_build（上次重建的时间/频道数），
-- 让管理台能显示"数据是什么时候更新的"，不用去翻 Actions。
CREATE TABLE IF NOT EXISTS kv (
  k TEXT PRIMARY KEY,
  v TEXT NOT NULL
);

-- 节目单。时间一律 Unix 秒（和服务端其它地方一致；JS 里用要 ×1000）。
--
-- 为什么按 channel_id 存而不是 xmltv_id：客户端是按频道 id 查的，
-- 一次索引就能取到，不用先查映射。
-- 数据量控制：只存匹配上的频道 + 前后几天的窗口，重建时整表替换。
CREATE TABLE IF NOT EXISTS epg_programs (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  channel_id  INTEGER NOT NULL,
  xmltv_id    TEXT    NOT NULL DEFAULT '',
  start_at    INTEGER NOT NULL,
  end_at      INTEGER NOT NULL,
  title       TEXT    NOT NULL DEFAULT '',
  description TEXT    NOT NULL DEFAULT '',
  category    TEXT    NOT NULL DEFAULT ''
);

CREATE INDEX IF NOT EXISTS idx_epg_channel_time ON epg_programs (channel_id, start_at);
-- 按 xmltv_id 查：同一个节目源会被多个频道共用（「央视」和「央视-MCP」里
-- 都有 CCTV1，是同一路信号的不同来源）。写入时 channel_id 只能挂一个，
-- 所以查询必须按 xmltv_id 走，否则后来的那个频道永远没有节目单。
CREATE INDEX IF NOT EXISTS idx_epg_xmltv_time ON epg_programs (xmltv_id, start_at);
-- 首页「正在播出」按时间范围捞当前节目，没有这个索引就是整表扫描
CREATE INDEX IF NOT EXISTS idx_epg_time ON epg_programs (start_at, end_at);

-- 收藏（每台设备一份）。
-- 以前边缘版对 /tv/favorite 是「收下但不下发」，客户端收藏永远是空的。
CREATE TABLE IF NOT EXISTS favorites (
  device_id  INTEGER NOT NULL,
  channel_id INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (device_id, channel_id)
);

CREATE INDEX IF NOT EXISTS idx_favorites_device ON favorites (device_id);

-- 跨端续播：终端上报「在看哪个台、看到第几秒」。
-- 边缘没有时移，所以只能用来提示「上次在另一台设备看到这儿」，
-- 不能像 NAS 那样从偏移处起播。
CREATE TABLE IF NOT EXISTS watch_progress (
  device_id    INTEGER NOT NULL,
  channel_id   INTEGER NOT NULL,
  position_sec INTEGER NOT NULL DEFAULT 0,
  updated_at   INTEGER NOT NULL,
  PRIMARY KEY (device_id, channel_id)
);

CREATE INDEX IF NOT EXISTS idx_progress_updated ON watch_progress (updated_at DESC);

-- 屏蔽规则**不建表**：清单就存在 kv 的 `blocked` 这一行里（一段 JSON）。
--
-- 为什么复用 kv 而不是新开两张表：D1 免费额度是按"行读/行写"算的，
-- 而屏蔽清单只在管理台改配置时才变、每次查询都是同一行——
-- 单独建表只会多一张要维护、要迁移、要多一次查询的表。
-- 也没有新增查询：CI 读它时和 `last_build` 合并成一条 SELECT。

--
-- 为什么不直接给 devices 加列：schema.sql 每次部署都整份执行，而 SQLite 没有
-- `ADD COLUMN IF NOT EXISTS`——加列的迁移在这个幂等脚本里写不干净。
-- 单独一张表就只要 CREATE TABLE IF NOT EXISTS，老库新库都能跑。
CREATE TABLE IF NOT EXISTS device_state (
  device_id      INTEGER PRIMARY KEY,
  config_json    TEXT    NOT NULL DEFAULT '',
  config_version INTEGER NOT NULL DEFAULT 1,
  app_version    TEXT    NOT NULL DEFAULT '',
  last_ip        TEXT    NOT NULL DEFAULT ''
);
