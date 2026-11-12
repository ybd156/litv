package api

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/litv/litv/server/internal/safedial"
	"github.com/litv/litv/server/internal/store"
)

// segRequest 用**正常签名链路**构造一个 /seg 请求（不伪造签名）。
//
// 伪造签名测到的是签名校验，不是我们要验的出站策略。
func segRequest(t *testing.T, a *API, chID int64, target string) *http.Request {
	t.Helper()
	const dk = "device-key-for-test"
	exp := time.Now().Add(time.Hour).Unix()
	sig := a.signer.SignSegment(chID, target, exp, dk)

	q := url.Values{}
	q.Set("u", target)
	q.Set("sig", sig)
	q.Set("exp", fmt.Sprint(exp))
	q.Set("dk", dk)
	return httptest.NewRequest(http.MethodGet, "/live/"+itoa(chID)+"/seg?"+q.Encode(), nil)
}

// TestSegmentProxyServesSegment 是**正向能力**测试。
//
// 为什么必须有它：现有的 5 条 /seg 测试里没有一条能证明"分片真的能代取"——
// handlers_segment_sig_test.go 只断言"不是 403"，而 502 也不是 403，
// 所以即使代理彻底坏了它们也是绿的（假绿）。
//
// 这里断言三件事：200、字节一致、origin 恰好被访问 1 次。
func TestSegmentProxyServesSegment(t *testing.T) {
	a, _ := newTestAPI(t)

	payload := []byte("TS-PAYLOAD-" + strings.Repeat("x", 64))
	var hits atomic.Int32
	origin := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		w.Header().Set("Content-Type", "video/mp2t")
		_, _ = w.Write(payload)
	}))
	defer origin.Close()

	chID, err := a.st.CreateChannel(context.Background(), store.Channel{Number: 1, Name: "分片台", Enabled: true})
	if err != nil {
		t.Fatalf("建频道失败: %v", err)
	}

	rec := doRaw(a, segRequest(t, a, chID, origin.URL+"/seg001.ts"))
	if rec.Code != http.StatusOK {
		t.Fatalf("分片代取失败：%d %s", rec.Code, rec.Body.String())
	}
	if got := rec.Body.Bytes(); string(got) != string(payload) {
		t.Fatalf("分片内容不一致：期望 %d 字节，实际 %d 字节", len(payload), len(got))
	}
	if n := hits.Load(); n != 1 {
		t.Errorf("origin 被访问了 %d 次，期望 1 次", n)
	}
}

// TestSegmentProxyBlocksLoopback 是本轮 A3 在 /seg 上的回归。
//
// 修复前必然失败：liveSegment 用 http.DefaultClient，没有任何地址判定，
// 于是 /seg 是一个"签名一到、任意 http/https 地址都能代取"的代理。
// 签名绑定了目标地址（R04），所以外部攻击者改不了 u——
// 但**上游清单本身**可以指向 127.0.0.1，而清单由第三方源提供。
//
// 断言方式：受害服务收到 0 次请求。只看状态码分不开
// "策略拒绝了"和"上游刚好连不上"。
func TestSegmentProxyBlocksLoopback(t *testing.T) {
	a, _ := newTestAPI(t)
	// 换回生产默认策略（newTestAPI 为正向用例放行了回环）。
	a.dialPolicy = safedial.DefaultPolicy()

	var hits atomic.Int32
	victim := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		_, _ = w.Write([]byte("INTERNAL-SECRET"))
	}))
	defer victim.Close()

	chID, err := a.st.CreateChannel(context.Background(), store.Channel{Number: 1, Name: "分片台", Enabled: true})
	if err != nil {
		t.Fatalf("建频道失败: %v", err)
	}

	rec := doRaw(a, segRequest(t, a, chID, victim.URL+"/secret"))
	if rec.Code == http.StatusOK {
		t.Fatalf("/seg 代取了本机地址：%d %s", rec.Code, rec.Body.String())
	}
	if n := hits.Load(); n != 0 {
		t.Fatalf("受害服务被访问了 %d 次——出站策略没有接上", n)
	}
}
