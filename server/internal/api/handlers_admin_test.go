package api

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"github.com/litv/litv/server/internal/auth"
	"github.com/litv/litv/server/internal/config"
	"github.com/litv/litv/server/internal/fetch"
	"github.com/litv/litv/server/internal/safedial"
	"github.com/litv/litv/server/internal/store"
	"github.com/litv/litv/server/internal/stream"
)

// newTestAPI 造一个最小可用的 API：临时 SQLite + 临时配置文件。
//
// 不碰真实数据库，也不用固定路径，避免测试之间互相污染。
func newTestAPI(t *testing.T) (*API, string) {
	t.Helper()

	dir := t.TempDir()
	cfgPath := filepath.Join(dir, "config.yaml")

	cfg := config.Default()
	cfg.Database.Path = filepath.Join(dir, "litv.db")
	cfg.Stream.CacheDir = filepath.Join(dir, "cache")
	cfg.Auth.Secret = "test-secret-for-signing-only"
	if err := cfg.EnsureDirs(); err != nil {
		t.Fatalf("创建目录失败: %v", err)
	}
	if err := cfg.Save(cfgPath); err != nil {
		t.Fatalf("写入初始配置失败: %v", err)
	}

	st, err := store.Open(cfg.Database.Path)
	if err != nil {
		t.Fatalf("打开数据库失败: %v", err)
	}
	t.Cleanup(func() { st.Close() })

	hub := stream.NewHub(stream.Options{})
	t.Cleanup(hub.Close)

	// 集成测试用 httptest（127.0.0.1）当上游；生产路径默认禁止回环。
	fetch.SetAllowLoopback(true)

	a := New(cfg, st, hub, "test")
	a.SetConfigPath(cfgPath)
	// 显式放行回环地址，**只在这里**。
	//
	// httptest 服务器都监听 127.0.0.1，而生产默认策略必须拒绝回环
	// （那正是"让 NAS 打自己管理口"的入口）。测试需要正向路径能通，
	// 所以走 Policy 注入，而不是放宽 safedial.DefaultPolicy——
	// 后者会让所有部署一起失去这条防线。
	a.dialPolicy = safedial.Policy{AllowLoopback: true}
	return a, cfgPath
}

// adminRequest 发一个带管理员身份的请求。
//
// 模拟 fnOS 网关转发：viaGateway=true 的入口 + X-Trim-* 身份头。
// 这是浏览器管理台的正常入口，用它可以覆盖真实的鉴权链路
// （包括 UpsertUser 落库），而不是绕过中间件直接塞 context。
func adminRequest(t *testing.T, a *API, method, path string, body any) *httptest.ResponseRecorder {
	t.Helper()

	var buf bytes.Buffer
	if body != nil {
		if err := json.NewEncoder(&buf).Encode(body); err != nil {
			t.Fatalf("编码请求体失败: %v", err)
		}
	}
	req := httptest.NewRequest(method, path, &buf)
	req.Header.Set("Content-Type", "application/json")
	// 网关注入的管理员身份。IsAdmin 必须是 "true"（见 auth.FromGateway）。
	req.Header.Set(auth.HeaderUserID, "42")
	req.Header.Set(auth.HeaderUsername, "admin")
	req.Header.Set(auth.HeaderIsAdmin, "true")

	rec := httptest.NewRecorder()
	a.Handler(true).ServeHTTP(rec, req)
	return rec
}

