package record

import (
	"strings"
	"testing"

	"github.com/litv/litv/server/internal/store"
)

// TestRecordArgsCarryStreamHeaders 是「录制不带 Referer」的回归测试。
//
// 以前录制只传 -user_agent，要求 Referer 的源站会在录制这一步 403，
// 用户看到的是「直播能看， recordings 里是 0 字节 / failed」。
func TestRecordArgsCarryStreamHeaders(t *testing.T) {
	m := NewManager(nil, Options{UserAgent: "default-ua"})
	stream := &store.Stream{
		URL:         "http://up.example/live.ts",
		HeadersJSON: `{"User-Agent":"custom-ua","Referer":"http://up.example/ref"}`,
	}

	args := m.args(stream, 60, "/tmp/rec.ts")

	uaIdx := indexOf(args, "-user_agent")
	if uaIdx < 0 || uaIdx+1 >= len(args) || args[uaIdx+1] != "custom-ua" {
		t.Fatalf("线路自带的 UA 没有传进去: %v", args)
	}
	hIdx := indexOf(args, "-headers")
	if hIdx < 0 || hIdx+1 >= len(args) {
		t.Fatalf("没有传 -headers: %v", args)
	}
	if !strings.Contains(args[hIdx+1], "Referer: http://up.example/ref") {
		t.Fatalf("-headers 里没有 Referer: %q", args[hIdx+1])
	}
	if strings.Contains(args[hIdx+1], "custom-ua") {
		t.Fatal("UA 同时出现在 -headers 里（会出现两个 UA）")
	}
	// -headers 必须在 -i 之前，否则 ffmpeg 不会应用到输入
	if iIdx := indexOf(args, "-i"); iIdx < hIdx {
		t.Fatalf("-headers 写在了 -i 后面，对输入不生效: %v", args)
	}
}

// TestRecordArgsFallbackUserAgent：线路没写 UA 时用配置里的默认值。
func TestRecordArgsFallbackUserAgent(t *testing.T) {
	m := NewManager(nil, Options{UserAgent: "default-ua"})
	args := m.args(&store.Stream{URL: "http://up.example/live.ts"}, 60, "/tmp/rec.ts")

	uaIdx := indexOf(args, "-user_agent")
	if uaIdx < 0 || args[uaIdx+1] != "default-ua" {
		t.Fatalf("没有回落到默认 UA: %v", args)
	}
	if indexOf(args, "-headers") >= 0 {
		t.Fatalf("没有额外头时不该传 -headers: %v", args)
	}
}

// TestRecordArgsDropsHeaderInjection：源文件是不可信输入，
// 带 CRLF 的头值不能变成额外的 ffmpeg 参数。
func TestRecordArgsDropsHeaderInjection(t *testing.T) {
	m := NewManager(nil, Options{})
	stream := &store.Stream{
		URL:         "http://up.example/live.ts",
		HeadersJSON: `{"Referer":"x\r\nX-Evil: 1"}`,
	}
	args := m.args(stream, 60, "/tmp/rec.ts")

	for _, a := range args {
		if strings.Contains(a, "X-Evil") {
			t.Fatalf("CRLF 注入被放进了参数: %q", a)
		}
	}
}

func indexOf(args []string, want string) int {
	for i, a := range args {
		if a == want {
			return i
		}
	}
	return -1
}
