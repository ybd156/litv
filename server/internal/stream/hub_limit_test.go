package stream

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"testing"
	"time"
)

// sub 是测试里订阅的简写，统一补齐 headers 参数。
func sub(h *Hub, chID int64, urlStr string) (*Subscriber, error) {
	return h.Subscribe(context.Background(), chID, urlStr, nil)
}

// TestMaxUpstreamsIsEnforced 是 B02 的核心回归测试。
//
// 修复前必然失败：Options 里根本没有 MaxUpstreams。
// MaxConcurrent 只是配置项和界面上的一行数字，从未限制实际连接数。
// NAS 的上行带宽、fd、内存都是有限的，用户把几百个台点一遍就能拖垮整机。
func TestMaxUpstreamsIsEnforced(t *testing.T) {
	const limit = 3

	opts := testOptions()
	opts.MaxUpstreams = limit
	h := NewHub(opts)
	defer h.Close()

	for i := 0; i < limit; i++ {
		// 每个频道用不同 URL，避免被当成"同一上游复用"
		u := fmt.Sprintf("http://127.0.0.1:1/ch%d.ts", i)
		if _, err := sub(h, int64(i+1), u); err != nil {
			t.Fatalf("第 %d 个订阅在限额内却失败了: %v", i+1, err)
		}
	}

	// 超过限额必须被拒
	if _, err := sub(h, int64(limit+1), "http://127.0.0.1:1/over.ts"); err == nil {
		t.Fatalf("超出上限 %d 后仍然允许新建上游——限额没有生效", limit)
	} else if !errors.Is(err, ErrTooManyStreams) {
		t.Errorf("错误类型不对，期望 ErrTooManyStreams，实际 %v", err)
	}
}

// TestMaxUpstreamsAllowsReuseOfExisting 确认复用已有上游不占新额度。
func TestMaxUpstreamsAllowsReuseOfExisting(t *testing.T) {
	opts := testOptions()
	opts.MaxUpstreams = 2
	h := NewHub(opts)
	defer h.Close()

	const chID = int64(7)
	const urlStr = "http://127.0.0.1:1/ch7.ts"

	if _, err := sub(h, chID, urlStr); err != nil {
		t.Fatalf("首个订阅失败: %v", err)
	}
	// 同频道同 URL 再订阅：复用，不该被限额挡住
	if _, err := sub(h, chID, urlStr); err != nil {
		t.Fatalf("复用已有上游被限额挡住了: %v", err)
	}
	// 第二个不同频道：额度没满，应成功
	if _, err := sub(h, 8, "http://127.0.0.1:1/ch8.ts"); err != nil {
		t.Fatalf("第 2 个不同频道理应允许: %v", err)
	}
	// 第三个不同频道：超限
	if _, err := sub(h, 9, "http://127.0.0.1:1/ch9.ts"); err == nil {
		t.Fatal("第 3 个上游应当被拒")
	}
}

// TestCountExcludesClosedUpstreams 确认 Count 不把已关闭条目算进去。
//
// 这是 B02 的第二个缺陷：Count 直接 return len(h.ups)，
// 而 close 是异步收尾，map 里会残留已关闭条目，
// 导致"明明没超额却报满了"。
func TestCountExcludesClosedUpstreams(t *testing.T) {
	opts := testOptions()
	opts.MaxUpstreams = 100
	h := NewHub(opts)
	defer h.Close()

	made := make([]*Upstream, 0, 5)
	for i := 0; i < 5; i++ {
		up := makeTestUpstream(opts, int64(i+1), fmt.Sprintf("http://x/%d.ts", i))
		h.mu.Lock()
		h.ups[int64(i+1)] = up
		h.mu.Unlock()
		made = append(made, up)
	}
	if got := h.Count(); got != 5 {
		t.Fatalf("初始计数应为 5，实际 %d", got)
	}

	// 关掉 3 个但不从 map 删除——模拟 close 异步收尾的窗口
	for i := 0; i < 3; i++ {
		made[i].close()
	}
	if got := h.Count(); got != 2 {
		t.Errorf("关闭 3 个后计数应为 2，实际 %d（残留的已关闭条目被算进去了）", got)
	}
}

