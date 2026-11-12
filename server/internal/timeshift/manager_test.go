package timeshift

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

// TestReapRemovesIdleRing 覆盖「Ring 永不回收」的修复。
//
// 修复前 Manager 不回收任何 Ring，每个看过的频道都永久持有一个打开的文件
// 句柄；用户把几百个台点一遍就可能在 NAS 上耗尽 fd。这里把活跃时间调旧
// 再跑一次回收，要求 Ring 从表里消失、文件也被删掉。
func TestReapRemovesIdleRing(t *testing.T) {
	dir := t.TempDir()
	m := NewManager(dir, true, Options{SegmentBytes: 1024, MaxSegments: 2})
	t.Cleanup(m.Close)

	m.Write(3, []byte("hello"))
	r := m.ring(3)
	if r == nil {
		t.Fatal("Ring 没有创建")
	}
	path := filepath.Join(dir, channelDirName(3))
	if _, err := os.Stat(path); err != nil {
		t.Fatalf("时移目录不存在: %v", err)
	}

	m.reap(time.Now())
	if _, ok := m.rings[3]; !ok {
		t.Fatal("刚用过的 Ring 被误回收了")
	}

	// 把活跃时间调到阈值以前
	r.mu.Lock()
	r.lastUsed = time.Now().Add(-idleReapAfter - time.Minute)
	r.mu.Unlock()

	m.reap(time.Now())
	if _, ok := m.rings[3]; ok {
		t.Fatal("空闲超时的 Ring 没有被回收")
	}
	left, err := os.ReadDir(path)
	if err == nil {
		for _, e := range left {
			t.Fatalf("回收后还留着文件: %s", e.Name())
		}
	}
}

// TestCleanStaleSegmentsOnlyTouchesOurFiles 确认启动清理不会误删用户文件。
//
// timeshift.dir 是可配置的，用户很可能指到一个已经有别的东西的目录。
func TestCleanStaleSegmentsOnlyTouchesOurFiles(t *testing.T) {
	root := t.TempDir()

	ours := filepath.Join(root, "ch12")
	if err := os.MkdirAll(ours, 0o755); err != nil {
		t.Fatal(err)
	}
	writeFile(t, filepath.Join(ours, "ch12-123456789.ts"))
	keep := []string{
		filepath.Join(ours, "notes.txt"),
		filepath.Join(ours, "other-1.ts"),
		filepath.Join(root, "README.md"),
		filepath.Join(root, "chX", "chX-1.ts"),
	}
	for _, p := range keep {
		if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
			t.Fatal(err)
		}
		writeFile(t, p)
	}

	cleanStaleSegments(root)

	if _, err := os.Stat(filepath.Join(ours, "ch12-123456789.ts")); !os.IsNotExist(err) {
		t.Fatal("上次运行遗留的时移文件没有被清掉")
	}
	for _, p := range keep {
		if _, err := os.Stat(p); err != nil {
			t.Errorf("误删了非时移文件 %s: %v", p, err)
		}
	}
}

func writeFile(t *testing.T, path string) {
	t.Helper()
	if err := os.WriteFile(path, []byte("x"), 0o644); err != nil {
		t.Fatalf("写文件失败: %v", err)
	}
}
