package safedial

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"sync/atomic"
	"testing"
	"time"
)

// TestBlockedTable 固定「永久拒绝」的边界。
//
// 这张表就是策略规格：改动任何一条判定都会让某一行变红。
//
// 特别注意**不在**表里的两类：RFC1918 与 IPv6 ULA。它们必须放行——
// 家庭 LAN 源、组播转单播网关全靠它，封掉等于废掉产品的核心用法。
func TestBlockedTable(t *testing.T) {
	cases := []struct {
		addr    string
		blocked bool
		why     string
	}{
		{"127.0.0.1", true, "loopback"},
		{"127.0.0.2", true, "整个 127/8 都是 loopback，不是只有 .1"},
		{"0.0.0.0", true, "未指定"},
		{"::", true, "IPv6 未指定"},
		{"::1", true, "IPv6 loopback"},
		{"::ffff:127.0.0.1", true, "IPv4-mapped loopback，必须先 Unmap 再判"},
		{"169.254.169.254", true, "链路本地（云元数据端点）"},
		{"fe80::1", true, "IPv6 链路本地"},
		{"100.64.0.1", true, "CGNAT，IsPrivate 不覆盖"},
		{"192.0.0.8", true, "IETF 特殊用途"},
		{"198.18.0.1", true, "基准测试段"},
		{"240.0.0.1", true, "保留段"},
		{"255.255.255.255", true, "广播"},
		{"224.0.0.1", true, "组播"},
		{"64:ff9b::a00:1", true, "NAT64 伪装的 v4 私网地址"},

		// 必须放行的（产品功能）：LAN 源。
		{"10.1.2.3", false, "RFC1918"},
		{"172.16.5.4", false, "RFC1918"},
		{"192.168.1.10", false, "RFC1918（LAN IPTV 源）"},
		{"fd12:3456::1", false, "IPv6 ULA"},
		// 公网。
		{"1.1.1.1", false, "公网"},
		{"223.5.5.5", false, "公网"},
		{"2606:4700::1111", false, "公网 IPv6"},
	}
	for _, c := range cases {
		addr, err := netip.ParseAddr(c.addr)
		if err != nil {
			t.Fatalf("用例地址非法 %q: %v", c.addr, err)
		}
		if got := Blocked(addr); got != c.blocked {
			t.Errorf("Blocked(%s)=%v，期望 %v（%s）", c.addr, got, c.blocked, c.why)
		}
	}
	// 解析失败的零值地址按「禁止」处理（fail closed）。
	if !Blocked(netip.Addr{}) {
		t.Error("无效地址应当按禁止处理（fail closed）")
	}
}

// TestControlUsesParsedAddrPort 确认判定打在**解析后的 IP**上。
//
// 这是本包最关键的实现约束：判定必须发生在 net.Dialer.Control 里，
// 参数 address 已经是 "ip:port"。如果改成在 URL 层匹配字符串，
// 127.1 / 0x7f.1 / [::ffff:127.0.0.1] 都能绕过。
//
// 这里直接调 Control（不需要真的连出去），验证它拒绝的是 IP 而不是字面量。
func TestControlUsesParsedAddrPort(t *testing.T) {
	p := DefaultPolicy()
	cases := []struct {
		addr    string
		blocked bool
	}{
		{"127.0.0.1:80", true},
		{"[::1]:443", true},
		{"[::ffff:127.0.0.1]:80", true},
		{"169.254.169.254:80", true},
		{"192.168.1.10:8080", false}, // LAN 源必须放行
		{"10.0.0.5:80", false},
		{"1.1.1.1:443", false},
	}
	for _, c := range cases {
		err := p.Control("tcp", c.addr, nil)
		if c.blocked && err == nil {
			t.Errorf("Control(%s) 应当拒绝，实际放行", c.addr)
		}
		if c.blocked && err != nil && !errors.Is(err, ErrBlockedAddress) {
			t.Errorf("Control(%s) 错误类型不对: %v", c.addr, err)
		}
		if !c.blocked {
			// 例外：如果这个 LAN 地址恰好是**本机**的地址，
			// 拒绝它是正确行为（"让 NAS 打自己"必须挡）。
			// 这种情况下跳过断言，避免测试结果随运行机器变化。
			if ap, perr := netip.ParseAddrPort(c.addr); perr == nil && IsLocalAddr(ap.Addr()) {
				t.Logf("%s 是本机地址，按设计会被拒绝，跳过", c.addr)
				continue
			}
			if err != nil {
				t.Errorf("Control(%s) 不应拒绝（LAN 源是产品功能）: %v", c.addr, err)
			}
		}
	}
	// 非 ip:port 形式的地址（unix socket）不归本策略管。
	if err := p.Control("unix", "/tmp/sock", nil); err != nil {
		t.Errorf("unix socket 不该被拒绝: %v", err)
	}
}

