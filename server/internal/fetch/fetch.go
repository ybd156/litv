// Package fetch 负责抓取远端订阅源。
//
// 这一层要处理的现实问题：
//   - 很多源站会拒绝非浏览器 UA
//   - 部分源返回 gzip / deflate
//   - 有的源响应极慢，必须设超时，否则会把管理台"刷新"按钮卡死
//   - 大小限制：防止用户误填一个大文件地址把内存吃爆
package fetch

import (
	"bytes"
	"compress/gzip"
	"context"
	"crypto/tls"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strings"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/litv/litv/server/internal/safedial"
)

// Result 是抓取结果。
type Result struct {
	Body        []byte
	ContentType string
	FinalURL    string
	ElapsedMs   int64
}

// Options 控制抓取行为。
type Options struct {
	Timeout   time.Duration
	UserAgent string
	MaxBytes  int64
	Headers   map[string]string
}

// DefaultOptions 给一份适合国内源站的默认值。
func DefaultOptions() Options {
	return Options{
		// 45 秒：给大源留余量。
		//
		// 真机反馈：live.ottiptv.cc 这类 Cloudflare 前置、带一长串签名参数的源，
		// 20 秒会稳定失败在 "timeout awaiting response headers"。
		// 这类站生成几万条频道的 m3u 本身就要十几秒，不是故障。
		Timeout:   45 * time.Second,
		UserAgent: "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36",
		MaxBytes:  32 << 20, // 32MB，足够放下几万条频道
	}
}

var client = NewClient(0)

// allowLoopback 只给测试用（httptest 起在 127.0.0.1）。
// 生产路径必须保持关闭，否则服务端就成了访问本机管理面的跳板。
var allowLoopback atomic.Bool

// SetAllowLoopback 打开/关闭对回环地址的访问。只应在测试初始化时调用。
func SetAllowLoopback(v bool) { allowLoopback.Store(v) }

// checkIP 拒绝「本机 / 链路本地 / 未指定 / 组播」地址。
//
// 私网（RFC1918、ULA）默认放行：很多用户的自建源就在局域网里，
// 一刀切会让「源在 NAS 同网段」这种最常见的部署直接不可用。
// 这里挡住的是 SSRF 最危险的那一档——本机服务与云元数据地址。
func checkIP(ip net.IP) error {
	if ip == nil {
		return fmt.Errorf("地址不合法")
	}
	if allowLoopback.Load() {
		return nil
	}
	if ip.IsLoopback() || ip.IsUnspecified() || ip.IsMulticast() ||
		ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast() {
		return fmt.Errorf("禁止访问本机或链路本地地址")
	}
	// 本机网卡地址（比如 NAS 自己的 192.168.x.x）同样要拒。
	// 只拒回环挡不住「远端内容指向本机 LAN 地址」这条路径——
	// 对服务端来说那是自己的 HTTP 管理面，不是外部站点。
	if addr, ok := netip.AddrFromSlice(ip); ok && safedial.IsLocalAddr(addr) {
		return fmt.Errorf("禁止访问本机地址")
	}
	return nil
}

// ValidateURL 是出站抓取的入口校验：只允许 http/https，并拒绝字面量形式的
// 回环/链路本地地址。
//
// 光靠这一步不够——域名可以在两次解析之间换 IP（DNS rebinding），
// 所以真正的拦截在 DialContext 里按**解析结果**再做一次。
func ValidateURL(raw string) error {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil {
		return fmt.Errorf("地址不合法: %w", err)
	}
	if u.Scheme != "http" && u.Scheme != "https" {
		return fmt.Errorf("只允许 http/https 地址")
	}
	host := u.Hostname()
	if host == "" {
		return fmt.Errorf("地址缺少主机名")
	}
	// localhost 是域名形式的本机地址，必须在入口就挡住：
	// DialContext 的解析结果校验也能拦，但这里拦得更早、错误信息更清楚。
	if strings.EqualFold(host, "localhost") || strings.HasSuffix(strings.ToLower(host), ".localhost") {
		return fmt.Errorf("禁止访问本机或链路本地地址")
	}
	if ip := net.ParseIP(host); ip != nil {
		return checkIP(ip)
	}
	return nil
}

// safeDialer 在真正建连前按解析结果校验每一个 IP。
func safeDialer(d *net.Dialer) func(ctx context.Context, network, addr string) (net.Conn, error) {
	return func(ctx context.Context, network, addr string) (net.Conn, error) {
		host, port, err := net.SplitHostPort(addr)
		if err != nil {
			return nil, err
		}
		ips, err := net.DefaultResolver.LookupIPAddr(ctx, host)
		if err != nil {
			return nil, err
		}
		var lastErr error
		for _, ipa := range ips {
			if cerr := checkIP(ipa.IP); cerr != nil {
				lastErr = cerr
				continue
			}
			conn, derr := d.DialContext(ctx, network, net.JoinHostPort(ipa.IP.String(), port))
			if derr == nil {
				return conn, nil
			}
			lastErr = derr
		}
		if lastErr == nil {
			lastErr = fmt.Errorf("地址解析失败: %s", host)
		}
		return nil, lastErr
	}
}

