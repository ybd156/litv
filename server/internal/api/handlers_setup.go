package api

import (
	"context"
	"net/http"
	"strings"
	"time"

	"github.com/litv/litv/server/internal/auth"
	"github.com/litv/litv/server/internal/config"
	"github.com/litv/litv/server/internal/store"
)

// 首次初始化：新部署没有管理密码时，让用户自己在管理台上设一个。
//
// 为什么需要它：以前只有两条路——配置文件里写明文 admin_password，
// 或者安装脚本随机生成一段替你写进去。前者要用户去翻 YAML，后者会
// 把口令打在终端里（还会进 shell 历史）。真正的产品做法是「第一次打开
// 就让我自己设」。
//
// 安全边界：
//   - 只有「数据库里没有哈希」且「配置文件也没给明文」时才允许设置；
//   - 设完立即写库，之后配置里的明文不再能覆盖它（改密码走登录后的接口）；
//   - 接口是未鉴权的，所以只回答一个布尔值和「行不行」，不回显任何配置；
//   - 设置成功直接签发会话，省掉一次登录，也避免"设完发现登不进"。

// setupState 回答「这台实例还要不要初始化」。
func (a *API) setupState(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	required, err := a.setupRequired(r.Context())
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, "初始化状态暂不可用")
		return
	}
	ok(w, map[string]any{"required": required})
}

// setupRequired 是「还允许初始化」的唯一判据。
func (a *API) setupRequired(ctx context.Context) (bool, error) {
	if _, has, err := a.st.KVGet(ctx, store.KVAdminPasswordHash); err != nil {
		return false, err
	} else if has {
		return false, nil
	}
	// 配置文件里给了明文（老部署、容器环境变量）就不需要初始化。
	return strings.TrimSpace(a.conf().Auth.AdminPassword) == "", nil
}

// setupAdmin 设置管理密码并直接登录。
func (a *API) setupAdmin(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	required, err := a.setupRequired(r.Context())
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, "初始化暂不可用")
		return
	}
	if !required {
		// 已经设过了。不区分"配置里有明文"与"库里已有哈希"，
		// 避免把部署细节告诉未鉴权的调用者。
		fail(w, http.StatusConflict, 1004, "已经初始化过了，请直接登录")
		return
	}
	password, err := decodeLoginPassword(r)
	if err != nil {
		fail(w, http.StatusBadRequest, 1001, "参数错误：password 最多 256 bytes")
		return
	}
	if len([]byte(password)) < auth.AdminPasswordMinLen {
		fail(w, http.StatusBadRequest, 1003, "密码至少 8 位")
		return
	}
	hash, err := auth.HashAdminPassword(password)
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, "初始化暂不可用")
		return
	}
	if err := a.st.KVSet(r.Context(), store.KVAdminPasswordHash, hash); err != nil {
		a.auditAuth(r.Context(), store.AuthLoginFailure, store.AuditStorageError, 0)
		fail(w, http.StatusInternalServerError, 5001, "初始化暂不可用")
		return
	}
	plain, session, code, _ := a.issueAdminSession(r.Context())
	if code != 0 {
		// 密码已经写进去了：告诉前端"设好了但会话没建成，去登录"，
		// 而不是让它以为初始化失败、再设一次（第二次会 409）。
		ok(w, map[string]any{"token": "", "expires_at": 0, "need_login": true})
		return
	}
	a.auditAuth(r.Context(), store.AuthLoginSuccess, store.AuditCredentials, session.ID)
	ok(w, map[string]any{"token": plain, "expires_at": session.ExpiresAt})
}

// issueAdminSession 按配置签发一个管理台会话。
//
// 抽出来是因为「初始化」和「口令登录」都必须用同一套 TTL/上限校验——
// 两条路径各写一遍，迟早会有一条忘了夹紧。
func (a *API) issueAdminSession(ctx context.Context) (string, *store.AdminSession, int, string) {
	plain, hash, err := auth.NewToken()
	if err != nil {
		return "", nil, 5001, "管理员登录暂不可用"
	}
	hours, cap := a.conf().Auth.AdminTokenHours, a.conf().Auth.AdminMaxSessions
	// Default 也保护那些不走 Load 直接构造 Config 的调用方。
	// 显式越界值由 Store 失败关闭，绝不产生超长 TTL。
	if hours == 0 {
		hours = config.DefaultAdminTokenHours
	}
	if cap == 0 {
		cap = config.DefaultAdminMaxSessions
	}
	if hours < 1 || hours > config.MaxAdminTokenHours {
		return "", nil, 5001, "管理员登录暂不可用"
	}
	session, err := a.st.CreateAdminSession(ctx, hash, a.now().Unix(), time.Duration(hours)*time.Hour, cap)
	if err != nil {
		return "", nil, 5001, "管理员登录暂不可用"
	}
	return plain, session, 0, ""
}
