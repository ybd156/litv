package remux

import (
	"context"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"
)

// writeFakeFFmpeg 造一个「假的 ffmpeg」：忽略参数，先记一笔启动次数，
// 睡一下再写出一个最小播放列表。
//
// 真 ffmpeg 在 CI 里不一定有，而且起真进程会读网络。这里只关心
// 「同一个频道会不会被并发拉起两个进程」这一件事。
func writeFakeFFmpeg(t *testing.T, dir string) string {
	t.Helper()
	script := filepath.Join(dir, "ffmpeg")
	body := `#!/bin/sh
echo started >> "$(dirname "$0")/starts.log"
sleep 0.3
printf '#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\nseg00000.ts\n' > index.m3u8
sleep 30
`
	if err := os.WriteFile(script, []byte(body), 0o755); err != nil {
		t.Fatalf("写假 ffmpeg 失败: %v", err)
	}
	return script
}

// TestEnsureStartsOnlyOneFFmpeg 是并发双 ffmpeg 的回归测试。
//
// 修复前 ensure 在「检查有没有任务」和「登记新任务」之间放开了 m.mu，
// 两台设备同时换到同一个台时，两路请求都看到「没有任务」，
// 于是各起一个 ffmpeg 写同一个目录：cleanDir 会删掉对方刚写出的切片，
// index.m3u8 也会互相覆盖，表现为画面反复中断、花屏。
func TestEnsureStartsOnlyOneFFmpeg(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("假 ffmpeg 是 shell 脚本，Windows 上跑不了")
	}

	dir := t.TempDir()
	m := NewManager(Options{
		FFmpeg: writeFakeFFmpeg(t, dir),
		Dir:    filepath.Join(dir, "hls"),
	})
	t.Cleanup(m.Close)

	var wg sync.WaitGroup
	for i := 0; i < 5; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			if _, err := m.PlaylistContext(ctx, 7, "http://example.com/live.ts", "/seg/", nil); err != nil {
				t.Errorf("拉播放列表失败: %v", err)
			}
		}()
	}
	wg.Wait()

	data, err := os.ReadFile(filepath.Join(dir, "starts.log"))
	if err != nil {
		t.Fatalf("假 ffmpeg 没有被启动过: %v", err)
	}
	if n := len(strings.Fields(string(data))); n != 1 {
		t.Fatalf("ffmpeg 被启动了 %d 次，期望 1 次（并发请求应复用同一个任务）", n)
	}
}

// TestOpenSegmentRejectsTraversal 确认切片名不能被用来读目录外的文件。
func TestOpenSegmentRejectsTraversal(t *testing.T) {
	dir := t.TempDir()
	m := NewManager(Options{FFmpeg: "ffmpeg", Dir: dir})
	m.jobs[1] = &Job{channelID: 1, dir: dir}

	for _, name := range []string{"../config.yaml", `..\x`, "a/b.ts", "..ts"} {
		if _, _, err := m.OpenSegment(1, name); err == nil {
			t.Errorf("%q 被当成合法切片名了", name)
		}
	}
}

// TestOpenSegmentReportsMissingJob：任务还没起来时给 404 而不是空文件。
func TestOpenSegmentReportsMissingJob(t *testing.T) {
	m := NewManager(Options{FFmpeg: "ffmpeg", Dir: t.TempDir()})
	if _, _, err := m.OpenSegment(99, "seg00000.ts"); err == nil {
		t.Fatal("不存在的任务应该返回错误")
	}
}

// writeArgLoggingFFmpeg 造一个会记录启动次数与参数的假 ffmpeg。
//
// 为什么需要参数：本轮要验证「换线必须重建」，光数启动次数不够，
// 还得证明重建后 ffmpeg 拿到的是**新地址**。
func writeArgLoggingFFmpeg(t *testing.T, dir string) string {
	t.Helper()
	script := filepath.Join(dir, "ffmpeg-args")
	body := `#!/bin/sh
echo started >> "$(dirname "$0")/starts.log"
printf '%s\n' "$@" > "$(dirname "$0")/args.log"
printf '#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\nseg00000.ts\n' > index.m3u8
sleep 30
`
	if err := os.WriteFile(script, []byte(body), 0o755); err != nil {
		t.Fatalf("写假 ffmpeg 失败: %v", err)
	}
	return script
}

