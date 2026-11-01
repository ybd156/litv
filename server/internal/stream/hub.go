// Package stream 实现「一个上游 → N 个终端」的扇出（fanout）。
//
// 为什么必须自己做：家庭里 3 台设备看同一个频道，如果各自去拉上游，
// 就是 3 倍带宽、3 倍上游压力、换台还要各自等首帧。
// 正确做法是：服务端只拉 1 路上游，维护一个环形缓冲，
// 新客户端直接从缓冲区里最近的 GOP 起播——这才是「毫秒级换台」的来源。
package stream

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"sync"
	"sync/atomic"
	"time"
)

// ErrTooManyStreams 并发流数超限。
var ErrTooManyStreams = errors.New("并发流数量已达上限")

// Options 是 Hub 的运行参数。
type Options struct {
	RingSize    int           // 每频道环形缓冲字节数
	ChunkSize   int           // 单次读取块大小
	UserAgent   string        // 上游 UA
	Timeout     time.Duration // 上游连接超时
	IdleTimeout time.Duration // 无订阅者多久后回收上游
}

// Hub 管理所有活跃的上游连接。
type Hub struct {
	opts Options
	mu   sync.Mutex
	ups  map[int64]*Upstream // key = channelID
}

// NewHub 创建 Hub。
func NewHub(opts Options) *Hub {
	if opts.RingSize <= 0 {
		opts.RingSize = 2 << 20
	}
	if opts.ChunkSize <= 0 {
		opts.ChunkSize = 32 << 10
	}
	if opts.Timeout <= 0 {
		opts.Timeout = 15 * time.Second
	}
	if opts.IdleTimeout <= 0 {
		opts.IdleTimeout = 60 * time.Second
	}
	return &Hub{opts: opts, ups: make(map[int64]*Upstream)}
}

// Subscriber 是一个终端连接。
type Subscriber struct {
	ID      int64
	C       <-chan []byte
	ch      chan []byte
	up      *Upstream
	dropped atomic.Int64
}

// Dropped 返回因下游太慢而丢弃的块数（用于诊断卡顿）。
func (s *Subscriber) Dropped() int64 { return s.dropped.Load() }

// Upstream 是一路上游连接。
type Upstream struct {
	ChannelID int64
	URL       string
	Headers   map[string]string

	opts Options

	mu      sync.RWMutex
	ring    []byte
	subs    map[*Subscriber]struct{}
	closed  bool
	lastErr string
	started time.Time
	bytesIn atomic.Int64

	cancel context.CancelFunc
	nextID atomic.Int64
}

// Subscribe 订阅一个频道。若该频道已有上游则直接复用。
func (h *Hub) Subscribe(ctx context.Context, channelID int64, url string, headers map[string]string) (*Subscriber, error) {
	h.mu.Lock()
	up, ok := h.ups[channelID]
	if !ok || up.closed || up.URL != url {
		// 新建（或替换：源换了地址）
		if up != nil {
			up.close()
		}
		var cancel context.CancelFunc
		ctx2, c := context.WithCancel(context.Background())
		cancel = c
		up = &Upstream{
			ChannelID: channelID,
			URL:       url,
			Headers:   headers,
			opts:      h.opts,
			subs:      make(map[*Subscriber]struct{}),
			started:   time.Now(),
			cancel:    cancel,
		}
		h.ups[channelID] = up
		go up.run(ctx2)
	}
	h.mu.Unlock()

	return up.subscribe(ctx)
}

// Stats 返回当前活跃上游概况。
func (h *Hub) Stats() []map[string]any {
	h.mu.Lock()
	defer h.mu.Unlock()
	out := make([]map[string]any, 0, len(h.ups))
	for _, up := range h.ups {
		up.mu.RLock()
		out = append(out, map[string]any{
			"channel_id": up.ChannelID,
			"url":        up.URL,
			"subscribers": len(up.subs),
			"uptime_s":   int(time.Since(up.started).Seconds()),
			"bytes_in":   up.bytesIn.Load(),
			"last_error": up.lastErr,
		})
		up.mu.RUnlock()
	}
	return out
}

// Close 关闭全部上游。
func (h *Hub) Close() {
	h.mu.Lock()
	defer h.mu.Unlock()
	for _, up := range h.ups {
		up.close()
	}
	h.ups = map[int64]*Upstream{}
}

// Count 返回活跃频道数。
func (h *Hub) Count() int {
	h.mu.Lock()
	defer h.mu.Unlock()
	return len(h.ups)
}

// ---------- Upstream 内部 ----------

