package timeshift

import (
	"os"
	"path/filepath"
	"testing"
)

// TestAlignToKeyframeFindsIDR 是回看黑屏修复的核心回归测试。
//
// 时移段按字节截断，段内偏移按时间比例估算，几乎不可能落在关键帧上。
// 从 GOP 中间开始解码拿不到 IDR，画面就是黑的——这就是「点回看直接黑屏」。
func TestAlignToKeyframeFindsIDR(t *testing.T) {
	const pkt = 188
	data := make([]byte, pkt*100)
	// 每包首字节是 TS 同步字节 0x47
	for i := 0; i < len(data); i += pkt {
		data[i] = 0x47
	}
	// 在第 50 个包的中间放一个 IDR 起始码
	idrPos := pkt*50 + 40
	copy(data[idrPos:], []byte{0x00, 0x00, 0x01, 0x65})

	path := filepath.Join(t.TempDir(), "seg.ts")
	if err := os.WriteFile(path, data, 0o644); err != nil {
		t.Fatalf("写测试文件失败: %v", err)
	}

	// 估算偏移故意放在关键帧之前一点
	got := alignToKeyframe(path, int64(idrPos-10))
	want := int64(pkt * 50) // 该关键帧所在 TS 包的包首
	if got != want {
		t.Fatalf("没有对齐到关键帧：得到 %d，期望 %d（包首）", got, want)
	}
}

// TestAlignToKeyframeFourByteStartCode 确认 00 00 00 01 65 也能识别。
func TestAlignToKeyframeFourByteStartCode(t *testing.T) {
	const pkt = 188
	data := make([]byte, pkt*40)
	for i := 0; i < len(data); i += pkt {
		data[i] = 0x47
	}
	idrPos := pkt*20 + 30
	copy(data[idrPos:], []byte{0x00, 0x00, 0x00, 0x01, 0x65})

	path := filepath.Join(t.TempDir(), "seg.ts")
	if err := os.WriteFile(path, data, 0o644); err != nil {
		t.Fatalf("写测试文件失败: %v", err)
	}
	got := alignToKeyframe(path, int64(idrPos))
	if got != int64(pkt*20) {
		t.Fatalf("四字节起始码未对齐：得到 %d，期望 %d", got, pkt*20)
	}
}

// TestAlignToKeyframeFallsBack 确认找不到关键帧时保持原偏移（不越界、不乱读）。
func TestAlignToKeyframeFallsBack(t *testing.T) {
	const pkt = 188
	data := make([]byte, pkt*10)
	for i := 0; i < len(data); i += pkt {
		data[i] = 0x47
	}
	path := filepath.Join(t.TempDir(), "seg.ts")
	if err := os.WriteFile(path, data, 0o644); err != nil {
		t.Fatalf("写测试文件失败: %v", err)
	}
	const approx = int64(500)
	if got := alignToKeyframe(path, approx); got != approx {
		t.Fatalf("没有关键帧时应保持原偏移 %d，实际 %d", approx, got)
	}
}

// TestAlignToKeyframeMissingFile 确认文件不存在时不 panic、保持原偏移。
func TestAlignToKeyframeMissingFile(t *testing.T) {
	const approx = int64(1234)
	if got := alignToKeyframe(filepath.Join(t.TempDir(), "nope.ts"), approx); got != approx {
		t.Fatalf("文件不存在时应返回原偏移，实际 %d", got)
	}
}
