package api

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/litv/litv/server/internal/auth"
)

func nonAdminRequest(t *testing.T, a *API, method, path string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(method, path, nil)
	req.Header.Set(auth.HeaderUserID, "43")
	req.Header.Set(auth.HeaderUsername, "family")
	req.Header.Set(auth.HeaderIsAdmin, "false")

	rec := httptest.NewRecorder()
	a.Handler(true).ServeHTTP(rec, req)
	return rec
}

// TestNonAdminCannotReadSensitiveAdminEndpoints 是提权修复的回归测试。
//
// 修复前 requireAdmin 的规则是「非管理员允许 GET 一切」，于是同一台 NAS 上
// 的普通 fnOS 用户能直接读到：源地址（通常带账号密码）、系统日志与配置、
// 设备列表、录制文件，以及 /web/play 签发的播放地址。
func TestNonAdminCannotReadSensitiveAdminEndpoints(t *testing.T) {
	a, _ := newTestAPI(t)

	paths := []string{
		"/api/v1/sources",
		"/api/v1/system/logs",
		"/api/v1/system/config",
		"/api/v1/system/streams",
		"/api/v1/devices",
		"/api/v1/web/play/1",
		"/api/v1/recordings",
	}
	for _, path := range paths {
		rec := nonAdminRequest(t, a, http.MethodGet, path)
		if rec.Code != http.StatusForbidden {
			t.Errorf("%s 对非管理员返回 %d，期望 403", path, rec.Code)
		}
	}
}

// TestNonAdminCanReadOwnIdentity：只放行「我是谁」。
// 否则管理台连"你不是管理员"都判断不出来，只能显示空白页。
func TestNonAdminCanReadOwnIdentity(t *testing.T) {
	a, _ := newTestAPI(t)

	rec := nonAdminRequest(t, a, http.MethodGet, "/api/v1/system/me")
	if rec.Code != http.StatusOK {
		t.Fatalf("/system/me 对非管理员返回 %d，期望 200", rec.Code)
	}
}

// TestAdminCanStillReadEverything 确认收紧没有误伤管理员。
func TestAdminCanStillReadEverything(t *testing.T) {
	a, _ := newTestAPI(t)

	rec := adminRequest(t, a, http.MethodGet, "/api/v1/sources", nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("管理员读 /sources 返回 %d，期望 200", rec.Code)
	}
}
