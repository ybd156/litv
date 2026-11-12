package api

import (
	"context"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/litv/litv/server/internal/store"
)

// TestPlayURLDoesNotLeakDeviceKey 是「dk 里带明文 device_key」的回归测试。
//
// device_key 不是普通标识：服务端遇到「已 bound 且平台一致」的同一个 key
// 会直接换发可用 token，所以它等价于设备凭据。它以前明文出现在播放/分片
// 地址的 dk 参数里——地址一旦进浏览器历史、反代日志或 Referer，
// 知道它的人就能把这台设备顶掉。
func TestPlayURLDoesNotLeakDeviceKey(t *testing.T) {
	a, _ := newTestAPI(t)
	ctx := context.Background()

	chID, err := a.st.CreateChannel(ctx, store.Channel{Number: 1, Name: "台", Enabled: true})
	if err != nil {
		t.Fatalf("建频道失败: %v", err)
	}
	sid, err := a.st.AddStream(ctx, chID, "http://up.example/live.m3u8", "hd", 1)
	if err != nil {
		t.Fatalf("加线路失败: %v", err)
	}
	if _, err := a.st.DB().ExecContext(ctx,
		`UPDATE streams SET health=1, latency_ms=1 WHERE id=?`, sid); err != nil {
		t.Fatalf("更新健康度失败: %v", err)
	}

	const rawKey = "stable-device-key-0123456789"
	token := boundDeviceToken(t, a, rawKey)

	rec := deviceGET(t, a, fmt.Sprintf("/api/v1/tv/play/%d", chID), token)
	if rec.Code != http.StatusOK {
		t.Fatalf("play 失败：%d %s", rec.Code, rec.Body.String())
	}
	playURL := playURLString(t, rec.Body.Bytes())
	if strings.Contains(playURL, rawKey) {
		t.Fatalf("播放地址里出现了明文 device_key:\n%s", playURL)
	}

	u, err := url.Parse(playURL)
	if err != nil {
		t.Fatalf("解析播放地址失败: %v", err)
	}
	q := u.Query()
	if q.Get("dk") == "" {
		t.Fatal("播放地址缺少 dk，签名将没有设备绑定")
	}
	if q.Get("dk") == rawKey {
		t.Fatal("dk 仍是明文 device_key")
	}

	// 派生值只是签名材料，校验路径必须照常通过（所以升级不会让新地址 403）。
	if _, err := a.signer.VerifyStreamSig(chID, sid, mustInt64(t, q.Get("exp")), q.Get("dk"), q.Get("sig")); err != nil {
		t.Fatalf("用派生 dk 校验签名失败: %v", err)
	}

	// 旧地址（dk=明文）仍然能校验：这是兼容性的一部分，
	// 已发出的 12 小时地址不该因为升级立刻失效。
	exp := time.Now().Add(time.Hour).Unix()
	legacySig := a.signer.Sign(chID, exp, rawKey)
	if err := a.signer.Verify(chID, exp, rawKey, legacySig); err != nil {
		t.Fatalf("旧签名不再通过校验: %v", err)
	}
}
