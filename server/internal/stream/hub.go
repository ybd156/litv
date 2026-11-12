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

	"github.com/litv/litv/server/internal/fetch"
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
	//
	// 它**不在**扇出热路径上：Hub 会先把数据丢进每频道的定长队列，
	// 由单独的写协程调用这个回调（见 tee.go），所以这里做阻塞的磁盘写
	// 是安全的。要小心的是别在回调里再调回 Hub（会打死锁）。
	OnData func(channelID int64, data []byte)

	// MaxTeeStreams 是全局同时进行的时移 tee 上限（<=0 用默认 64）。
	MaxTeeStreams int

	// TeeQueueBlocks 是每频道时移队列的块数（<=0 用默认 16，上限 64）。
	TeeQueueBlocks int

	// StaleDataTimeout 上游「还连着但 N 秒没数据」判定卡死（<=0 用 15s）。
	StaleDataTimeout time.Duration

	// StaleStartTimeout 上游「建连后 N 秒一个字节都没收到」判定卡死（<=0 用 20s）。
	StaleStartTimeout time.Duration

	// tee 是全局名额池，由 NewHub 初始化；nil 表示不 tee（测试构造）。
	tee *teeBudget
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
		opts.RingSize = defaultRingSize
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
	// 缓冲小于「一个 GOP」时，新订阅者落进去连一个完整 GOP 都拿不到，
	// 只能等下一个 IDR——表现就是用户说的「换台转圈很久」。
	// 4MB 在 8Mbps（约 1MB/s）下是 4 秒，给 2 秒 GOP 留了一倍余量。
	if opts.RingSize < 4<<20 {
		smallRingWarned.Do(func() {
			slog.Warn("环形缓冲偏小，换台可能等下一个关键帧才出画",
				"ring_size", opts.RingSize,
				"建议", "至少 4MB；8Mbps 约 1MB/s，6MB 更稳（配置项 ring_size）")
		})
	}
	if opts.MaxTeeStreams <= 0 {
		opts.MaxTeeStreams = 64
	}
	opts.tee = newTeeBudget(opts.MaxTeeStreams)
	return &Hub{opts: opts, ups: make(map[int64]*Upstream)}
}

// smallRingWarned 保证「缓冲偏小」只提醒一次，不刷屏。
var smallRingWarned sync.Once

// staleDataTimeout 上游「连着但不推数据」的判定阈值。
//
// 取值依据：要大于「最坏情况下两块数据之间的间隔」。直播 TS 是连续流，
// 正常间隔几十毫秒级，0.5Mbps 的低码率台一块 32KB 也就 0.5 秒。
// 15 秒足够吸收上游抖动，又不会让用户对着黑屏等太久；
// 给太小会把「轮播源在广告/切台时短暂停流 2–5 秒」误判成死连接并重建。
func (o Options) staleDataTimeout() time.Duration {
	if o.StaleDataTimeout > 0 {
		return o.StaleDataTimeout
	}
	return 15 * time.Second
}

// staleStartTimeout 上游「建连后一个字节都没收到」的判定阈值。
//
// 冷启动的源站常见 3–8 秒才吐第一个字节，20 秒给足余量；
// 超过还没有数据说明线路本身不通，重建后由 BestStream 换到下一条。
func (o Options) staleStartTimeout() time.Duration {
	if o.StaleStartTimeout > 0 {
		return o.StaleStartTimeout
	}
	return 20 * time.Second
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
	ring    ringBuffer
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

	// client 是这个上游专属的出站客户端，**在 Subscribe 时建一次**。
	//
	// 以前 readOnce 每次调用都新建 client + Transport：
	//   - Transport 被丢弃后，它的空闲连接不会被主动关掉，
	//     死台按指数退避（1s→10s）重连，等于每 10 秒漏一批 fd；
	//   - 顺带也就没法给它接出站地址策略（SSRF）。
	//
	// 现在：每个上游一个 Transport（保住 opts.Timeout 对响应头的控制），
	// 上游 close 时 CloseIdleConnections。
	client    *http.Client
	transport *http.Transport

	// teeOn 表示时移写协程已经拉起并占着全局名额。
	teeOn atomic.Bool

	// teeOnce 保证 teeDone 只关闭一次。
	teeOnce sync.Once

	// tee 是每频道的定长队列，满了丢块（见 tee.go）。
	tee chan []byte

	// teeDone 关闭即通知写协程退出。
	teeDone chan struct{}

	// teeDropped 因队列满而丢掉的块数（磁盘跟不上）。
	teeDropped atomic.Int64

	// teeSkipped 因全局名额耗尽而放弃 tee 的块数。
	teeSkipped atomic.Int64
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
			stale = now.Sub(time.Unix(0, last)) > h.opts.staleDataTimeout()
		} else if now.Sub(up.started) > h.opts.staleStartTimeout() {
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
			ring:      newRingBuffer(h.opts.RingSize),
			subs:      make(map[*Subscriber]struct{}),
			started:   time.Now(),
			cancel:    cancel,
		}
		// 队列与退出信号先建好：只在真正需要 tee 时才拉起写协程，
		// 但通道本身要提前存在，免得「建通道」与「上游被关闭」之间出现竞态。
		up.tee = make(chan []byte, teeQueueBlocks(h.opts))
		up.teeDone = make(chan struct{})
		up.transport = newUpstreamTransport(h.opts)
		up.client = &http.Client{
			// 直播流不设总超时，靠 ctx 控制。
			Timeout:   0,
			Transport: up.transport,
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

			// 以下为诊断「卡顿」新增（additive，既有键一个都不动）：
			// dropped 是**每个订阅者各自的**丢块计数（下游太慢），这里求和成
			// 频道维度 —— 非 0 就说明这个频道有人正在卡。
			"dropped":         sumDropped(up.subs),
			"tee_dropped":     up.teeDropped.Load(),
			"tee_skipped":     up.teeSkipped.Load(),
			"last_data_age_s": lastDataAge(up.lastData.Load()),
			"ring_bytes":      up.ring.ringBytes(),
			"ring_max":        up.ring.ringCap(),
		})
		up.mu.RUnlock()
	}
	return out
}

