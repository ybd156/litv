// 本文件是 stream 包的回归测试，重点是两件事：
//
//  1. 上游读取 goroutine 与「订阅者注销 / 上游回收」之间的竞态。
//     旧实现 broadcast() 先在 RLock 下拷贝 subs，释放锁之后才逐个发送，
//     这条缝隙里 close(s.ch) 一旦插进来，就是 send on closed channel。
//     这个 panic 发生在上游后台 goroutine 里，HTTP 层的 Recoverer 兜不住，
//     会打死整个 litvd 进程——所以必须用测试把它钉死。
//
//  2. 「慢客户端绝不阻塞上游」这条设计意图必须被保留：
//     下游读得慢只能丢块（计入 Dropped），不能反压上游的读取循环，
//     否则一个卡死的客户端会拖垮同一频道所有终端。
package stream

import (
	"context"
	"sync"
	"testing"
	"time"
)

// testOptions 返回一份显式参数的测试配置：不依赖 NewHub 的默认值，
// 并把 IdleTimeout 放到很大，避免测试期间触发空闲回收干扰断言。
func testOptions() Options {
	return Options{
		RingSize:    1 << 20,
		ChunkSize:   188 * 4,
		Timeout:     time.Second,
		IdleTimeout: time.Hour,
	}
}

// makeTestUpstream 直接构造一个上游对象，用于单独驱动扇出逻辑。
// 它不会启动 run()，因此不会产生真实的上游连接；
// ctx/cancel 只用于让 close() 有东西可以取消。
func makeTestUpstream(opts Options, channelID int64, url string) *Upstream {
	_, cancel := context.WithCancel(context.Background())
	return &Upstream{
		ChannelID: channelID,
		URL:       url,
		opts:      opts,
		subs:      make(map[*Subscriber]struct{}),
		started:   time.Now(),
		cancel:    cancel,
	}
}

// makeSubscribers 造 n 个订阅者并登记到 u.subs。
// fill=true 时把每个通道塞满，模拟永远不读的慢客户端。
func makeSubscribers(u *Upstream, n, size int, fill bool) []*Subscriber {
	subs := make([]*Subscriber, 0, n)
	for i := 0; i < n; i++ {
		s := &Subscriber{
			ID: int64(i + 1),
			ch: make(chan []byte, size),
			up: u,
		}
		s.C = s.ch
		if fill {
			for j := 0; j < size; j++ {
				s.ch <- []byte{0}
			}
		}
		u.subs[s] = struct{}{}
		subs = append(subs, s)
	}
	return subs
}

// TestBroadcastVsDisconnect 复现「send on closed channel」：
// 上游 goroutine 正在广播，终端同时断开（unsubscribe）或上游被回收（close）。
//
// 旧实现在 broadcast() 释放 RLock 之后才发送，而 close()/unsubscribe()
// 是在 u.mu.Lock() 下 close(s.ch) 的，两者没有共同的同步约束，
// 于是发送可能落在已经关闭的通道上 → panic。
func TestBroadcastVsDisconnect(t *testing.T) {
	t.Run("上游回收close", func(t *testing.T) { runBroadcastRace(t, true) })
	t.Run("终端断开unsubscribe", func(t *testing.T) { runBroadcastRace(t, false) })
}

// runBroadcastRace 是上面两个子测试的共用驱动：
// 一边不停地 broadcast，另一边把最后一名订阅者摘掉。
// 任何一次窗口命中都会 panic 在 broadcast 里；测试 goroutine 把它 recover
// 出来并判定失败。这里的 recover 只用于把 panic 变成测试失败报告，
// 不用于在生产实现里掩盖竞态。
func runBroadcastRace(t *testing.T, useClose bool) {
	t.Helper()
	const (
		rounds = 400
		nsubs  = 64
	)
	opts := testOptions()
	chunk := make([]byte, opts.ChunkSize)

	for r := 0; r < rounds; r++ {
		u := makeTestUpstream(opts, 1, "http://127.0.0.1:1/")
		subs := makeSubscribers(u, nsubs, 4, false)

		stop := make(chan struct{})
		panicCh := make(chan any, 1)
		var wg sync.WaitGroup

		// 扇出方：模拟上游读取循环里的 broadcast
		wg.Add(1)
		go func() {
			defer wg.Done()
			defer func() {
				if p := recover(); p != nil {
					select {
					case panicCh <- p:
					default:
					}
				}
			}()
			for {
				select {
				case <-stop:
					return
				default:
					u.broadcast(chunk)
				}
			}
		}()

		// 断开方：模拟终端下线或上游回收
		closerDone := make(chan struct{})
		go func() {
			defer close(closerDone)
			if useClose {
				u.close()
			} else {
				u.unsubscribe(subs[len(subs)-1])
			}
		}()

		<-closerDone
		close(stop)
		wg.Wait()

		select {
		case p := <-panicCh:
			t.Fatalf("第 %d 轮广播打在已关闭的订阅通道上：%v", r, p)
		default:
		}
	}
	// AfterFunc 的空闲回收已经靠 IdleTimeout 推得很远，这里不做额外收尾。
}

