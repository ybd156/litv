package fetch

import (
	"context"
	"strings"
	"testing"
	"time"
)

// TestValidateURLRejectsDangerousTargets 覆盖 SSRF 最危险的几类目标。
//
// 这些地址以前会被 fetch.Get 直接抓取：恶意 m3u 里写一条
// http://127.0.0.1:8080/... 的线路，服务端就会替它去访问本机管理面；
// 169.254.169.254 是云厂商的元数据服务，能读到实例凭据。
func TestValidateURLRejectsDangerousTargets(t *testing.T) {
	cases := []string{
		"http://127.0.0.1:8080/api",
		"http://localhost:8080/api",
		"http://[::1]/x",
		"http://169.254.169.254/latest/meta-data",
		"http://0.0.0.0/x",
		"file:///etc/passwd",
		"ftp://example.com/x",
		"//example.com/x",
	}
	for _, raw := range cases {
		if err := ValidateURL(raw); err == nil {
			t.Errorf("%q 被放行了（应当拒绝）", raw)
		}
	}
}

// TestValidateURLAllowsPrivateLAN 是兼容性回归：用户的自建源经常就在
// 局域网（192.168/10/172.16），一刀切会让最常见的部署直接不可用。
func TestValidateURLAllowsPrivateLAN(t *testing.T) {
	for _, raw := range []string{
		"http://192.168.1.10:8080/live.m3u8",
		"http://10.0.0.5:9981/stream",
		"https://example.com/playlist.m3u",
		"http://user:pass@example.com:8080/x",
	} {
		if err := ValidateURL(raw); err != nil {
			t.Errorf("%q 被错误拒绝: %v", raw, err)
		}
	}
}

// TestGetRejectsLoopbackBeforeDial 确认拒绝发生在建连之前：
// 端口 1 上没有服务，如果错误是「连接被拒绝」就说明拦截没生效。
func TestGetRejectsLoopbackBeforeDial(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	_, err := Get(ctx, "http://127.0.0.1:1/x", DefaultOptions())
	if err == nil {
		t.Fatal("抓取本机地址竟然成功了")
	}
	if !strings.Contains(err.Error(), "禁止访问") {
		t.Fatalf("错误不是 SSRF 拦截: %v", err)
	}
}

// TestSetAllowLoopback 确认测试开关真的能放开限制。
// 这也说明生产默认值必须是关闭的——否则这个开关就形同虚设。
func TestSetAllowLoopback(t *testing.T) {
	t.Cleanup(func() { SetAllowLoopback(false) })
	SetAllowLoopback(true)

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	_, err := Get(ctx, "http://127.0.0.1:1/x", DefaultOptions())
	if err == nil {
		t.Fatal("端口 1 上不可能有服务")
	}
	if strings.Contains(err.Error(), "禁止访问") {
		t.Fatalf("SetAllowLoopback(true) 之后仍然被拦: %v", err)
	}
}
