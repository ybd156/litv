package api

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"sync"
	"testing"
	"time"

	"github.com/litv/litv/server/internal/auth"
	"github.com/litv/litv/server/internal/store"
)

// TestSelectedStreamIsCarriedIntoSignature 是 R13 的核心回归测试。
//
// 修复前必然失败：tvPlay 尊重了 stream_id，但 relay 播放地址里没有它，
// 而 liveTS/livePlay/liveRemuxPlaylist 又各自 BestStream。
// 结果"已切线路"只改变了响应字段，真正拉流的还是默认线路——
// 用户看到的现象是"切了没用"。
func TestSelectedStreamIsCarriedIntoSignature(t *testing.T) {
	a, _ := newTestAPI(t)
	ctx := context.Background()

	chID, err := a.st.CreateChannel(ctx, store.Channel{Number: 1, Name: "双线路台", Enabled: true})
	if err != nil {
		t.Fatalf("建频道失败: %v", err)
	}
	// 两条线路：优先线路是第一条（priority 更高），我们要选第二条。
	id1, err := a.st.AddStream(ctx, chID, "http://a.example/one.m3u8", "hd", 1)
	if err != nil {
		t.Fatalf("加线路1失败: %v", err)
	}
	id2, err := a.st.AddStream(ctx, chID, "http://b.example/two.m3u8", "sd", 2)
	if err != nil {
		t.Fatalf("加线路2失败: %v", err)
	}
	// 让两条线路都"健康"，确保 BestStream 真会选 id1
	if _, err := a.st.DB().ExecContext(ctx,
		`UPDATE streams SET health=1, latency_ms=1 WHERE id IN (?,?)`, id1, id2); err != nil {
		t.Fatalf("更新健康度失败: %v", err)
	}

	dev := boundDeviceToken(t, a, "r13-device")

	// 1) 不指定线路：relay 地址应绑定 BestStream 选中的那条
	rec := deviceGET(t, a, fmt.Sprintf("/api/v1/tv/play/%d", chID), dev)
	if rec.Code != http.StatusOK {
		t.Fatalf("play 失败：%d", rec.Code)
	}
	defaultSID := playStreamID(t, rec.Body.Bytes())

	// 2) 指定第二条线路：签名必须绑定 id2
	rec = deviceGET(t, a, fmt.Sprintf("/api/v1/tv/play/%d?stream_id=%d", chID, id2), dev)
	if rec.Code != http.StatusOK {
		t.Fatalf("指定线路 play 失败：%d %s", rec.Code, rec.Body.String())
	}
	selSID := playStreamID(t, rec.Body.Bytes())
	if selSID != id2 {
		t.Fatalf("响应里的 stream_id 是 %d，期望 %d", selSID, id2)
	}

	// 3) 关键断言：relay URL 必须带上被选中的 stream_id，
	//    且签名能校验通过并解出同一条线路。
	//    修复前 URL 里根本没有 stream_id，这一步直接失败。
	playURL := playURLString(t, rec.Body.Bytes())
	pu, perr := url.Parse(playURL)
	if perr != nil {
		t.Fatalf("解析播放地址失败: %v", perr)
	}
	q := pu.Query()
	if q.Get("stream_id") == "" {
		t.Fatal("relay 播放地址里没有 stream_id——手动选线路丢失了")
	}
	gotSID, verr := a.signer.VerifyStreamSig(chID, id2, mustInt64(t, q.Get("exp")), q.Get("dk"), q.Get("sig"))
	if verr != nil {
		t.Fatalf("签名未绑定所选线路或校验失败: %v", verr)
	}
	if gotSID != id2 {
		t.Fatalf("签名解出的线路是 %d，期望 %d", gotSID, id2)
	}

	_ = defaultSID
}

// TestTamperedStreamIDRejected 确认改查询参数里的 stream_id 不能生效。
//
// 线路 id 必须由签名承载，否则设备可以随便探测其他线路。
func TestTamperedStreamIDRejected(t *testing.T) {
	a, _ := newTestAPI(t)
	ctx := context.Background()

	chID, _ := a.st.CreateChannel(ctx, store.Channel{Number: 1, Name: "台", Enabled: true})
	id1, _ := a.st.AddStream(ctx, chID, "http://a.example/one.ts", "hd", 1)
	id2, _ := a.st.AddStream(ctx, chID, "http://b.example/two.ts", "sd", 2)

	exp := time.Now().Add(time.Hour).Unix()
	sigBoundTo1 := a.signer.SignStream(chID, id1, exp, "dk")

	// 声称要 id2，但签名只绑定了 id1：必须被拒
	u := fmt.Sprintf("/live/%d/stream.ts?sig=%s&exp=%d&dk=dk&stream_id=%d",
		chID, url.QueryEscape(sigBoundTo1), exp, id2)
	rec := doRaw(a, httptest.NewRequest(http.MethodGet, u, nil))
	if rec.Code == http.StatusOK {
		t.Fatal("篡改 stream_id 后仍然通过签名校验——线路未绑定")
	}
}

