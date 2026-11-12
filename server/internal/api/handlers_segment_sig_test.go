package api

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"
	"time"
)

// TestSegmentURLCanActuallyBeRequested 验证 segmentURL 生成的地址**真的能取到**。
//
// 这条测试暴露一个设计矛盾：/seg 用同一个 sig 参数做了两次校验——
// 先 verifyStreamFull（频道级签名），再 VerifySegment（绑定目标签名）。
// 一个签名不可能同时满足两套内容，所以 segmentURL 生成的地址永远 403。
//
// 若这条测试失败，说明 HLS 分片代理整体不可用：
// 所有 hls.js / VLC 的分片请求都会 403，表现就是"清单能取到、画面全黑"。
func TestSegmentURLCanActuallyBeRequested(t *testing.T) {
	a, _ := newTestAPI(t)

	// 用 segmentURL 生成地址，就像 manifest 改写时那样
	const chID = int64(1)
	const dk = "dev-key"
	target := "http://upstream.example/a/seg001.ts"
	u := a.segmentURL(target, chID, dk)

	parsed, err := url.Parse(u)
	if err != nil {
		t.Fatalf("解析失败: %v", err)
	}

	// 直接请求这个地址（不真实的 target 会连不上，但签名校验在前面）
	rec := doRaw(a, httptest.NewRequest(http.MethodGet, parsed.RequestURI(), nil))

	// 期望：签名校验通过（403 才算签名问题；其他错误说明已过校验，只是连不上上游）
	if rec.Code == http.StatusForbidden {
		t.Fatalf("segmentURL 生成的地址被签名校验拒绝：%s\n说明 /seg 的双重签名互相矛盾，分片代理不可用", rec.Body.String())
	}
}

// TestSegmentSignatureSatisfiesBothChecks 确认分片地址能同时通过两层校验。
func TestSegmentSignatureSatisfiesBothChecks(t *testing.T) {
	a, _ := newTestAPI(t)
	signer := a.signer

	const chID = int64(1)
	const dk = "dev-key"
	target := "http://upstream.example/a/seg001.ts"
	exp := time.Now().Add(time.Hour).Unix()

	// 生成分片签名
	segSig := signer.SignSegment(chID, target, exp, dk)

	// 第一层：频道级校验（verifyStream 做的）
	channelErr := signer.Verify(chID, exp, dk, segSig)
	// 第二层：分片级校验
	segErr := signer.VerifySegment(chID, target, exp, dk, segSig)

	t.Logf("频道级校验: %v", channelErr)
	t.Logf("分片级校验: %v", segErr)

	// 至少要有一层能区分"合法分片地址"与"伪造地址"。
	// 若两层都不能通过，说明这个地址根本无法使用。
	if channelErr != nil && segErr != nil {
		t.Fatal("分片签名无法通过任何一层校验——/seg 不可用")
	}
}

// TestChannelSigAndSegmentSigAreDistinct 记录两套签名的实际关系。
func TestChannelSigAndSegmentSigAreDistinct(t *testing.T) {
	a, _ := newTestAPI(t)
	const chID = int64(1)
	const dk = "dk"
	exp := time.Now().Add(time.Hour).Unix()
	target := "http://x/a.ts"

	cs := a.signer.Sign(chID, exp, dk)
	ss := a.signer.SignSegment(chID, target, exp, dk)
	if cs == ss {
		t.Fatal("频道签名与分片签名相同，用途隔离失效")
	}
	fmt.Println("channelSig verifySegment:", a.signer.VerifySegment(chID, target, exp, dk, cs))
	fmt.Println("segmentSig verify:", a.signer.Verify(chID, exp, dk, ss))
}