// TestPutConfigAcceptsRealSettingsPayload 是本轮的回归测试。
//
// 背景（修复前必然失败）：管理台 SettingsView.save() 提交 6 个字段，
// 而 putConfig 只声明了 3 个，decode() 又开了 DisallowUnknownFields，
// 所以每次点「保存」都返回 400。用户在设置页什么都改不了。
//
// 这里直接照抄 admin/src/views/SettingsView.vue 的 payload 形状。
func TestPutConfigAcceptsRealSettingsPayload(t *testing.T) {
	a, cfgPath := newTestAPI(t)

	// 与 SettingsView.save() 的请求体一字不差。
	payload := map[string]any{
		"port":              28027,
		"max_concurrent":    8,
		"transcode_enabled": true,
		"log_level":         "debug",
		"epg_refresh_hour":  4,
		"allow_lan":         true,
	}

	rec := adminRequest(t, a, http.MethodPut, "/api/v1/system/config", payload)
	if rec.Code != http.StatusOK {
		t.Fatalf("设置页真实 payload 被拒绝：status=%d body=%s", rec.Code, rec.Body.String())
	}

	var out struct {
		Code int `json:"code"`
		Data struct {
			Saved bool `json:"saved"`
		} `json:"data"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatalf("解析响应失败: %v body=%s", err, rec.Body.String())
	}
	if out.Code != 0 || !out.Data.Saved {
		t.Fatalf("保存未成功：%s", rec.Body.String())
	}

	// 修复前这里是断言失败的第二层：保存只改内存，配置根本没落盘。
	onDisk := config.MustLoadForTest(t, cfgPath)
	if onDisk.Stream.MaxConcurrent != 8 {
		t.Errorf("max_concurrent 未持久化：期望 8，实际 %d", onDisk.Stream.MaxConcurrent)
	}
	if !onDisk.Transcode.Enabled {
		t.Error("transcode_enabled 未持久化：期望 true，实际 false")
	}
	if onDisk.Log.Level != "debug" {
		t.Errorf("log_level 未持久化：期望 debug，实际 %q", onDisk.Log.Level)
	}
	if onDisk.EPG.RefreshCron != "0 4 * * *" {
		t.Errorf("epg_refresh_hour 未换算成 cron：期望 \"0 4 * * *\"，实际 %q", onDisk.EPG.RefreshCron)
	}
	if onDisk.Server.Host != "0.0.0.0" {
		t.Errorf("allow_lan 未生效：期望 0.0.0.0，实际 %q", onDisk.Server.Host)
	}
}

// TestPutConfigRejectsInvalidValues 确认非法值被整体拒绝，而不是静默写坏配置。
func TestPutConfigRejectsInvalidValues(t *testing.T) {
	a, cfgPath := newTestAPI(t)
	before := config.MustLoadForTest(t, cfgPath).Stream.MaxConcurrent

	cases := []struct {
		name    string
		payload map[string]any
	}{
		{"端口越界", map[string]any{"port": 70000}},
		{"并发为 0", map[string]any{"max_concurrent": 0}},
		{"EPG 小时越界", map[string]any{"epg_refresh_hour": 25}},
		{"非法日志级别", map[string]any{"log_level": "verbose"}},
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			rec := adminRequest(t, a, http.MethodPut, "/api/v1/system/config", c.payload)
			if rec.Code != http.StatusBadRequest {
				t.Errorf("%s：期望 400，实际 %d body=%s", c.name, rec.Code, rec.Body.String())
			}
		})
	}

	// 被拒绝的请求不能改动磁盘配置。
	after := config.MustLoadForTest(t, cfgPath).Stream.MaxConcurrent
	if after != before {
		t.Errorf("非法请求改动了下发配置：%d -> %d", before, after)
	}
}

// TestGetConfigRoundTrip 确认保存后 GET 读回一致。
func TestGetConfigRoundTrip(t *testing.T) {
	a, _ := newTestAPI(t)

	payload := map[string]any{
		"max_concurrent":    5,
		"transcode_enabled": false,
		"log_level":         "warn",
		"epg_refresh_hour":  3,
		"allow_lan":         false,
		"port":              19000,
	}
	if rec := adminRequest(t, a, http.MethodPut, "/api/v1/system/config", payload); rec.Code != http.StatusOK {
		t.Fatalf("保存失败：%d %s", rec.Code, rec.Body.String())
	}

	rec := adminRequest(t, a, http.MethodGet, "/api/v1/system/config", nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("读取配置失败：%d", rec.Code)
	}
	var out struct {
		Data struct {
			MaxConcurrent int    `json:"max_concurrent"`
			LogLevel      string `json:"log_level"`
			AllowLAN      bool   `json:"allow_lan"`
			Port          int    `json:"port"`
		} `json:"data"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	if out.Data.MaxConcurrent != 5 || out.Data.LogLevel != "warn" {
		t.Errorf("回读不一致：max_concurrent=%d log_level=%q", out.Data.MaxConcurrent, out.Data.LogLevel)
	}
	if out.Data.AllowLAN {
		t.Error("allow_lan=false 应当回读为 false")
	}
	if out.Data.Port != 19000 {
		t.Errorf("port 回读不一致：%d", out.Data.Port)
	}
}

// httptestRecorder 是测试里用的最小响应快照（状态码 + body）。
type httptestRecorder struct {
	code int
	body []byte
}

// rawRequest 发一个可指定入口的请求：viaGateway=true 模拟浏览器，
// false 模拟终端 App 直连 TCP。两者鉴权路径完全不同，必须分开测。
func rawRequest(t *testing.T, a *API, method, path string, body any, viaGateway bool) *httptest.ResponseRecorder {
	t.Helper()

	var buf bytes.Buffer
	if body != nil {
		if err := json.NewEncoder(&buf).Encode(body); err != nil {
			t.Fatalf("编码请求体失败: %v", err)
		}
	}
	req := httptest.NewRequest(method, path, &buf)
	req.Header.Set("Content-Type", "application/json")
	if viaGateway {
		req.Header.Set(auth.HeaderUserID, "42")
		req.Header.Set(auth.HeaderUsername, "admin")
		req.Header.Set(auth.HeaderIsAdmin, "true")
	}

	rec := httptest.NewRecorder()
	a.Handler(viaGateway).ServeHTTP(rec, req)
	return rec
}

// TestPutConfigRequiresAdmin 确认非管理员不能写配置。
func TestPutConfigRequiresAdmin(t *testing.T) {
	a, _ := newTestAPI(t)

	req := httptest.NewRequest(http.MethodPut, "/api/v1/system/config",
		bytes.NewBufferString(`{"max_concurrent":9}`))
	req.Header.Set("Content-Type", "application/json")

	// viaGateway=false 且没有 token：拿不到任何身份。
	rec := httptest.NewRecorder()
	a.Handler(false).ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Errorf("未认证请求写配置：期望 401，实际 %d", rec.Code)
	}
}

