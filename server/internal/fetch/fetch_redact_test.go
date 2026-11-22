package fetch

import (
	"errors"
	"net/url"
	"strings"
	"testing"
)

func TestRedactURL(t *testing.T) {
	cases := []struct {
		in   string
		want string
	}{
		{"http://example.com/a/b.m3u8?token=SECRET&exp=1", "http://example.com/a/b.m3u8"},
		{"http://user:pass@example.com/live.m3u8?x=1", "http://example.com/live.m3u8"},
		{"https://example.com:8443/path/#frag", "https://example.com:8443/path/"},
	}
	for _, c := range cases {
		if got := redactURL(c.in); got != c.want {
			t.Errorf("redactURL(%q) = %q，期望 %q", c.in, got, c.want)
		}
	}
}

// TestSafeErrStripsCredentials 是这条修复的回归：**凭据不得进日志/库/管理台**。
//
// 背景：net/http 的传输错误是 *url.Error，它的 Error() 会原样带出完整 URL；
// 而 fetch 的错误串会被写进 sources.last_error（落库）、slog 和管理台界面。
// 以前那几处直接 `%w` 包装，等于把源地址里的账号密码/签名一起存下来。
func TestSafeErrStripsCredentials(t *testing.T) {
	const rawURL = "http://user:pass@origin.example/live.m3u8?token=SECRET123&exp=1700000000"
	ue := &url.Error{Op: "Get", URL: rawURL, Err: errors.New("connection refused")}

	msg := safeErr(ue).Error()
	for _, secret := range []string{"SECRET123", "pass@", "user:", "exp=1700000000"} {
		if strings.Contains(msg, secret) {
			t.Fatalf("错误串里残留 %q —— 会落库也会进日志：%s", secret, msg)
		}
	}
	// 但不能脱敏到无法定位问题：host + path 必须留着。
	for _, keep := range []string{"origin.example", "/live.m3u8", "connection refused"} {
		if !strings.Contains(msg, keep) {
			t.Fatalf("过度脱敏，丢了排查用的 %q：%s", keep, msg)
		}
	}
}

// TestSafeErrPassesThrough 确认非 url.Error 的错误不被改写（避免影响既有文案）。
func TestSafeErrPassesThrough(t *testing.T) {
	base := errors.New("响应为空")
	if got := safeErr(base); got.Error() != base.Error() {
		t.Fatalf("普通错误被改写了：%q", got.Error())
	}
	if safeErr(nil) != nil {
		t.Fatal("nil 应当原样返回 nil")
	}
}
