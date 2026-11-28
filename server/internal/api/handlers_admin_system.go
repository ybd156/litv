package api

// 管理系统设置：健康检查、系统信息与日志、配置读写、探活（探测/失效台）。

import (
	"context"
	"fmt"
	"net/http"
	"runtime"
	"strconv"
	"strings"
	"time"

	"github.com/litv/litv/server/internal/config"
	"github.com/litv/litv/server/internal/probe"
)

// health 是公开探活。除了版本与运行时长，还报告这个实例的入口形态（endpoint）：
// 前端据此决定该说「请从飞牛桌面打开」还是「用管理密码登录」。
// 之所以由服务端说，是因为前端只能看到静态资源挂在哪个前缀——自建 Docker 挂在根路径上
// 和 Cloudflare Worker 长得一模一样，以前靠 BASE_URL==='/' 推断，把 1Panel 部署误判成边缘部署。
func (a *API) health(w http.ResponseWriter, r *http.Request) {
	ok(w, map[string]any{
		"version":  a.version,
		"uptime":   int(time.Since(a.startAt).Seconds()),
		"endpoint": a.endpointKind(),
	})
}

// endpointKind 报告这个实例的入口形态。
//
// edge（Cloudflare Worker）不跑本进程，所以这里只会返回 gateway 或 direct；
// 前端拿不到这个字段时自己回退到编译期推断。
func (a *API) endpointKind() string {
	if a.conf().Server.GatewaySocket != "" {
		return "gateway"
	}
	return "direct"
}

func (a *API) systemMe(w http.ResponseWriter, r *http.Request) {
	id, _ := identityFrom(r.Context())
	ok(w, map[string]any{
		"uid":         id.UserID, // 前端契约字段名
		"user_id":     id.UserID, // 兼容别名
		"username":    id.Username,
		"is_admin":    id.IsAdmin,
		"via_gateway": id.ViaGateway,
	})
}

func (a *API) systemInfo(w http.ResponseWriter, r *http.Request) {
	// 概览页需要的聚合数字一并返回，避免前端打 N 个接口
	st, _ := a.st.Stats(r.Context())
	data := map[string]any{
		"version":  a.version,
		"uptime_s": int(time.Since(a.startAt).Seconds()),
		// 管理台读的是 uptime_seconds，这里同时给两个名字，省得再对一次契约
		"uptime_seconds":  int(time.Since(a.startAt).Seconds()),
		"active_channels": a.hub.ChannelCount(),
		"active_streams":  a.hub.Count(),
		"max_concurrent":  a.conf().Stream.MaxConcurrent,
		"arch":            runtime.GOARCH,
		"port":            a.conf().Server.Port,
		"gateway_socket":  a.conf().Server.GatewaySocket,
		"prefix":          a.conf().Server.Prefix,
		"transcode":       a.conf().Transcode.Enabled,
		"db_path":         a.conf().Database.Path,
	}
	// 实际端口可能和配置不同（被占用时自动顺延），TV 要填的是这个
	if a.actualPort > 0 {
		data["actual_port"] = a.actualPort
		data["port_changed"] = a.actualPort != a.conf().Server.Port
	}
	// ffmpeg 的真实解析结果（配置里可能只写了个名字）
	data["ffmpeg"] = a.ffmpegPath
	data["remux_available"] = a.ffmpegPath != ""
	if st != nil {
		data["channels_total"] = st.ChannelsTotal
		data["channels_enabled"] = st.ChannelsOn
		data["devices_total"] = st.DevicesTotal
		data["devices_online"] = st.DevicesOnline
		data["devices_pending"] = st.DevicesPending
		data["sources_total"] = st.SourcesTotal
		data["sources_failed"] = st.SourcesFailed
		data["plays_today"] = st.PlaysToday
	}
	ok(w, data)
}

func (a *API) systemLogs(w http.ResponseWriter, r *http.Request) {
	// M1 阶段：日志走 stdout，由 cmd/main 重定向到文件；
	// 这里返回最近的内存快照位置说明，后续接入环形缓冲。
	ok(w, map[string]any{
		"hint":  "日志文件位于 $TRIM_PKGVAR/litv.log",
		"lines": []string{},
	})
}

