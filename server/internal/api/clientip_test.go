package api

import (
	"net/http"
	"testing"

	"github.com/litv/litv/server/internal/config"
)

// TestClientIPRespectsTrustedProxy 钉住「什么时候才采信 X-Forwarded-For」。
//
// 这条逻辑只影响「显示设备 IP」，但一旦写错（比如无条件采信转发头），
// 很容易被顺手拿去做鉴权，所以必须把边界测清楚。
func TestClientIPRespectsTrustedProxy(t *testing.T) {
	cfg := &config.Config{}
	cfg.Server.TrustProxy = true
	cfg.Server.TrustedProxies = []string{"127.0.0.1/32", "::1/128"}

	cases := []struct {
		name       string
		remoteAddr string
		xff        string
		xri        string
		want       string
	}{
		{"受信代理 + XFF 多段取最左", "127.0.0.1:54321", "203.0.113.9, 10.0.0.1", "", "203.0.113.9"},
		{"受信代理 + X-Real-IP 优先", "127.0.0.1:54321", "203.0.113.9", "198.51.100.7", "198.51.100.7"},
		{"受信代理但无转发头 -> 用连接地址", "127.0.0.1:54321", "", "", "127.0.0.1"},
		{"非受信对端 -> 忽略伪造的 XFF", "192.168.1.50:1234", "203.0.113.9", "", "192.168.1.50"},
		{"受信代理 + 非法 XFF -> 回退连接地址", "127.0.0.1:54321", "not-an-ip", "", "127.0.0.1"},
	}
	for _, c := range cases {
		r, err := http.NewRequest(http.MethodGet, "/api/v1/tv/heartbeat", nil)
		if err != nil {
			t.Fatalf("构造请求失败: %v", err)
		}
		r.RemoteAddr = c.remoteAddr
		if c.xff != "" {
			r.Header.Set("X-Forwarded-For", c.xff)
		}
		if c.xri != "" {
			r.Header.Set("X-Real-IP", c.xri)
		}
		if got := clientIP(r, cfg); got != c.want {
			t.Errorf("%s: 期望 %q，实际 %q", c.name, c.want, got)
		}
	}
}

// TestClientIPTrustProxyDisabled 确认关掉 trust_proxy 后一律用连接地址。
func TestClientIPTrustProxyDisabled(t *testing.T) {
	cfg := &config.Config{}
	cfg.Server.TrustProxy = false
	cfg.Server.TrustedProxies = []string{"127.0.0.1/32"}

	r, _ := http.NewRequest(http.MethodGet, "/", nil)
	r.RemoteAddr = "127.0.0.1:5000"
	r.Header.Set("X-Forwarded-For", "203.0.113.9")

	if got := clientIP(r, cfg); got != "127.0.0.1" {
		t.Errorf("trust_proxy=false 时应返回连接地址，实际 %q", got)
	}
}

// TestPeerTrustedBareIP 确认只写 IP（不带掩码）也能匹配。
func TestPeerTrustedBareIP(t *testing.T) {
	if !peerTrusted("127.0.0.1", []string{"127.0.0.1"}) {
		t.Error("裸 IP 应等价于 /32")
	}
	if peerTrusted("10.0.0.5", []string{"127.0.0.1"}) {
		t.Error("不在网段内的地址不应匹配")
	}
	if !peerTrusted("::1", []string{"::1"}) {
		t.Error("IPv6 裸地址应等价于 /128")
	}
}
