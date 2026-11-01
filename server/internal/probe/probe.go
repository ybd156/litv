// Package probe 做源健康探测与自动择优。
//
// 为什么必须做：一个频道挂 3 条线路，用户不该知道哪条是好的。
// 服务端定期探测、记录延迟和可用性，终端只管按 BestStream 拿最优的那条。
// 这样"某个源挂了"这件事对用户完全透明——换台时自动走好的线路。
package probe

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/litv/litv/server/internal/store"
)

// Prober 周期性探测所有线路。
type Prober struct {
	st          *store.Store
	timeout     time.Duration
	concurrency int
	userAgent   string
	client      *http.Client

	// OnDone 每轮探测结束后回调（用于告警、指标上报）。
	// 在探测 goroutine 里同步调用，别做重活。
	OnDone func(ctx context.Context, res *Result)
}

// New 创建探测器。
func New(st *store.Store, timeout time.Duration, concurrency int, userAgent string) *Prober {
	if timeout <= 0 {
		timeout = 8 * time.Second
	}
	if concurrency <= 0 {
		concurrency = 6
	}
	return &Prober{
		st:          st,
		timeout:     timeout,
		concurrency: concurrency,
		userAgent:   userAgent,
		client: &http.Client{
			Timeout: timeout,
			Transport: &http.Transport{
				MaxIdleConns:        32,
				MaxIdleConnsPerHost: 4,
				IdleConnTimeout:     30 * time.Second,
				DisableCompression:  true,
			},
			CheckRedirect: func(req *http.Request, via []*http.Request) error {
				if len(via) >= 5 {
					return http.ErrUseLastResponse
				}
				return nil
			},
		},
	}
}

// Result 是一次探测的汇总。
type Result struct {
	Checked int `json:"checked"`
	Healthy int `json:"healthy"`
	Failed  int `json:"failed"`
}

// RunOnce 跑一轮探测。
//
// 设计取舍：只读前 128KB 就断开，不拉整个流——探测的目的是"能不能播"，
// 不是"播完"，否则一轮探测就把家里带宽打满了。
func (p *Prober) RunOnce(ctx context.Context) (*Result, error) {
	streams, err := p.st.ListStreamsForProbe(ctx, 500)
	if err != nil {
		return nil, err
	}
	res := &Result{Checked: len(streams)}
	if len(streams) == 0 {
		return res, nil
	}

	sem := make(chan struct{}, p.concurrency)
	var wg sync.WaitGroup
	var mu sync.Mutex

	for _, s := range streams {
		s := s
		wg.Add(1)
		sem <- struct{}{}
		go func() {
			defer wg.Done()
			defer func() { <-sem }()

			healthy, latency := p.check(ctx, s.URL)
			health := 0
			if healthy {
				health = 1
			}
			_ = p.st.UpdateStreamHealth(ctx, s.ID, health, latency)

			mu.Lock()
			if healthy {
				res.Healthy++
			} else {
				res.Failed++
			}
			mu.Unlock()
		}()
	}
	wg.Wait()
	return res, nil
}

// check 返回 (是否可用, 延迟毫秒)。
func (p *Prober) check(ctx context.Context, url string) (bool, int) {
	// udp/rtp 组播没法用 HTTP 探，直接标记"未知"当健康处理（本地组播通常都是通的）
	lower := strings.ToLower(url)
	if strings.HasPrefix(lower, "udp://") || strings.HasPrefix(lower, "rtp://") || strings.HasPrefix(lower, "igmp://") {
		return true, 0
	}

	cctx, cancel := context.WithTimeout(ctx, p.timeout)
	defer cancel()

	start := time.Now()
	req, err := http.NewRequestWithContext(cctx, http.MethodGet, url, nil)
	if err != nil {
		return false, 0
	}
	req.Header.Set("User-Agent", p.userAgent)
	// 只要开头一点点，够判断"有没有数据"就行
	req.Header.Set("Range", "bytes=0-131071")
	req.Header.Set("Accept", "*/*")

	resp, err := p.client.Do(req)
	if err != nil {
		return false, 0
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusPartialContent {
		return false, 0
	}

	// HLS 清单还要校验内容像不像 m3u8
	if strings.Contains(lower, ".m3u8") {
		head := make([]byte, 512)
		n, _ := io.ReadFull(resp.Body, head)
		body := string(head[:n])
		if !strings.Contains(body, "#EXTM3U") && !strings.Contains(body, "#EXTINF") {
			return false, 0
		}
	} else {
		buf := make([]byte, 4096)
		n, _ := resp.Body.Read(buf)
		if n == 0 {
			return false, 0
		}
	}

	return true, int(time.Since(start).Milliseconds())
}

// Run 起一个后台循环，按 interval 反复探测。
func (p *Prober) Run(ctx context.Context, interval time.Duration) {
	if interval <= 0 {
		interval = 15 * time.Minute
	}
	// 启动后先等 30 秒，避免和服务启动抢资源
	select {
	case <-ctx.Done():
		return
	case <-time.After(30 * time.Second):
	}

	for {
		res, err := p.RunOnce(ctx)
		if err != nil {
			slog.Warn("源探测失败", "err", err)
		} else if res.Checked > 0 {
			slog.Info("源探测完成", "checked", res.Checked, "healthy", res.Healthy, "failed", res.Failed)
			if p.OnDone != nil {
				p.OnDone(ctx, res)
			}
		}

		select {
		case <-ctx.Done():
			return
		case <-time.After(interval):
		}
	}
}
