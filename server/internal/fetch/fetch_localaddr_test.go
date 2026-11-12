package fetch

import (
	"context"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// localNonLoopbackIPs 列出本机所有非回环 IPv4 地址。
//
// CI 容器里可能一个都没有（只有 lo），这种情况下相关用例 skip，
// 而不是假通过——跳过和通过必须能区分。
func localNonLoopbackIPs(t *testing.T) []net.IP {
	t.Helper()
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		t.Fatalf("枚举网卡失败: %v", err)
	}
	var out []net.IP
	for _, a := range addrs {
		ipn, ok := a.(*net.IPNet)
		if !ok {
			continue
		}
		ip4 := ipn.IP.To4()
		if ip4 == nil || ip4.IsLoopback() || ip4.IsLinkLocalUnicast() {
			continue
		}
		out = append(out, ip4)
	}
	return out
}

// TestCheckIPRejectsLocalNICAddress 是本轮 O01 的核心断言。
//
// 改之前 checkIP 只拒回环/链路本地，**放行本机自己的 LAN 地址**。
// 于是远端 TVBox 的 lives 里写 http://<NAS 的 LAN IP>:5666/... 就能让服务端
// 去访问自己的管理面。
func TestCheckIPRejectsLocalNICAddress(t *testing.T) {
	SetAllowLoopback(false)
	t.Cleanup(func() { SetAllowLoopback(false) })

	ips := localNonLoopbackIPs(t)
	if len(ips) == 0 {
		t.Skip("本机没有非回环 IPv4 地址，无法验证本机网卡防线")
	}
	for _, ip := range ips {
		if err := checkIP(ip); err == nil {
			t.Errorf("本机网卡地址 %s 竟然被放行", ip)
		} else if !strings.Contains(err.Error(), "禁止访问") {
			t.Errorf("%s 的错误不是 SSRF 拦截: %v", ip, err)
		}
	}
}

// TestCheckIPAllowsRemotePrivate 保证不误伤：远端私网/ULA 是产品功能，
// 很多用户的自建源就在局域网里，一刀切会让常见部署直接不可用。
func TestCheckIPAllowsRemotePrivate(t *testing.T) {
	SetAllowLoopback(false)
	t.Cleanup(func() { SetAllowLoopback(false) })

	local := map[string]bool{}
	for _, ip := range localNonLoopbackIPs(t) {
		local[ip.String()] = true
	}
	// 挑几个「肯定是别人」的私网地址
	for _, raw := range []string{"10.99.99.99", "192.168.254.254", "172.31.254.254"} {
		if local[raw] {
			continue // 恰好是本机地址，跳过
		}
		ip := net.ParseIP(raw)
		if ip == nil {
			t.Fatalf("解析失败: %s", raw)
		}
		if err := checkIP(ip); err != nil {
			t.Errorf("远端私网地址 %s 被误拒: %v", raw, err)
		}
	}
}

// TestGetRejectsLocalNICBeforeDial 验证拦截发生在**建连之前**：
// 在本机非回环地址上起一个真实的 victim listener，
// 然后让 fetch.Get 去访问它。要求请求被拒且 victim 一次都没被命中。
func TestGetRejectsLocalNICBeforeDial(t *testing.T) {
	SetAllowLoopback(false)
	t.Cleanup(func() { SetAllowLoopback(false) })

	ips := localNonLoopbackIPs(t)
	if len(ips) == 0 {
		t.Skip("本机没有非回环 IPv4 地址，无法起 victim listener")
	}

	var hits int32
	srv := &httptest.Server{
		Listener: mustListen(t, ips[0]),
		Config: &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			atomic.AddInt32(&hits, 1)
			_, _ = w.Write([]byte("victim"))
		})},
	}
	srv.Start()
	defer srv.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	_, err := Get(ctx, srv.URL+"/secret", DefaultOptions())
	if err == nil {
		t.Fatal("抓取本机网卡地址竟然成功了")
	}
	if !strings.Contains(err.Error(), "禁止访问") {
		t.Fatalf("错误不是 SSRF 拦截: %v", err)
	}
	if got := atomic.LoadInt32(&hits); got != 0 {
		t.Fatalf("victim 被命中了 %d 次，说明拦截发生在建连之后", got)
	}
}

func mustListen(t *testing.T, ip net.IP) net.Listener {
	t.Helper()
	l, err := net.Listen("tcp", net.JoinHostPort(ip.String(), "0"))
	if err != nil {
		t.Skipf("无法在 %s 上监听: %v", ip, err)
	}
	return l
}

// TestSafeDialerRejectsLocalNIC 覆盖第二层：ValidateURL 只看字面量地址，
// 域名解析出来的结果必须由 dialer 再判一次（DNS rebinding 挡的就是这个）。
func TestSafeDialerRejectsLocalNIC(t *testing.T) {
	SetAllowLoopback(false)
	t.Cleanup(func() { SetAllowLoopback(false) })

	ips := localNonLoopbackIPs(t)
	if len(ips) == 0 {
		t.Skip("本机没有非回环 IPv4 地址")
	}

	d := safeDialer(&net.Dialer{Timeout: time.Second})
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	addr := net.JoinHostPort(ips[0].String(), "9")
	conn, err := d(ctx, "tcp", addr)
	if err == nil {
		_ = conn.Close()
		t.Fatalf("dialer 竟然连上了本机网卡地址 %s", addr)
	}
	if !strings.Contains(err.Error(), "禁止访问") {
		t.Fatalf("dialer 的错误不是 SSRF 拦截: %v", err)
	}
}
