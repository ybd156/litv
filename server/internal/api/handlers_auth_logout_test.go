package api

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/litv/litv/server/internal/auth"
	"github.com/litv/litv/server/internal/store"
)

// TestLogoutRevokesAdminSession 是「logout 是个空壳」的回归测试。
//
// 修复前 handler 存在但路由没挂，前端点退出只会清本地 token，
// 服务端会话还能用满整个 TTL（默认 7 天）——旧 token 泄露即等于长期有效。
func TestLogoutRevokesAdminSession(t *testing.T) {
	a, _ := newTestAPI(t)
	ctx := context.Background()

	plain, hash, err := auth.NewToken()
	if err != nil {
		t.Fatalf("生成 token 失败: %v", err)
	}
	if _, err := a.st.CreateAdminSession(ctx, hash, time.Now().Unix(), time.Hour, 8); err != nil {
		t.Fatalf("建会话失败: %v", err)
	}

	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/logout", nil)
	req.Header.Set("Authorization", "Bearer "+plain)
	rec := doRaw(a, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("注销返回 %d：%s", rec.Code, rec.Body.String())
	}

	sess, err := a.st.GetAdminSessionByToken(ctx, hash, time.Now().Unix())
	if err != nil && !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("查询会话失败: %v", err)
	}
	if sess != nil {
		t.Fatal("注销后会话仍然有效")
	}
}

// TestLogoutAllRevokesEverySession 覆盖「撤销全部会话」。
// 它是「token 泄露后怎么办」的唯一止血手段，必须挂在管理员路由下。
func TestLogoutAllRevokesEverySession(t *testing.T) {
	a, _ := newTestAPI(t)
	ctx := context.Background()

	_, hash, err := auth.NewToken()
	if err != nil {
		t.Fatalf("生成 token 失败: %v", err)
	}
	if _, err := a.st.CreateAdminSession(ctx, hash, time.Now().Unix(), time.Hour, 8); err != nil {
		t.Fatalf("建会话失败: %v", err)
	}

	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/logout-all", nil)
	req.Header.Set(auth.HeaderUserID, "42")
	req.Header.Set(auth.HeaderUsername, "admin")
	req.Header.Set(auth.HeaderIsAdmin, "true")
	rec := httptest.NewRecorder()
	a.Handler(true).ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("撤销全部会话返回 %d：%s", rec.Code, rec.Body.String())
	}

	sess, _ := a.st.GetAdminSessionByToken(ctx, hash, time.Now().Unix())
	if sess != nil {
		t.Fatal("撤销全部后仍有会话有效")
	}
}
