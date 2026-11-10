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
	"net/http"
	"strings"
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
		Timeout:   20 * time.Second,
		UserAgent: "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36",
		MaxBytes:  32 << 20, // 32MB，足够放下几万条频道
	}
}

// newTransport 造一个抓取用 Transport。
//
// 两条必须保留的既有结论：
//   - 关掉 HTTP/2：实测某些源站（live.ottiptv.cc 这类 Cloudflare 前置的）
//     对 Go 的 h2 客户端会一直挂着不返回响应头，报
//     "http2: timeout awaiting response headers" 或 context deadline exceeded；
//     同一个地址用 curl（h1.1 或 h2）都是 200 秒回。抓清单/分片走 h1.1 没有损失。
//   - 不设 Proxy：http.ProxyFromEnvironment 会让实际连接目标变成代理地址，
//     地址策略要么误杀用户正常的代理，要么被代理整个绕过去。
//     需要代理的场景应该在配置里显式声明，而不是靠环境变量悄悄改出站路径。
//
// 出站地址策略用 safedial 的默认档（全项目只有这一档）：
// 放行 RFC1918 / IPv6 ULA（LAN 源是产品功能），拒绝 loopback、链路本地、
// CGNAT 与本机网卡地址（挡住"打 NAS 自己"和云元数据端点）。
func newTransport() *http.Transport {
	return &http.Transport{
		DialContext:           safedial.DefaultPolicy().DialContext,
		TLSHandshakeTimeout:   8 * time.Second,
		ResponseHeaderTimeout: 12 * time.Second,
		MaxIdleConns:          32,
		IdleConnTimeout:       60 * time.Second,
		ForceAttemptHTTP2:     false,
		TLSNextProto:          map[string]func(string, *tls.Conn) http.RoundTripper{},
	}
}

// client 是包级复用的抓取客户端。
//
// 不要每次请求新建：Transport 被丢弃后它的空闲连接不会被主动关掉，
// 长期运行会漏 fd。
var client = &http.Client{
	Transport: newTransport(),
	// 不自动跟随无限重定向。
	//
	// 地址判定不在这里做：safedial 是在 DialContext（connect 之前）判的，
	// 每一次重定向都是一次新的拨号，所以自动被同一套策略覆盖，
	// 不需要（也不该）在 URL 层再判一遍——那既重复又更容易被绕过。
	CheckRedirect: func(req *http.Request, via []*http.Request) error {
		if len(via) >= maxRedirects {
			return fmt.Errorf("重定向次数过多")
		}
		return nil
	},
}

const maxRedirects = 8

// Get 抓取一个 URL。
func Get(ctx context.Context, url string, opt Options) (*Result, error) {
	if opt.Timeout <= 0 {
		opt.Timeout = 20 * time.Second
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
		// 被地址策略拦下的错误单独标注：调用方据此决定「不要重试」，
		// 也别把它当成「源站挂了」去累加失败次数。
		if safedial.IsBlockedError(err) {
			return nil, fmt.Errorf("目标地址被出站策略拒绝: %w", err)
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
