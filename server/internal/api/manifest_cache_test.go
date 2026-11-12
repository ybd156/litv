package api

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/litv/litv/server/internal/store"
)

func manifestRequest(a *API, chID, sid int64, dk string) *http.Request {
	exp := time.Now().Add(time.Hour).Unix()
	q := url.Values{}
	if sid > 0 {
		q.Set("sig", a.signer.SignStream(chID, sid, exp, dk))
		q.Set("stream_id", strconv.FormatInt(sid, 10))
	} else {
		q.Set("sig", a.signer.Sign(chID, exp, dk))
	}
	q.Set("exp", strconv.FormatInt(exp, 10))
	q.Set("dk", dk)
	return httptest.NewRequest(http.MethodGet,
		fmt.Sprintf("/live/%d/index.m3u8?%s", chID, q.Encode()), nil)
}

// TestManifestCacheCoalescesUpstreamFetches 覆盖 HLS 清单缓存的两条不变式：
//
//  1. 同一频道连续/并发请求只抓一次上游（以前每个客户端每 2–6 秒各抓一次，
//     并把 UpdateStreamHealth 变成高频写库）；
//  2. 缓存的是**上游原文**，改写按请求者的设备标识进行——否则 B 会拿到
//     A 的签名地址（等于把 A 的设备标识发给 B）。
func TestManifestCacheCoalescesUpstreamFetches(t *testing.T) {
	a, _ := newTestAPI(t)
	ctx := context.Background()

	var hits int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&hits, 1)
		w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
		fmt.Fprint(w, "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\nseg1.ts\n")
	}))
	defer srv.Close()

	chID, err := a.st.CreateChannel(ctx, store.Channel{Number: 1, Name: "缓存台", Enabled: true})
	if err != nil {
		t.Fatalf("建频道失败: %v", err)
	}
	sid, err := a.st.AddStream(ctx, chID, srv.URL+"/live.m3u8", "hd", 1)
	if err != nil {
		t.Fatalf("加线路失败: %v", err)
	}

	recA := doRaw(a, manifestRequest(a, chID, sid, "dev-A"))
	if recA.Code != http.StatusOK {
		t.Fatalf("A 拉清单失败：%d %s", recA.Code, recA.Body.String())
	}
	recB := doRaw(a, manifestRequest(a, chID, sid, "dev-B"))
	if recB.Code != http.StatusOK {
		t.Fatalf("B 拉清单失败：%d %s", recB.Code, recB.Body.String())
	}

	if got := atomic.LoadInt32(&hits); got != 1 {
		t.Fatalf("上游被请求了 %d 次，期望 1 次（应命中缓存）", got)
	}

	bodyA := recA.Body.String()
	bodyB := recB.Body.String()
	if !strings.Contains(bodyA, "dk=dev-A") || strings.Contains(bodyA, "dk=dev-B") {
		t.Fatalf("A 的清单里设备标识不对:\n%s", bodyA)
	}
	if !strings.Contains(bodyB, "dk=dev-B") || strings.Contains(bodyB, "dk=dev-A") {
		t.Fatalf("B 拿到了 A 的签名地址（缓存了改写结果）:\n%s", bodyB)
	}

	// 分片请求要靠这个记录取「同一条线路」的请求头。
	if gotSID, ok := a.cachedManifestStream(chID); !ok || gotSID != sid {
		t.Fatalf("清单选中的线路没有被记录：sid=%d ok=%v", gotSID, ok)
	}
}
