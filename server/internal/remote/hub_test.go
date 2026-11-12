package remote

import (
	"sync"
	"testing"
)

// TestSendAfterCloseDoesNotPanic 是「send on closed channel」的回归测试。
//
// 修复前 Send 在拿到 Conn 之后直接 `c.send <- b`，而 close() 会 close(send)。
// 两者之间没有任何同步：连接刚好在这一刻断开，就会 panic。
// 这个 panic 发生在 HTTP handler 里，虽然 Recoverer 能兜住，
// 但同一条路径在别处调用就是整个进程退出。
//
// 修复后 send 永不关闭，收尾只关 done，所以 Send 必须返回 false。
func TestSendAfterCloseDoesNotPanic(t *testing.T) {
	h := NewHub()
	c := &Conn{
		DeviceID: 1,
		send:     make(chan []byte, 16),
		done:     make(chan struct{}),
		hub:      h,
	}
	h.conns[1] = c

	c.close()

	if h.Send(1, Command{Type: "key", Key: "ok"}) {
		t.Fatal("连接已关闭，Send 仍然报告成功")
	}
}

// TestSendConcurrentWithClose 压并发：任意时刻 close 都不能让 Send 崩。
// 用 -race 跑时还能顺带证明 done/send 的读写是同步的。
func TestSendConcurrentWithClose(t *testing.T) {
	h := NewHub()
	c := &Conn{
		DeviceID: 2,
		send:     make(chan []byte, 64),
		done:     make(chan struct{}),
		hub:      h,
	}
	h.conns[2] = c

	// 把队列里的消息及时取走：否则缓冲满了以后每次 Send 都要等 2 秒超时，
	// 测试会慢到没法跑。取到 done 关闭为止。
	go func() {
		for {
			select {
			case <-c.send:
			case <-c.done:
				return
			}
		}
	}()

	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < 200; j++ {
				h.Send(2, Command{Type: "key", Key: "ok"})
			}
		}()
	}
	wg.Add(1)
	go func() {
		defer wg.Done()
		c.close()
	}()
	wg.Wait()

	if h.Send(2, Command{Type: "key", Key: "ok"}) {
		t.Fatal("连接已关闭，Send 仍然报告成功")
	}
}
