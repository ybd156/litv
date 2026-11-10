package api

import (
	"sync"
	"time"
)

// statsEventsPerMinute 是单台设备每分钟允许上报的播放质量事件数。
//
// 取值依据：正常观看每分钟 1~4 条（起播 / 偶发卡顿 / 停止），
// 60 是一个不会误伤真机、又能挡住"反复写 1MB 行把表撑爆"的宽松上限。
const statsEventsPerMinute = 60

// statsRateLimiter 是「按设备固定窗口」限流。
//
// 为什么不复用 auth.LoginLimiter：那个是"IP 5 次/分 + 全局 30 次/分"，
// 专门为应急登录设计的——套到遥测上会把正常观看直接掐死。
// 两者语义不同，共用一个实例只会两头都不对。
//
// 为什么不引入第三方限流库：这里只需要一个窗口计数，
// 多一个依赖就多一份版本漂移。
type statsRateLimiter struct {
	mu     sync.Mutex
	limit  int
	window time.Duration

	start  time.Time
	counts map[int64]int
}

func newStatsRateLimiter(limit int, window time.Duration) *statsRateLimiter {
	if limit <= 0 {
		limit = statsEventsPerMinute
	}
	if window <= 0 {
		window = time.Minute
	}
	return &statsRateLimiter{
		limit:  limit,
		window: window,
		start:  time.Now(),
		counts: make(map[int64]int),
	}
}

// allow 记一次并返回是否放行。
//
// 固定窗口（而不是滑动窗口）是刻意的：这里只需要"别让一台设备无限刷"，
// 均匀性不重要，而固定窗口零分配、无逐请求定时器。
//
// 窗口切换时整个 map 重置——设备数在家庭场景是个位数，
// 重置的代价可以忽略，也就不需要为"过期清理"再写一套逻辑。
func (l *statsRateLimiter) allow(deviceID int64, now time.Time) bool {
	l.mu.Lock()
	defer l.mu.Unlock()

	if now.Sub(l.start) >= l.window {
		l.start = now
		l.counts = make(map[int64]int, len(l.counts))
	}
	if l.counts[deviceID] >= l.limit {
		return false
	}
	l.counts[deviceID]++
	return true
}
