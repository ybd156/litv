package stream

// ringBuffer 是扇出用的分块环形缓冲。
//
// 旧实现是一个 []byte：写满以后每读一块都要 append + 重新切片 + 整块拷贝。
// 32KB 的读取块配 2MB 缓冲，等于每块额外 memmove 2MB——CPU 全花在搬数据上，
// 频道一多就很明显。分块版只做「尾插 + 头删」，不搬已有数据。
//
// 缓冲区只在 Upstream.mu 下读写（push/snapshot）。
type ringBuffer struct {
	chunks [][]byte
	size   int
	max    int
}

func newRingBuffer(max int) ringBuffer {
	if max <= 0 {
		max = 2 << 20
	}
	return ringBuffer{max: max}
}

// push 追加一块数据，并从头部丢弃直到总量回到上限以内。
// chunk 的所有权转移给缓冲，调用方不要再改它。
func (r *ringBuffer) push(chunk []byte) {
	if len(chunk) == 0 {
		return
	}
	r.chunks = append(r.chunks, chunk)
	r.size += len(chunk)

	for r.size > r.max && len(r.chunks) > 1 {
		r.size -= len(r.chunks[0])
		r.chunks[0] = nil // 让 GC 能回收整块
		r.chunks = r.chunks[1:]
	}
	// 单块就超过整个缓冲：只留尾部 max 字节，保证新订阅者仍有数据可播。
	if r.size > r.max && len(r.chunks) == 1 {
		last := r.chunks[0]
		if cut := len(last) - r.max; cut > 0 {
			r.chunks[0] = last[cut:]
			r.size = r.max
		}
	}
	if len(r.chunks) == 0 {
		r.chunks = nil
	}
}

// snapshot 返回一份独立的副本，供新订阅者立刻起播。
//
// 会尽量从「可安全开始解码」的位置裁剪，见 tsAlignedStart。
func (r *ringBuffer) snapshot() []byte {
	if r.size == 0 {
		return nil
	}
	buf := make([]byte, 0, r.size)
	for _, c := range r.chunks {
		buf = append(buf, c...)
	}
	start := tsAlignedStart(buf)
	if start <= 0 {
		return buf
	}
	out := make([]byte, len(buf)-start)
	copy(out, buf[start:])
	return out
}

const tsPacketSize = 188

// tsAlignedStart 找一个可以安全开始解码的位置。
//
// 优先返回 PAT（PID 0 的载荷起始包）：解码器先拿到 PAT/PMT 才认得这个流。
// 没有 PAT 就退到任意 TS 包边界（0x47 同步字节按 188 字节等距出现）。
// 返回 -1 表示这段数据不像 MPEG-TS，或者本来就已经对齐。
func tsAlignedStart(b []byte) int {
	if len(b) < tsPacketSize*3 {
		return -1
	}

	// 找出 188 字节相位：缓冲起点可能落在某个包中间，
	// 只有一个偏移能让 0x47 按 188 的间隔连续出现。
	bestOff, bestHits := -1, 0
	limit := tsPacketSize
	if len(b) < limit {
		limit = len(b)
	}
	for off := 0; off < limit; off++ {
		hits := 0
		for i := off; i < len(b); i += tsPacketSize {
			if b[i] != 0x47 {
				break
			}
			hits++
		}
		if hits > bestHits {
			bestOff, bestHits = off, hits
		}
	}
	if bestHits < 3 {
		return -1
	}

	for i := bestOff; i+tsPacketSize <= len(b); i += tsPacketSize {
		if b[i] != 0x47 {
			break
		}
		pid := (uint16(b[i+1]&0x1f) << 8) | uint16(b[i+2])
		payloadStart := b[i+1]&0x40 != 0
		if pid == 0 && payloadStart {
			if i == 0 {
				return -1
			}
			return i
		}
	}
	if bestOff == 0 {
		return -1
	}
	return bestOff
}
