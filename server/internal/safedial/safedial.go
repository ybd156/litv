// Package safedial 给所有「出站抓取」提供统一的地址策略（SSRF 防线）。
//
// # 为什么需要这一层
//
// 本项目的上游地址来自两类都不完全可信的来源：
//  1. 管理员手工填写的订阅源 / EPG 地址 / 台标地址；
//  2. **远端内容里冒出来的地址**：m3u8 里的子清单与分片、TVBox 配置里的线路、
//     重定向的 Location 头，以及用户导入的线路 URL（播放主通路）。
//
// 第 2 类等价于「让远端决定服务端去连谁」。不拦的话，NAS 就是一个能把内网
// HTTP 服务的响应原样回吐给终端的代理。项目不内置任何源，用户导入的第三方源
// 就是外部输入，这一条必须认真对待。
//
// # 策略：永久拒绝「自打」，放行 LAN
//
// 永久拒绝：
//   - loopback（127.0.0.0/8、::1）
//   - 未指定（0.0.0.0、::）
//   - 链路本地（169.254.0.0/16、fe80::/10）——云元数据端点就在这一段
//   - CGNAT（100.64.0.0/10）
//   - 组播 / 保留段 / NAT64 伪装段
//   - **本机各网卡上真实监听的地址**（含 LAN 地址）：挡住「让 NAS 打自己的管理口」
//
// 明确放行：RFC1918 与 IPv6 ULA。
//
// 为什么放行 LAN：本仓库明确支持「用户自有 LAN 源走 HTTP」（见 config 里
// direct 的说明与 AGENTS §6「用户自有 LAN 服务可以走 HTTP」）。
// 一刀切封掉 RFC1918 会让家庭 LAN IPTV 源全部失效，那是产品功能，不是漏洞。
//
// # 剩余风险（明确记录，不要当成已解决）
//
// 放行 LAN 意味着：一份恶意/被劫持的上游清单，仍然可以让服务端去访问
// **同一 LAN 内其它主机的 HTTP 服务**（例如路由器管理页、NAS 上的其它服务端口、
// 另一台设备的调试接口）。本轮只关掉了「打自己」和「打云元数据」这两条最致命的路径。
//
// 后续收敛方向（不在本轮）：把允许目标从「整个 LAN」收窄到
// 「该频道已配置线路的 host 集合 + CDN 常见域」，即按频道做 host allowlist。
// 那需要把线路 host 带进 safedial 的调用链（每个请求一个 Policy 实例），
// 属于行为变更，需单独评估兼容性。
//
// # 判定打在解析后的 IP 上
//
// 判段发生在 net.Dialer.Control 里：此时域名已经解析完，拿到的是真正要连的
// sockaddr，用 netip.ParseAddrPort 取 IP 再判。
//
// 刻意**不**在 URL 字符串上匹配 "127.0.0.1"：
//   - 127.1 / 0x7f.1 / [::ffff:127.0.0.1] 这些写法都能绕过字符串匹配；
//   - DNS rebinding 会在「校验」和「连接」之间换掉解析结果。
//     在 Control 里判，校验与连接之间没有窗口。
//
// 本包**不读环境变量、没有全局开关、没有调试后门**：
// 唯一的放宽入口是显式构造的 Policy.AllowLoopback，它只给测试注入用
// （httptest 服务器都监听回环），生产构造（DefaultPolicy）永远是严格的。
package safedial

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/netip"
	"sync"
	"syscall"
	"time"
)

// ErrBlockedAddress 表示目标落在禁止出站的地址上。
//
// 调用方可以用 errors.Is 区分「被策略拒绝」和「网络真的不通」：
// 前者是配置/攻击问题，重试没有意义，也不该累加「源站挂了」的失败计数。
var ErrBlockedAddress = errors.New("目标地址被出站策略拒绝（本机/链路本地/保留地址）")