func (a *API) getConfig(w http.ResponseWriter, r *http.Request) {
	// 只暴露可安全展示的字段，绝不返回 secret / 密码
	ok(w, map[string]any{
		"port":              a.conf().Server.Port,
		"prefix":            a.conf().Server.Prefix,
		"max_concurrent":    a.conf().Stream.MaxConcurrent,
		"ring_size":         a.conf().Stream.RingSize,
		"transcode":         a.conf().Transcode.Enabled,
		"transcode_enabled": a.conf().Transcode.Enabled, // 前端字段名，与 transcode 同义
		"hwaccel":           a.conf().Transcode.HWAccel,
		"epg_enabled":       a.conf().EPG.Enabled,
		"epg_refresh_hour":  epgRefreshHour(a.conf().EPG.RefreshCron),
		"token_hours":       a.conf().Auth.TokenHours,
		"log_level":         a.conf().Log.Level,
		"allow_lan":         a.conf().Server.Host == "0.0.0.0",
	})
}

// epgRefreshHour 从 cron 里粗取小时（"0 3 * * *" → 3），取不到给默认 3。
func epgRefreshHour(cron string) int {
	fields := strings.Fields(strings.TrimSpace(cron))
	if len(fields) >= 2 {
		if h, err := strconv.Atoi(fields[1]); err == nil && h >= 0 && h <= 23 {
			return h
		}
	}
	return 3
}

func (a *API) putConfig(w http.ResponseWriter, r *http.Request) {
	// 字段必须与管理台 SettingsView 实际提交的 payload 对齐。
	// 之前这里只认 3 个字段，而 decode() 开了 DisallowUnknownFields，
	// 导致页面每次点「保存」都收到 400。
	var body struct {
		Port             *int    `json:"port"`
		MaxConcurrent    *int    `json:"max_concurrent"`
		Transcode        *bool   `json:"transcode"`
		TranscodeEnabled *bool   `json:"transcode_enabled"`
		LogLevel         *string `json:"log_level"`
		EPGRefreshHour   *int    `json:"epg_refresh_hour"`
		AllowLAN         *bool   `json:"allow_lan"`
	}
	if err := decode(r, &body); err != nil {
		fail(w, http.StatusBadRequest, 1001, "参数错误: "+err.Error())
		return
	}

	// 先整体校验，任一字段不合法就整体拒绝——不要出现"保存了一半"的配置。
	if body.Port != nil && (*body.Port < 1 || *body.Port > 65535) {
		fail(w, http.StatusBadRequest, 1001, "端口要在 1~65535 之间")
		return
	}
	if body.MaxConcurrent != nil && (*body.MaxConcurrent < 1 || *body.MaxConcurrent > 1000) {
		fail(w, http.StatusBadRequest, 1001, "最大并发流要在 1~1000 之间")
		return
	}
	if body.EPGRefreshHour != nil && (*body.EPGRefreshHour < 0 || *body.EPGRefreshHour > 23) {
		fail(w, http.StatusBadRequest, 1001, "EPG 刷新时间要在 0~23 点之间")
		return
	}
	if body.LogLevel != nil && config.NormalizeLogLevel(*body.LogLevel) == "" {
		fail(w, http.StatusBadRequest, 1001, "日志级别只能是 debug/info/warn/error")
		return
	}

	// 串行化读改写：两个并发 PUT 交错会互相覆盖。
	a.cfgMu.Lock()
	defer a.cfgMu.Unlock()

	// 一次 Load 出快照，后面所有"改前值"判断与 Clone 都用它。
	// 分成多次 conf() 会读到两次发布之间的值，导致「哪些字段需要重启」判断错位
	// （old 来自旧配置、next 克隆自新配置）。
	cur := a.conf()

	// 记录改前的值，用于判断哪些需要重启才生效。
	oldPort := cur.Server.Port
	oldHost := cur.Server.Host
	oldEPGCron := cur.EPG.RefreshCron

	// ★ 在**副本**上改。
	//
	// 以前直接改 a.cfg（live 对象）再 Save：落盘失败时内存里已经是新值、
	// 日志级别也已经生效，而磁盘还是旧的——重启后出现「设置看起来成功、
	// 实际回滚」的诡异状态，GET 和实际行为还对不上。
	next := cur.Clone()
	if next == nil {
		fail(w, http.StatusInternalServerError, 5001, "配置复制失败，未做任何修改")
		return
	}

	if body.MaxConcurrent != nil {
		next.Stream.MaxConcurrent = *body.MaxConcurrent
	}
	// 两个字段名同义，前端用 transcode_enabled，老客户端用 transcode
	if body.TranscodeEnabled != nil {
		next.Transcode.Enabled = *body.TranscodeEnabled
	} else if body.Transcode != nil {
		next.Transcode.Enabled = *body.Transcode
	}
	if body.Port != nil {
		next.Server.Port = *body.Port
	}
	if body.AllowLAN != nil {
		if *body.AllowLAN {
			next.Server.Host = "0.0.0.0"
		} else {
			next.Server.Host = "127.0.0.1"
		}
	}
	if body.EPGRefreshHour != nil {
		// cron 是 "分 时 日 月 周"；管理台只给一个整点，这里固定为整点触发。
		next.EPG.RefreshCron = fmt.Sprintf("0 %d * * *", *body.EPGRefreshHour)
	}
	if body.LogLevel != nil {
		lv := config.NormalizeLogLevel(*body.LogLevel)
		next.Log.Level = lv
	}

	// 校验通过的配置要真正落盘，否则重启后全部丢失（这是之前的另一个缺陷）。
	if err := next.Save(a.cfgPath); err != nil {
		// 落盘失败：live 配置一个字都没动，内存与磁盘保持一致。
		fail(w, http.StatusInternalServerError, 5001, "保存配置失败: "+err.Error())
		return
	}

	// 落盘成功才发布：先换 live 快照，再让日志级别生效。
	// 顺序不能反——反了就会出现「日志已变、配置没存」的中间态。
	// atomic 换指针保证读侧要么看到旧快照、要么看到新快照，不存在中间态。
	a.cfgPtr.Store(next)
	if body.LogLevel != nil {
		config.SetLogLevel(next.Log.Level)
	}

	// 明确告诉前端哪些字段需要重启，不要用"保存成功"含糊带过。
	restartNeeded := []string{}
	if body.Port != nil && *body.Port != oldPort {
		restartNeeded = append(restartNeeded, "port")
	}
	if body.AllowLAN != nil && a.conf().Server.Host != oldHost {
		restartNeeded = append(restartNeeded, "allow_lan")
	}
	if body.EPGRefreshHour != nil && a.conf().EPG.RefreshCron != oldEPGCron {
		restartNeeded = append(restartNeeded, "epg_refresh_hour")
	}

	a.st.Audit(r.Context(), 0, "config.update", "system", strings.Join(restartNeeded, ","), peerIPFrom(r.Context()))
	ok(w, map[string]any{
		"saved":          true,
		"restart_needed": restartNeeded,
		"note":           "已写入配置文件；端口与局域网访问需重启服务后生效",
	})
}

