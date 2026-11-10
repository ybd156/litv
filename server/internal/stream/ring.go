package stream

// ringBuffer 是扇出用的分块环形缓冲。
//
// 旧实现是一个 []byte：写满以后每读一块都要 append + 重新切片 + 整块拷贝。
// 32KB 的读取块配 2MB 缓冲，等于每块额外 memmove 2MB——CPU 全花在搬数据上，
// 频道一多就很明显（8Mbps 单频道约 62MB/s 的拷贝 + GC 压力）。
//
// 分块版只做「尾插 + 头删」，不搬已有数据。槽位数组在第一次写入时按
// 「缓冲上限 / 块大小」一次性分配，之后 push 不再分配（见 TestRingPushNoAlloc）。
//
// 缓冲区只在 Upstream.mu 下读写（push/snapshot）。
type ringBuffer struct {
	chunks [][]byte
	head   int
	count  int
	size   int
	max    int
}

func newRingBuffer(max int) ringBuffer {
	if max <= 0 {
		max = defaultRingSize
	}
	return ringBuffer{max: max}
}

// defaultRingSize 是没配置时的期望缓冲字节数。
//
// 换算：ring 至少要装下「一个 GOP + 一个 PAT 周期」，否则新订阅者
// 落进缓冲时连一个完整的 GOP 都拿不到，只能等下一个 IDR——
// 用户看到的就是「换台先黑一下」。
//
//	码率 8Mbps → 1MB/s，GOP 2s → 2MB；留 3 倍余量 = 6MB
//	码率 4Mbps → 0.5MB/s → 1.5MB；同样取 6MB 更稳
//	码率 20Mbps（4K）→ 2.5MB/s，GOP 2s → 5MB，6MB 刚好够一个 GOP
//
// 所以 6MB 是「家用 IPTV 到 4K 都能用」的下限，上限由内存决定。
const defaultRingSize = 6 << 20

// ensureSlots 按第一块数据的大小一次性分配槽位。
//
// 为什么要预分配：每来一块就 append 一次槽位切片，虽然摊还 O(1)，
// 但 32KB 一块、8Mbps 就是每秒 31 次，多频道叠加后是持续的 GC 压力。
// 槽位数按「缓冲上限 / 块大小」推算，留 8 个余量吸收块大小抖动。
func (r *ringBuffer) ensureSlots(chunkLen int) {
	if len(r.chunks) > 0 {
		return
	}
	slots := r.max/chunkLen + 8
	if slots < 64 {
		slots = 64
	}
	if slots > 4096 {
		slots = 4096
	}
	r.chunks = make([][]byte, slots)
}

// dropHead 丢掉最旧的一块。调用方保证 count > 0。
func (r *ringBuffer) dropHead() {
	r.size -= len(r.chunks[r.head])
	r.chunks[r.head] = nil // 让 GC 能回收整块
	r.head = (r.head + 1) % len(r.chunks)
	r.count--
}

// push 追加一块数据，并从头部丢弃直到总量回到上限以内。
// chunk 的所有权转移给缓冲，调用方不要再改它。
func (r *ringBuffer) push(chunk []byte) {
	if len(chunk) == 0 {
		return
	}
	if r.max <= 0 {
		r.max = defaultRingSize
	}
	r.ensureSlots(len(chunk))

	// 槽位用满时先腾一个：宁可少留一块历史，也不能让 count 越界。
	if r.count == len(r.chunks) {
		r.dropHead()
	}
	r.chunks[(r.head+r.count)%len(r.chunks)] = chunk
	r.count++
	r.size += len(chunk)

	for r.size > r.max && r.count > 1 {
		r.dropHead()
	}
	// 单块就超过整个缓冲：只留尾部 max 字节，保证新订阅者仍有数据可播。
	if r.size > r.max {
		cut := r.size - r.max
		if first := r.chunks[r.head]; cut < len(first) {
			r.chunks[r.head] = first[cut:]
			r.size -= cut
		}
	}
}

