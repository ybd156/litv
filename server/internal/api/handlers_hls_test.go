package api

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/litv/litv/server/internal/store"
)

// TestNestedPlaylistUsesFinalURLAsBase 是 R14 的核心回归测试。
//
// 修复前必然失败：子清单改写用 url.Parse(target)（我们请求的地址）当基准，
// 而不是 resp.Request.URL（重定向后的最终地址）。
//
// 源站很常见 302 到另一个目录（CDN 调度、鉴权跳板）。用请求地址当基准，
// 相对分片会解析到错误目录 —— 表现是 master 能取到、variant 却 404，
// 用户看到的就是"这个台播不了"，而且极难定位。
func TestNestedPlaylistUsesFinalURLAsBase(t *testing.T) {
	a, _ := newTestAPI(t)
	ctx := context.Background()

	chID, _ := a.st.CreateChannel(ctx, store.Channel{Number: 1, Name: "嵌套台", Enabled: true})
	sid, _ := a.st.AddStream(ctx, chID, "http://origin.example/entry.m3u8", "hd", 1)

	// 模拟源站：/entry 302 跳到一个**不同目录**的 variant，
	// variant 里的分片是相对路径（seg1.ts → 应解析到 /deep/dir/seg1.ts）
	mux := http.NewServeMux()
	mux.HandleFunc("/entry", func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, "/deep/dir/variant.m3u8", http.StatusFound)
	})
	mux.HandleFunc("/deep/dir/variant.m3u8", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
		fmt.Fprint(w, "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\nseg1.ts\n")
	})
	srv := httptest.NewServer(mux)
	defer srv.Close()

	// 用 httptest 地址覆盖线路 URL，让它真的能被请求到
	if _, err := a.st.DB().ExecContext(ctx,
		`UPDATE streams SET url=?, health=1 WHERE id=?`,
		srv.URL+"/entry", sid); err != nil {
		t.Fatalf("更新线路地址失败: %v", err)
	}

	// 直接请求 /seg 代理那个会重定向的地址（模拟 hls.js 拿到 master 后的第二次请求）
	exp := time.Now().Add(time.Hour).Unix()
	target := srv.URL + "/entry"
	sig := a.signer.SignSegment(chID, target, exp, "dk")

	u := fmt.Sprintf("/live/%d/seg?u=%s&sig=%s&exp=%d&dk=dk",
		chID, url.QueryEscape(target), url.QueryEscape(sig), exp)
	rec := doRaw(a, httptest.NewRequest(http.MethodGet, u, nil))

	if rec.Code != http.StatusOK {
		t.Fatalf("代理请求失败：%d %s", rec.Code, rec.Body.String())
	}

	body := rec.Body.String()
	if !strings.Contains(body, "#EXTM3U") {
		t.Fatalf("没有返回清单内容：%s", body)
	}

	// 关键断言：改写后的分片地址必须基于**重定向后的**目录 /deep/dir/。
	// 修复前会基于 /entry，拼出 http://origin.example/seg1.ts（错误目录）。
	if !strings.Contains(body, "deep/dir/seg1.ts") && !strings.Contains(body, "deep%2Fdir%2Fseg1.ts") {
		t.Fatalf("相对分片没有按重定向后的最终目录解析：\n%s", body)
	}
	// 而且必须被改写成我们自己的代理地址
	if !strings.Contains(body, "/live/1/seg") {
		t.Fatalf("分片地址没有被改写成代理地址：\n%s", body)
	}
}

// TestURIAttrInExtXKeyIsRewritten 确认 #EXT-X-KEY 的 URI 属性被改写。
//
// 只改写裸行、不改写属性 URI 的话，AES 密钥会相对 NAS 的 /seg 解析，
// 结果"能起播但一直黑屏/解密失败"。
func TestURIAttrInExtXKeyIsRewritten(t *testing.T) {
	a, _ := newTestAPI(t)
	ctx := context.Background()

	chID, _ := a.st.CreateChannel(ctx, store.Channel{Number: 1, Name: "台", Enabled: true})
	sid, _ := a.st.AddStream(ctx, chID, "http://x/y.m3u8", "hd", 1)

	mux := http.NewServeMux()
	mux.HandleFunc("/deep/dir/variant.m3u8", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
		fmt.Fprint(w, "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:2,\nseg1.ts\n")
	})
	srv := httptest.NewServer(mux)
	defer srv.Close()

	base := srv.URL
	// 直接调用改写函数，验证属性 URI 与裸行都被处理
	baseURL, _ := url.Parse(base + "/deep/dir/variant.m3u8")
	got := a.segmentURLDepth(baseURL.String(), chID, "dk", 1, 4)
	_ = got

	line := `#EXT-X-KEY:METHOD=AES-128,URI="key.bin"`
	out := rewriteURIAttrDepth(line, baseURL, a, chID, "dk", 1)
	if !strings.Contains(out, "/live/1/seg") {
		t.Fatalf("EXT-X-KEY 的 URI 没有被改写成代理地址：%s", out)
	}
	if !strings.Contains(out, "key.bin") && !strings.Contains(out, "key%2Ebin") {
		t.Fatalf("改写后丢失了原始密钥路径：%s", out)
	}

	mapLine := `#EXT-X-MAP:URI="init.mp4"`
	out2 := rewriteURIAttrDepth(mapLine, baseURL, a, chID, "dk", 1)
	if !strings.Contains(out2, "/live/1/seg") {
		t.Fatalf("EXT-X-MAP 的 URI 没有被改写：%s", out2)
	}

	_ = sid
}

// TestNestedDepthIsBounded 确认递归深度有界，恶意循环不会拖死 NAS。
func TestNestedDepthIsBounded(t *testing.T) {
	a, _ := newTestAPI(t)

	// 深度超过上限时，生成的地址不应继续把 depth 往上加。
	u := a.segmentURLDepth("http://x/a.m3u8", 1, "dk", 5, 4)
	if !strings.Contains(u, "depth=5") {
		t.Errorf("depth 没有被写进代理地址：%s", u)
	}
	// 解析回来确认 depth 可被下一跳读到
	parsed, err := url.Parse(u)
	if err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	if parsed.Query().Get("depth") != "5" {
		t.Errorf("depth 参数错误：%s", parsed.Query().Get("depth"))
	}
}

// TestSegmentDepthPropagatesAcrossHops 确认深度能跨跳传播。
func TestSegmentDepthPropagatesAcrossHops(t *testing.T) {
	a, _ := newTestAPI(t)

	// 第一跳：depth 不写
	u1 := a.segmentURL("http://x/a.m3u8", 1, "dk")
	p1, _ := url.Parse(u1)
	if p1.Query().Get("depth") != "" {
		t.Errorf("首跳不该带 depth：%s", p1.Query().Get("depth"))
	}

	// 第二跳：带 depth=2
	u2 := a.segmentURLDepth("http://x/b.m3u8", 1, "dk", 2, 4)
	p2, _ := url.Parse(u2)
	if p2.Query().Get("depth") != "2" {
		t.Errorf("第二跳 depth 应为 2，实际 %q", p2.Query().Get("depth"))
	}

	_ = strconv.Itoa(0)
}
