package stream

import (
	"bytes"
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/litv/litv/server/internal/fetch"
)

// TestTwoLinesSameChannelCoexist 是「同频道两台设备选不同线路互相掐断」的回归。
//
// 旧实现以 channelID 作唯一键：第二个订阅者带着不同的 url 进来时，
// `up.URL != url` 被判成"源换了地址"，于是把整条上游 close 掉重建——
// 先到的那台被挂在已关闭的上游上，表现是"我看得好好的突然断了/一直重连"。
// 现在键是 (channelID, url)：两条线路各有一条上游，互不打扰。
//
// 同时钉住两个计数口径：active_streams 按上游连接算（=2），
// active_channels 按台去重（=1）。拆键之后这两个数不再相等，
// 管理台的两个指标必须分开取，否则"一个台看两条线"会被算成"两个台"。
func TestTwoLinesSameChannelCoexist(t *testing.T) {
	// httptest 服务器监听 127.0.0.1，而 readOnce 第一步就是 fetch.ValidateURL
	// 的 SSRF 校验——不放行回环的话，上游根本连不上，测试会以"拿不到数据"失败，
	// 看起来像是我要修的那个 bug 复现了，其实是测试环境没开门。
	fetch.SetAllowLoopback(true)
	t.Cleanup(func() { fetch.SetAllowLoopback(false) })

	newSrc := func(tag byte) *httptest.Server {
		return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Type", "video/mp2t")
			w.WriteHeader(http.StatusOK)
			fl, _ := w.(http.Flusher)
			// 必须是**标准 TS 形状**：188 字节对齐、每包首字节 0x47。
			// 只写零星几个字节的话，ring 的包对齐永远拼不出一个完整包，
			// 订阅者会什么都收不到（这也是本测试第一版失败的真正原因）。
			pkt := bytes.Repeat([]byte{0x47}, 188)
			for i := 0; i < 20; i++ {
				pkt[1] = tag
				if _, err := w.Write(pkt); err != nil {
					return
				}
				if fl != nil {
					fl.Flush()
				}
				time.Sleep(10 * time.Millisecond)
			}
		}))
	}
	srcA, srcB := newSrc('A'), newSrc('B')
	defer srcA.Close()
	defer srcB.Close()

	h := NewHub(testOptions())
	defer h.Close()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	subA, err := h.Subscribe(ctx, 1, srcA.URL, nil)
	if err != nil {
		t.Fatalf("订阅 A 线路失败: %v", err)
	}
	subB, err := h.Subscribe(ctx, 1, srcB.URL, nil)
	if err != nil {
		t.Fatalf("订阅 B 线路失败: %v", err)
	}

	// ① 两条上游并存（旧实现在这里只有 1 条：A 被 B 顶掉了）。
	if got := h.Count(); got != 2 {
		t.Fatalf("期望 2 条上游（同一台的两条线路），实际 %d", got)
	}
	// ② 但只算一个台。
	if got := h.ChannelCount(); got != 1 {
		t.Fatalf("期望 1 个活跃频道，实际 %d", got)
	}

	// ③ A 的订阅者必须还能拿到数据——这是"没被掐断"最直接的证据。
	select {
	case <-subA.C:
	case <-time.After(3 * time.Second):
		t.Fatal("A 线路的订阅者拿不到数据：很可能被 B 的订阅顶掉并 close 了上游")
	}
	select {
	case <-subB.C:
	case <-time.After(3 * time.Second):
		t.Fatal("B 线路的订阅者拿不到数据")
	}

	// ④ 同一条线路再订阅一次应当复用，不新增上游（README 那句"同台共用一份上行"）。
	if _, err := h.Subscribe(ctx, 1, srcA.URL, nil); err != nil {
		t.Fatalf("重复订阅 A 线路失败: %v", err)
	}
	if got := h.Count(); got != 2 {
		t.Fatalf("重复订阅同一线路后上游数应仍为 2，实际 %d（说明没复用）", got)
	}
}
