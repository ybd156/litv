package fetch

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/litv/litv/server/internal/safedial"
)

// TestGetBlocksLoopback 是本轮 A3 在 fetch 层上的回归。
//
// 修复前必然失败：fetch.Get 直接用默认客户端，没有任何地址判定，
// 于是"订阅源地址"可以指向 127.0.0.1 / 169.254.169.254，
// NAS 就会把内网服务的响应当成"M3U 清单"读进来。
//
// 断言方式：受害服务收到 **0 次**请求。
// 只断言"返回了错误"是不够的——网络不通、超时、策略拒绝都会返回错误，
// 那三种情况分不开，测试可能因为无关原因假绿。
func TestGetBlocksLoopback(t *testing.T) {
	var hits atomic.Int32
	victim := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		_, _ = w.Write([]byte("#EXTM3U\n#EXTINF:-1,内网台\nhttp://internal/1.ts\n"))
	}))
	defer victim.Close()

	opt := DefaultOptions()
	opt.Timeout = 3 * time.Second

	_, err := Get(context.Background(), victim.URL+"/list.m3u", opt)
	if err == nil {
		t.Fatal("抓到了本机地址——出站地址策略没有生效")
	}
	if n := hits.Load(); n != 0 {
		t.Fatalf("受害服务被访问了 %d 次", n)
	}
	// 错误里要能看出是策略拒绝，调用方据此决定"不要重试"。
	if !safedial.IsBlockedError(err) {
		t.Errorf("错误未标注为策略拒绝: %v", err)
	}
}

// TestGetBlocksCloudMetadata 覆盖云元数据端点（链路本地段）。
//
// 这一段是 SSRF 最经典的收益点：拿到云厂商的实例凭据。
// 这里不需要真的连出去——策略在建连之前就拒了，所以用一个
// 不会解析的域名也没有意义，直接判段更稳。
func TestGetBlocksCloudMetadata(t *testing.T) {
	opt := DefaultOptions()
	opt.Timeout = 2 * time.Second

	_, err := Get(context.Background(), "http://169.254.169.254/latest/meta-data/", opt)
	if err == nil {
		t.Fatal("抓到了云元数据端点")
	}
	if !safedial.IsBlockedError(err) {
		t.Errorf("错误未标注为策略拒绝: %v", err)
	}
}

// TestGetErrorMessageDoesNotLeakSchemeDetail 只是顺手确认错误串仍是中文可读的。
func TestGetErrorMessageDoesNotLeakSchemeDetail(t *testing.T) {
	opt := DefaultOptions()
	opt.Timeout = time.Second
	_, err := Get(context.Background(), "http://127.0.0.1:1/x", opt)
	if err == nil {
		t.Fatal("应当失败")
	}
	if strings.Contains(err.Error(), "INTERNAL") {
		t.Errorf("错误串里出现了不该有的内容: %v", err)
	}
}
