package api

import (
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"sync/atomic"
	"testing"
	"time"

	"github.com/litv/litv/server/internal/auth"
)

// TestSegmentSignatureIsBoundToTarget 是 R04 的核心回归测试。
//
// 修复前必然失败（且是真实可利用的漏洞）：
// segmentURL 签的是「频道级播放签名」HMAC(channelID|exp|deviceKey)，
// 签名内容里**没有目标地址**。所以持有有效播放签名的已授权设备，
// 只要把查询参数 u 换成任意地址，NAS 就会代替它去请求——
// 播放权限被放大成"任意 HTTP 代理"，可以拿它探内网服务。
func TestSegmentSignatureIsBoundToTarget(t *testing.T) {
	a, _ := newTestAPI(t)
	signer := auth.NewSigner("test-secret-for-signing-only")

	const chID = int64(7)
	const dk = "device-key"

	orig := "http://upstream.example/a/seg001.ts"
	exp := time.Now().Add(time.Hour).Unix()
	sig := signer.SignSegment(chID, orig, exp, dk)

	// 1) 原目标必须能通过
	if err := signer.VerifySegment(chID, orig, exp, dk, sig); err != nil {
		t.Fatalf("原目标签名不该失败: %v", err)
	}

	// 2) 改目标（这正是攻击手法）必须失败
	if err := signer.VerifySegment(chID, "http://169.254.169.254/latest/meta-data/", exp, dk, sig); err == nil {
		t.Fatal("改掉 u 之后签名仍然通过——/seg 退化成任意 HTTP 代理")
	}

	// 3) 换频道必须失败
	if err := signer.VerifySegment(chID+1, orig, exp, dk, sig); err == nil {
		t.Fatal("换频道后签名仍然通过")
	}

	// 4) 换设备必须失败
	if err := signer.VerifySegment(chID, orig, exp, "other-device", sig); err == nil {
		t.Fatal("换设备后签名仍然通过")
	}

	// 5) 换到期时间必须失败
	if err := signer.VerifySegment(chID, orig, exp+3600, dk, sig); err == nil {
		t.Fatal("改到期时间后签名仍然通过")
	}

	_ = a
}

// TestSegmentSignatureNotReusableAsPlaySignature 确认用途隔离。
//
// 分片签名带 "seg" 前缀，不能被拿来当频道播放签名用；
// 反之，旧的频道级播放签名也不能当分片签名用（这正是 R04 的漏洞本体）。
func TestSegmentSignatureNotReusableAsPlaySignature(t *testing.T) {
	signer := auth.NewSigner("test-secret-for-signing-only")
	const chID = int64(7)
	const dk = "device-key"
	exp := time.Now().Add(time.Hour).Unix()

	playSig := signer.Sign(chID, exp, dk)
	target := "http://upstream.example/a/seg001.ts"

	// 拿播放签名冒充分片签名：必须失败（修复前这里会通过）
	if err := signer.VerifySegment(chID, target, exp, dk, playSig); err == nil {
		t.Fatal("频道级播放签名被当成分片签名接受——R04 未修复")
	}

	// 反过来，分片签名也不能当播放签名用
	segSig := signer.SignSegment(chID, target, exp, dk)
	if err := signer.Verify(chID, exp, dk, segSig); err == nil {
		t.Fatal("分片签名被当成播放签名接受")
	}
}

// TestSegmentSignatureExpires 确认过期校验生效。
func TestSegmentSignatureExpires(t *testing.T) {
	signer := auth.NewSigner("test-secret-for-signing-only")
	past := time.Now().Add(-time.Hour).Unix()
	sig := signer.SignSegment(7, "http://x/a.ts", past, "dk")
	if err := signer.VerifySegment(7, "http://x/a.ts", past, "dk", sig); err == nil {
		t.Fatal("已过期的分片签名仍然通过")
	}
}

// TestSegmentURLSignsActualTarget 端到端：segmentURL 生成的地址必须校验通过，
// 而改掉其中的 u 必须被拒。走真实的 handler，不是只测签名函数。
//
// 关键：篡改目标必须指向一个**真实可访问**的服务器。
// 如果指向连不上的地址，旧实现会因上游超时返回 502，
// 看上去"也没返回 200"，就无法区分"签名拒绝了"和"上游连不上"——
// 那样测试会在漏洞未修复时假绿。这里起一个 httptest 服务器，
// 使得"谁都能拿它当代理访问到"这件事在旧实现下真实发生。
func TestSegmentURLSignsActualTarget(t *testing.T) {
	a, _ := newTestAPI(t)

	// 受害服务：代表内网里任意一个不该被外部访问的 HTTP 服务。
	var victimHit int32
	victim := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&victimHit, 1)
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("INTERNAL-SECRET"))
	}))
	defer victim.Close()

	target := "http://upstream.example/b.ts"
	u := a.segmentURL(target, 7, "dk-1")
	parsed, err := url.Parse(u)
	if err != nil {
		t.Fatalf("解析 segmentURL 失败: %v", err)
	}
	if got := parsed.Query().Get("u"); got != target {
		t.Errorf("u 参数被改写：期望 %q 实际 %q", target, got)
	}

	// 攻击：攻击者本身就是已授权设备，手里有**合法的频道级播放签名**。
	// 它唯一要改的就是 u —— 这才是 R04 的真实利用形态。
	// 旧实现下签名内容不含目标，所以改 u 后依然通过，代理真的去访问 victim。
	expPlay := time.Now().Add(time.Hour).Unix()
	playSig := a.signer.Sign(7, expPlay, "dk-1")

	q := url.Values{}
	q.Set("u", victim.URL+"/latest/meta-data")
	q.Set("sig", playSig)
	q.Set("exp", strconv.FormatInt(expPlay, 10))
	q.Set("dk", "dk-1")

	// 必须用相对路径建请求：直接拿完整 URL 会带上 Host，
	// httptest 里路由匹配不上，测到的就变成 404 而不是 /seg 本身（假绿）。
	req := httptest.NewRequest(http.MethodGet, "/live/7/seg?"+q.Encode(), nil)
	rec := doRaw(a, req)

	if rec.Code == http.StatusOK {
		t.Fatal("篡改 u 后 /seg 仍然返回 200——任意 HTTP 代理漏洞")
	}
	// 更强的判据：受害服务绝不能被访问到。
	if atomic.LoadInt32(&victimHit) != 0 {
		t.Fatal("受害服务被代请求到了——签名没有绑定实际目标")
	}
}

// TestNormalizeSegmentTargetIsStable 确认归一化不会把不同目标混为一谈。
func TestNormalizeSegmentTargetIsStable(t *testing.T) {
	signer := auth.NewSigner("test-secret-for-signing-only")
	exp := time.Now().Add(time.Hour).Unix()

	// 大小写 scheme/host 与默认端口属于同一目标
	sig := signer.SignSegment(7, "HTTP://Upstream.Example:80/a.ts", exp, "dk")
	if err := signer.VerifySegment(7, "http://upstream.example/a.ts", exp, "dk", sig); err != nil {
		t.Errorf("同一目标的不同写法应当等价: %v", err)
	}

	// 不同路径必须仍然不同（归一化不能吃掉路径差异）
	if err := signer.VerifySegment(7, "http://upstream.example/b.ts", exp, "dk", sig); err == nil {
		t.Fatal("不同路径被判为同一目标——签名强度被归一化削弱")
	}
}