// 这些段 netip 的内置判定不覆盖，但同样不该被出站抓取摸到。
var blockedPrefixes = []netip.Prefix{
	// CGNAT（RFC 6598）。IsPrivate 只看 RFC1918，100.64/10 会漏过去，
	// 而它经常是运营商侧的内网地址。
	netip.MustParsePrefix("100.64.0.0/10"),
	// IETF 协议分配段（含若干特殊用途地址）。
	netip.MustParsePrefix("192.0.0.0/24"),
	// 基准测试保留段。
	netip.MustParsePrefix("198.18.0.0/15"),
	// 240/4 保留段，含广播地址 255.255.255.255。
	netip.MustParsePrefix("240.0.0.0/4"),
	// NAT64：能把一个 v4 目标伪装成 v6 地址，绕过基于 v4 段的判断。
	netip.MustParsePrefix("64:ff9b::/96"),
}

// Blocked 判断「永远不许出站」的地址段。
//
// 纯函数：不看本机网卡，所以结果与运行环境无关，可以直接用来写表驱动测试。
// 本机监听地址的判定在 Policy.Control 里，是独立的一层。
func Blocked(addr netip.Addr) bool {
	if !addr.IsValid() {
		// 无效地址按「禁止」处理（fail closed）：宁可抓取失败，
		// 也不要在策略失效的情况下把请求放出去。
		return true
	}
	// Unmap：把 ::ffff:127.0.0.1 还原成 127.0.0.1 再判，
	// 否则一个 IPv4-mapped 地址就能绕过所有 v4 段判定。
	// WithZone：链路本地地址可能带 %eth0，带 zone 的地址比较行为容易出错。
	addr = addr.Unmap().WithZone("")

	if addr.IsUnspecified() || // 0.0.0.0 / ::
		addr.IsLoopback() || // 127/8 / ::1
		addr.IsLinkLocalUnicast() || // 169.254/16 / fe80::/10
		addr.IsLinkLocalMulticast() ||
		addr.IsMulticast() {
		return true
	}
	// 注意：这里**不**判 IsPrivate()——RFC1918 与 ULA 是产品功能，必须放行。
	for _, p := range blockedPrefixes {
		if p.Contains(addr) {
			return true
		}
	}
	return false
}

// IsPrivateAddr 判断地址是否落在 RFC1918 / IPv6 ULA 段。
//
// 与 Blocked 的区别：这些地址**是允许出站的**（LAN 源、组播转单播网关都靠它），
// 所以不能拿来当拦截条件。它只用于「打个标记」，例如审计里记录
// 「这一路分片打到了 LAN」——那是需要留下痕迹的行为，不是要拒的行为。
func IsPrivateAddr(addr netip.Addr) bool {
	if !addr.IsValid() {
		return false
	}
	return addr.Unmap().WithZone("").IsPrivate()
}

// Policy 是一份出站策略。
//
// 零值即最严格策略，生产代码只用 DefaultPolicy()。
type Policy struct {
	// AllowLoopback 只给测试注入用。
	//
	// 置 true 会**整体关掉**判段（httptest 服务器都监听 127.0.0.1，
	// 不关掉就没法测「正常抓取」这条正向路径）。
	// 生产代码不要置它——没有配置文件项、没有环境变量能打开它。
	AllowLoopback bool
}

// DefaultPolicy 返回生产策略。
func DefaultPolicy() Policy { return Policy{} }

// Control 是 net.Dialer.Control 的实现。
//
// 时机很关键：它由 net 包在 connect(2) **之前**调用，参数 address 是
// 已经解析好的 "ip:port"（域名在这一步之前就解完了）。
// 所以这里判的是真正要连的 IP，DNS rebinding 换不了这个值。
func (p Policy) Control(network, address string, _ syscall.RawConn) error {
	if p.AllowLoopback {
		return nil
	}
	// 非 "ip:port" 形式的地址（unix socket 等）不归本策略管。
	ap, err := netip.ParseAddrPort(address)
	if err != nil {
		return nil
	}
	addr := ap.Addr()
	if Blocked(addr) {
		return fmt.Errorf("%w: %s", ErrBlockedAddress, addr)
	}
	// 「让 NAS 打自己」这一类：本机各网卡上真实存在的地址。
	//
	// loopback 已经被上面拦掉，这里补的是「本机的 LAN 地址」
	// （例如 NAS 自己就是 192.168.1.5，那么连 192.168.1.5 的管理口要拒）。
	// 不拒的话，一份恶意清单就能让 NAS 去调自己的管理接口。
	if IsLocalAddr(addr) {
		return fmt.Errorf("%w: 本机监听地址 %s", ErrBlockedAddress, addr)
	}
	return nil
}

