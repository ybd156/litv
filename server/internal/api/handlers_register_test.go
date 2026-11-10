package api

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
)

// registerCall 直接打 TCP 入口（viaGateway=false），模拟终端注册。
//
// 不用 adminRequest：那条路带网关注入身份，和终端注册的真实形态不一样。
func registerCall(t *testing.T, a *API, key, platform string) *httptest.ResponseRecorder {
	t.Helper()
	body, _ := json.Marshal(map[string]any{
		"device_key": key,
		"name":       "测试设备",
		"platform":   platform,
	})
	req := httptest.NewRequest(http.MethodPost, "/api/v1/device/register", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	a.Handler(false).ServeHTTP(rec, req)
	return rec
}

// TestDeviceRegisterIsRateLimited 覆盖 A7 的限流。
//
// 修复前必然失败：deviceRegister 完全没有限流，
// 通用邀请码只有 6 位、device_key 也可以猜，等于给了一个不限速的暴力入口。
//
// 这里断言的是**第 N+1 次被拒**，并且带 Retry-After——
// 不给 Retry-After 的话客户端只会立刻重试，把窗口越刷越长。
func TestDeviceRegisterIsRateLimited(t *testing.T) {
	a, _ := newTestAPI(t)

	// LoginIPLimit = 5 / 分钟（同一个限流器形态），前 5 次应放行。
	for i := 0; i < 5; i++ {
		rec := registerCall(t, a, "key-"+strconv.Itoa(i), "android")
		if rec.Code == http.StatusTooManyRequests {
			t.Fatalf("第 %d 次就被限流了，限流太紧", i+1)
		}
	}

	rec := registerCall(t, a, "key-over", "android")
	if rec.Code != http.StatusTooManyRequests {
		t.Fatalf("第 6 次注册没有被限流：%d %s", rec.Code, rec.Body.String())
	}
	if ra := rec.Header().Get("Retry-After"); ra == "" {
		t.Error("429 响应缺少 Retry-After 头")
	}
	// 信封形状不能变。
	var out struct {
		Code int    `json:"code"`
		Msg  string `json:"msg"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatalf("429 响应不是合法信封: %v body=%s", err, rec.Body.String())
	}
	if out.Code == 0 {
		t.Error("429 的信封 code 不应为 0")
	}
}

// TestDeviceRegisterPlatformSwitchRejected 覆盖 A7 的核心安全边界。
//
// 修复前必然失败：store.RegisterWithBindCode 只在 existStatus == "bound" 时
// 才返回 ErrDeviceKeyConflict，pending 行会被直接覆盖 token_hash 并返回成功。
// 于是知道一台"待批准"设备的 device_key，就能把它顶掉，
// 再让管理员去"批准"那台其实是攻击者的设备。
func TestDeviceRegisterPlatformSwitchRejected(t *testing.T) {
	a, _ := newTestAPI(t)

	first := registerCall(t, a, "stable-key", "android")
	if first.Code != http.StatusOK {
		t.Fatalf("首次注册失败：%d %s", first.Code, first.Body.String())
	}

	// 同 key、换平台：必须是 409 + code 1002（而不是 200 + pending）。
	second := registerCall(t, a, "stable-key", "ios")
	if second.Code != http.StatusConflict {
		t.Fatalf("同 device_key 换平台未被拒绝：%d %s", second.Code, second.Body.String())
	}
	var out struct {
		Code int `json:"code"`
	}
	if err := json.Unmarshal(second.Body.Bytes(), &out); err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	if out.Code != 1002 {
		t.Errorf("期望 code 1002（设备标识冲突），实际 %d", out.Code)
	}
}

// TestDeviceRegisterAuditHasNoPlaintextKey 确认审计里不出现 device_key 明文。
//
// device_key 是 ANDROID_ID 之类的稳定标识派生出来的，
// 明文落进 audit_logs 等于把"设备持久身份"存进一张会展示、会导出的表。
func TestDeviceRegisterAuditHasNoPlaintextKey(t *testing.T) {
	a, _ := newTestAPI(t)
	const key = "PLAINTEXT-DEVICE-KEY-9f3a"

	if rec := registerCall(t, a, key, "android"); rec.Code != http.StatusOK {
		t.Fatalf("注册失败：%d %s", rec.Code, rec.Body.String())
	}

	rows, err := a.st.DB().QueryContext(context.Background(),
		`SELECT action, target, detail FROM audit_logs WHERE action LIKE 'device.register%'`)
	if err != nil {
		t.Fatalf("查审计失败: %v", err)
	}
	defer rows.Close()

	n := 0
	for rows.Next() {
		var action, target, detail string
		if err := rows.Scan(&action, &target, &detail); err != nil {
			t.Fatalf("扫描失败: %v", err)
		}
		n++
		if target == key || detail == key {
			t.Fatalf("审计里出现了 device_key 明文：action=%s target=%s", action, target)
		}
		if len(target) > 12 {
			t.Errorf("审计指纹过长（应 ≤12 hex）：%q", target)
		}
	}
	if n == 0 {
		t.Fatal("注册没有写审计（A7 要求留痕）")
	}
}
