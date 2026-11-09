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
