// Package timeshift 实现「边看边录 + 时移回看」。
//
// 思路：IPTV 的时移不需要像传统 STB 那样先录后看——扇出层（stream.Upstream）
// 本来就把字节流读进来了，顺手 tee 一份到磁盘就是天然的时移缓冲。
//
// 存储策略：按频道切分段落文件，环形保留最近 N 段。
//   - 单文件无限长会越滚越大（NAS 硬盘会被吃光）
//   - 分段后删除最旧的一段只是 unlink，不影响正在读的句柄
//
// 索引：内存里记 (段落文件, 该段的起始时间, 大小)。
// 时移请求 → 二分找到目标时间所在的段落 → 从对应偏移开始读。
package timeshift

import (
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// Segment 是一段录制文件。
type Segment struct {
	Path      string
	StartedAt time.Time
	Bytes     int64
}

// Ring 是单个频道的环形录制缓冲。
type Ring struct {
	channelID int64
	dir       string
	segBytes  int64 // 单段最大字节数
	maxSegs   int   // 最多保留几段

	mu       sync.RWMutex
	segs     []*Segment
	cur      *os.File
	curSeg   *Segment
	closed   bool
	lastUsed time.Time
}

// Options 是 Ring 的参数。
type Options struct {
	// 单段最大字节数，默认 64MB（约 5~8 分钟高清直播）
	SegmentBytes int64
	// 最多保留几段，默认 12 段（约 1 小时）
	MaxSegments int
}

// NewRing 创建一个频道的录制缓冲。dir 由调用方保证存在。
func NewRing(channelID int64, dir string, opt Options) (*Ring, error) {
	if opt.SegmentBytes <= 0 {
		opt.SegmentBytes = 64 << 20
	}
	if opt.MaxSegments <= 0 {
		opt.MaxSegments = 12
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, fmt.Errorf("创建时移目录失败: %w", err)
	}
	return &Ring{
		channelID: channelID,
		dir:       dir,
		segBytes:  opt.SegmentBytes,
		maxSegs:   opt.MaxSegments,
		lastUsed:  time.Now(),
	}, nil
}

// LastUsed 返回最后一次读写的时间（空闲回收据此判断）。
func (r *Ring) LastUsed() time.Time {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.lastUsed
}

// Write 追加一段 TS 数据。并发安全。
func (r *Ring) Write(p []byte) (int, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.closed {
		return 0, fmt.Errorf("录制已关闭")
	}
	r.lastUsed = time.Now()

	if r.cur == nil {
		if err := r.rotateLocked(); err != nil {
			return 0, err
		}
	}

	n, err := r.cur.Write(p)
	if n > 0 {
		r.curSeg.Bytes += int64(n)
	}

	// 单段写满 → 切下一段
	if r.curSeg.Bytes >= r.segBytes {
		_ = r.rotateLocked()
	}
	return n, err
}

// rotateLocked 关闭当前段、开新段、清理过旧段。调用方需持锁。
func (r *Ring) rotateLocked() error {
	if r.cur != nil {
		_ = r.cur.Close()
		r.cur = nil
	}

	name := fmt.Sprintf("ch%d-%d.ts", r.channelID, time.Now().UnixNano())
	path := filepath.Join(r.dir, name)
	f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		return err
	}
	seg := &Segment{Path: path, StartedAt: time.Now()}
	r.segs = append(r.segs, seg)
	r.cur = f
	r.curSeg = seg

	// 超出保留数 → 删最旧的
	for len(r.segs) > r.maxSegs {
		old := r.segs[0]
		r.segs = r.segs[1:]
		_ = os.Remove(old.Path)
	}
	return nil
}

// SpanSeconds 返回当前可回看的秒数（最旧段落距今多久）。0 表示没有录制。
func (r *Ring) SpanSeconds() int {
	r.mu.RLock()
	defer r.mu.RUnlock()
	if len(r.segs) == 0 {
		return 0
	}
	return int(time.Since(r.segs[0].StartedAt).Seconds())
}

// Open 从「距今 backSeconds 秒」的位置开始读。
// 返回的 ReadCloser 会按顺序跨段落读到最新。
func (r *Ring) Open(backSeconds int) (*Reader, error) {
	// 先单独刷新一次活跃时间：Open 内部持的是读锁，
	// 不能在里面写 lastUsed（会数据竞争），而且 alignToKeyframe 要读文件，
	// 也不适合全程占写锁阻塞正在进行的录制写入。
	r.mu.Lock()
	r.lastUsed = time.Now()
	r.mu.Unlock()

	r.mu.RLock()
	defer r.mu.RUnlock()

	if len(r.segs) == 0 {
		return nil, fmt.Errorf("还没有可回看的录制")
	}

	target := time.Now().Add(-time.Duration(backSeconds) * time.Second)

	// 选段：最后一个「起始时间不晚于目标 且 有内容」的段。
	// 跳过空段很重要——刚轮转出来的新段大小是 0，回看"几秒前"很容易正好命中它，
	// 结果就是拉到一个空文件、返回 0 字节。
	idx := -1
	for i := len(r.segs) - 1; i >= 0; i-- {
		if r.segs[i].Bytes > 0 && !r.segs[i].StartedAt.After(target) {
			idx = i
			break
		}
	}
	if idx < 0 {
		// 目标比所有段都早，或中间有空段：退回到最早的非空段
		for i := 0; i < len(r.segs); i++ {
			if r.segs[i].Bytes > 0 {
				idx = i
				break
			}
		}
	}
	if idx < 0 {
		return nil, fmt.Errorf("还没有可回看的录制（缓冲为空）")
	}

	seg := r.segs[idx]

	// 段内偏移：用「下一段的起始时间」作为本段结束，比例估算。
	// 恒定码率下这个估算足够准（误差在百毫秒级，播放器会自己对齐关键帧）。
	end := time.Now()
	if idx+1 < len(r.segs) {
		end = r.segs[idx+1].StartedAt
	}
	var offset int64
	total := end.Sub(seg.StartedAt).Seconds()
	elapsed := target.Sub(seg.StartedAt).Seconds()
	if total > 0 && elapsed > 0 {
		ratio := elapsed / total
		if ratio > 1 {
			ratio = 1
		}
		offset = int64(float64(seg.Bytes) * ratio)
	}
	if offset >= seg.Bytes {
		offset = 0
	}

	// 关键：把估算出来的字节偏移对齐到关键帧。
	//
	// 上面的 offset 是「按时间比例 × 段大小」估出来的，必然落在 GOP 中间。
	// 从 GOP 中间开始喂给播放器，解码器拿不到 IDR，画面就是黑的——
	// 直到下一个关键帧到来（可能好几秒）。这就是「点回看直接黑屏」的原因。
	offset = alignToKeyframe(seg.Path, offset)

	paths := make([]string, 0, len(r.segs)-idx)
	for _, s := range r.segs[idx:] {
		if s.Bytes > 0 {
			paths = append(paths, s.Path)
		}
	}
	if len(paths) == 0 {
		return nil, fmt.Errorf("还没有可回看的录制（缓冲为空）")
	}
	return &Reader{paths: paths, offset: offset}, nil
}

