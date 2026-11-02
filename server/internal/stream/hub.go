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

	// MaxUpstreams 同时在跑的上游连接上限（B02）。<=0 表示不限制。
	//
	// 修复前 MaxConcurrent 只是个配置项和界面上的一行数字，
	// 从没在创建上游处真正生效。NAS 的上行带宽、fd 和内存都是有限的，
	// 用户把几百个台点一遍就能把整台机器拖垮。
	MaxUpstreams int

	// OnData 每读到一块上游数据时回调（用于时移录制）。
	// 必须快速返回，不要在里面做阻塞 IO——它在扇出热路径上。
	OnData func(channelID int64, data []byte)
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

// subscriberCount 返回当前订阅者数量。
//
// 用于判断上游是否「空闲」（可以为了腾名额而回收）。
// subs 由 u.mu 保护，这里必须走同一把锁，否则 -race 会报 DATA RACE。
// 锁顺序：调用方持 h.mu，这里再取 u.mu —— 与 close()/isClosed() 的顺序一致，
// 不会形成反向依赖。
func (u *Upstream) subscriberCount() int {
	u.mu.RLock()
	defer u.mu.RUnlock()
	return len(u.subs)
}

// isClosed 报上游是否已经收尾。
//
// 为什么要包一层而不是直接读 u.closed：closed 由 u.mu 保护，
// 但 Hub.Subscribe 是在 h.mu 下判断上游是否可复用的。
// 用 h.mu 去读一个由 u.mu 保护的字段等于没有同步，-race 会直接报 DATA RACE
// （而且真机上可能读到撕裂的旧值，把新订阅挂到一个正在关闭的上游上）。
// 所有对 closed 的访问都必须经过 u.mu，这里就是唯一入口。
func (u *Upstream) isClosed() bool {
	u.mu.RLock()
	defer u.mu.RUnlock()
	return u.closed
}

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
	// 最后一次收到上游数据的 UnixNano。0 = 还没收到过。
	// 用来判断「连接还开着但已经不推数据了」这种半死状态。
	lastData atomic.Int64

	cancel context.CancelFunc
	nextID atomic.Int64
}