// sumDropped 把每个订阅者的丢块计数求和成频道维度。
//
// 为什么要求和：dropped 记在 Subscriber 上（谁慢谁丢），
// 但运维关心的是「这个频道现在有人卡吗」，所以展示频道级总和。
// 调用方必须持有 u.mu（快照 subs）。
func sumDropped(subs map[*Subscriber]struct{}) int64 {
	var n int64
	for s := range subs {
		n += s.dropped.Load()
	}
	return n
}

// lastDataAge 返回距上次收到上游数据的秒数；从没收到过返回 -1。
func lastDataAge(nano int64) int {
	if nano <= 0 {
		return -1
	}
	return int(time.Since(time.Unix(0, nano)).Seconds())
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
	// 关键：把缓冲区里已有的数据先灌进去，让新客户端立刻有画面。
	// snapshot 已经是一份独立副本，并且从 PAT/包边界开始。
	if snap := u.ring.snapshot(); len(snap) > 0 {
		select {
		case s.ch <- snap:
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
	// 先停掉 tee 写协程：它可能正卡在一次磁盘写里，
	// 关掉 teeDone 后它写完手上的块就退出，不阻塞上游收尾。
	if d, s := u.teeDropped.Load(), u.teeSkipped.Load(); d > 0 || s > 0 {
		slog.Info("时移 tee 有丢块（播放不受影响，回看会有缺口）",
			"channel", u.ChannelID, "dropped", d, "skipped", s)
	}
	u.stopTee()
	if u.cancel != nil {
		u.cancel()
	}
	// 上游收尾时把空闲连接也关掉。
	// Transport 是每个上游专属的（见 Upstream.client 的注释），
	// 不关的话它会带着 keep-alive 连接一直挂在那里等 GC。
	if u.transport != nil {
		u.transport.CloseIdleConnections()
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

// newUpstreamTransport 造一个上游拉流用的 Transport。
//
// 出站地址策略：线路 URL 来自用户导入的清单，属于外部输入，必须过 safedial。
// 用**默认档**（放行 RFC1918 / IPv6 ULA / 公网；拒绝 loopback、链路本地、
// CGNAT、本机网卡地址）：
//   - LAN 源（组播转单播网关、另一台机器上的 TS 服务）是正常用法，必须能播；
//   - 但绝不能让一份恶意清单把 NAS 指到 127.0.0.1 的管理口
//     或 169.254.169.254 的元数据端点。
func newUpstreamTransport(opts Options) *http.Transport {
	// 基础 Transport 用 fetch 的：出站地址策略与 fetch 同源，
	// 而且它认 fetch.SetAllowLoopback 这个测试开关——httptest 服务器都监听
	// 127.0.0.1，stream 的测试正是靠它在本地起假上游。
	// （拿 safedial 自己造会让所有走回环的测试变成"上游被策略拒绝"。）
	t := fetch.NewTransport()
	// 直播流不能压缩：DisableCompression 既省 CPU，也避免上游送 gzip TS 时解不明白。
	t.DisableCompression = true
	// 响应头超时沿用 Hub 的配置（upstream_timeout），语义与改动前一致：
	// 0 表示不限（直播流靠 ctx 控制），这里不做兜底，免得改变既有行为。
	t.ResponseHeaderTimeout = opts.Timeout
	t.MaxIdleConnsPerHost = 4
	t.IdleConnTimeout = 90 * time.Second
	return t
}

func (u *Upstream) readOnce(ctx context.Context) error {
	// 出站连接的 SSRF 校验。订阅源是不可信输入：一条指向 127.0.0.1
	// 或云元数据地址的线路，不应该让服务端替它去连。
	// 这里是入口层的字符串校验（错误信息更清楚）；
	// 真正的拦截在 safedial 的 Dialer.Control 里按**解析后的 IP**再做一次，
	// 所以 DNS rebinding 换不掉判定结果。
	if err := fetch.ValidateURL(u.URL); err != nil {
		return err
	}
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

	// client 在上游创建时建好并复用（见 Upstream.client 的注释）。
	// 退回到新建只是防御：makeTestUpstream 之类的测试构造没有走 Subscribe。
	client := u.client
	if client == nil {
		client = &http.Client{Timeout: 0, Transport: newUpstreamTransport(u.opts)}
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
			// 时移录制：只入队，**不在这里写盘**（见 tee.go）。
			//
			// 空闲上游（最后一个订阅者已离开、还在等 IdleTimeout 回收）
			// 会继续从上游读数据。若这时照常 tee，等于为没人看的频道
			// 持续写盘——NAS 上几个台叠加就是持续的磁盘 IO，
			// 反过来拖慢正在看的那个台（表现为换台后卡一下）。
			// 时移的语义本来就是「看过就有回看」，没人看就不必录。
			u.teeMaybe(chunk)
		}
		if err != nil {
			if errors.Is(err, io.EOF) {
				return fmt.Errorf("上游关闭连接")
			}
			return err
		}
	}
}

// appendRing 把新数据放进分块环形缓冲。
// 调用方是唯一的上游读取 goroutine；这里上锁是因为 subscribe 会并发读。
func (u *Upstream) appendRing(chunk []byte) {
	u.mu.Lock()
	u.ring.push(chunk)
	u.mu.Unlock()
}