// TestLimitIsAtomicUnderConcurrency 确认并发订阅不会冲破限额。
func TestLimitIsAtomicUnderConcurrency(t *testing.T) {
	const limit = 5
	const goroutines = 40

	opts := testOptions()
	opts.MaxUpstreams = limit
	h := NewHub(opts)
	defer h.Close()

	var (
		wg      sync.WaitGroup
		mu      sync.Mutex
		succeed int
		reject  int
	)
	for i := 0; i < goroutines; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			u := fmt.Sprintf("http://127.0.0.1:1/c%d.ts", i)
			_, err := sub(h, int64(i+1), u)
			mu.Lock()
			if err == nil {
				succeed++
			} else if errors.Is(err, ErrTooManyStreams) {
				reject++
			}
			mu.Unlock()
		}(i)
	}
	wg.Wait()

	if succeed > limit {
		t.Fatalf("并发下成功了 %d 个上游，超过限额 %d——检查与插入不是原子的", succeed, limit)
	}
	if succeed+reject != goroutines {
		t.Errorf("有请求既没成功也没被明确拒绝：成功=%d 拒绝=%d 总数=%d",
			succeed, reject, goroutines)
	}
	t.Logf("并发结果：成功 %d，被拒 %d", succeed, reject)
}

// TestZeroMaxMeansUnlimited 确认 <=0 表示不限制（默认行为不变）。
func TestZeroMaxMeansUnlimited(t *testing.T) {
	opts := testOptions()
	opts.MaxUpstreams = 0
	h := NewHub(opts)
	defer h.Close()

	for i := 0; i < 10; i++ {
		u := fmt.Sprintf("http://127.0.0.1:1/u%d.ts", i)
		if _, err := sub(h, int64(i+1), u); err != nil {
			t.Fatalf("MaxUpstreams=0 时不该限制，第 %d 个失败: %v", i+1, err)
		}
	}
}

// TestReleasedSlotCanBeReused 确认上游回收后额度会释放。
func TestReleasedSlotCanBeReused(t *testing.T) {
	opts := testOptions()
	opts.MaxUpstreams = 1
	h := NewHub(opts)
	defer h.Close()

	up := makeTestUpstream(opts, 1, "http://x/1.ts")
	h.mu.Lock()
	h.ups[1] = up
	h.mu.Unlock()

	up.close()
	if got := h.Count(); got != 0 {
		t.Fatalf("关闭后计数应为 0，实际 %d", got)
	}

	if _, err := sub(h, 2, "http://127.0.0.1:1/new.ts"); err != nil {
		t.Fatalf("释放额度后仍被拒: %v", err)
	}
}

// TestIdleUpstreamEvictedInsteadOfRejecting 是 B02 限额语义的回归测试。
//
// 背景：上游在最后一个订阅者离开后还要等 IdleTimeout（默认 60 秒）才回收。
// 如果直接把"等待回收的空闲连接"也算满，用户连换几个台就会撞上限——
// 表现为"换到某个台突然播不了"。限额的本意是限制**同时在看的流**，
// 所以满额时应当先回收空闲上游，而不是直接拒绝。
func TestIdleUpstreamEvictedInsteadOfRejecting(t *testing.T) {
	opts := testOptions()
	opts.MaxUpstreams = 2
	h := NewHub(opts)
	defer h.Close()

	// 造两个"空闲已久"的上游：没有订阅者，且 started 已在宽限期之外
	for i := 0; i < 2; i++ {
		u := makeTestUpstream(opts, int64(i+1), fmt.Sprintf("http://x/%d.ts", i))
		u.started = time.Now().Add(-time.Minute)
		h.mu.Lock()
		h.ups[int64(i+1)] = u
		h.mu.Unlock()
	}
	if got := h.Count(); got != 2 {
		t.Fatalf("初始应有 2 个上游，实际 %d", got)
	}

	// 满额，但都是空闲的：应当回收一个，让新请求通过
	if _, err := sub(h, 99, "http://127.0.0.1:1/new.ts"); err != nil {
		t.Fatalf("空闲上游没有被回收，新请求被拒: %v（换台会撞上限）", err)
	}
}

// TestActiveUpstreamsNotEvicted 确认"有人在看"的上游不会被回收。
//
// 这是上一条的反面：不能为了接新请求就把正在播的流踢掉。
func TestActiveUpstreamsNotEvicted(t *testing.T) {
	opts := testOptions()
	opts.MaxUpstreams = 2
	h := NewHub(opts)
	defer h.Close()

	// 两个都有订阅者的上游（通过 sub 建立，subs 非空）
	for i := 0; i < 2; i++ {
		u := fmt.Sprintf("http://127.0.0.1:1/a%d.ts", i)
		if _, err := sub(h, int64(i+1), u); err != nil {
			t.Fatalf("建立第 %d 个上游失败: %v", i+1, err)
		}
	}
	// 把 started 拨到宽限期之外，确保不是"因为太新"才没被回收
	h.mu.Lock()
	for _, u := range h.ups {
		u.started = time.Now().Add(-time.Minute)
	}
	h.mu.Unlock()

	// 满额且都在用：必须拒绝
	if _, err := sub(h, 99, "http://127.0.0.1:1/new.ts"); err == nil {
		t.Fatal("正在播放的上游被回收了，新请求挤掉了别人的流")
	}
}