// snapshot 返回一份独立的副本，供新订阅者立刻起播。
//
// 会尽量从「能立刻出画面」的位置裁剪，见 alignedStart：优先 IDR，
// 其次 PAT，并在这两种情况下补齐 PAT/PMT（否则解码器要等下一个 PSI 周期）。
func (r *ringBuffer) snapshot() []byte {
	if r.size == 0 || r.count == 0 || len(r.chunks) == 0 {
		return nil
	}
	buf := make([]byte, 0, r.size)
	for i := 0; i < r.count; i++ {
		buf = append(buf, r.chunks[(r.head+i)%len(r.chunks)]...)
	}

	start, psi := alignedStart(buf)
	if start <= 0 && len(psi) == 0 {
		return buf
	}
	if start < 0 {
		start = 0
	}
	// 不需要补 PSI 时直接复用 buf 的底层数组做尾部切片：
	// 只是换个起点，没必要再把整段数据拷一遍（新订阅者的首块能有几 MB）。
	tail := buf[start:]
	if len(psi) == 0 {
		return tail
	}
	out := make([]byte, 0, len(psi)+len(tail))
	out = append(out, psi...)
	out = append(out, tail...)
	return out
}

// ringBytes 返回当前缓冲里的有效字节数（诊断用）。
func (r *ringBuffer) ringBytes() int { return r.size }

// ringCap 返回缓冲上限（诊断用）。
func (r *ringBuffer) ringCap() int { return r.max }

const tsPacketSize = 188

// alignedStart 找一个可以安全开始解码的位置。
//
// 返回 (start, psi)：
//   - start 是首块应该从哪个字节开始；-1 表示「原样返回，不用裁」；
//   - psi 是需要补在 start 之前的 PAT/PMT 包（可能为 nil）。
//
// 选择顺序与理由：
//  1. **IDR 之前的最近 PAT**：从 GOP 中间起播，解码器要一直丢到下一个关键帧
//     才出画，这段时间就是用户看到的「换台先黑一下」。找到 IDR 还不行，
//     在它之前退到最近的 PAT，播放器才拿得到 PID 表。
//  2. **第一个 PAT**：缓冲里一个 IDR 都没有（超长 GOP、或数据还没攒够）时，
//     至少让解码器认出这个流的 PID 结构。
//  3. **包边界**：都没有就退到 0x47 相位，至少不会从半个包开始解。
//
// 非 MPEG-TS 数据（不以 0x47 等距出现）一律返回 -1，调用方原样使用。
func alignedStart(b []byte) (int, []byte) {
	if len(b) < tsPacketSize*3 {
		return -1, nil
	}
	phase := tsPhase(b)
	if phase < 0 {
		return -1, nil
	}
	pat, pmt := psiPackets(b, phase)

	if idr := firstIDRPacket(b, phase); idr > 0 {
		// 起点选「IDR 之前的最近一个 PAT」，而不是 IDR 本身：
		// 新订阅者要按 PAT → PMT → PES 的顺序才认得这路流。
		// 中间那段（PAT 到 IDR 之间）的数据解码器会自己丢掉，
		// 代价远小于「从 GOP 中间起播、一直等到下一个 IDR」。
		if p := lastPATBefore(b, phase, idr); p > 0 {
			return p, prependPSI(b, p, pat, pmt)
		}
		return idr, prependPSI(b, idr, pat, pmt)
	}

	// 没有 IDR：退到第一个 PAT（本函数修复前的行为）。
	for i := phase; i+tsPacketSize <= len(b); i += tsPacketSize {
		if b[i] != 0x47 {
			break
		}
		if pidAt(b, i) == 0 {
			if i == 0 {
				return -1, prependPSI(b, i, pat, pmt)
			}
			return i, prependPSI(b, i, pat, pmt)
		}
	}
	if phase > 0 {
		return phase, prependPSI(b, phase, pat, pmt)
	}
	return -1, prependPSI(b, phase, pat, pmt)
}

// tsPhase 找出 188 字节的包相位。
//
// 缓冲起点可能落在某个包中间，只有一个偏移能让 0x47 按 188 的间隔连续出现。
// 连续命中不足 3 次就认为这不是 TS 数据（返回 -1）。
func tsPhase(b []byte) int {
	limit := tsPacketSize
	if len(b) < limit {
		limit = len(b)
	}
	bestOff, bestHits := -1, 0
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
	return bestOff
}

