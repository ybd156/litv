package api

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"strconv"
	"sync"
	"time"

	"github.com/litv/litv/server/internal/auth"
	"github.com/litv/litv/server/internal/config"
	"github.com/litv/litv/server/internal/store"
)

const (
	loginBodyBytes   = 4096
	loginLimitedCode = 2004
)

// Capture before any middleware can rewrite RemoteAddr. TCP requests never
// trust X-Forwarded-For/X-Real-IP/True-Client-IP. The Unix gateway has no client
// socket IP, so all gateway emergency logins share one bucket (not a header).
func (a *API) peerIPMiddleware(viaGateway bool) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			ip := auth.PeerIP(r.RemoteAddr)
			if viaGateway {
				ip = "gateway"
			}
			ctx := context.WithValue(r.Context(), ctxPeerIP, ip)
			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}

func peerIPFrom(ctx context.Context) string {
	ip, _ := ctx.Value(ctxPeerIP).(string)
	if ip == "" {
		return "unknown"
	}
	return ip
}

func adminSessionFrom(ctx context.Context) (*store.AdminSession, bool) {
	session, ok := ctx.Value(ctxAdminSession).(*store.AdminSession)
	return session, ok && session != nil
}

// Successful/failed admitted attempts are already bounded at 30/min globally;
// expiry/logout events remove their source row once. Only unlimited rejection
// traffic needs coalescing: one event per fixed reason/minute, across ALL IPs.
// No attacker-controlled key can grow this map or flood the database/log sink.
type authAuditGate struct {
	mu        sync.Mutex
	limitedAt map[store.AuthAuditReason]time.Time
}

func (g *authAuditGate) allow(action store.AuthAuditAction, reason store.AuthAuditReason, now time.Time) bool {
	if action != store.AuthLoginLimited {
		return true
	}
	switch reason {
	case store.AuditIPWindow, store.AuditIPLockout, store.AuditGlobalWindow, store.AuditCapacity:
	default:
		return false
	}
	g.mu.Lock()
	defer g.mu.Unlock()
	if last, ok := g.limitedAt[reason]; ok && now.Before(last.Add(time.Minute)) {
		return false
	}
	if g.limitedAt == nil {
		g.limitedAt = make(map[store.AuthAuditReason]time.Time)
	}
	g.limitedAt[reason] = now
	return true
}

func (a *API) auditAuth(ctx context.Context, action store.AuthAuditAction, reason store.AuthAuditReason, sessionID int64) {
	if !a.authAudit.allow(action, reason, a.now()) {
		return
	}
	ip := peerIPFrom(ctx)
	slog.Info("authentication", "action", string(action), "reason", string(reason), "session_id", sessionID, "peer_ip", ip)
	if err := a.st.AuditAuth(ctx, action, reason, sessionID, ip); err != nil {
		// Database/decoder errors are not serialized: they may contain secrets.
		slog.Warn("authentication audit unavailable", "action", string(action))
	}
}