// TestDialContextRejectsLoopbackBeforeConnecting 是 SSRF 防线的第一层回归。
//
// 修复前的行为：fetch 直接交给默认拨号器，没有任何地址判定，
// 127.0.0.1 上的服务**真的会被连上并收到请求**。
//
// 断言的是「受害服务一次都没被访问」，不是「返回了错误」——
// 后者在网络不通、超时、策略拒绝时都成立，分不开，容易假绿。
func TestDialContextRejectsLoopbackBeforeConnecting(t *testing.T) {
	var hit atomic.Int32
	victim := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hit.Add(1)
		_, _ = w.Write([]byte("INTERNAL-SECRET"))
	}))
	defer victim.Close()

	addr := victim.Listener.Addr().String() // 127.0.0.1:PORT
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	conn, err := DefaultPolicy().DialContext(ctx, "tcp", addr)
	if err == nil {
		if conn != nil {
			_ = conn.Close()
		}
		t.Fatalf("连上了 %s——地址策略没有生效", addr)
	}
	if !errors.Is(err, ErrBlockedAddress) {
		t.Fatalf("错误类型不对，期望 ErrBlockedAddress 链，实际 %v", err)
	}
	if n := hit.Load(); n != 0 {
		t.Fatalf("受害服务被访问了 %d 次——策略是在连接之后才生效的", n)
	}
}

// TestClientRespectsPolicy 覆盖「拨号入口真的接到 Client 上」这件事。
//
// DialContext 单测过了但 Client 没接上它，等于没防。
// 同一份用例跑两遍：默认策略必须拒绝，AllowLoopback 必须放行——
// 后者是**正向对照**，没有它的话，一个"永远连不上"的实现也能让上半段变绿。
func TestClientRespectsPolicy(t *testing.T) {
	victim := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte("SECRET"))
	}))
	defer victim.Close()

	// 1) 生产默认策略：必须拒绝，且受害服务零访问。
	var strictHits atomic.Int32
	strictVictim := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		strictHits.Add(1)
		_, _ = w.Write([]byte("INTERNAL-SECRET"))
	}))
	defer strictVictim.Close()

	strict := DefaultPolicy().Client(2 * time.Second)
	if resp, err := strict.Get(strictVictim.URL + "/x"); err == nil {
		_ = resp.Body.Close()
		t.Fatal("默认策略抓到了本机地址")
	}
	if n := strictHits.Load(); n != 0 {
		t.Fatalf("默认策略下受害服务被访问了 %d 次", n)
	}

	// 2) 测试注入允许回环：必须能通（证明客户端确实接上了拨号器）。
	loose := Policy{AllowLoopback: true}.Client(2 * time.Second)
	resp, err := loose.Get(victim.URL + "/x")
	if err != nil {
		t.Fatalf("AllowLoopback 策略居然连不上回环：%v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Errorf("状态码 %d", resp.StatusCode)
	}
}

// TestPolicyHasNoEnvBypass 是一个防回归的"守门"测试。
//
// 生产必须无法通过环境变量放宽策略。这里设一个典型命名，
// 断言默认策略仍然拒绝回环——如果以后有人加了 os.Getenv 开关，这条会红。
func TestPolicyHasNoEnvBypass(t *testing.T) {
	t.Setenv("LITV_ALLOW_PRIVATE_TARGETS", "1")
	t.Setenv("LITV_SAFEDIAL_ALLOW_LOOPBACK", "1")

	err := DefaultPolicy().Control("tcp", "127.0.0.1:80", nil)
	if err == nil {
		t.Fatal("环境变量能放宽出站策略——生产防线可被静默关闭")
	}
}