func newTransport() *http.Transport {
	return &http.Transport{
		Proxy: http.ProxyFromEnvironment,
		DialContext: safeDialer(&net.Dialer{
			Timeout:   8 * time.Second,
			KeepAlive: 30 * time.Second,
			// 生产 safedial 策略：connect(2) 之前按真实 IP 判一次。
			// 分片代理早就接了这条，订阅抓取这个入口之前漏了。
			//
			// AllowLoopback 跟随 fetch 自己的开关：测试要连 httptest（127.0.0.1），
			// 生产默认 false。用 DefaultPolicy() 的静态值会把既有测试全打红。
			Control: func(network, address string, c syscall.RawConn) error {
				p := safedial.DefaultPolicy()
				p.AllowLoopback = allowLoopback.Load()
				return p.Control(network, address, c)
			},
		}),
		TLSHandshakeTimeout: 8 * time.Second,
		// 30 秒：见 DefaultOptions 的说明。12 秒对慢源站太紧。
		ResponseHeaderTimeout: 30 * time.Second,
		MaxIdleConns:          32,
		IdleConnTimeout:       60 * time.Second,
		// 关掉 HTTP/2：实测某些源站（live.ottiptv.cc 这类 Cloudflare 前置的）
		// 对 Go 的 h2 客户端会一直挂着不返回响应头，报
		// "http2: timeout awaiting response headers" 或 context deadline exceeded；
		// 同一个地址用 curl（h1.1 或 h2）都是 200 秒回。
		// 抓清单/分片这种小请求走 h1.1 没有任何损失。
		ForceAttemptHTTP2: false,
		TLSNextProto:      map[string]func(string, *tls.Conn) http.RoundTripper{},
	}
}

// NewTransport 返回带 SSRF 校验的 Transport。
//
// 直播上游（stream.Hub）要自己控制「等响应头的超时」和连接数，
// 所以单独导出，由调用方在返回的 Transport 上改参数。
func NewTransport() *http.Transport { return newTransport() }

// NewClient 返回带 SSRF 防护和重定向上限的 HTTP 客户端。
//
// timeout <= 0 表示不设总超时，由调用方 ctx 控制（直播/分片这类长连接）。
// 之所以要导出：分片代理必须用带超时的 client，
// 以前的 http.DefaultClient 没有超时，一个卡住的源会永久占住一个 goroutine。
func NewClient(timeout time.Duration) *http.Client {
	return &http.Client{
		Transport: newTransport(),
		Timeout:   timeout,
		// 不自动跟随无限重定向
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) >= 8 {
				return fmt.Errorf("重定向次数过多")
			}
			return nil
		},
	}
}

// Get 抓取一个 URL。
func Get(ctx context.Context, url string, opt Options) (*Result, error) {
	if err := ValidateURL(url); err != nil {
		return nil, err
	}
	if opt.Timeout <= 0 {
		opt.Timeout = 45 * time.Second
	}
	if opt.MaxBytes <= 0 {
		opt.MaxBytes = 32 << 20
	}
	start := time.Now()

	ctx, cancel := context.WithTimeout(ctx, opt.Timeout)
	defer cancel()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, fmt.Errorf("构造请求失败: %w", err)
	}
	ua := opt.UserAgent
	if ua == "" {
		ua = DefaultOptions().UserAgent
	}
	req.Header.Set("User-Agent", ua)
	req.Header.Set("Accept", "*/*")
	req.Header.Set("Accept-Language", "zh-CN,zh;q=0.9")
	for k, v := range opt.Headers {
		req.Header.Set(k, v)
	}

	resp, err := client.Do(req)
	if err != nil {
		// 把最常见的两种超时说成人话：用户看到的原始错误是
		//   net/http: timeout awaiting response headers
		//   context deadline exceeded
		// 光看这两句没人知道该改什么。
		msg := err.Error()
		switch {
		case strings.Contains(msg, "timeout awaiting response headers"):
			return nil, fmt.Errorf("源站 %d 秒内没有返回响应头：可能是源站限流、"+
				"签名参数（sign/auth_token）已过期，或 NAS 到该站网络不通", int(opt.Timeout.Seconds()))
		case strings.Contains(msg, "context deadline exceeded") || strings.Contains(msg, "Client.Timeout"):
			return nil, fmt.Errorf("抓取超时（%d 秒）：源站响应太慢，或 NAS 到该站网络不通", int(opt.Timeout.Seconds()))
		}
		return nil, fmt.Errorf("请求失败: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("HTTP %d", resp.StatusCode)
	}

	body, err := io.ReadAll(io.LimitReader(resp.Body, opt.MaxBytes))
	if err != nil {
		return nil, fmt.Errorf("读取响应失败: %w", err)
	}
	if len(body) == 0 {
		return nil, fmt.Errorf("响应为空")
	}

	// .xml.gz / .m3u.gz 这类文件是按"附件"发的（Content-Type 不是 gzip），
	// Go 的 http 客户端不会自动解压，得自己按魔数判断。
	// EPG 源大量用 t.xml.gz，不解压解析器只会看到一堆二进制。
	if len(body) > 2 && body[0] == 0x1f && body[1] == 0x8b {
		zr, zerr := gzip.NewReader(bytes.NewReader(body))
		if zerr == nil {
			defer zr.Close()
			plain, derr := io.ReadAll(io.LimitReader(zr, opt.MaxBytes))
			if derr == nil && len(plain) > 0 {
				body = plain
			}
		}
	}

	return &Result{
		Body:        body,
		ContentType: resp.Header.Get("Content-Type"),
		FinalURL:    resp.Request.URL.String(),
		ElapsedMs:   time.Since(start).Milliseconds(),
	}, nil
}

// GuessType 根据 URL 后缀与 Content-Type 猜格式，作为 parse.Detect 的前置提示。
func GuessType(url, contentType string) string {
	u := strings.ToLower(url)
	ct := strings.ToLower(contentType)
	switch {
	case strings.Contains(u, ".m3u8") || strings.Contains(u, ".m3u"):
		return "m3u"
	case strings.Contains(u, ".txt"):
		return "txt"
	case strings.Contains(u, ".json"):
		return "tvbox"
	case strings.Contains(ct, "json"):
		return "tvbox"
	case strings.Contains(ct, "mpegurl"):
		return "m3u"
	}
	return ""
}
