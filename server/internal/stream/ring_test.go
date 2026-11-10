package stream

import (
	"bytes"
	"testing"
)

func tsPacket(pid uint16, payloadStart bool, fill byte) []byte {
	p := make([]byte, tsPacketSize)
	p[0] = 0x47
	p[1] = byte(pid>>8) & 0x1f
	if payloadStart {
		p[1] |= 0x40
	}
	p[2] = byte(pid)
	for i := 4; i < len(p); i++ {
		p[i] = fill
	}
	return p
}

// TestRingBufferBounded 确认总量不会无限增长（旧实现靠整块拷贝做到这点，
// 新实现靠丢头部块）。
func TestRingBufferBounded(t *testing.T) {
	r := newRingBuffer(1000)
	for i := 0; i < 100; i++ {
		r.push(make([]byte, 100))
	}
	if r.size > r.max {
		t.Fatalf("缓冲区 %d 字节，超过上限 %d", r.size, r.max)
	}
	if r.size == 0 {
		t.Fatal("缓冲区被清空了，新订阅者会拿不到数据")
	}
}

// TestRingBufferSingleHugeChunk：单块就超过上限时保留尾部，
// 不能把刚读到的整块丢掉（否则大块读取的上游永远缓冲不到数据）。
func TestRingBufferSingleHugeChunk(t *testing.T) {
	r := newRingBuffer(1000)
	data := bytes.Repeat([]byte{0x5a}, 2500)
	r.push(data)

	if r.size != 1000 {
		t.Fatalf("期望保留 1000 字节，实际 %d", r.size)
	}
	snap := r.snapshot()
	if len(snap) != 1000 {
		t.Fatalf("快照长度 %d，期望 1000", len(snap))
	}
}

// TestRingBufferSnapshotStartsAtPAT 是本轮重写的核心行为：
// 缓冲起点落在包中间时，快照必须退到 TS 包边界，并优先从 PAT 开始。
//
// 旧实现直接把整个 []byte 拷给新订阅者：起点可能是任意字节，
// 解码器要先丢掉半个包才能同步，表现为换台时先花屏一下。
func TestRingBufferSnapshotStartsAtPAT(t *testing.T) {
	var stream []byte
	// 故意让数据从某个包的中间开始（模拟环形缓冲丢掉了前半包）
	stream = append(stream, bytes.Repeat([]byte{0xaa}, 100)...)
	for i := 0; i < 5; i++ {
		stream = append(stream, tsPacket(0x0101, true, byte(i))...)
	}
	stream = append(stream, tsPacket(0, true, 0x00)...) // PAT
	for i := 0; i < 3; i++ {
		stream = append(stream, tsPacket(0x0101, true, byte(i))...)
	}

	r := newRingBuffer(1 << 20)
	// 按不规则大小分块推入，覆盖分块存储路径
	for i := 0; i < len(stream); i += 70 {
		end := i + 70
		if end > len(stream) {
			end = len(stream)
		}
		r.push(stream[i:end])
	}

	snap := r.snapshot()
	if len(snap) == 0 {
		t.Fatal("快照为空")
	}
	if snap[0] != 0x47 {
		t.Fatalf("快照不是从 TS 同步字节开始：0x%02x", snap[0])
	}
	pid := (uint16(snap[1]&0x1f) << 8) | uint16(snap[2])
	if pid != 0 {
		t.Fatalf("快照没有从 PAT 开始，PID=%d", pid)
	}
	// 尾部数据必须完整保留
	if len(snap) <= tsPacketSize*3 {
		t.Fatalf("快照过短，尾部被截掉了：%d", len(snap))
	}
}

// TestRingBufferSnapshotNonTS 非 TS 数据（例如裸流/测试数据）原样返回，
// 不能因为对齐逻辑把内容改坏。
func TestRingBufferSnapshotNonTS(t *testing.T) {
	const payload = "this is definitely not an MPEG-TS stream"
	r := newRingBuffer(1 << 20)
	r.push([]byte(payload))

	if got := string(r.snapshot()); got != payload {
		t.Fatalf("非 TS 数据被改写：%q", got)
	}
}

// psiPacket 造一个 PAT（tableID=0x00，PID 0）或 PMT（tableID=0x02）包。
//
// 结构要能让 ring.go 的 psiPackets 认出来：
// 载荷起始（PUSI=1）→ pointer_field=0 → section 头（table_id + 2 字节长度）。
func psiPacket(pid uint16, tableID byte) []byte {
	p := tsPacket(pid, true, 0x00)
	p[3] = 0x10 // 只有载荷（无自适应字段）
	p[4] = 0x00 // pointer_field
	p[5] = tableID
	p[6] = 0xb0 // section_length 高 4 位
	p[7] = 0x0d // section_length = 13（psiPackets 只收 9..1021）
	return p
}

// idrPacket 造一个载荷里带 H.264 IDR 起始码的 PES 包。
func idrPacket(pid uint16) []byte {
	p := tsPacket(pid, true, 0x11)
	p[3] = 0x10
	copy(p[9:], []byte{0x00, 0x00, 0x01, 0x65})
	return p
}

