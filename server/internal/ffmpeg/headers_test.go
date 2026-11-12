package ffmpeg

import (
	"strings"
	"testing"
)

func TestHeadersArgSortsAndFilters(t *testing.T) {
	got := HeadersArg(map[string]string{
		"Referer":    "http://up.example/",
		"X-A":        "1",
		"Bad\nKey":   "x",
		"Injection":  "a\r\nX-Evil: 1",
		"EmptyValue": "",
	})
	want := "Referer: http://up.example/\r\nX-A: 1\r\n"
	if got != want {
		t.Fatalf("头拼接不对:\n got=%q\nwant=%q", got, want)
	}
	// 键必须稳定排序，否则每次拼出不同顺序
	if strings.Index(got, "Referer") > strings.Index(got, "X-A") {
		t.Fatal("头的顺序不稳定（没有按 key 排序）")
	}
}

func TestHeadersArgEmpty(t *testing.T) {
	if got := HeadersArg(nil); got != "" {
		t.Fatalf("空头应返回空串，实际 %q", got)
	}
	if got := HeadersArg(map[string]string{"Bad:Key": "1"}); got != "" {
		t.Fatalf("非法键应被全部过滤，实际 %q", got)
	}
}

func TestSplitUserAgent(t *testing.T) {
	ua, rest := SplitUserAgent(map[string]string{
		"user-agent": "Mozilla/5.0",
		"Referer":    "http://up.example/",
	})
	if ua != "Mozilla/5.0" {
		t.Fatalf("UA 没取出来: %q", ua)
	}
	if len(rest) != 1 || rest["Referer"] != "http://up.example/" {
		t.Fatalf("剩余头不对: %+v", rest)
	}
	if _, ok := rest["user-agent"]; ok {
		t.Fatal("UA 不应该同时留在 -headers 里（会出现两个 UA）")
	}

	ua, rest = SplitUserAgent(nil)
	if ua != "" || rest != nil {
		t.Fatalf("空输入应返回空值: %q %+v", ua, rest)
	}
}