func (u *Upstream) subscribe(ctx context.Context) (*Subscriber, error) {
	u.mu.Lock()
	if u.closed {
		u.mu.Unlock()
		return nil, fmt.Errorf("上游已关闭")
	}
	s := &Subscriber{
		ID: u.nextID.Add(1),
		ch: make(chan []byte, 256),
		up: u,
	}
	// 关键：把缓冲区里已有的数据先灌进去，让新客户端立刻有画面
	if len(u.ring) > 0 {
		snapshot := make([]byte, len(u.ring))
		copy(snapshot, u.ring)
		select {
		case s.ch <- snapshot:
		default:
		}
	}
	u.subs[s] = struct{}{}
	u.mu.Unlock()

	s.C = s.ch

	// 下游断开时自动注销
	go func() {
		<-ctx.Done()
		u.unsubscribe(s)
	}()
	return s, nil
}

func (u *Upstream) unsubscribe(s *Subscriber) {
	u.mu.Lock()
	if _, ok := u.subs[s]; ok {
		delete(u.subs, s)
		close(s.ch)
	}
	empty := len(u.subs) == 0
	u.mu.Unlock()

	if empty {
		// 没人看了，等一会儿再回收；期间可能有人回来接着看
		time.AfterFunc(u.opts.IdleTimeout, func() {
			u.mu.RLock()
			stillEmpty := len(u.subs) == 0
			u.mu.RUnlock()
			if stillEmpty {
				u.close()
			}
		})
	}
}

func (u *Upstream) close() {
	u.mu.Lock()
	if u.closed {
		u.mu.Unlock()
		return
	}
	u.closed = true
	for s := range u.subs {
		delete(u.subs, s)
		close(s.ch)
	}
	u.mu.Unlock()
	if u.cancel != nil {
		u.cancel()
	}
}

func (u *Upstream) broadcast(chunk []byte) {
	u.mu.RLock()
	subs := make([]*Subscriber, 0, len(u.subs))
	for s := range u.subs {
		subs = append(subs, s)
	}
	u.mu.RUnlock()

	for _, s := range subs {
		select {
		case s.ch <- chunk:
		default:
			// 下游太慢：丢块而不是阻塞上游，避免一个卡死的客户端拖垮所有人
			s.dropped.Add(1)
		}
	}
}

// run 是上游读取循环，带自动重连。
func (u *Upstream) run(ctx context.Context) {
	defer u.close()

	backoff := time.Second
	for {
		if ctx.Err() != nil {
			return
		}
		err := u.readOnce(ctx)
		if ctx.Err() != nil {
			return
		}
		if err != nil {
			u.mu.Lock()
			u.lastErr = err.Error()
			u.mu.Unlock()
			slog.Warn("上游中断，准备重连", "channel", u.ChannelID, "err", err, "backoff", backoff)
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(backoff):
		}
		if backoff < 10*time.Second {
			backoff *= 2
		}
	}
}

func (u *Upstream) readOnce(ctx context.Context) error {
	reqCtx, cancel := context.WithCancel(ctx)
	defer cancel()

	req, err := http.NewRequestWithContext(reqCtx, http.MethodGet, u.URL, nil)
	if err != nil {
		return err
	}
	ua := u.opts.UserAgent
	if ua == "" {
		ua = "LITV/1.0"
	}
	req.Header.Set("User-Agent", ua)
	req.Header.Set("Accept", "*/*")
	// 直播流不要缓存
	req.Header.Set("Cache-Control", "no-cache")
	for k, v := range u.Headers {
		req.Header.Set(k, v)
	}

	client := &http.Client{
		Timeout: 0, // 直播流不设总超时，靠 ctx 控制
		Transport: &http.Transport{
			ResponseHeaderTimeout: u.opts.Timeout,
			DisableCompression:    true,
			MaxIdleConnsPerHost:   4,
		},
	}
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("上游返回 HTTP %d", resp.StatusCode)
	}

	buf := make([]byte, u.opts.ChunkSize)
	for {
		n, err := resp.Body.Read(buf)
		if n > 0 {
			u.bytesIn.Add(int64(n))
			// 拷贝一份给订阅者共享（buf 会被下一轮覆盖）
			chunk := make([]byte, n)
			copy(chunk, buf[:n])
			u.appendRing(chunk)
			u.broadcast(chunk)
		}
		if err != nil {
			if errors.Is(err, io.EOF) {
				return fmt.Errorf("上游关闭连接")
			}
			return err
		}
	}
}

// appendRing 维护环形缓冲。丢弃旧数据时对齐到 188 字节（MPEG-TS 包长），
// 这样新订阅者拿到的第一块大概率从包边界开始，减少花屏。
func (u *Upstream) appendRing(chunk []byte) {
	u.mu.Lock()
	defer u.mu.Unlock()
	u.ring = append(u.ring, chunk...)
	if len(u.ring) <= u.opts.RingSize {
		return
	}
	drop := len(u.ring) - u.opts.RingSize
	const tsPacket = 188
	drop = (drop / tsPacket) * tsPacket
	if drop <= 0 {
		return
	}
	rest := make([]byte, len(u.ring)-drop)
	copy(rest, u.ring[drop:])
	u.ring = rest
}