// TestRingBufferSnapshotStartsAtPATBeforeIDR 是本轮「起播慢」的核心行为断言。
//
// 用户症状：换台要转很久圈才出画。机制：ring 头是按字节裁的，落在 GOP 中间；
// 从 GOP 中间解码，解码器要一直丢到下一个 IDR 才出画。
//
// 正确做法：快照起点 = 「第一个 IDR 之前最近的 PAT」，播放器拿到
// PAT → PMT → PES(丢弃) → IDR 就能立刻出画。
//
// 修复前 tsAlignedStart 只找**缓冲里第一个** PAT：本例里那是最上面那个
// PAT #1，距离第一个 IDR 还有 26 个包 → 这段全是白等。所以断言
// 「IDR 必须出现在快照开头 4 个包以内」，旧代码必红（26 > 4）。
func TestRingBufferSnapshotStartsAtPATBeforeIDR(t *testing.T) {
	const videoPID = 0x0101
	var stream []byte
	stream = append(stream, psiPacket(0, 0x00)...)      // PAT #1（旧实现会从这里起播）
	stream = append(stream, psiPacket(0x1000, 0x02)...) // PMT #1
	for i := 0; i < 20; i++ {                           // 一段非 IDR 的 PES
		stream = append(stream, tsPacket(videoPID, true, 0x11)...)
	}
	stream = append(stream, psiPacket(0, 0x00)...)      // PAT #2 ← 期望的起点
	stream = append(stream, psiPacket(0x1000, 0x02)...) // PMT #2
	stream = append(stream, tsPacket(videoPID, true, 0x11)...)
	stream = append(stream, tsPacket(videoPID, true, 0x11)...)
	stream = append(stream, idrPacket(videoPID)...) // 第一个 IDR
	stream = append(stream, tsPacket(videoPID, true, 0x11)...)

	r := newRingBuffer(1 << 20)
	for i := 0; i < len(stream); i += 100 {
		end := i + 100
		if end > len(stream) {
			end = len(stream)
		}
		r.push(stream[i:end])
	}

	snap := r.snapshot()
	if len(snap) < tsPacketSize*3 {
		t.Fatalf("快照过短：%d 字节", len(snap))
	}
	if snap[0] != 0x47 {
		t.Fatalf("快照不是从 TS 同步字节开始：0x%02x", snap[0])
	}
	if pid := pidAt(snap, 0); pid != 0 {
		t.Fatalf("快照没有从 PAT 开始，PID=%#x（换台后解码器认不出流结构）", pid)
	}
	// PMT 也必须落在开头附近，否则音频/视频 PID 认不出来。
	pmtFound := false
	for i := 0; i+tsPacketSize*6 <= len(snap) && i < tsPacketSize*6; i += tsPacketSize {
		if pidAt(snap, i) == 0x1000 {
			pmtFound = true
			break
		}
	}
	if !pmtFound {
		t.Fatal("快照开头 6 个包里没有 PMT，解码器拿不到 PID 表")
	}

	// 关键断言：第一个 IDR 必须在开头 4 个包以内。
	idrPkt := -1
	for i := 0; i+4 < len(snap); i++ {
		if snap[i] == 0 && snap[i+1] == 0 && snap[i+2] == 1 && snap[i+3] == 0x65 {
			idrPkt = i / tsPacketSize
			break
		}
	}
	if idrPkt < 0 {
		t.Fatal("快照里找不到 IDR")
	}
	if idrPkt > 4 {
		t.Fatalf("第一个 IDR 在第 %d 个包，期望 ≤4：起点退到了缓冲里第一个 PAT，"+
			"播放器要丢掉 %d 个包才出画（就是用户说的「换台转圈很久」）",
			idrPkt, idrPkt*tsPacketSize)
	}
}

// TestRingPushNoAlloc 守住「每块数据不再重新分配整段缓冲」。
//
// 修复前 push 是 append + 重新切片 + 整块拷贝：32KB 一块配 2MB 缓冲，
// 等于每块额外 memmove 2MB；8Mbps 单频道约 62MB/s 的拷贝 + GC 压力。
// 用 testing.AllocsPerRun 而不是 -bench：CI 只跑 go test -race ./...，
// 不会跑 Benchmarks（grep 'bench' .github/workflows/ = 0 命中）。
func TestRingPushNoAlloc(t *testing.T) {
	r := newRingBuffer(6 << 20)
	chunk := make([]byte, 32<<10)
	r.push(chunk) // 先付掉槽位数组的一次性分配

	if n := testing.AllocsPerRun(100, func() { r.push(chunk) }); n != 0 {
		t.Fatalf("push 有分配：%.1f allocs/op，应为 0（否则 CPU 全花在搬数据上）", n)
	}
	if r.ringBytes() == 0 {
		t.Fatal("缓冲里没有数据")
	}
}