// Close 停止录制并清理。
func (r *Ring) Close() {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.closed = true
	if r.cur != nil {
		_ = r.cur.Close()
		r.cur = nil
	}
}

// Cleanup 删除该频道的全部录制文件（停用频道/关服务时用）。
func (r *Ring) Cleanup() {
	r.Close()
	r.mu.Lock()
	segs := r.segs
	r.segs = nil
	r.mu.Unlock()
	for _, s := range segs {
		_ = os.Remove(s.Path)
	}
}

// alignToKeyframe 把近似字节偏移对齐到 H.264 的关键帧（IDR）。
//
// 为什么必须做：时移段是按字节截断的（默认 64MB 一段），段内偏移又是
// 按「时间比例 × 段大小」估出来的，几乎不可能正好落在关键帧上。
// 从 GOP 中间开始解码，解码器拿不到 IDR，画面就是黑的——要等下一个关键帧，
// 用户看到的就是「点回看直接黑屏，过一会儿才出画面」。
//
// 做法：往前回退一个余量（估算可能已经越过关键帧），向后扫 IDR 起始码
// （00 00 01 65 / 00 00 00 01 65），找到后把偏移退到该 TS 包的 0x47 同步字节，
// 让解复用器能从包边界开始同步。
//
// 扫不到就退回原偏移——宁可黑一下，也不要读错位置。
func alignToKeyframe(path string, approx int64) int64 {
	const (
		backScan = 1 << 20 // 往前回退 1MB（覆盖一个较长 GOP）
		fwdScan  = 4 << 20 // 往后最多扫 4MB
		tsPacket = 188
	)
	f, err := os.Open(path)
	if err != nil {
		return approx
	}
	defer f.Close()

	start := approx - backScan
	if start < 0 {
		start = 0
	}
	if _, err := f.Seek(start, io.SeekStart); err != nil {
		return approx
	}
	buf := make([]byte, fwdScan)
	n, _ := io.ReadFull(f, buf)
	if n < 5 {
		return approx
	}
	buf = buf[:n]

	// 找 IDR 起始码
	idr := -1
	for i := 0; i+4 < n; i++ {
		// 00 00 01 65
		if buf[i] == 0 && buf[i+1] == 0 && buf[i+2] == 1 && buf[i+3] == 0x65 {
			idr = i
			break
		}
		// 00 00 00 01 65
		if i+5 < n && buf[i] == 0 && buf[i+1] == 0 && buf[i+2] == 0 && buf[i+3] == 1 && buf[i+4] == 0x65 {
			idr = i
			break
		}
	}
	if idr < 0 {
		return approx
	}

	// 退到该关键帧所在的 TS 包边界（往前找 0x47 同步字节）
	pkt := idr
	for back := 0; back < tsPacket && pkt > 0; back++ {
		if buf[pkt] == 0x47 {
			break
		}
		pkt--
	}
	if buf[pkt] != 0x47 {
		pkt = idr // 没找到同步字节就用关键帧位置本身
	}
	return start + int64(pkt)
}

// Reader 顺序读多段录制文件。
type Reader struct {
	paths  []string
	offset int64
	cur    *os.File
	i      int
}

// Read 实现 io.Reader：当前段读完自动切下一段。
func (r *Reader) Read(p []byte) (int, error) {
	for {
		if r.cur == nil {
			if r.i >= len(r.paths) {
				return 0, io.EOF
			}
			f, err := os.Open(r.paths[r.i])
			if err != nil {
				r.i++
				continue
			}
			if r.offset > 0 {
				if _, err := f.Seek(r.offset, 0); err != nil {
					_ = f.Close()
					r.i++
					r.offset = 0
					continue
				}
				r.offset = 0
			}
			r.cur = f
		}

		n, err := r.cur.Read(p)
		if n > 0 {
			return n, nil
		}
		if err != nil {
			_ = r.cur.Close()
			r.cur = nil
			r.i++
			continue
		}
		// n==0 且无错误：极小概率，重试
	}
}

// Close 关闭当前文件句柄。
func (r *Reader) Close() error {
	if r.cur != nil {
		return r.cur.Close()
	}
	return nil
}