// Dialer 返回带本策略的拨号器。
//
// 用它（或它的 DialContext）构造 Transport，是本包唯一正确的接法。
func (p Policy) Dialer() *net.Dialer {
	return &net.Dialer{
		Timeout:   10 * time.Second,
		KeepAlive: 30 * time.Second,
		Control:   p.Control,
	}
}

// DialContext 便于直接赋给 http.Transport.DialContext。
func (p Policy) DialContext(ctx context.Context, network, address string) (net.Conn, error) {
	return p.Dialer().DialContext(ctx, network, address)
}

// Transport 返回一个接了本策略的基础 Transport。
//
// 刻意不设 Proxy：http.ProxyFromEnvironment 会让实际连接目标变成代理地址，
// 策略要么误杀用户正常的代理，要么被代理整个绕过去。
// 需要代理的场景应该在配置里显式声明，而不是靠环境变量悄悄改出站路径。
//
// 调用方若还需要别的参数（关 HTTP/2、调 MaxIdleConns 等），
// 拿 DialContext 自己建 Transport 即可，不要在这里堆参数。
func (p Policy) Transport() *http.Transport {
	return &http.Transport{
		DialContext:           p.DialContext,
		TLSHandshakeTimeout:   10 * time.Second,
		ResponseHeaderTimeout: 15 * time.Second,
		MaxIdleConns:          16,
		IdleConnTimeout:       60 * time.Second,
	}
}

// Client 返回接了本策略的客户端。
//
// 调用方应当做成包级变量复用：Transport 被丢弃后，它的空闲连接
// 不会被主动关掉，每请求新建等于慢性 fd 泄漏。
func (p Policy) Client(timeout time.Duration) *http.Client {
	return &http.Client{Timeout: timeout, Transport: p.Transport()}
}

// IsBlockedError 便捷判断：错误链里是否包含「地址被策略拒绝」。
func IsBlockedError(err error) bool { return errors.Is(err, ErrBlockedAddress) }

// ---------- 本机监听地址 ----------

var (
	localMu    sync.Mutex
	localSet   map[netip.Addr]struct{}
	localAt    time.Time
	localAddrs = 30 * time.Second
)

// IsLocalAddr 判断 addr 是否是本机某个网卡上真实存在的地址。
//
// 结果缓存 30 秒：拨号是热路径（每个上游重连都会走），
// 而 InterfaceAddrs 在部分平台上是一次系统调用。
// 30 秒的窗口意味着「刚给 NAS 加了新 IP」最多晚半分钟生效，可以接受。
func IsLocalAddr(addr netip.Addr) bool {
	if !addr.IsValid() {
		return true
	}
	addr = addr.Unmap().WithZone("")

	localMu.Lock()
	defer localMu.Unlock()
	now := time.Now()
	if localSet == nil || now.Sub(localAt) > localAddrs {
		localSet = readLocalAddrs()
		localAt = now
	}
	_, ok := localSet[addr]
	return ok
}

func readLocalAddrs() map[netip.Addr]struct{} {
	out := map[netip.Addr]struct{}{}
	ifaces, err := net.InterfaceAddrs()
	if err != nil {
		// 读不到就当「没有本机地址」：loopback/链路本地那几条硬规则仍然生效，
		// 不能因为系统调用失败就把请求全放过去（那是更差的结果）。
		return out
	}
	for _, a := range ifaces {
		var s string
		switch v := a.(type) {
		case *net.IPNet:
			s = v.IP.String()
		case *net.IPAddr:
			s = v.IP.String()
		default:
			continue
		}
		if ip, perr := netip.ParseAddr(s); perr == nil {
			out[ip.Unmap().WithZone("")] = struct{}{}
		}
	}
	return out
}