// TestSubscribeClosedFlagReadRace 盯住另一处竞态：
// Hub.Subscribe 在 h.mu 下读 up.closed（hub.go 里上游卡死判定处），
// 而 Upstream.close() 在 u.mu 下写 up.closed。
// 两把锁保护同一个字段，等于没有保护；-race 会直接报 DATA RACE。
//
// 这里不追求两个操作在时间上恰好同时发生：只要访问之间没有
// happens-before（不共享同一把锁、也没有 channel 同步），race detector 就会报。
func TestSubscribeClosedFlagReadRace(t *testing.T) {
	// 指向必然连不上的端口：即使走到了重建上游，readOnce 也只会立刻失败退避，
	// 这些后台 goroutine 会在本轮 hub.Close() 里被 cancel 收掉。
	const deadURL = "http://127.0.0.1:1/"
	const rounds = 200
	const id int64 = 7

	for r := 0; r < rounds; r++ {
		hub := NewHub(testOptions())
		up := makeTestUpstream(hub.opts, id, deadURL)

		// 直接把上游塞进 hub，模拟「该频道已有活跃上游」
		hub.mu.Lock()
		hub.ups[id] = up
		hub.mu.Unlock()

		ctx, cancel := context.WithCancel(context.Background())
		start := make(chan struct{})
		var wg sync.WaitGroup
		wg.Add(2)

		// 写 closed：走 u.mu
		go func() {
			defer wg.Done()
			<-start
			up.close()
		}()

		// 读 closed：走 h.mu
		go func() {
			defer wg.Done()
			<-start
			_, _ = hub.Subscribe(ctx, id, deadURL, nil)
		}()

		close(start)
		wg.Wait()
		cancel()
		hub.Close()
	}
}

// TestSlowSubscriberNeverBlocksUpstream 守住核心设计意图：
// 下游太慢只能丢块（计入 Dropped），绝不能反压上游读取循环。
// 这个测试在修复前后都必须通过——它是防止「为了修竞态而加大锁」的刹车。
func TestSlowSubscriberNeverBlocksUpstream(t *testing.T) {
	opts := testOptions()
	u := makeTestUpstream(opts, 1, "http://127.0.0.1:1/")

	// 慢客户端：通道塞满且永远不读
	slow := &Subscriber{ID: 1, ch: make(chan []byte, 1), up: u}
	slow.C = slow.ch
	slow.ch <- []byte{0}
	u.subs[slow] = struct{}{}

	chunk := make([]byte, opts.ChunkSize)
	const n = 5000

	done := make(chan struct{})
	go func() {
		defer close(done)
		for i := 0; i < n; i++ {
			u.broadcast(chunk)
		}
	}()

	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatalf("上游广播被慢客户端阻塞：%d 块在 5s 内没有扇出完", n)
	}

	if got := slow.Dropped(); got != n {
		t.Fatalf("慢客户端丢块计数 = %d，期望 %d（丢块必须计数，便于诊断卡顿）", got, n)
	}
}

// TestFanoutDeliversToEverySubscriber 是正常路径的基线：
// 一块数据必须送达每一个还有容量的订阅者。
func TestFanoutDeliversToEverySubscriber(t *testing.T) {
	opts := testOptions()
	u := makeTestUpstream(opts, 1, "http://127.0.0.1:1/")
	subs := makeSubscribers(u, 3, 4, false)

	chunk := []byte{1, 2, 3}
	u.broadcast(chunk)

	for _, s := range subs {
		select {
		case got := <-s.ch:
			if len(got) != len(chunk) {
				t.Fatalf("订阅者 %d 收到 %d 字节，期望 %d", s.ID, len(got), len(chunk))
			}
		default:
			t.Fatalf("订阅者 %d 没有收到广播", s.ID)
		}
		if s.Dropped() != 0 {
			t.Fatalf("订阅者 %d 不该有丢块，实际 %d", s.ID, s.Dropped())
		}
	}
}

// TestClosedUpstreamRejectsNewSubscribers 收起后：
// 新订阅必须被拒绝，且后续广播不能再 panic（关闭是终态）。
func TestClosedUpstreamRejectsNewSubscribers(t *testing.T) {
	u := makeTestUpstream(testOptions(), 1, "http://127.0.0.1:1/")
	u.close()

	if _, err := u.subscribe(context.Background()); err == nil {
		t.Fatal("上游已关闭，subscribe 仍然返回成功")
	}
	// 关闭之后上游可能还会有一块数据在途；这里必须安全地什么都不做。
	u.broadcast(make([]byte, 188))
	u.close() // 重复关闭必须幂等
}
