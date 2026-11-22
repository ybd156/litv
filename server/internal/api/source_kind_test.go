package api

import "testing"

func TestIsHLSSource(t *testing.T) {
	cases := []struct {
		url  string
		want bool
	}{
		{"http://origin.example/live/cctv1.m3u8", true},
		{"http://origin.example/live/cctv1.M3U8", true},
		{"https://origin.example/hls/play.m3u8?sign=abc&exp=1", true},
		{"http://origin.example/live/stream.ts", false},
		{"http://origin.example:8080/1", false},
		{"", false},
	}
	for _, c := range cases {
		if got := isHLSSource(c.url); got != c.want {
			t.Errorf("isHLSSource(%q) = %v，期望 %v", c.url, got, c.want)
		}
	}
}

// TestIsHLSSource_KnownLimitation 钉住一个**已知缺陷的现状**，描述的不是期望行为。
//
// 不带 .m3u8 后缀的 HLS（这里用查询串形式举例）现在会被判成"不是 HLS"：
// /tv/play 于是下发 kind=ts，客户端把 HLS 当裸 TS 播 → 黑屏 / 无声。
// 根因不在这个函数，而在"封装类型只能靠 URL 猜"这件事本身（详见 source_kind.go 注释）。
//
// 这条断言的价值：将来真去修（让 parse 把真实封装落进 streams 表）时，
// 它会先亮起来，逼着修的人显式反转它并更新注释，而不是悄悄改掉线上行为没人知道。
func TestIsHLSSource_KnownLimitation(t *testing.T) {
	const suffixlessHLS = "http://origin.example/live/123?fmt=hls"
	if isHLSSource(suffixlessHLS) {
		t.Fatalf("这个地址现在被判成 HLS 了。若是有意修好了「后缀缺失」的问题，" +
			"请删掉这条测试并同步更新 source_kind.go 的已知局限说明")
	}
}