// Subscribe 订阅一个频道。若该频道已有上游则直接复用。
func (h *Hub) Subscribe(ctx context.Context, channelID int64, url string, headers map[string]string) (*Subscriber, error) {
	h.mu.Lock()
	up, ok := h.ups[channelID]

	// 上游卡死判定：连接还开着，但已经不推数据了。
	// 不重建的话，新订阅者会挂在这个死连接上一直转圈。
	stale := false
	if ok && up != nil && !up.isClosed() {
		now := time.Now()
		if last := up.lastData.Load(); last > 0 {
			stale = now.Sub(time.Unix(0, last)) > 15*time.Second
		} else if now.Sub(up.started) > 20*time.Second {
			// 建了 20 秒一个字节都没收到
			stale = true
		}
	}

	if !ok || up.isClosed() || up.URL != url || stale {
		// 新建（或替换：源换了地址）
		//
		// B02：在真正创建之前原子地检查全局上限。
		// 检查与插入都在 h.mu 内完成，所以并发请求不会同时越过限制。
		// 只有"新增"才受限；替换同频道的上游不算涨数（它本来就在 map 里）。
		if up == nil && h.opts.MaxUpstreams > 0 && h.liveCountLocked() >= h.opts.MaxUpstreams {
			// 先尝试回收一个**空闲**上游，再决定是否拒绝。
			//
			// 为什么必须这样：上游在最后一个订阅者离开后还要等 IdleTimeout
			// （默认 60 秒）才真正回收。若把"等待回收的空闲连接"也算满，
			// 用户连换几个台就会撞上限——表现为"换到某个台突然播不了"。
			// 限额的本意是限制**同时在看的流**，不是限制历史残留。
			if !h.evictIdleLocked() {
				h.mu.Unlock()
				return nil, ErrTooManyStreams
			}
		}
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
			"channel_id":  up.ChannelID,
			"url":         up.URL,
			"subscribers": len(up.subs),
			"uptime_s":    int(time.Since(up.started).Seconds()),
			"bytes_in":    up.bytesIn.Load(),
			"last_error":  up.lastErr,
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
//
// B02：以前直接 return len(h.ups)，但 map 里会残留已经 close 却尚未
// 从表中删除的条目（close 在上游 goroutine 里异步收尾），
// 于是这个数字虚高。拿它做限额判断会出现"明明没超额却报满了"。
// 这里只统计真正还在跑的上游。
func (h *Hub) Count() int {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.liveCountLocked()
}

// liveCountLocked 统计未关闭的上游。调用方必须已持有 h.mu。
func (h *Hub) liveCountLocked() int {
	n := 0
	for _, u := range h.ups {
		if u != nil && !u.isClosed() {
			n++
		}
	}
	return n
}

// evictIdleLocked 关闭并移除一个空闲上游，给新请求腾出名额。
//
// 判定「空闲」要带宽限期：刚创建、尚未完成首次订阅的上游同样是 0 订阅者，
// 若立刻回收会误伤刚刚发起的播放。所以只回收「存在了一段时间且确实没人看」的。
//
// 已关闭但仍留在 map 里的条目也顺手清掉：它不占资源，但会污染计数。
// 返回 true 表示腾出了名额。
func (h *Hub) evictIdleLocked() bool {
	const grace = 3 * time.Second
	for id, u := range h.ups {
		if u == nil || u.isClosed() {
			delete(h.ups, id)
			return true
		}
		if u.subscriberCount() == 0 && time.Since(u.started) > grace {
			delete(h.ups, id)
			u.close()
			return true
		}
	}
	return false
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
	// 必须在读锁内就地发送，不能先拷贝 subs、放锁后再发。
	//
	// 旧实现就是「RLock 下拷贝 → 放锁 → 逐个非阻塞发送」，
	// 而 close()/unsubscribe() 是在 u.mu.Lock() 下 close(s.ch) 的。
	// 两者没有共同的同步约束，拷贝与 close 之间那条缝隙一旦命中，
	// 就是 send on closed channel。这个 panic 发生在上游读取 goroutine 里，
	// HTTP 层的 Recoverer 兜不住，会直接打死整个 litvd 进程。
	//
	// 现在的收敛方式：订阅者的通道只在持锁、且已从 u.subs 里摘除之后才关闭；
	// 扇出只在持读锁、且订阅者仍在 u.subs 里时才发送。读锁与写锁互斥，
	// 所以「正在用的通道」不可能被并发关闭——单一 close owner 由此成立。
	//
	// 持锁会不会反压上游？不会：发送全部走 default 分支，永不阻塞，
	// 慢客户端只丢块并计数。所以「一个卡死的客户端不能拖垮所有人」的设计意图保留。
	u.mu.RLock()
	defer u.mu.RUnlock()
	for s := range u.subs {
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
			u.lastData.Store(time.Now().UnixNano())
			// 拷贝一份给订阅者共享（buf 会被下一轮覆盖）
			chunk := make([]byte, n)
			copy(chunk, buf[:n])
			u.appendRing(chunk)
			u.broadcast(chunk)
			// 时移录制：只在**确实有人在看**时才 tee 到磁盘。
			//
			// 空闲上游（最后一个订阅者已离开、还在等 IdleTimeout 回收）
			// 会继续从上游读数据。若这时照常写时移盘，等于为没人看的频道
			// 持续写盘——NAS 上几个台叠加就是持续的磁盘 IO，
			// 反过来拖慢正在看的那个台（表现为换台后卡一下）。
			//
			// 时移的语义本来就是「看过就有回看」，没人看就不必录。
			if u.opts.OnData != nil && u.subscriberCount() > 0 {
				u.opts.OnData(u.ChannelID, chunk)
			}
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
