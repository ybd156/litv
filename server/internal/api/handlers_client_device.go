package api

// 终端注册与心跳：电视/手机首次注册换 token、注册结果分类（限流账）、心跳。

import (
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/litv/litv/server/internal/auth"
	"github.com/litv/litv/server/internal/store"
)

// deviceRegister 终端首次注册：客户端带设备唯一标识来换 token，此时状态是 pending，
// 需要在管理台批准后才能使用。这就是「绑定码」流程的服务端一半。
// 设备注册的结果分类，只用于限流记账。
const (
	registerOK    = iota // 成功：清零失败streak
	registerBad          // 凭据/参数被拒：计入失败
	registerInfra        // 服务端内部错误：两个标志都不置（不能把宕库算成攻击）
)

func (a *API) deviceRegister(w http.ResponseWriter, r *http.Request) {
	// 注册端点也有暴力面：device_key 可以猜，通用邀请码只有 6 位。
	//
	// 限流放在**解码之前**：解析请求体同样要花 CPU，畸形请求本身就是攻击流量。
	//
	// 用独立的 deviceLimiter，不与 loginLimiter 共用桶——共用等于让攻击者
	// 用注册请求把全局额度吃光，顺带把"网关挂掉时唯一的后路"（应急密码登录）
	// 一起饿死。
	peer := peerIPFrom(r.Context())
	att, retry, _ := a.deviceLimiter.Begin(peer, a.now())
	if att == nil {
		secs := int(retry.Seconds())
		if secs < 1 {
			secs = 1
		}
		// Retry-After 让客户端知道该等多久，而不是自己瞎重试把窗口越刷越长。
		w.Header().Set("Retry-After", strconv.Itoa(secs))
		fail(w, http.StatusTooManyRequests, 2005, "注册请求过于频繁，请稍后再试")
		return
	}
	outcome := registerInfra
	defer func() {
		// Finish 在 *LoginAttempt 上（幂等），放在 defer 里保证每条返回路径都会记账。
		// 只有"凭证类失败"才计入失败计数：基础设施错误不该让人被锁。
		att.Finish(outcome == registerOK, outcome == registerBad, a.now())
	}()

	var body struct {
		DeviceKey string `json:"device_key"`
		Name      string `json:"name"`
		Platform  string `json:"platform"`
		Model     string `json:"model"`
		Code      string `json:"code"` // 可选：直接带绑定码则跳过审批
		// AppVersion 客户端版本号，仅用于管理台展示"装的是哪版"。
		AppVersion string `json:"app_version"`
	}
	if err := decode(r, &body); err != nil {
		outcome = registerBad
		fail(w, http.StatusBadRequest, 1001, "参数错误: "+err.Error())
		return
	}
	if body.DeviceKey == "" {
		outcome = registerBad
		fail(w, http.StatusBadRequest, 1001, "device_key 不能为空")
		return
	}
	body.Platform = strings.ToLower(strings.TrimSpace(body.Platform))
	if store.IsReservedDevicePlatform(body.Platform) {
		outcome = registerBad
		fail(w, http.StatusBadRequest, 1001, "platform 为保留值")
		return
	}
	if body.Platform == "" {
		body.Platform = "android"
	}

	// 审计用的指纹：device_key 的 sha256 前 12 位十六进制。
	//
	// 绝不写明文：device_key 是 ANDROID_ID 之类的稳定标识派生出来的，
	// 写进 audit_logs 就等于把"设备的持久身份"存进一张会展示、会导出的表。
	// 12 位（48 bit）足够在家庭规模下区分设备，又不构成可复用的标识符。
	fp := auth.HashToken(body.DeviceKey)[:12]
	audit := func(action, detail string) {
		a.st.Audit(r.Context(), 0, action, fp, detail, peerIPFrom(r.Context()))
	}

	plain, hash, err := auth.NewToken()
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}

	// 设备注册 + 绑定码消费必须在同一个事务里完成。
	// 以前是"先 INSERT 设备、再单独消费码、最后批准"，中间任何一步失败
	// 都会留下半成品（设备在、码被吃、状态还是 pending）。
	// 传 TokenHours：换发 token 时要按同一份 TTL 建立新会话有效期，
	// 否则过期设备重注册拿到的凭据会被 GetDeviceByToken 立刻判成已过期。
	id, status, err := a.st.RegisterWithBindCode(
		r.Context(), body.DeviceKey, hash, body.Name, body.Platform, body.Model, body.Code,
		a.conf().Auth.TokenHours)
	if err != nil {
		switch {
		case errors.Is(err, store.ErrReservedDevicePlatform):
			outcome = registerBad
			audit("device.register.reject", "platform 为保留值")
			fail(w, http.StatusBadRequest, 1001, "platform 为保留值")
		case errors.Is(err, store.ErrDeviceKeyConflict):
			// 这条必须留痕：它正是"有人拿着别人的 device_key 来顶设备"的信号。
			outcome = registerBad
			audit("device.register.conflict", "device_key 已属于其它平台")
			fail(w, http.StatusConflict, 1002, "该设备标识已被其他平台占用，请重新安装或联系管理员清理旧设备")
		case errors.Is(err, store.ErrBindCodeNotFound):
			// 绑定码错误同样计入失败：否则 6 位通用邀请码的暴力面一点没变。
			outcome = registerBad
			audit("device.register.badcode", "绑定码不存在")
			fail(w, http.StatusBadRequest, 2004, "绑定码不存在")
		case errors.Is(err, store.ErrBindCodeUsed):
			outcome = registerBad
			audit("device.register.badcode", "绑定码已被使用")
			fail(w, http.StatusBadRequest, 2004, "绑定码已被使用")
		case errors.Is(err, store.ErrBindCodeExpired):
			outcome = registerBad
			audit("device.register.badcode", "绑定码已过期")
			fail(w, http.StatusBadRequest, 2004, "绑定码已过期")
		case errors.Is(err, store.ErrBindCodeMismatch):
			outcome = registerBad
			audit("device.register.badcode", "绑定码不属于这台设备")
			fail(w, http.StatusBadRequest, 2004, "绑定码不属于这台设备")
		default:
			// 已存在同一 device_key（UNIQUE 冲突）：说明是重装/重启。
			// 事务已回滚，设备表保持原样，让用户去管理台重新批准或换绑定码。
			fail(w, http.StatusConflict, 2003, "该设备已注册，请在管理台重新批准或使用绑定码")
		}
		return
	}
	outcome = registerOK
	audit("device.register.ok", "status="+status)

	// 记录来源 IP 与版本号（只用于展示，不参与鉴权）。
	_ = a.st.UpdateDeviceClientInfo(r.Context(), id, clientIP(r, a.conf()), body.AppVersion)

	ok(w, map[string]any{
		"device_id": id,
		"token":     plain,
		"status":    status,
		"hint":      hintFor(status),
	})
}

