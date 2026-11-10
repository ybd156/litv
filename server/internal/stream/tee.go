package stream

// 时移 tee：把上游读到的数据**异步**交给 OnData（落地实现是写磁盘）。
//
// 为什么必须异步：OnData 的实现是 timeshift.Manager.Write → Ring.Write，
// 那是同步 syscall。放在上游读取循环里，磁盘一抖（NAS 上很常见），
// 整个频道的扇出就跟着停——所有观众一起卡，CPU 却是空的。
// 用户反馈的「播几分钟就卡一下 / 冻帧」就是这个机制。
//
// 队列形状（改之前先看这三条）：
//  1. 每频道一个**定长** chan，满了就丢块并计数。绝不用无界队列：
//     磁盘卡住时无界队列会一路吃内存，最后变成 OOM。
//  2. 每频道**一个**写协程。多个协程会让同一频道的写变成随机写，反而更慢。
//  3. 热路径（readOnce）里只做一次非阻塞 send，**不碰磁盘、不持 hub 锁**。
//
// 全局上限（重要）：峰值内存 ≈ 有观众的频道数 × TeeQueueBlocks × 块大小。
// 16 × 32KB ≈ 512KB/频道，64 个频道约 32MB。
// 但「有观众的频道数」是任一已绑定设备反复开台就能拉起来的，
// 所以必须有全局并发 tee 上限（默认 64，见 Options.MaxTeeStreams）：
// 超限**直接不 tee**（播放完全不受影响），只累计 tee_skipped。
// 宁可少一段时移，也不能把 NAS 内存吃爆。

import (
	"sync/atomic"
)

// teeBudget 是全局 tee 名额池。
type teeBudget struct {
	// slots 是令牌池，容量 = 允许同时 tee 的频道数。
	slots chan struct{}

	// skipped 累计「因为没有名额而放弃 tee」的块数。
	skipped atomic.Int64
}

func newTeeBudget(n int) *teeBudget {
	if n <= 0 {
		return nil
	}
	return &teeBudget{slots: make(chan struct{}, n)}
}

// acquire 非阻塞地占一个名额。拿不到就记一笔 skipped。
func (b *teeBudget) acquire() bool {
	if b == nil {
		return false
	}
	select {
	case b.slots <- struct{}{}:
		return true
	default:
		b.skipped.Add(1)
		return false
	}
}

func (b *teeBudget) release() {
	if b == nil {
		return
	}
	select {
	case <-b.slots:
	default:
	}
}

// TeeSkipped 返回因为超出全局 tee 上限而被放弃的块数（诊断用）。
func (h *Hub) TeeSkipped() int64 {
	if h == nil || h.opts.tee == nil {
		return 0
	}
	return h.opts.tee.skipped.Load()
}

// teeQueueBlocks 返回每频道时移队列的深度。
func teeQueueBlocks(opts Options) int {
	n := opts.TeeQueueBlocks
	if n <= 0 {
		n = 16
	}
	if n > 64 {
		n = 64
	}
	return n
}

// teeMaybe 把一块数据交给时移写协程。绝不阻塞、绝不碰磁盘。
//
// chunk 的所有权在这里转交给队列；调用方（readOnce）之后不再改它。
func (u *Upstream) teeMaybe(chunk []byte) {
	if u.opts.OnData == nil || u.tee == nil || u.subscriberCount() == 0 {
		return
	}
	if !u.teeOn.Load() && !u.startTee() {
		u.teeSkipped.Add(1)
		return
	}
	select {
	case u.tee <- chunk:
	default:
		// 磁盘跟不上：丢块并计数。时移允许出现缺口，
		// 但绝不能反压上游读取循环（那会拖垮所有观众）。
		u.teeDropped.Add(1)
	}
}

// startTee 抢一个全局名额并拉起写协程。返回 false 表示没名额，本次不 tee。
func (u *Upstream) startTee() bool {
	if !u.teeOn.CompareAndSwap(false, true) {
		return true // 已经在跑
	}
	if !u.opts.tee.acquire() {
		u.teeOn.Store(false)
		return false
	}
	go u.teeLoop()
	return true
}

func (u *Upstream) teeLoop() {
	for {
		select {
		case <-u.teeDone:
			return
		case blk, ok := <-u.tee:
			if !ok {
				return
			}
			u.opts.OnData(u.ChannelID, blk)
		}
	}
}

// stopTee 结束写协程并归还名额。幂等，由 close() 调用。
//
// teeOn **不**复位：关闭之后再来的数据块只会被丢进没人读的队列并计入
// tee_dropped，不会重新拉起协程（否则每条数据都会新建一个立刻退出的
// 协程，还会漏掉一个全局名额）。
func (u *Upstream) stopTee() {
	u.teeOnce.Do(func() {
		if u.teeDone != nil {
			close(u.teeDone)
		}
		if u.teeOn.Load() {
			u.opts.tee.release()
		}
	})
}
