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
