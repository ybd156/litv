package api

// 播放质量上报：起播耗时/卡顿/观看时长上报，数值边界都收在这里。

import (
	"net/http"

	"github.com/litv/litv/server/internal/store"
)

// tvReportStats 终端上报播放事件（起播/卡顿/失败）。
//
// 为什么要客户端上报而不是服务端自己算：
// 服务端只知道"有没有把字节发出去"，不知道"用户看到第一帧用了多久"、
// "播放器有没有卡"。起播耗时和卡顿只有客户端能测准。
func (a *API) tvReportStats(w http.ResponseWriter, r *http.Request) {
	dev, okDev := deviceFrom(r.Context())
	if !okDev {
		fail(w, http.StatusUnauthorized, 2003, "设备未绑定")
		return
	}
	// 用 decodeLenient（不拒绝未知字段），只有这个端点这样做。
	//
	// 原因：decode 全局开了 DisallowUnknownFields，于是"新 APK + 旧 NAS"
	// 这种升级顺序下，客户端多带一个新字段就会收到 400，
	// 整条 QoE 上报链路静默失效——而这是唯一能证明"卡顿有没有修好"的数据源。
	// 遥测是"有总比没有好"，不能因为一个不认识的字段全丢。
	var body struct {
		ChannelID int64  `json:"channel_id"`
		Event     string `json:"event"`
		StartupMs int    `json:"startup_ms"`
		Detail    string `json:"detail"`
		StallMs   int    `json:"stall_ms"`
		WatchMs   int    `json:"watch_ms"`
		Path      string `json:"path"`
		Engine    string `json:"engine"`
	}
	if err := decodeLenient(r, &body); err != nil {
		fail(w, http.StatusBadRequest, 1001, "参数错误: "+err.Error())
		return
	}
	switch body.Event {
	case "start", "stall", "error", "stop":
	default:
		fail(w, http.StatusBadRequest, 1001, "event 只能是 start/stall/error/stop")
		return
	}

	// 按设备限流：这个端点以前完全没有上限，一台已绑定设备可以反复写
	// 接近 1MB 的行，把 play_events 撑爆（也把 NAS 的盘写满）。
	if !a.statsLimiter.allow(dev.ID, a.now()) {
		w.Header().Set("Retry-After", "30")
		fail(w, http.StatusTooManyRequests, 2005, "上报过于频繁")
		return
	}

	// 入参边界（A3-2）。
	//
	// 选择 clamp 而不是 400：这是遥测，丢了这一条就少一个数据点；
	// 而"收到一个负数"多半是客户端算错了，夹到合法区间仍然有统计价值。
	// 唯一坚持 400 的是未知 event（上面），因为没有 event 的行没法归类。
	ev := store.PlayEvent{
		DeviceID:  dev.ID,
		ChannelID: body.ChannelID,
		Event:     body.Event,
		Detail:    truncateBytes(body.Detail, maxStatDetailBytes),
		Path:      truncateBytes(body.Path, maxStatTagBytes),
		Engine:    truncateBytes(body.Engine, maxStatTagBytes),
	}
	if ev.ChannelID < 0 {
		ev.ChannelID = 0
	}
	var clamped bool
	ev.StartupMs, clamped = clampInt(body.StartupMs, 0, maxStartupMs)
	_ = clamped
	ev.StallMs, _ = clampInt(body.StallMs, 0, maxDurationMs)
	ev.WatchMs, _ = clampInt(body.WatchMs, 0, maxDurationMs)

	// 上报失败不影响播放，所以这里不把错误往上抛
	_ = a.st.AddPlayEvent(r.Context(), ev)
	ok(w, map[string]any{"recorded": true})
}

// 上报字段的边界。数值单位都是毫秒。
const (
	// 单条 detail 上限。1MB 的行会让 play_events 迅速膨胀，
	// 而 detail 只是"给排查看的补充说明"，512 字节够写清原因。
	maxStatDetailBytes = 512
	// path / engine 是自由文本（真实值 exo|mpv|vlc|avs3），只做长度截断。
	maxStatTagBytes = 32
	// 起播耗时上限 10 分钟：超过这个数的值一定是客户端算错了（时钟/单位）。
	maxStartupMs = 600_000
	// 单次卡顿/观看时长上限 24 小时。
	maxDurationMs = 86_400_000
)