func hintFor(status string) string {
	if status == "bound" {
		return "已绑定，可以直接使用"
	}
	return "请在管理台「终端设备」页批准这台设备，或输入 6 位绑定码"
}

func (a *API) tvHeartbeat(w http.ResponseWriter, r *http.Request) {
	dev, _ := deviceFrom(r.Context())
	var body struct {
		Playing int64  `json:"playing_channel"`
		State   string `json:"state"`
		// 客户端上次看到的版本：用来判断"我是不是该重新拉配置了"
		ConfigVersion int64 `json:"config_version"`
		// AppVersion 客户端版本号（仅展示用）
		AppVersion string `json:"app_version"`
	}
	_ = decode(r, &body)
	_ = a.st.TouchDeviceWithInfo(r.Context(), dev.ID, clientIP(r, a.conf()), body.AppVersion)
	// 频道列表指纹：客户端据此判断"要不要重新拉一次列表"。
	// 没有它，管理员在服务端启用/新增频道后，电视端永远看不到变化
	// （客户端只在打开播放页时 bootstrap 一次）。
	// 算不出来不影响心跳本身，返回空串让客户端跳过判断即可。
	chVer, _ := a.st.ChannelsVersion(r.Context())

	ok(w, map[string]any{
		// R17：用独立的 config_version，不再用 bound_at。
		// bound_at 是绑定时间，管理台改配置时它不变，
		// 终端据此比对永远看不出"配置变了"，改了参数等于没改。
		"config_version":   dev.ConfigVersion,
		"channels_version": chVer,
		"server_time":      time.Now().Unix(),
	})
}