func (a *API) passwordLogin(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	attempt, retry, reason := a.loginLimiter.Begin(peerIPFrom(r.Context()), a.now())
	if retry > 0 {
		_ = r.Body.Close()
		a.auditAuth(r.Context(), store.AuthLoginLimited, store.AuthAuditReason(reason), 0)
		seconds := int64((retry + time.Second - 1) / time.Second)
		if seconds < 1 {
			seconds = 1
		}
		w.Header().Set("Retry-After", strconv.FormatInt(seconds, 10))
		writeJSON(w, http.StatusTooManyRequests, resp{Code: loginLimitedCode, Msg: "登录尝试过于频繁，请稍后重试", Data: map[string]any{"retry_after": seconds}})
		return
	}
	success, credentialFailure := false, true
	defer func() { attempt.Finish(success, credentialFailure, a.now()) }()

	password, err := decodeLoginPassword(r)
	if err != nil {
		a.auditAuth(r.Context(), store.AuthLoginFailure, store.AuditInvalidRequest, 0)
		fail(w, http.StatusBadRequest, 1001, "参数错误：password 最多 256 bytes")
		return
	}
	if !auth.PasswordMatches(password, a.cfg.Auth.AdminPassword) {
		reason := store.AuditCredentials
		if a.cfg.Auth.AdminPassword == "" {
			reason = store.AuditDisabled
		}
		a.auditAuth(r.Context(), store.AuthLoginFailure, reason, 0)
		fail(w, http.StatusUnauthorized, 2001, "密码错误")
		return
	}
	success, credentialFailure = true, false
	plain, hash, err := auth.NewToken()
	if err != nil {
		a.auditAuth(r.Context(), store.AuthLoginFailure, store.AuditStorageError, 0)
		fail(w, http.StatusInternalServerError, 5001, "管理员登录暂不可用")
		return
	}
	hours, cap := a.cfg.Auth.AdminTokenHours, a.cfg.Auth.AdminMaxSessions
	// Default also protects embedders that construct Config without Load.
	// Explicit out-of-range values fail closed in Store, never create long TTLs.
	if hours == 0 {
		hours = config.DefaultAdminTokenHours
	}
	if cap == 0 {
		cap = config.DefaultAdminMaxSessions
	}
	if hours < 1 || hours > config.MaxAdminTokenHours {
		a.auditAuth(r.Context(), store.AuthLoginFailure, store.AuditStorageError, 0)
		fail(w, http.StatusInternalServerError, 5001, "管理员登录暂不可用")
		return
	}
	session, err := a.st.CreateAdminSession(r.Context(), hash, a.now().Unix(), time.Duration(hours)*time.Hour, cap)
	if err != nil {
		a.auditAuth(r.Context(), store.AuthLoginFailure, store.AuditStorageError, 0)
		fail(w, http.StatusInternalServerError, 5001, "管理员登录暂不可用")
		return
	}
	a.auditAuth(r.Context(), store.AuthLoginSuccess, store.AuditCredentials, session.ID)
	ok(w, map[string]any{"token": plain, "expires_at": session.ExpiresAt})
}

func decodeLoginPassword(r *http.Request) (string, error) {
	defer r.Body.Close()
	body, err := io.ReadAll(io.LimitReader(r.Body, loginBodyBytes+1))
	if err != nil || len(body) > loginBodyBytes {
		return "", errors.New("invalid login body")
	}
	var input struct {
		Password string `json:"password"`
	}
	dec := json.NewDecoder(bytes.NewReader(body))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&input); err != nil {
		return "", errors.New("invalid login body")
	}
	var extra any
	if err := dec.Decode(&extra); err != io.EOF {
		return "", errors.New("invalid login body")
	}
	if len(input.Password) > auth.MaxPasswordBytes {
		return "", errors.New("invalid password length")
	}
	return input.Password, nil
}

// Logout revokes the presented emergency session only. Gateway/device identities
// cannot accidentally revoke an unrelated session. A replay returns normal 401.
func (a *API) passwordLogout(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	session, okSession := adminSessionFrom(r.Context())
	if !okSession {
		fail(w, http.StatusUnauthorized, 2001, "未认证：需要管理员会话")
		return
	}
	revoked, err := a.st.RevokeAdminSession(r.Context(), session.ID)
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, "注销暂不可用")
		return
	}
	if revoked {
		a.auditAuth(r.Context(), store.AuthSessionRevoked, store.AuditLogout, session.ID)
	}
	ok(w, map[string]any{"revoked": true})
}

// Revoke-all requires an administrator via the normal admin middleware. It
// revokes emergency sessions (including the caller's), not gateway or devices.
func (a *API) revokeAdminSessions(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	n, err := a.st.RevokeAllAdminSessions(r.Context())
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, "撤销管理员会话暂不可用")
		return
	}
	if n > 0 {
		a.auditAuth(r.Context(), store.AuthSessionRevoked, store.AuditRevoke, 0)
	}
	ok(w, map[string]any{"revoked": n})
}
