package stream

import (
	"bytes"
	"context"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"github.com/litv/litv/server/internal/fetch"
)

// TestTimeshiftTeeSkippedWhenNoSubscribers 确认没人看时不再写时移盘。
//
// 背景：上游在最后一个订阅者离开后还要等 IdleTimeout（默认 60 秒）才回收，
// 这期间它仍会从上游读数据。若照常把每块都 tee 到磁盘，等于为没人看的频道
// 持续写盘——NAS 上几个台叠加就是持续的磁盘 IO，反过来拖慢正在看的那个台
// （用户表现为「换几个台之后卡一下」）。
func TestTimeshiftTeeSkippedWhenNoSubscribers(t *testing.T) {
	// 上游是 httptest（127.0.0.1），SSRF 防护默认会拒绝；这里只放行回环。
	fetch.SetAllowLoopback(true)
	t.Cleanup(func() { fetch.SetAllowLoopback(false) })

	var teeCalls atomic.Int64

	opts := testOptions()
	opts.IdleTimeout = time.Hour // 别让空闲回收干扰断言
	opts.OnData = func(channelID int64, data []byte) {
		teeCalls.Add(1)
	}

	// 持续吐 TS 字节的上游
	payload := bytes.Repeat([]byte{0x47}, 188*4)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		flusher, _ := w.(http.Flusher)
		for i := 0; i < 400; i++ {
			if _, err := w.Write(payload); err != nil {
				return
			}
			if flusher != nil {
				flusher.Flush()
			}
			time.Sleep(3 * time.Millisecond)
		}
	}))
	defer srv.Close()

	h := NewHub(opts)
	defer h.Close()

	ctx, cancel := context.WithCancel(context.Background())
	sub, err := h.Subscribe(ctx, 1, srv.URL, nil)
	if err != nil {
		t.Fatalf("订阅失败: %v", err)
	}
	// 把数据读掉，避免订阅者通道塞满
	go func() {
		for range sub.C {
		}
	}()

	// 有订阅者时应当持续 tee
	deadline := time.Now().Add(2 * time.Second)
	for teeCalls.Load() == 0 && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if teeCalls.Load() == 0 {
		t.Fatal("有订阅者时没有写时移盘")
	}

	// 退订：取消 context 让订阅者被移除
	cancel()

	// 等订阅者真的被摘掉
	deadline = time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		h.mu.Lock()
		up := h.ups[1]
		h.mu.Unlock()
		if up == nil || up.subscriberCount() == 0 {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}

	// 记录当前计数，再观察一段时间：不应继续增长（允许极小的在途竞态）
	base := teeCalls.Load()
	time.Sleep(400 * time.Millisecond)
	after := teeCalls.Load()

	if after-base > 2 {
		t.Fatalf("没人看了仍在写时移盘：%d -> %d（会持续占用磁盘 IO）", base, after)
	}
}
