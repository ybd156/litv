package store

import (
	"context"
	"database/sql"
	"path/filepath"
	"testing"

	_ "modernc.org/sqlite"
)

// TestMigrateFromOldPlayEventsSchema 升级路径回归。
//
// 事故背景（0.1.14 升级失败）：schema 里加了
// `CREATE INDEX idx_play_events_path ON play_events(path, created_at)`，
// 而 `path` 列是靠后面的 ALTER TABLE 补的，schema 先执行、ALTER 后执行。
// 老库的 play_events 没有 path 列 → 建索引报 "no such column: path"
// → migrate 把它当成「建表失败」返回 → 服务直接起不来。
//
// 所有测试都在**新建库**上跑，CREATE TABLE 里本来就有 path，
// 所以 CI 全绿也发现不了。这个测试专门造一个「老库」形状。
func TestMigrateFromOldPlayEventsSchema(t *testing.T) {
	path := filepath.Join(t.TempDir(), "old.db")

	// 1) 手工建一张「0.1.13 时代」的 play_events：没有 stall_ms/watch_ms/path/engine
	raw, err := sql.Open("sqlite", "file:"+path)
	if err != nil {
		t.Fatalf("打开老库失败: %v", err)
	}
	oldSchema := `
CREATE TABLE play_events (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id  INTEGER NOT NULL DEFAULT 0,
  channel_id INTEGER NOT NULL DEFAULT 0,
  event      TEXT NOT NULL,
  startup_ms INTEGER NOT NULL DEFAULT 0,
  detail     TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL
);
INSERT INTO play_events(event, created_at) VALUES('start', 1700000000);
`
	if _, err := raw.Exec(oldSchema); err != nil {
		t.Fatalf("建老库失败: %v", err)
	}
	if err := raw.Close(); err != nil {
		t.Fatalf("关闭老库失败: %v", err)
	}

	// 2) 走正式入口打开（会跑完整 migrate）——这一步在事故版本上会失败
	st, err := Open(path)
	if err != nil {
		t.Fatalf("从老库升级失败（这正是 0.1.14 的升级事故）: %v", err)
	}
	defer func() { _ = st.Close() }()

	// 3) 新列必须补上，且老数据还在
	ctx := context.Background()
	for _, col := range []string{"stall_ms", "watch_ms", "path", "engine"} {
		var n int
		// 查询该列能证明它存在；不存在会报 no such column
		if err := st.DB().QueryRowContext(ctx,
			`SELECT COUNT(*) FROM play_events WHERE `+col+` IS NOT NULL`).Scan(&n); err != nil {
			t.Fatalf("列 %s 没有补上: %v", col, err)
		}
	}
	var rows int
	if err := st.DB().QueryRowContext(ctx, `SELECT COUNT(*) FROM play_events`).Scan(&rows); err != nil {
		t.Fatalf("统计老数据失败: %v", err)
	}
	if rows != 1 {
		t.Fatalf("老数据丢了：期望 1 行，实际 %d 行", rows)
	}

	// 4) 依赖新列的索引必须真的建出来了（否则「移到 ALTER 之后」等于没做）
	var idx int
	if err := st.DB().QueryRowContext(ctx,
		`SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='idx_play_events_path'`).Scan(&idx); err != nil {
		t.Fatalf("查索引失败: %v", err)
	}
	if idx != 1 {
		t.Fatal("idx_play_events_path 没有建出来——索引被移出 schema 后忘了在 ALTER 之后补建")
	}

	// 5) 再开一次必须幂等（升级后重启不能报错）
	st2, err := Open(path)
	if err != nil {
		t.Fatalf("重复打开（模拟重启）失败，迁移不幂等: %v", err)
	}
	_ = st2.Close()
}

// TestMigrateOldChannelsSchema 同样守一遍频道表。
//
// channels 新增了 src_missing / missing_since，它们被 ListChannels 的 WHERE
// 和 GetChannel 的 SELECT 直接引用。列没补上时表现不是「启动失败」，
// 而是所有频道接口 500——更难查，所以也钉一条。
func TestMigrateOldChannelsSchema(t *testing.T) {
	path := filepath.Join(t.TempDir(), "oldch.db")

	raw, err := sql.Open("sqlite", "file:"+path)
	if err != nil {
		t.Fatalf("打开老库失败: %v", err)
	}
	// 0.1.13 的 channels 形状（只保留迁移会碰到的列）
	oldSchema := `
CREATE TABLE channels (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  number     INTEGER NOT NULL DEFAULT 0,
  name       TEXT NOT NULL,
  group_name TEXT NOT NULL DEFAULT '',
  logo       TEXT NOT NULL DEFAULT '',
  tvg_id     TEXT NOT NULL DEFAULT '',
  epg_id     TEXT NOT NULL DEFAULT '',
  enabled    INTEGER NOT NULL DEFAULT 1,
  sort       INTEGER NOT NULL DEFAULT 0,
  source_id  INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL DEFAULT 0
);
INSERT INTO channels(name, enabled) VALUES('老频道', 0);
`
	if _, err := raw.Exec(oldSchema); err != nil {
		t.Fatalf("建老库失败: %v", err)
	}
	_ = raw.Close()

	st, err := Open(path)
	if err != nil {
		t.Fatalf("从老库升级失败: %v", err)
	}
	defer func() { _ = st.Close() }()

	ctx := context.Background()
	// 默认列表必须能查（它会拼 src_missing = 0）。
	// 注意 ChannelQuery{} 的 Enabled 是 nil，本来就不按启用状态过滤——
	// 这条断言要验的是「src_missing 列存在、WHERE 拼得出来」，不是启用过滤。
	list, _, err := st.ListChannels(ctx, ChannelQuery{})
	if err != nil {
		t.Fatalf("老库升级后查频道失败（src_missing 列可能没补上）: %v", err)
	}
	if len(list) != 1 {
		t.Fatalf("期望查回 1 条频道，实际 %d 条", len(list))
	}
	if list[0].Name != "老频道" {
		t.Fatalf("频道数据被破坏：期望「老频道」，实际 %q", list[0].Name)
	}
	// 用户手动停用的状态不能被升级改掉
	if list[0].Enabled {
		t.Fatal("升级后 enabled 被改成了 1——用户手动停用的状态丢了")
	}

	// GetChannel 也要能读（它 SELECT 了 missing_since）
	var id int64
	if err := st.DB().QueryRowContext(ctx, `SELECT id FROM channels LIMIT 1`).Scan(&id); err != nil {
		t.Fatalf("取频道 id 失败: %v", err)
	}
	if _, err := st.GetChannel(ctx, id); err != nil {
		t.Fatalf("GetChannel 在老库升级后失败: %v", err)
	}
}