// TestLogLevelAppliedAtRuntime 确认日志级别能运行期切换。
func TestLogLevelAppliedAtRuntime(t *testing.T) {
	if ok := config.SetLogLevel("debug"); !ok {
		t.Fatal("debug 应当是合法级别")
	}
	if ok := config.SetLogLevel("bogus"); ok {
		t.Error("bogus 不该被接受")
	}
	if got := config.NormalizeLogLevel("ERROR"); got != "error" {
		t.Errorf("大小写归一化失败：%q", got)
	}
}

// TestConfigSaveIsAtomicAndPrivate 确认配置文件权限收紧且能原子替换。
func TestConfigSaveIsAtomicAndPrivate(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.yaml")

	cfg := config.Default()
	cfg.Auth.Secret = "0123456789abcdef0123456789abcdef"
	if err := cfg.Save(path); err != nil {
		t.Fatalf("保存失败: %v", err)
	}

	info, err := os.Stat(path)
	if err != nil {
		t.Fatalf("配置文件不存在: %v", err)
	}
	// 配置里有管理密码和签名密钥，不能让同组/其他用户可读。
	if perm := info.Mode().Perm(); perm&0o077 != 0 {
		t.Errorf("配置文件权限过宽：%o", perm)
	}

	reloaded, err := config.Load(path)
	if err != nil {
		t.Fatalf("重新加载失败: %v", err)
	}
	if reloaded.Auth.Secret != "0123456789abcdef0123456789abcdef" {
		t.Errorf("密钥未正确往返：%q", reloaded.Auth.Secret)
	}

	// 目录里不应残留临时文件。
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatalf("读取目录失败: %v", err)
	}
	for _, e := range entries {
		if e.Name() != "config.yaml" {
			t.Errorf("残留临时文件：%s", e.Name())
		}
	}
}

// TestGatewayHeaderNotTrustedOnTCP 是安全边界的回归测试。
//
// X-Trim-* 头只在 viaGateway=true 时可信；TCP 入口伪造这些头不能变成管理员。
func TestGatewayHeaderNotTrustedOnTCP(t *testing.T) {
	a, _ := newTestAPI(t)

	req := httptest.NewRequest(http.MethodPut, "/api/v1/system/config",
		bytes.NewBufferString(`{"max_concurrent":9}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Trim-Userid", "evil")
	req.Header.Set("X-Trim-Username", "evil")
	req.Header.Set("X-Trim-Isadmin", "1")

	rec := httptest.NewRecorder()
	a.Handler(false).ServeHTTP(rec, req)

	if rec.Code == http.StatusOK {
		t.Fatal("TCP 入口伪造 X-Trim 头拿到了管理员权限——安全边界被破坏")
	}
}

var _ = auth.HashToken // 保持 auth 导入，后续用例会用到
