// Package fetch 负责抓取远端订阅源。
//
// 这一层要处理的现实问题：
//   - 很多源站会拒绝非浏览器 UA
//   - 部分源返回 gzip / deflate
//   - 有的源响应极慢，必须设超时，否则会把管理台"刷新"按钮卡死
//   - 大小限制：防止用户误填一个大文件地址把内存吃爆
package fetch

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"time"
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

var client = &http.Client{
	Transport: &http.Transport{
		Proxy: http.ProxyFromEnvironment,
		DialContext: (&net.Dialer{
			Timeout:   8 * time.Second,
			KeepAlive: 30 * time.Second,
		}).DialContext,
		TLSHandshakeTimeout:   8 * time.Second,
		ResponseHeaderTimeout: 12 * time.Second,
		MaxIdleConns:          32,
		IdleConnTimeout:       60 * time.Second,
		ForceAttemptHTTP2:     true,
	},
	// 不自动跟随无限重定向
	CheckRedirect: func(req *http.Request, via []*http.Request) error {
		if len(via) >= 8 {
			return fmt.Errorf("重定向次数过多")
		}
		return nil
	},
}

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
