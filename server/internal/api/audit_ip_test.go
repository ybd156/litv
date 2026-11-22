package api

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/litv/litv/server/internal/auth"
)

// TestAuditIPIgnoresForwardedFor 固定一条反抵赖前提：
// 客户端带 X-Forwarded-For 不能改变审计里记的"谁干的"。
//
// 旧实现挂了 middleware.RealIP：它按 XFF 改写 r.RemoteAddr，**无视 trust_proxy**，
// 于是 17 处审计（当时直接传 r.RemoteAddr）全都记成了伪造的地址 ——
// 任何人带一个头就能把操作记到别人头上。
//
// 网关入口的 peerIPFrom 恒为 "gateway"（Unix socket 没有客户端 IP），
// 所以这里断言的期望值是 "gateway"：既证明没用 XFF，也证明走的是 ctx 那条路。
func TestAuditIPIgnoresForwardedFor(t *testing.T) {
	a, _ := newTestAPI(t)

	req := httptest.NewRequest(http.MethodPut, "/api/v1/system/config",
		bytes.NewReader([]byte(`{"log_level":"info"}`)))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set(auth.HeaderUserID, "42")
	req.Header.Set(auth.HeaderUsername, "admin")
	req.Header.Set(auth.HeaderIsAdmin, "true")
	// 伪造来源
	req.Header.Set("X-Forwarded-For", "203.0.113.9")
	req.Header.Set("X-Real-IP", "203.0.113.10")

	rec := httptest.NewRecorder()
	a.Handler(true).ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("PUT /system/config = %d，期望 200（这条用例需要一个会写审计的成功路径）", rec.Code)
	}

	var ip string
	if err := a.st.DB().QueryRow(`SELECT ip FROM audit_logs ORDER BY id DESC LIMIT 1`).Scan(&ip); err != nil {
		t.Fatalf("读 audit_logs 失败: %v", err)
	}
	if ip == "203.0.113.9" || ip == "203.0.113.10" {
		t.Fatalf("审计记录了伪造的转发头地址 %q —— X-Forwarded-For 不该被采信", ip)
	}
	if ip != "gateway" {
		t.Fatalf("审计 ip = %q，期望 \"gateway\"（网关入口的 socket 对端标识）", ip)
	}
}
