package remux

import "testing"

// TestRemuxWindowCoversClientBuffer 钉住一条**跨端不变量**。
//
// 窗口 = SegmentSeconds × PlaylistSize。Android 侧对「自建源」设的 maxBuffer
// 上限是 40 秒（见 `ExoEngine.kt` 的 buffer 分档），窗口必须**明显大于**它；
// 否则播放位置贴着窗口左边缘，周期性 ERROR_CODE_BEHIND_LIVE_WINDOW →
// seek 回边缘 → 重缓冲。
//
// 历史：默认值曾经是 6，也就是 2×6 = **12 秒**，只有客户端上限的三成。
// 走 remux 的台因此"必然周期性重缓冲"，而客户端那边只能靠调小 minBuffer 躲，
// 根因一直在服务端。这条测试防止有人把它调小回去。
func TestRemuxWindowCoversClientBuffer(t *testing.T) {
	const clientMaxBufferSeconds = 40 // ExoEngine.kt：自建源 maxBufferMs 上限 40_000
	const wantMarginSeconds = 15      // 至少留 15 秒余量，别贴着边缘

	m := NewManager(Options{})
	defer m.Close()

	got := m.WindowSeconds()
	if want := clientMaxBufferSeconds + wantMarginSeconds; got < want {
		t.Fatalf("remux 窗口只有 %d 秒（SegmentSeconds=%d × PlaylistSize=%d），"+
			"而客户端 maxBuffer 上限是 %d 秒，至少要 %d 秒——否则必然周期性掉出直播窗口",
			got, m.opt.SegmentSeconds, m.opt.PlaylistSize, clientMaxBufferSeconds, want)
	}
}

// TestRemuxExplicitPlaylistSizeHonored 确认显式配置被如实采用（不被静默夹取）。
//
// 为什么单独测这个：`NewManager` 会读配置文件传进来的值，如果它"顺手"把
// 小值夹到默认值，运维改了 playlist_size 却发现日志里窗口没变——那种静默
// 覆盖比报错难查得多。
func TestRemuxExplicitPlaylistSizeHonored(t *testing.T) {
	m := NewManager(Options{SegmentSeconds: 2, PlaylistSize: 8})
	defer m.Close()
	if got, want := m.WindowSeconds(), 16; got != want {
		t.Fatalf("显式 playlist_size 没被采用：窗口 %d 秒，期望 %d 秒", got, want)
	}
}