func (a *API) activeStreams(w http.ResponseWriter, r *http.Request) {
	ok(w, a.hub.Stats())
}

// runProbe 手动触发一轮源探测（管理台「立即检测所有源」按钮）。
// 探测是并发 + 只读前 128KB，不会把带宽打满，但也不要频繁点。
func (a *API) runProbe(w http.ResponseWriter, r *http.Request) {
	pr := probe.New(a.st,
		time.Duration(a.conf().Probe.TimeoutSec)*time.Second,
		a.conf().Probe.Concurrency,
		a.conf().Stream.UserAgent)
	res, err := pr.RunOnce(r.Context())
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	a.st.Audit(r.Context(), 0, "probe.run", "streams",
		formatErr("检测 %d 条，可用 %d，失效 %d", res.Checked, res.Healthy, res.Failed), peerIPFrom(r.Context()))

	// 手动探测也走一遍告警判定，方便用户立刻验证 webhook 配得对不对
	a.checkDeadChannels(r.Context(), res)

	ok(w, res)
}

// deadChannels 返回"所有线路都挂了"的频道，管理台可以直接列出。
func (a *API) deadChannels(w http.ResponseWriter, r *http.Request) {
	items, err := a.st.ChannelsWithNoHealthyStream(r.Context())
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	ok(w, map[string]any{
		"count":         len(items),
		"items":         items,
		"alert_enabled": a.alert != nil && a.alert.Enabled(),
	})
}

// checkDeadChannels 判定并发送告警。去重由 Notifier 内部按 key 做。
func (a *API) checkDeadChannels(ctx context.Context, res *probe.Result) {
	if a.alert == nil || !a.alert.Enabled() {
		return
	}
	dead, err := a.st.ChannelsWithNoHealthyStream(ctx)
	if err != nil || len(dead) == 0 {
		return
	}
	var sb strings.Builder
	for i, d := range dead {
		if i >= 10 {
			sb.WriteString(formatErr("\n…还有 %d 个", len(dead)-10))
			break
		}
		sb.WriteString(formatErr("\n· %s（%s）%d 条线路全失效", d.Name, d.Group, d.Total))
	}
	body := sb.String()
	if res != nil {
		body = formatErr("本轮探测 %d 条线路，可用 %d，失效 %d。%s",
			res.Checked, res.Healthy, res.Failed, sb.String())
	}
	_ = a.alert.Send("dead-channels",
		formatErr("LITV：%d 个频道没有可用线路", len(dead)), body)
}