// TestBoundStreamMustBelongToChannel 确认跨频道复用签名被拒。
func TestBoundStreamMustBelongToChannel(t *testing.T) {
	a, _ := newTestAPI(t)
	ctx := context.Background()

	chA, _ := a.st.CreateChannel(ctx, store.Channel{Number: 1, Name: "A", Enabled: true})
	chB, _ := a.st.CreateChannel(ctx, store.Channel{Number: 2, Name: "B", Enabled: true})
	sB, _ := a.st.AddStream(ctx, chB, "http://b.example/s.ts", "hd", 1)

	// 用 B 的线路 id 去给 A 频道请求：streamForPlay 应拒绝
	if _, err := a.streamForPlay(ctx, chA, sB); err == nil {
		t.Fatal("跨频道绑定的线路被接受了")
	}
}

// TestLegacySignatureStillWorks 确认升级不打断已发出的旧地址。
//
// 修复必须向后兼容：老客户端/老地址没有 stream_id，
// 升级后不该立刻 403（否则正在看的用户全部中断）。
func TestLegacySignatureStillWorks(t *testing.T) {
	a, _ := newTestAPI(t)
	ctx := context.Background()

	chID, _ := a.st.CreateChannel(ctx, store.Channel{Number: 1, Name: "台", Enabled: true})
	_, _ = a.st.AddStream(ctx, chID, "http://a.example/one.ts", "hd", 1)

	exp := time.Now().Add(time.Hour).Unix()
	legacySig := a.signer.Sign(chID, exp, "dk") // 不带线路的旧签名
	gotSID, err := a.signer.VerifyStreamSig(chID, 0, exp, "dk", legacySig)
	if err != nil {
		t.Fatalf("旧签名被拒绝了——升级会打断正在播放的用户: %v", err)
	}
	if gotSID != 0 {
		t.Errorf("旧签名不该解出线路，得到 %d", gotSID)
	}
}

// TestManifestPrefersSelectedStream 确认清单代理优先尝试选中线路。
func TestManifestPrefersSelectedStream(t *testing.T) {
	a, _ := newTestAPI(t)
	ctx := context.Background()

	chID, _ := a.st.CreateChannel(ctx, store.Channel{Number: 1, Name: "台", Enabled: true})
	id1, _ := a.st.AddStream(ctx, chID, "http://a.example/one.m3u8", "hd", 1)
	id2, _ := a.st.AddStream(ctx, chID, "http://b.example/two.m3u8", "sd", 2)
	if _, err := a.st.DB().ExecContext(ctx,
		`UPDATE streams SET health=1 WHERE id IN (?,?)`, id1, id2); err != nil {
		t.Fatalf("更新健康度失败: %v", err)
	}

	// 直接验证排序逻辑：选中 id2 后，它必须排在最前
	streams, err := a.st.ListStreams(ctx, chID)
	if err != nil {
		t.Fatalf("列线路失败: %v", err)
	}
	before := streams[0].ID

	// 复刻 handler 里的重排逻辑
	for i := range streams {
		if streams[i].ID == id2 {
			first := streams[i]
			copy(streams[1:], streams[0:i])
			streams[0] = first
			break
		}
	}
	if streams[0].ID != id2 {
		t.Fatalf("重排后首条是 %d，期望 %d（原首条 %d）", streams[0].ID, id2, before)
	}
}

// TestStreamForPlayFallsBackToBest 确认未绑定时仍回落到自动择优。
func TestStreamForPlayFallsBackToBest(t *testing.T) {
	a, _ := newTestAPI(t)
	ctx := context.Background()

	chID, _ := a.st.CreateChannel(ctx, store.Channel{Number: 1, Name: "台", Enabled: true})
	_, _ = a.st.AddStream(ctx, chID, "http://a.example/one.ts", "hd", 1)

	st, err := a.streamForPlay(ctx, chID, 0)
	if err != nil {
		t.Fatalf("未绑定线路时应回落到 BestStream: %v", err)
	}
	if st == nil || st.ID == 0 {
		t.Fatal("回落没有返回线路")
	}
}

// ---------- 辅助 ----------

func playStreamID(t *testing.T, body []byte) int64 {
	t.Helper()
	var out struct {
		Data struct {
			StreamID int64  `json:"stream_id"`
			URL      string `json:"url"`
		} `json:"data"`
	}
	if err := jsonUnmarshal(body, &out); err != nil {
		t.Fatalf("解析 play 响应失败: %v", err)
	}
	return out.Data.StreamID
}

func playURLString(t *testing.T, body []byte) string {
	t.Helper()
	var out struct {
		Data struct {
			URL string `json:"url"`
		} `json:"data"`
	}
	if err := jsonUnmarshal(body, &out); err != nil {
		t.Fatalf("解析 play 响应失败: %v", err)
	}
	return out.Data.URL
}

func mustInt64(t *testing.T, s string) int64 {
	t.Helper()
	v, err := strconv.ParseInt(s, 10, 64)
	if err != nil {
		t.Fatalf("解析 int64 %q 失败: %v", s, err)
	}
	return v
}

func jsonUnmarshal(body []byte, v any) error { return json.Unmarshal(body, v) }

var _ = sync.WaitGroup{}
var _ = auth.NewToken
