package fetch

import (
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
)

// TestTransportDoesNotFollowEnvProxy 固定一条安全前提：出站抓取不读代理环境变量。
//
// 为什么这条是安全前提而不是洁癖：
//   - safedial 的策略判在 net.Dialer.Control 上，那是"真正要连的地址"；
//   - 一旦设了 Proxy，connect 的目标变成**代理地址**，Control 校验的就成了代理，
//     代理再去解析真实目标 —— 整套 SSRF 防线被整体绕过；
//   - 而 HTTP_PROXY 在容器/用户 shell 里很常见，属于"环境一改就静默失效"。
func TestTransportDoesNotFollowEnvProxy(t *testing.T) {
	tr := NewTransport()
	if tr.Proxy == nil {
		return // 更彻底的接法：完全不设代理
	}
	req := httptest.NewRequest(http.MethodGet, "http://example.com/x", nil)
	if u, err := tr.Proxy(req); err != nil || u != nil {
		t.Fatalf("Transport 会跟随代理（%v / %v）：出站策略会被整体绕过", u, err)
	}
}

// TestCheckIPBlocksReservedRanges 要求 fetch 与 safedial 用同一份地址段判定。
//
// 以前 fetch.checkIP 自己写了一遍：只挡 loopback / 链路本地 / 组播 / 本机网卡，
// 漏了 CGNAT、192.0.0.0/24、198.18/15、240/4、NAT64 —— 而这些在
// safedial.Blocked 里都有。两套判定并存，迟早分叉成"一个入口拦、另一个不拦"。
func TestCheckIPBlocksReservedRanges(t *testing.T) {
	SetAllowLoopback(false)
	t.Cleanup(func() { SetAllowLoopback(false) })

	for _, s := range []string{
		"100.64.0.1",  // CGNAT
		"192.0.0.10",  // IETF 协议分配段
		"198.18.0.5",  // 基准测试保留段
		"240.0.0.1",   // 保留段
		"127.0.0.1",   // 回环
		"169.254.1.1", // 链路本地（云元数据就在这一段）
	} {
		if err := checkIP(net.ParseIP(s)); err == nil {
			t.Errorf("checkIP(%s) = nil，应当被拒（与 safedial.Blocked 不一致）", s)
		}
	}
}

// TestCheckIPAllowsPrivateLAN 防止上面的收紧误伤产品功能：
// 用户自有 LAN 源必须仍然能抓。
func TestCheckIPAllowsPrivateLAN(t *testing.T) {
	SetAllowLoopback(false)
	t.Cleanup(func() { SetAllowLoopback(false) })

	for _, s := range []string{"192.168.1.10", "10.0.0.5", "172.16.3.4"} {
		if err := checkIP(net.ParseIP(s)); err != nil {
			t.Errorf("checkIP(%s) = %v，RFC1918 必须放行（LAN 源是产品功能）", s, err)
		}
	}
}
