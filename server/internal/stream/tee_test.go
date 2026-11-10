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

// TestTeeDoesNotBlockFanout 是「播几分钟就卡顿 / 冻帧」的核心回归测试。
//
// 修复前 OnData（时移落盘）是在上游读取循环里**同步**调用的：
// 磁盘一抖，这个频道的所有订阅者一起停——CPU 却是空的，所以极难定位。
// 现在 tee 走独立写协程，读循环只做一次非阻塞入队。
//
// 断言方式：把一个 OnData 卡死 500ms，然后要求订阅者在这期间仍能
// 持续收到后续数据块。给 -race 留了宽松系数（只要求收到 5 块）。
func TestTeeDoesNotBlockFanout(t *testing.T) {
	// httptest 监听 127.0.0.1，出站策略默认不许回环——测试里显式打开。
	fetch.SetAllowLoopback(true)
	t.Cleanup(func() { fetch.SetAllowLoopback(false) })

	release := make(chan struct{})
	var teeing atomic.Int32

	opts := testOptions()
	opts.OnData = func(channelID int64, data []byte) {
		teeing.Add(1)
		<-release // 模拟磁盘卡住
	}

	chunk := bytes.Repeat([]byte{0x47}, 188*4)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		flusher, _ := w.(http.Flusher)
		for i := 0; i < 800; i++ {
			if _, err := w.Write(chunk); err != nil {
				return
			}
			if flusher != nil {
				flusher.Flush()
			}
			time.Sleep(2 * time.Millisecond)
		}
	}))
	defer srv.Close()

	h := NewHub(opts)
	defer h.Close()
	defer close(release)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	sub, err := h.Subscribe(ctx, 1, srv.URL, nil)
	if err != nil {
		t.Fatalf("订阅失败: %v", err)
	}

	// 阶段 1：边读边等 tee 真的卡在 OnData 里。
	// （不读的话订阅通道会塞满，后面就分不清「没数据」和「被丢掉」）
	deadline := time.Now().Add(3 * time.Second)
	for teeing.Load() == 0 && time.Now().Before(deadline) {
		select {
		case <-sub.C:
		case <-time.After(10 * time.Millisecond):
		}
	}
	if teeing.Load() == 0 {
		t.Fatal("tee 没有被调用（时移链路没接上）")
	}

	// 阶段 2：OnData 已经卡住，此时读循环必须仍在产出新块。
	got := 0
	deadline = time.Now().Add(2 * time.Second)
	for got < 5 && time.Now().Before(deadline) {
		select {
		case _, open := <-sub.C:
			if !open {
				t.Fatal("订阅通道被关闭")
			}
			got++
		case <-time.After(300 * time.Millisecond):
		}
	}
	if got < 5 {
		t.Fatalf("OnData 卡住期间只收到 %d 块新数据：tee 仍然压在扇出热路径上"+
			"（表现为磁盘一抖全频道卡顿）", got)
	}
}

// TestTeeQueueDropsInsteadOfBlocking 队列满时丢块并计数，绝不反压上游。
//
// 定长队列 + 满则丢，是无界队列的反面：磁盘彻底不动时内存也不会涨。
func TestTeeQueueDropsInsteadOfBlocking(t *testing.T) {
	release := make(chan struct{})
	opts := testOptions()
	opts.OnData = func(channelID int64, data []byte) { <-release }
	opts.tee = newTeeBudget(4)

	u := makeTestUpstream(opts, 1, "http://127.0.0.1:1/")
	u.tee = make(chan []byte, 2)
	u.teeDone = make(chan struct{})

	sub := &Subscriber{ID: 1, ch: make(chan []byte, 4), up: u}
	sub.C = sub.ch
	u.subs[sub] = struct{}{}

	// 第一块被协程取走（然后卡在 OnData 里），之后队列塞满 2 块，剩下全丢
	for i := 0; i < 10; i++ {
		u.teeMaybe(make([]byte, 16))
	}
	if u.teeDropped.Load() == 0 {
		t.Fatal("队列满时没有丢块计数：说明实现会阻塞上游读取循环（内存会一直涨）")
	}
	u.stopTee()
	close(release)
}

// TestTeeSkippedWhenBudgetExhausted 全局名额耗尽时只计数、不启动写协程。
//
// 这条对应「内存放大」的防线：任一已绑定设备可以反复开台把
// 「有观众的频道数」拉起来，没有全局上限就是一条廉价 OOM 路径。
func TestTeeSkippedWhenBudgetExhausted(t *testing.T) {
	var called atomic.Int32
	opts := testOptions()
	opts.tee = newTeeBudget(1)
	if !opts.tee.acquire() {
		t.Fatal("占用唯一名额失败")
	}
	opts.OnData = func(channelID int64, data []byte) { called.Add(1) }

	u := makeTestUpstream(opts, 1, "http://127.0.0.1:1/")
	u.tee = make(chan []byte, 4)
	u.teeDone = make(chan struct{})

	sub := &Subscriber{ID: 1, ch: make(chan []byte, 4), up: u}
	sub.C = sub.ch
	u.subs[sub] = struct{}{}

	u.teeMaybe(make([]byte, 16))
	if u.teeSkipped.Load() == 0 {
		t.Fatal("名额耗尽时没有计 tee_skipped（会静默把内存吃光）")
	}
	if called.Load() != 0 {
		t.Fatal("名额耗尽时仍然启动了 tee 写协程")
	}
	u.stopTee()
}

// TestTeeBudgetCapAndRelease 直接盯住名额池的语义。
func TestTeeBudgetCapAndRelease(t *testing.T) {
	b := newTeeBudget(1)
	if !b.acquire() {
		t.Fatal("第一个名额应当能拿到")
	}
	if b.acquire() {
		t.Fatal("全局上限失效：第二个名额也拿到了")
	}
	if got := b.skipped.Load(); got != 1 {
		t.Fatalf("skipped=%d，期望 1", got)
	}
	b.release()
	if !b.acquire() {
		t.Fatal("归还名额后应当能再拿到")
	}
}

// TestStatsKeepsStableKeys 锁死 /api/v1/system/streams 的键集合。
//
// Hub.Stats() 返回 []map[string]any：没有类型保护、没有前端消费，
// 顺手删一个键不会有人报错，只会让「卡顿没法诊断」。所以把键钉在这里。
func TestStatsKeepsStableKeys(t *testing.T) {
	h := NewHub(testOptions())
	defer h.Close()

	u := makeTestUpstream(h.opts, 7, "http://127.0.0.1:1/")
	u.tee = make(chan []byte, 4)
	u.teeDone = make(chan struct{})
	h.mu.Lock()
	h.ups[7] = u
	h.mu.Unlock()

	stats := h.Stats()
	if len(stats) != 1 {
		t.Fatalf("期望 1 条上游统计，实际 %d", len(stats))
	}
	want := []string{
		"channel_id", "url", "subscribers", "uptime_s", "bytes_in", "last_error",
		"dropped", "tee_dropped", "tee_skipped", "last_data_age_s",
		"ring_bytes", "ring_max",
	}
	for _, k := range want {
		if _, ok := stats[0][k]; !ok {
			t.Errorf("Stats 缺少键 %q：既有键不能删，新键要 additive", k)
		}
	}
}