// pidAt 读一个 TS 包的 PID。
func pidAt(b []byte, i int) uint16 {
	if i+2 >= len(b) {
		return 0x1fff
	}
	return (uint16(b[i+1]&0x1f) << 8) | uint16(b[i+2])
}

// firstIDRPacket 找到第一个 IDR 所在的 TS 包起点，找不到返回 -1。
//
// IDR 的起始码是 00 00 01 65（四字节形式 00 00 00 01 65 会被三字节形态
// 命中在偏移 +1 处）。这个判据与时移的 alignToKeyframe 保持一致。
func firstIDRPacket(b []byte, phase int) int {
	for i := phase; i+4 < len(b); i++ {
		if b[i] != 0 || b[i+1] != 0 || b[i+2] != 1 || b[i+3] != 0x65 {
			continue
		}
		if off := packetStartOf(phase, i); off >= 0 {
			return off
		}
	}
	return -1
}

// packetStartOf 返回 pos 所在 TS 包的起始偏移。
func packetStartOf(phase, pos int) int {
	if pos < phase {
		return -1
	}
	return phase + ((pos-phase)/tsPacketSize)*tsPacketSize
}

// lastPATBefore 找 before 之前（含）最近的一个 PAT 包起点，找不到返回 -1。
func lastPATBefore(b []byte, phase, before int) int {
	found := -1
	for i := phase; i+tsPacketSize <= before; i += tsPacketSize {
		if b[i] != 0x47 || b[i+1]&0x40 == 0 {
			continue
		}
		if pidAt(b, i) == 0 {
			found = i
		}
	}
	return found
}

// psiPackets 抓最近的 PAT 与 PMT 整包（各 188 字节）。
//
// 为什么要抓：ring 的头是按字节裁的，PAT/PMT 的发送周期（常见 100ms–1s）
// 比缓冲时长长得多，裁完之后头部往往既没有 PAT 也没有 PMT。
// 新订阅者会先看到一堆认不出 PID 的 PES，表现是「有数据但一直黑屏」。
func psiPackets(b []byte, phase int) (pat, pmt []byte) {
	for i := phase; i+tsPacketSize <= len(b); i += tsPacketSize {
		if b[i] != 0x47 {
			break
		}
		// PSI 一定从载荷起始包开始，中间包不可能是 section 开头。
		if b[i+1]&0x40 == 0 {
			continue
		}
		hdr := 4
		if afc := (b[i+3] >> 4) & 0x3; afc&0x2 != 0 {
			hdr += int(b[i+4]) + 1
		}
		if hdr >= tsPacketSize {
			continue
		}
		p := i + hdr
		sec := p + 1 + int(b[p])
		if sec+3 >= i+tsPacketSize {
			continue
		}
		tableID := b[sec]
		switch {
		case pidAt(b, i) == 0 && tableID == 0x00:
			pat = append(pat[:0], b[i:i+tsPacketSize]...)
		case tableID == 0x02:
			// PMT：section_length 必须像样，避免把随机载荷当成 PMT。
			if secLen := int(b[sec+1]&0x0f)<<8 | int(b[sec+2]); secLen >= 9 && secLen <= 1021 {
				pmt = append(pmt[:0], b[i:i+tsPacketSize]...)
			}
		}
	}
	return pat, pmt
}

// prependPSI 返回需要补在 start 之前的那几个 PSI 包。
//
// 只在「起点不是 PAT」时才补，而且必须补 PAT——只给一个 PMT 没有意义。
// 正常路径走不到这里（起点会先退到 IDR 之前最近的 PAT），
// 兜底的是「缓冲里已经没留下 PAT」这种极端情况。
func prependPSI(b []byte, start int, pat, pmt []byte) []byte {
	if start <= 0 || len(pat) == 0 {
		return nil
	}
	if pidAt(b, start) == 0 {
		return nil
	}
	out := make([]byte, 0, len(pat)+len(pmt))
	out = append(out, pat...)
	out = append(out, pmt...)
	return out
}
