package api

import (
	"encoding/json"
	"net/http"
	"testing"
)

// registerDevice 发一次终端注册请求（TCP 入口，无需身份）。
func registerDevice(t *testing.T, a *API, key, code string) (*httptestRecorder, map[string]any) {
	t.Helper()
	body := map[string]any{
		"device_key": key,
		"name":       "测试终端",
		"platform":   "android",
		"model":      "test",
	}
	if code != "" {
		body["code"] = code
	}
	rec := rawRequest(t, a, http.MethodPost, "/api/v1/device/register", body, false)

	var out struct {
		Code int            `json:"code"`
		Msg  string         `json:"msg"`
		Data map[string]any `json:"data"`
	}
	_ = json.Unmarshal(rec.Body.Bytes(), &out)
	return &httptestRecorder{code: rec.Code, body: rec.Body.Bytes()}, out.Data
}

// TestGenericBindCodeBindsNewDevice 是本轮 R06 的核心回归测试。
//
// 修复前必然失败：管理台「生成绑定码」不传 device_id，服务端默认写 0；
// 而注册流程要求「码里的 device_id == 新设备自增 ID」，通用码的 0
// 永远不可能等于新 ID（AUTOINCREMENT 从 1 开始），所以通用码根本绑不上，
// 设备一直是 pending，用户输对码也用不了。
func TestGenericBindCodeBindsNewDevice(t *testing.T) {
	a, _ := newTestAPI(t)

	// 管理台实际调用：createBindCode() 不带 device_id → 通用邀请码。
	rec := adminRequest(t, a, http.MethodPost, "/api/v1/devices/bindcode", nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("生成绑定码失败：%d %s", rec.Code, rec.Body.String())
	}
	var bc struct {
		Data struct {
			Code string `json:"code"`
		} `json:"data"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &bc); err != nil || bc.Data.Code == "" {
		t.Fatalf("没拿到绑定码：%s", rec.Body.String())
	}

	// 终端用这个通用码注册，应当直接 bound。
	_, data := registerDevice(t, a, "dev-key-1", bc.Data.Code)
	if data["status"] != "bound" {
		t.Fatalf("通用绑定码未生效：status=%v（期望 bound）", data["status"])
	}
	if tok, _ := data["token"].(string); tok == "" {
		t.Error("未返回 token")
	}
}

// TestWrongDeviceDoesNotConsumeBindCode 确认错设备不会把码吃掉。
//
// 修复前：ConsumeBindCode 在校验归属之前就 UPDATE used=1，
// 所以一台不该用这个码的设备也会把码作废，真正该绑的设备反而绑不上。
func TestWrongDeviceDoesNotConsumeBindCode(t *testing.T) {
	a, _ := newTestAPI(t)

	// 先注册一台设备，针对它生成专有码。
	adminRequest(t, a, http.MethodPost, "/api/v1/devices/bindcode", map[string]any{"device_id": 0})

	rec := adminRequest(t, a, http.MethodPost, "/api/v1/devices/bindcode", map[string]any{})
	var bc struct {
		Data struct {
			Code string `json:"code"`
		} `json:"data"`
	}
	_ = json.Unmarshal(rec.Body.Bytes(), &bc)
	code := bc.Data.Code

	// 正确设备兑换成功
	_, d1 := registerDevice(t, a, "good-key", code)
	if d1["status"] != "bound" {
		t.Fatalf("正确设备未能绑定：%v", d1["status"])
	}

	// 同一个码再被另一台设备用：必须失败，且不能悄悄变成 bound
	_, d2 := registerDevice(t, a, "other-key", code)
	if d2["status"] == "bound" {
		t.Fatal("已被使用的绑定码仍然让第二台设备变成 bound")
	}
}

// TestBindCodeErrorsAreDistinct 确认不同错误有明确返回，而不是静默 pending。
func TestBindCodeErrorsAreDistinct(t *testing.T) {
	a, _ := newTestAPI(t)

	cases := []struct {
		name string
		code string
		desc string
	}{
		{"不存在的码", "ZZZZZZ", "绑定码不存在"},
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			rec, _ := registerDevice(t, a, "k-"+c.name, c.code)
			if rec.code == http.StatusOK {
				t.Errorf("%s：期望被拒绝，实际 200", c.desc)
			}
		})
	}
}

// TestDeviceRegistrationRejectsAdminPlatform 确认客户端不能自称管理员。
func TestDeviceRegistrationRejectsAdminPlatform(t *testing.T) {
	a, _ := newTestAPI(t)

	rec := rawRequest(t, a, http.MethodPost, "/api/v1/device/register", map[string]any{
		"device_key": "evil",
		"platform":   "admin",
	}, false)

	if rec.Code == http.StatusOK {
		t.Fatal("platform=admin 的注册被接受了——客户端可自封管理员")
	}
}

// TestDuplicateRegistrationDoesNotCreateSecondDevice 确认重复注册不留下第二台设备。
func TestDuplicateRegistrationDoesNotCreateSecondDevice(t *testing.T) {
	a, _ := newTestAPI(t)

	rec1 := rawRequest(t, a, http.MethodPost, "/api/v1/device/register",
		map[string]any{"device_key": "dup-key"}, false)
	if rec1.Code != http.StatusOK {
		t.Fatalf("首次注册失败：%d", rec1.Code)
	}

	rec2 := rawRequest(t, a, http.MethodPost, "/api/v1/device/register",
		map[string]any{"device_key": "dup-key"}, false)
	if rec2.Code == http.StatusOK {
		t.Fatal("重复 device_key 的注册应当被拒绝（409）")
	}

	// 关键：失败的注册必须整体回滚，设备表里只能有一条。
	rows, err := a.st.DB().Query(`SELECT COUNT(*) FROM devices WHERE device_key='dup-key'`)
	if err != nil {
		t.Fatalf("查询设备失败: %v", err)
	}
	defer rows.Close()
	if rows.Next() {
		var n int
		_ = rows.Scan(&n)
		if n != 1 {
			t.Errorf("重复注册后设备数为 %d，期望 1（事务未回滚）", n)
		}
	}
}