// TestEnsureRebuildsWhenUpstreamChanges 是「换线路没反应」的回归测试。
//
// 修复前 ensure 只看 channelID：线路换了也返回旧任务，ffmpeg 继续拉旧地址，
// 用户点了线路、画面一点都不变（iOS 上尤其明显，因为 AVPlayer 只走转封装）。
func TestEnsureRebuildsWhenUpstreamChanges(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("假 ffmpeg 是 shell 脚本，Windows 上跑不了")
	}
	dir := t.TempDir()
	m := NewManager(Options{FFmpeg: writeArgLoggingFFmpeg(t, dir), Dir: filepath.Join(dir, "hls")})
	t.Cleanup(m.Close)

	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()

	if _, err := m.PlaylistContext(ctx, 7, "http://example.com/a.ts", "/seg/", nil); err != nil {
		t.Fatalf("第一路启动失败: %v", err)
	}
	if _, err := m.PlaylistContext(ctx, 7, "http://example.com/b.ts", "/seg/", nil); err != nil {
		t.Fatalf("换线后启动失败: %v", err)
	}

	args, err := os.ReadFile(filepath.Join(dir, "args.log"))
	if err != nil {
		t.Fatalf("读不到假 ffmpeg 的参数: %v", err)
	}
	if !strings.Contains(string(args), "http://example.com/b.ts") {
		t.Fatalf("换线后 ffmpeg 仍在拉旧地址（用户点了线路没反应）：\n%s", args)
	}
	starts, err := os.ReadFile(filepath.Join(dir, "starts.log"))
	if err != nil {
		t.Fatalf("读不到启动次数: %v", err)
	}
	if n := len(strings.Fields(string(starts))); n != 2 {
		t.Fatalf("假 ffmpeg 启动了 %d 次，期望 2（旧任务必须被杀掉重建）", n)
	}
}

// TestEnsureReusesSameUpstream 是上一条的反面：线路没变就不许重建。
//
// 每个播放列表请求都重建 ffmpeg 的话，画面会周期性中断（播放器每 2 秒
// 取一次 m3u8），比不修还糟。
func TestEnsureReusesSameUpstream(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("假 ffmpeg 是 shell 脚本，Windows 上跑不了")
	}
	dir := t.TempDir()
	m := NewManager(Options{FFmpeg: writeArgLoggingFFmpeg(t, dir), Dir: filepath.Join(dir, "hls")})
	t.Cleanup(m.Close)

	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()

	for i := 0; i < 3; i++ {
		if _, err := m.PlaylistContext(ctx, 7, "http://example.com/a.ts", "/seg/", nil); err != nil {
			t.Fatalf("第 %d 次取列表失败: %v", i+1, err)
		}
	}
	starts, err := os.ReadFile(filepath.Join(dir, "starts.log"))
	if err != nil {
		t.Fatalf("读不到启动次数: %v", err)
	}
	if n := len(strings.Fields(string(starts))); n != 1 {
		t.Fatalf("同一路上游启动了 %d 次，期望 1（重复取列表不该重建）", n)
	}
}

// TestPlaylistWatchdogRebuildsStaleJob 覆盖「进程活着但列表停更」。
//
// 上游半死（TCP 还连着、不推数据）时 ffmpeg 既不退出也不产出切片；
// 不减列表就永远停在旧片段上 → 播放器取已删除的分片 → 404 循环黑屏。
func TestPlaylistWatchdogRebuildsStaleJob(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("假 ffmpeg 是 shell 脚本，Windows 上跑不了")
	}
	dir := t.TempDir()
	m := NewManager(Options{FFmpeg: writeArgLoggingFFmpeg(t, dir), Dir: filepath.Join(dir, "hls")})
	t.Cleanup(m.Close)

	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()

	if _, err := m.PlaylistContext(ctx, 7, "http://example.com/a.ts", "/seg/", nil); err != nil {
		t.Fatalf("启动失败: %v", err)
	}

	m.mu.Lock()
	j := m.jobs[7]
	m.mu.Unlock()
	if j == nil {
		t.Fatal("任务没有登记")
	}

	// 把「任务启动时间」和「列表 mtime」都拨回一分钟前，模拟停更。
	old := time.Now().Add(-time.Minute)
	j.mu.Lock()
	j.started = old
	j.mu.Unlock()
	idx := filepath.Join(dir, "hls", "ch7", "index.m3u8")
	if err := os.Chtimes(idx, old, old); err != nil {
		t.Fatalf("改列表 mtime 失败: %v", err)
	}

	if _, err := m.PlaylistContext(ctx, 7, "http://example.com/a.ts", "/seg/", nil); err != nil {
		t.Fatalf("看门狗重建后取列表失败: %v", err)
	}
	starts, err := os.ReadFile(filepath.Join(dir, "starts.log"))
	if err != nil {
		t.Fatalf("读不到启动次数: %v", err)
	}
	if n := len(strings.Fields(string(starts))); n != 2 {
		t.Fatalf("假 ffmpeg 启动了 %d 次，期望 2（看门狗应当重建一次）", n)
	}
}
