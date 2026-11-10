package api

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/gorilla/websocket"

	"github.com/go-chi/chi/v5"

	"github.com/litv/litv/server/internal/auth"
	"github.com/litv/litv/server/internal/fetch"
	"github.com/litv/litv/server/internal/remote"
	"github.com/litv/litv/server/internal/safedial"
	"github.com/litv/litv/server/internal/store"
	"github.com/litv/litv/server/internal/stream"
)

// ---------- 终端注册 ----------

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
		// Finish 是幂等的，放在 defer 里保证每条返回路径都会记账。
		a.deviceLimiter.Finish(att, outcome == registerOK, outcome == registerBad, a.now())
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
	id, status, err := a.st.RegisterWithBindCode(
		r.Context(), body.DeviceKey, hash, body.Name, body.Platform, body.Model, body.Code)
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
	_ = a.st.UpdateDeviceClientInfo(r.Context(), id, clientIP(r, a.cfg), body.AppVersion)

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

// ---------- 终端接口 ----------

func (a *API) tvBootstrap(w http.ResponseWriter, r *http.Request) {
	dev, _ := deviceFrom(r.Context())
	ctx := r.Context()

	groups, _ := a.st.ListGroups(ctx)
	// 不再写死 500：ChannelQuery 的管理台分页上限会把第 501 个之后的台
	// 从电视端首页里悄悄丢掉。
	chans, _ := a.st.ListAllEnabledChannels(ctx)
	favs, _ := a.st.ListFavorites(ctx, dev.ID)

	// 当前节目：一次批量取，避免 TV 端首页 N 次请求。
	// 以前是「每个频道查一次 ProgramsByXmltvID」，上千个频道就是上千次 SQL，
	// 首屏要等好几秒。
	type nowItem struct {
		ChannelID int64  `json:"channel_id"`
		Title     string `json:"title"`
		Start     int64  `json:"start"`
		End       int64  `json:"end"`
	}
	nowList := make([]nowItem, 0, len(chans))
	now := time.Now().Unix()
	epgIDs := make([]string, 0, len(chans))
	seen := make(map[string]bool, len(chans))
	for _, c := range chans {
		if c.EpgID == "" || seen[c.EpgID] {
			continue
		}
		seen[c.EpgID] = true
		epgIDs = append(epgIDs, c.EpgID)
	}
	byEpg, _ := a.st.ProgramsForEpgIDs(ctx, epgIDs, now-3600, now+3600)
	for _, c := range chans {
		if c.EpgID == "" {
			continue
		}
		for _, p := range byEpg[c.EpgID] {
			if p.StartAt <= now && p.EndAt > now {
				nowList = append(nowList, nowItem{ChannelID: c.ID, Title: p.Title, Start: p.StartAt, End: p.EndAt})
				break
			}
		}
	}

	var devCfg map[string]any
	if dev.ConfigJSON != "" {
		_ = json.Unmarshal([]byte(dev.ConfigJSON), &devCfg)
	}

	// 源列表：电视端列表按「源」分类用（参考实现的第一列）。
	// 只给 id 和名称，不带 URL 等敏感字段。
	srcs, _ := a.st.ListSources(ctx)
	srcBrief := make([]map[string]any, 0, len(srcs))
	for _, s := range srcs {
		srcBrief = append(srcBrief, map[string]any{"id": s.ID, "name": s.Name})
	}

	ok(w, map[string]any{
		"server": map[string]any{
			"version": a.version,
			"name":    "LITV",
		},
		"device": map[string]any{
			"id":     dev.ID,
			"name":   dev.Name,
			"config": devCfg,
		},
		"groups":    groups,
		"sources":   srcBrief,
		"channels":  chans,
		"favorites": favs,
		"now":       nowList,
	})
}

func (a *API) tvChannels(w http.ResponseWriter, r *http.Request) {
	chans, total, err := a.st.ListChannels(r.Context(), store.ChannelQuery{
		Group:   r.URL.Query().Get("group"),
		Limit:   queryInt(r, "limit", 500),
		Offset:  queryInt(r, "offset", 0),
		Enabled: boolPtr(true),
	})
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	ok(w, map[string]any{"total": total, "items": chans})
}

func (a *API) tvEPG(w http.ResponseWriter, r *http.Request) {
	id := pathInt64(r, "id")
	ch, err := a.st.GetChannel(r.Context(), id)
	if err != nil {
		fail(w, http.StatusNotFound, 3001, "频道不存在")
		return
	}
	if ch.EpgID == "" {
		ok(w, map[string]any{"channel": ch, "programs": []any{}, "note": "该频道未关联 EPG"})
		return
	}
	day := queryInt(r, "day", 0)
	// B01：按**频道所在地的日历日**取节目，不能按 UTC 零点截断。
	//
	// time.Truncate(24*time.Hour) 是从 Unix 纪元起的整 24 小时分片，
	// 等价于 UTC 零点。东八区用户看到的是"节目单错位 8 小时"：
	// 早上 8 点前查当天，拿到的其实是本地前一天的节目。
	// 用 time.Date 在指定时区里构造当地午夜，跨日、跨月、跨年才正确。
	start, end := localDayRange(a.now(), a.cfg.Server.Timezone, day)
	progs, err := a.st.ProgramsByXmltvID(r.Context(), ch.EpgID, start.Unix(), end.Unix())
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	// C1/C7：把「这一天是哪天」「服务端用哪个时区」一并下发。
	//
	// 客户端要显示「今天/明天」和节目时间，如果自己按设备时区算，
	// 会和服务端的日边界对不上（设备时区可能设错，或者用户在别的时区）。
	// 统一由服务端给 date/timezone/server_time，客户端只负责格式化。
	ok(w, map[string]any{
		"channel":     ch,
		"programs":    progs,
		"day":         day,
		"date":        start.Format("2006-01-02"),
		"timezone":    a.cfg.Server.Timezone,
		"server_time": a.now().Unix(),
		"day_start":   start.Unix(),
		"day_end":     end.Unix(),
	})
}

// localDayRange 返回指定时区里第 day 天的 [00:00, 次日00:00)。
//
// 用 time.Date 而不是 Truncate：
//   - Truncate 是 UTC 分片，在 UTC+8/UTC-5 都会错位；
//   - 夏令时切换日实际不是 24 小时（可能是 23 或 25 小时），
//     只有按日历构造次日零点才能拿到正确的区间。
func localDayRange(now time.Time, tz string, day int) (time.Time, time.Time) {
	loc := time.UTC
	if tz != "" {
		if l, err := time.LoadLocation(tz); err == nil {
			loc = l
		}
	}
	y, m, d := now.In(loc).Date()
	start := time.Date(y, m, d+day, 0, 0, 0, 0, loc)
	end := time.Date(y, m, d+day+1, 0, 0, 0, 0, loc)
	return start, end
}

func (a *API) tvFavorite(w http.ResponseWriter, r *http.Request) {
	dev, _ := deviceFrom(r.Context())
	var body struct {
		ChannelID int64 `json:"channel_id"`
		On        bool  `json:"on"`
	}
	if err := decode(r, &body); err != nil {
		fail(w, http.StatusBadRequest, 1001, "参数错误: "+err.Error())
		return
	}
	if err := a.st.SetFavorite(r.Context(), dev.ID, body.ChannelID, body.On); err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	ok(w, map[string]any{"saved": true})
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
	_ = a.st.TouchDeviceWithInfo(r.Context(), dev.ID, clientIP(r, a.cfg), body.AppVersion)
	ok(w, map[string]any{
		// R17：用独立的 config_version，不再用 bound_at。
		// bound_at 是绑定时间，管理台改配置时它不变，
		// 终端据此比对永远看不出"配置变了"，改了参数等于没改。
		"config_version": dev.ConfigVersion,
		"server_time":    time.Now().Unix(),
	})
}

// tvStreams 返回某频道的全部线路，供终端做「线路切换」。
// 只暴露必要字段，不回传上游真实地址（防抓包盗链）——终端只需要 id 和标签。
func (a *API) tvStreams(w http.ResponseWriter, r *http.Request) {
	id := pathInt64(r, "id")
	items, err := a.st.ListStreams(r.Context(), id)
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	out := make([]map[string]any, 0, len(items))
	for i, s := range items {
		label := s.Quality
		if label == "" {
			label = "线路 " + strconv.Itoa(i+1)
		}
		out = append(out, map[string]any{
			"id":         s.ID,
			"label":      label,
			"quality":    s.Quality,
			"priority":   s.Priority,
			"health":     s.Health, // 1 健康 / 0 失效 / -1 未探测
			"latency_ms": s.LatencyMs,
		})
	}
	ok(w, out)
}

// tvPlay 返回一个带签名的播放地址。终端不需要知道上游长什么样。
func (a *API) tvPlay(w http.ResponseWriter, r *http.Request) {
	slog.Info("tvPlay", "channel", pathInt64(r, "id"), "direct", r.URL.Query().Get("direct"))
	dev, _ := deviceFrom(r.Context())
	id := pathInt64(r, "id")

	ch, err := a.st.GetChannel(r.Context(), id)
	if err != nil {
		fail(w, http.StatusNotFound, 3001, "频道不存在")
		return
	}
	st, err := a.st.BestStream(r.Context(), id)
	// 终端手动切线路时用 stream_id 指定（优先于自动择优）
	if sidStr := r.URL.Query().Get("stream_id"); sidStr != "" {
		if sid, e := strconv.ParseInt(sidStr, 10, 64); e == nil && sid > 0 {
			if all, lerr := a.st.ListStreams(r.Context(), id); lerr == nil {
				for i := range all {
					if all[i].ID == sid {
						st = &all[i]
						err = nil
						break
					}
				}
			}
		}
	}
	if err != nil {
		fail(w, http.StatusNotFound, 3002, "该频道没有可用线路")
		return
	}

	kind := "ts"
	if strings.Contains(strings.ToLower(st.URL), ".m3u8") {
		kind = "hls"
	}
	// 客户端可以要求"给我 HLS"（iOS 的 AVPlayer 只认 HLS）。
	// 上游是裸 TS 时走转封装，上游本来就是 HLS 就直接用。
	wantHLS := strings.EqualFold(r.URL.Query().Get("fmt"), "hls")
	if wantHLS && kind == "ts" {
		if a.rx != nil && a.rx.Available() {
			kind = "remux"
		}
	}

	exp := time.Now().Add(12 * time.Hour).Unix()
	// R13：把选中的线路绑进签名，relay 各入口据此使用同一条线路，
	// 不再各自 BestStream。streamID 为 0 时退化为普通签名。
	// dk 用派生值：device_key 等价于设备凭据，不能出现在地址里。
	dk := a.signer.DeriveDeviceKey(dev.DeviceKey)
	sig := a.signer.SignStream(id, st.ID, exp, dk)
	path := fmt.Sprintf("%s/live/%d/stream.ts", a.cfg.Server.Prefix, id)
	switch kind {
	case "hls":
		path = fmt.Sprintf("%s/live/%d/index.m3u8", a.cfg.Server.Prefix, id)
	case "remux":
		path = fmt.Sprintf("%s/live/%d/remux.m3u8", a.cfg.Server.Prefix, id)
	}
	q := url.Values{}
	q.Set("sig", sig)
	q.Set("exp", strconv.FormatInt(exp, 10))
	q.Set("dk", dk)
	// stream_id 与签名一起出现：verifyStreamFull 会校验它们是否匹配。
	q.Set("stream_id", strconv.FormatInt(st.ID, 10))

	// 直连：把上游地址原样交给客户端，让它自己连源站。
	//
	// 省掉 NAS 的带宽和 CPU，起播也更快；代价是客户端要能访问到源站，
	// 且 UA/Referer 校验得客户端自己带（所以这里把头一起给出去）。
	// 只在客户端明确要（direct=1）且服务端允许时才走这条路。
	wantDirect := strings.EqualFold(r.URL.Query().Get("direct"), "1")
	if wantDirect && a.cfg.Stream.DirectEnabled && kind != "remux" {
		ok(w, map[string]any{
			"channel":   map[string]any{"id": ch.ID, "name": ch.Name, "logo": ch.Logo},
			"kind":      kind,
			"mode":      "direct",
			"url":       st.URL, // 上游原始地址
			"ua":        st.UserAgent(),
			"headers":   st.Headers(),
			"expires":   exp,
			"stream_id": st.ID,
		})
		a.st.Audit(r.Context(), 0, "play.direct", fmt.Sprint(id), ch.Name, r.RemoteAddr)
		return
	}

	ok(w, map[string]any{
		"channel":   map[string]any{"id": ch.ID, "name": ch.Name, "logo": ch.Logo},
		"kind":      kind,
		"mode":      "relay",
		"url":       path + "?" + q.Encode(),
		"expires":   exp,
		"stream_id": st.ID,
		// ua：这条线路专属的 UA（来自源配置）。客户端直连上游时要带上，
		// 否则源站会 403。为空表示用默认 UA。
		"ua": st.UserAgent(),
		// headers：整条线路的请求头（已清洗）。R09 之后两端 DTO 都要解码它，
		// 让 direct/relay 的自适应切换不至于因为缺头而失败。
		"headers": a.streamHeaders(id, st),
	})

	// 记一次播放，供概览页的「今日起播次数」统计
	a.st.Audit(r.Context(), 0, "play", fmt.Sprint(id), ch.Name, r.RemoteAddr)
}

// ---------- 手机当遥控 ----------

var wsUpgrader = websocket.Upgrader{
	ReadBufferSize:  1024,
	WriteBufferSize: 1024,
	// 家庭局域网自用：不校验 Origin（电视/手机的 Origin 各不相同）
	CheckOrigin: func(r *http.Request) bool { return true },
}

// tvWS 电视端建立遥控通道，连上后一直挂着等指令。
func (a *API) tvWS(w http.ResponseWriter, r *http.Request) {
	dev, okDev := deviceFrom(r.Context())
	if !okDev {
		fail(w, http.StatusUnauthorized, 2003, "设备未绑定")
		return
	}
	ws, err := wsUpgrader.Upgrade(w, r, nil)
	if err != nil {
		// Upgrade 失败时它已经写过响应了，这里不能再写
		return
	}
	a.remote.Register(dev.ID, ws)
}

// tvRemote 手机端发一条遥控指令给指定电视。
func (a *API) tvRemote(w http.ResponseWriter, r *http.Request) {
	from, okDev := deviceFrom(r.Context())
	if !okDev {
		fail(w, http.StatusUnauthorized, 2003, "设备未绑定")
		return
	}
	target := pathInt64(r, "id")
	if target == from.ID {
		fail(w, http.StatusBadRequest, 1001, "不能遥控自己")
		return
	}
	var cmd remote.Command
	if err := decode(r, &cmd); err != nil {
		fail(w, http.StatusBadRequest, 1001, "参数错误: "+err.Error())
		return
	}
	if cmd.Type == "" {
		cmd.Type = "key"
	}
	cmd.From = from.Name
	if !a.remote.Send(target, cmd) {
		fail(w, http.StatusNotFound, 3001, "目标设备不在线（电视上的 LITV 是否打开？）")
		return
	}
	ok(w, map[string]any{"sent": true})
}

// remoteTargets 列出可被遥控的设备（已绑定 + 是否在线）。
func (a *API) remoteTargets(w http.ResponseWriter, r *http.Request) {
	me, _ := deviceFrom(r.Context())
	items, err := a.st.ListDevices(r.Context(), "bound")
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	out := make([]map[string]any, 0, len(items))
	for _, d := range items {
		if me != nil && d.ID == me.ID {
			continue
		}
		out = append(out, map[string]any{
			"id":       d.ID,
			"name":     d.Name,
			"platform": d.Platform,
			"model":    d.Model,
			"online":   a.remote.IsOnline(d.ID),
		})
	}
	ok(w, out)
}

// ---------- 时移 / 回看 ----------

// tvTimeshift 告诉终端"这个台能回看多久"，并给出带签名的回看地址。
func (a *API) tvTimeshift(w http.ResponseWriter, r *http.Request) {
	dev, _ := deviceFrom(r.Context())
	id := pathInt64(r, "id")
	if a.ts == nil || !a.ts.Enabled() {
		ok(w, map[string]any{"available_seconds": 0, "enabled": false})
		return
	}
	span := a.ts.SpanSeconds(id)
	exp := time.Now().Add(6 * time.Hour).Unix()
	dk := a.signer.DeriveDeviceKey(dev.DeviceKey)
	sig := a.signer.Sign(id, exp, dk)
	q := url.Values{}
	q.Set("sig", sig)
	q.Set("exp", strconv.FormatInt(exp, 10))
	q.Set("dk", dk)
	ok(w, map[string]any{
		"enabled":           true,
		"available_seconds": span,
		"url": fmt.Sprintf("%s/live/%d/timeshift.ts?%s",
			a.cfg.Server.Prefix, id, q.Encode()),
	})
}

// liveTimeshift 从时移缓冲里读并吐给客户端。back 是"距今多少秒"。
func (a *API) liveTimeshift(w http.ResponseWriter, r *http.Request) {
	id, _, ok2 := a.verifyStream(r)
	if !ok2 {
		fail(w, http.StatusForbidden, 2002, "回看地址无效或已过期")
		return
	}
	if a.ts == nil || !a.ts.Enabled() {
		fail(w, http.StatusNotFound, 3001, "时移未开启")
		return
	}
	back, _ := strconv.Atoi(r.URL.Query().Get("back"))
	if back < 0 {
		back = 0
	}
	rd, err := a.ts.Open(id, back)
	if err != nil {
		fail(w, http.StatusNotFound, 3002, err.Error())
		return
	}
	defer rd.Close()

	flusher, canFlush := w.(http.Flusher)
	w.Header().Set("Content-Type", "video/mp2t")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.WriteHeader(http.StatusOK)

	buf := make([]byte, 64*1024)
	for {
		n, rerr := rd.Read(buf)
		if n > 0 {
			if _, werr := w.Write(buf[:n]); werr != nil {
				return
			}
			if canFlush {
				flusher.Flush()
			}
		}
		if rerr != nil {
			return
		}
		select {
		case <-r.Context().Done():
			return
		default:
		}
	}
}

// ---------- 裸 TS → HLS 转封装 ----------

// liveRemuxPlaylist 返回转封装后的 m3u8。
// 切片 URI 会被改写成 /live/{id}/remux/{seg}，复用同一套签名鉴权。
func (a *API) liveRemuxPlaylist(w http.ResponseWriter, r *http.Request) {
	id, _, sid, ok2 := a.verifyStreamFull(r)
	if !ok2 {
		fail(w, http.StatusForbidden, 2002, "播放地址无效或已过期")
		return
	}
	if a.rx == nil {
		fail(w, http.StatusServiceUnavailable, 5001, "服务端未启用转封装")
		return
	}
	// R13：用签名里绑定的线路，尊重手动选择
	st, err := a.streamForPlay(r.Context(), id, sid)
	if err != nil {
		fail(w, http.StatusNotFound, 3002, "该频道没有可用线路")
		return
	}

	// 切片 URI 会被改写为 /live/{id}/remux/{seg}，签名通过查询串补到每个 URI 上
	segPrefix := fmt.Sprintf("%s/live/%d/remux/", a.cfg.Server.Prefix, id)

	// R09：转封装也必须用同一份线路头。源站校验覆盖整条链路，
	// ffmpeg 用自己的默认 UA 会被 403，表现为"iOS 播不了但电视能播"。
	data, err := a.rx.PlaylistContext(r.Context(), id, st.URL, segPrefix, a.streamHeaders(id, st))
	if err != nil {
		fail(w, http.StatusBadGateway, 4001, err.Error())
		return
	}
	// 切片请求需要带上同样的签名，这里把查询串补到每个 URI 上
	if q := r.URL.RawQuery; q != "" {
		data = appendQueryToSegments(data, q)
	}

	w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Access-Control-Allow-Origin", "*")
	_, _ = w.Write(data)
}

// liveRemuxSegment 返回一个 HLS 切片。
func (a *API) liveRemuxSegment(w http.ResponseWriter, r *http.Request) {
	id, _, ok2 := a.verifyStream(r)
	if !ok2 {
		fail(w, http.StatusForbidden, 2002, "播放地址无效或已过期")
		return
	}
	if a.rx == nil {
		fail(w, http.StatusServiceUnavailable, 5001, "服务端未启用转封装")
		return
	}
	seg := chi.URLParam(r, "seg")
	f, modTime, err := a.rx.OpenSegment(id, seg)
	if err != nil {
		fail(w, http.StatusNotFound, 3001, err.Error())
		return
	}
	defer f.Close()
	// 用 ServeContent 而不是把整个切片读进内存再 Write：
	// 它能正确处理 Range（播放器 seek/断点续传会发 Range，以前一律回 200
	// 全量，等于每次 seek 都重传整片），并自动带 Content-Length/Last-Modified。
	w.Header().Set("Content-Type", "video/mp2t")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Access-Control-Allow-Origin", "*")
	http.ServeContent(w, r, seg, modTime, f)
}

// appendQueryToSegments 给播放列表里的每个切片 URI 补上签名查询串。
func appendQueryToSegments(data []byte, query string) []byte {
	lines := strings.Split(string(data), "\n")
	for i, line := range lines {
		t := strings.TrimSpace(line)
		if t == "" || strings.HasPrefix(t, "#") {
			continue
		}
		if strings.Contains(t, "?") {
			lines[i] = line + "&" + query
		} else {
			lines[i] = line + "?" + query
		}
	}
	return []byte(strings.Join(lines, "\n"))
}

// ---------- 播放质量上报 ----------

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
	var body struct {
		ChannelID int64  `json:"channel_id"`
		Event     string `json:"event"`
		StartupMs int    `json:"startup_ms"`
		Detail    string `json:"detail"`
	}
	if err := decode(r, &body); err != nil {
		fail(w, http.StatusBadRequest, 1001, "参数错误: "+err.Error())
		return
	}
	switch body.Event {
	case "start", "stall", "error", "stop":
	default:
		fail(w, http.StatusBadRequest, 1001, "event 只能是 start/stall/error/stop")
		return
	}
	// 上报失败不影响播放，所以这里不把错误往上抛
	_ = a.st.AddPlayEvent(r.Context(), store.PlayEvent{
		DeviceID:  dev.ID,
		ChannelID: body.ChannelID,
		Event:     body.Event,
		StartupMs: body.StartupMs,
		Detail:    body.Detail,
	})
	ok(w, map[string]any{"recorded": true})
}

// ---------- 跨端续播 ----------

// tvSaveProgress 终端上报"我在看哪个台、看到第几秒"。
func (a *API) tvSaveProgress(w http.ResponseWriter, r *http.Request) {
	dev, okDev := deviceFrom(r.Context())
	if !okDev {
		fail(w, http.StatusUnauthorized, 2003, "设备未绑定")
		return
	}
	var body struct {
		ChannelID   int64 `json:"channel_id"`
		PositionSec int   `json:"position_sec"`
	}
	if err := decode(r, &body); err != nil {
		fail(w, http.StatusBadRequest, 1001, "参数错误: "+err.Error())
		return
	}
	if body.ChannelID <= 0 {
		fail(w, http.StatusBadRequest, 1001, "channel_id 不能为空")
		return
	}
	_ = a.st.UpsertProgress(r.Context(), dev.ID, body.ChannelID, body.PositionSec)
	ok(w, map[string]any{"saved": true})
}

// tvResume 返回"最近在别的设备上看过的频道"，用于跨端续播提示。
func (a *API) tvResume(w http.ResponseWriter, r *http.Request) {
	dev, okDev := deviceFrom(r.Context())
	if !okDev {
		fail(w, http.StatusUnauthorized, 2003, "设备未绑定")
		return
	}
	rp, err := a.st.LatestResume(r.Context(), dev.ID)
	if err != nil {
		// 没有记录不是错误，返回空让客户端安静处理
		ok(w, map[string]any{"available": false})
		return
	}
	// 时移能不能覆盖到这个位置，决定了客户端是"从偏移处播"还是"直接看直播"
	tsAvail := 0
	if a.ts != nil && a.ts.Enabled() {
		tsAvail = a.ts.SpanSeconds(rp.ChannelID)
	}
	ok(w, map[string]any{
		"available":           true,
		"channel_id":          rp.ChannelID,
		"channel_name":        rp.ChannelName,
		"channel_logo":        rp.ChannelLogo,
		"group":               rp.Group,
		"position_sec":        rp.PositionSec,
		"device_name":         rp.DeviceName,
		"updated_at":          rp.UpdatedAt,
		"age_seconds":         int(time.Now().Unix() - rp.UpdatedAt),
		"timeshift_available": tsAvail,
	})
}

// ---------- 流出口 ----------

// verifyStream 校验签名，返回频道 ID 与设备标识。
func (a *API) verifyStream(r *http.Request) (int64, string, bool) {
	id, dk, _, ok := a.verifyStreamFull(r)
	return id, dk, ok
}

// verifyStreamFull 与 verifyStream 相同，另外返回签名里绑定的线路 id。
//
// R13：线路选择必须由签名承载。只靠查询参数 stream_id 的话，
// 攻击者可以随便改它去探测其他线路；更重要的是各拉流入口
// 需要知道"用户到底选了哪条"，否则又会各自 BestStream。
func (a *API) verifyStreamFull(r *http.Request) (id int64, dk string, streamID int64, ok bool) {
	id = pathInt64(r, "id")
	q := r.URL.Query()
	exp, _ := strconv.ParseInt(q.Get("exp"), 10, 64)
	// 查询参数里的 stream_id 只是"待校验的候选值"，
	// 只有签名里真的绑定了它才算数（见 VerifyStreamSig）。
	cand, _ := strconv.ParseInt(q.Get("stream_id"), 10, 64)
	gotSID, err := a.signer.VerifyStreamSig(id, cand, exp, q.Get("dk"), q.Get("sig"))
	if err != nil {
		return 0, "", 0, false
	}
	return id, q.Get("dk"), gotSID, true
}

// streamForPlay 解析本次请求该用哪条线路。
//
// 优先用签名里绑定的线路（R13）；没有绑定就回落到 BestStream。
// 绑定的线路必须属于该频道，否则说明签名被跨频道复用，直接拒绝。
func (a *API) streamForPlay(ctx context.Context, channelID, boundStreamID int64) (*store.Stream, error) {
	if boundStreamID > 0 {
		all, err := a.st.ListStreams(ctx, channelID)
		if err != nil {
			return nil, err
		}
		for i := range all {
			if all[i].ID == boundStreamID {
				return &all[i], nil
			}
		}
		// 绑定线路不属于这个频道：签名不可信。
		return nil, fmt.Errorf("线路 %d 不属于频道 %d", boundStreamID, channelID)
	}
	return a.st.BestStream(ctx, channelID)
}

// livePlay 是个分发器：告诉客户端该走 TS 还是 HLS。
func (a *API) livePlay(w http.ResponseWriter, r *http.Request) {
	slog.Info("livePlay", "path", r.URL.Path)
	id, _, sid, ok2 := a.verifyStreamFull(r)
	if !ok2 {
		fail(w, http.StatusForbidden, 2002, "播放地址无效或已过期")
		return
	}
	// R13：用签名里绑定的线路判断该走 TS 还是 HLS，
	// 否则会把用户选中的 TS 线路判成 HLS（或反之），跳转到错误的入口。
	st, err := a.streamForPlay(r.Context(), id, sid)
	if err != nil {
		fail(w, http.StatusNotFound, 3002, "该频道没有可用线路")
		return
	}
	target := fmt.Sprintf("%s/live/%d/stream.ts?%s", a.cfg.Server.Prefix, id, r.URL.RawQuery)
	if strings.Contains(strings.ToLower(st.URL), ".m3u8") {
		target = fmt.Sprintf("%s/live/%d/index.m3u8?%s", a.cfg.Server.Prefix, id, r.URL.RawQuery)
	}
	http.Redirect(w, r, target, http.StatusFound)
}

// liveTS 是核心：把上游 TS 字节流转给终端，多终端共用一路上游。
func (a *API) liveTS(w http.ResponseWriter, r *http.Request) {
	id, _, sid, ok2 := a.verifyStreamFull(r)
	if !ok2 {
		fail(w, http.StatusForbidden, 2002, "播放地址无效或已过期")
		return
	}
	// R13：用签名里绑定的线路，尊重手动选择
	st, err := a.streamForPlay(r.Context(), id, sid)
	if err != nil {
		fail(w, http.StatusNotFound, 3002, "该频道没有可用线路")
		return
	}

	// B02：先订阅再写响应头。
	//
	// 原来的顺序是 WriteHeader(200) 之后才 Subscribe，于是订阅失败
	// （比如并发流已达上限）只能静默 return——用户看到的是 200 加一片空白，
	// 完全不知道发生了什么。现在把订阅挪到写头之前，失败能返回正确的状态码。
	//
	// 带上这条线路专属的头：不传的话源站校验 UA 会直接 403，
	// 表现为"有画面没声音"或直接黑屏。
	sub, subErr := a.hub.Subscribe(r.Context(), id, st.URL, st.Headers())
	if subErr != nil {
		if errors.Is(subErr, stream.ErrTooManyStreams) {
			fail(w, http.StatusServiceUnavailable, 3003,
				"并发流已达上限，请稍后再试或调高「并发上限」")
			return
		}
		fail(w, http.StatusBadGateway, 4001, "订阅上游失败: "+subErr.Error())
		return
	}

	flusher, canFlush := w.(http.Flusher)
	w.Header().Set("Content-Type", "video/mp2t")
	w.Header().Set("Cache-Control", "no-cache, no-store")
	w.Header().Set("Connection", "keep-alive")
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.WriteHeader(http.StatusOK)

	for {
		select {
		case <-r.Context().Done():
			return
		case chunk, open := <-sub.C:
			if !open {
				return
			}
			if _, werr := w.Write(chunk); werr != nil {
				return
			}
			if canFlush {
				flusher.Flush()
			}
		}
	}
}

// liveManifest 代理 HLS 清单，并把分片地址改写成我们自己的出口（防上游防盗链、防源泄露）。
// streamUA 返回这条线路该用的 User-Agent。
//
// 顺序：线路自己的（来自源配置）→ 全局配置 → 空（调用方用默认）。
//
// 为什么必须按线路取：有的源站强制校验 UA，UA 不对会返回**另一份内容**
// （比如一段演示视频），而不是报错——用全局 UA 去抓就是"能播但播的不是这个台"。
func (a *API) streamUA(ctx context.Context, channelID int64) string {
	if st, err := a.st.BestStream(ctx, channelID); err == nil && st != nil {
		if ua := st.UserAgent(); ua != "" {
			return ua
		}
	}
	return a.cfg.Stream.UserAgent
}

// sanitizedHeaders 把线路配置里的头清洗成可安全外发的形式。
//
// 为什么必须清洗：headers_json 来自用户导入的源文件，属于不可信输入。
//
// 两类必须去掉的头：
//  1. 逐跳（hop-by-hop）头，比如 Connection / Host / Content-Length /
//     Transfer-Encoding。转发它们会让上游行为和预期不符，甚至破坏请求。
//  2. 身份类头，比如 Authorization / Cookie / X-Trim-*。
//     我们自己的设备 Bearer token 和管理员身份头**绝对不能**泄露给源站
//     —— 源站是第三方，给它就等于把凭据送出去。
func sanitizedHeaders(raw map[string]string) map[string]string {
	if len(raw) == 0 {
		return nil
	}
	blocked := map[string]bool{
		"connection":        true,
		"host":              true,
		"content-length":    true,
		"transfer-encoding": true,
		"upgrade":           true,
		"proxy-connection":  true,
		"te":                true,
		"trailer":           true,
		"authorization":     true,
		"cookie":            true,
		"set-cookie":        true,
	}
	out := make(map[string]string, len(raw))
	for k, v := range raw {
		lk := strings.ToLower(strings.TrimSpace(k))
		if lk == "" || blocked[lk] || strings.HasPrefix(lk, "x-trim-") || strings.HasPrefix(lk, "x-litv-") {
			continue
		}
		// 值里不允许换行：防止把用户可控内容注入成额外的头（CRLF 注入）。
		if strings.ContainsAny(v, "\r\n\x00") {
			continue
		}
		out[k] = strings.TrimSpace(v)
	}
	if len(out) == 0 {
		return nil
	}
	return out
}

// streamHeaders 返回某条线路该带的请求头（已清洗）。
//
// 这是 R09 的统一入口。以前 direct/relay/remux/分片 四条路径各自为政，
// 有的只传 UA、有的完全不传，导致"只在 UA=X 且 Referer=Y 时才给流"的源
// 只有其中某一条路径能播，用户看到的是"换个内核就好了/换个入口就好了"。
func (a *API) streamHeaders(channelID int64, st *store.Stream) map[string]string {
	var raw map[string]string
	if st != nil {
		raw = st.Headers()
	} else if s, err := a.st.BestStream(context.Background(), channelID); err == nil && s != nil {
		raw = s.Headers()
	}
	h := sanitizedHeaders(raw)

	// UA 单独兜一层：线路没配 UA 时用全局配置，保证总有值。
	if h == nil {
		h = map[string]string{}
	}
	if _, okUA := findHeaderKey(h, "user-agent"); !okUA {
		if ua := a.cfg.Stream.UserAgent; ua != "" {
			h["User-Agent"] = ua
		}
	}
	if len(h) == 0 {
		return nil
	}
	return h
}

// findHeaderKey 大小写不敏感地找一个头，返回真实键名。
func findHeaderKey(h map[string]string, want string) (string, bool) {
	for k := range h {
		if strings.EqualFold(k, want) {
			return k, true
		}
	}
	return "", false
}

// streamFetchOptions 用线路专属的 UA 与全部请求头去抓上游。
func (a *API) streamFetchOptions(ctx context.Context, channelID int64) fetch.Options {
	opt := fetch.DefaultOptions()
	opt.Headers = a.streamHeaders(channelID, nil)
	if ua := a.streamUA(ctx, channelID); ua != "" {
		opt.UserAgent = ua
	}
	return opt
}

func (a *API) liveManifest(w http.ResponseWriter, r *http.Request) {
	id, dk, sid, ok2 := a.verifyStreamFull(r)
	if !ok2 {
		fail(w, http.StatusForbidden, 2002, "播放地址无效或已过期")
		return
	}
	// 逐条线路尝试：源里常有一批签名过期的死线路，
	// 只试第一条的话，碰到死的就整个台播不了。
	streams, serr := a.st.ListStreams(r.Context(), id)
	if serr != nil || len(streams) == 0 {
		fail(w, http.StatusNotFound, 3002, "该频道没有可用线路")
		return
	}

	// R13：用户手动选了线路就优先试它。
	// 仍然保留"失败再试别的"——死线路不该让整个台播不了，
	// 但"能用哪条"的第一顺位必须是用户的选择，否则切了等于没切。
	if sid > 0 {
		for i := range streams {
			if streams[i].ID == sid {
				first := streams[i]
				copy(streams[1:], streams[0:i])
				streams[0] = first
				break
			}
		}
	}

	// 同一频道的并发请求合并成一次上游抓取。
	// 多台设备同时看同一个台时，以前每个客户端每 2–6 秒各抓一次清单，
	// 既压上游，也把 UpdateStreamHealth 变成高频写库。
	slot := a.manifestSlotFor(id)
	slot.mu.Lock()
	defer slot.mu.Unlock()
	// 命中条件里带上 sid：用户手动切了线路时不能用别的线路的缓存，
	// 否则「切了等于没切」——清单里的分片地址还是旧线路的。
	// sid=0（没指定）时按频道复用，这才是多设备同时看的主路径。
	if slot.fresh(time.Now()) && (sid == 0 || slot.streamID == sid) {
		a.writeManifest(w, manifestSnapshot{body: slot.body, base: slot.base, streamID: slot.streamID}, id, dk)
		return
	}

	var (
		res     *fetch.Result
		st      *store.Stream
		lastErr error
	)
	for i := range streams {
		cand := &streams[i]
		if !strings.Contains(strings.ToLower(cand.URL), ".m3u8") {
			lastErr = fmt.Errorf("不是 HLS 线路")
			continue
		}
		// 单条线路等 15 秒：源站冷请求有时要 8-10 秒，给太短会误判成死线路。
		// 等满默认的 20 秒就没时间试下一条了。
		cctx, cancel := context.WithTimeout(r.Context(), 15*time.Second)
		opt := fetch.DefaultOptions()
		// R09：整条线路的请求头都要带上，不只是 UA。
		// 只在 UA=X 且 Referer=Y 时才给流的源，缺任何一条都是 403。
		opt.Headers = a.streamHeaders(id, cand)
		if ua := cand.UserAgent(); ua != "" {
			opt.UserAgent = ua
		} else if a.cfg.Stream.UserAgent != "" {
			opt.UserAgent = a.cfg.Stream.UserAgent
		}
		got, ferr := fetch.Get(cctx, cand.URL, opt)
		cancel()
		if ferr != nil {
			lastErr = ferr
			// 记一笔不健康，让 BestStream 下次把它排后面
			_ = a.st.UpdateStreamHealth(r.Context(), cand.ID, -1, 0)
			continue
		}
		res, st = got, cand
		_ = a.st.UpdateStreamHealth(r.Context(), cand.ID, 1, int(got.ElapsedMs))
		break
	}
	if res == nil || st == nil {
		msg := "拉取清单失败"
		if lastErr != nil {
			msg += ": " + lastErr.Error()
		}
		slog.Warn("manifest 失败", "channel", id, "err", msg)
		fail(w, http.StatusBadGateway, 4001, msg)
		return
	}
	slog.Info("manifest 成功", "channel", id, "stream", st.ID, "size", len(res.Body))
	base, _ := url.Parse(res.FinalURL)
	slot.body = res.Body
	slot.base = base
	slot.streamID = st.ID
	slot.at = time.Now()
	a.writeManifest(w, manifestSnapshot{body: res.Body, base: base, streamID: st.ID}, id, dk)
}

// liveSegment 代理 HLS 分片（含子清单）。
func (a *API) liveSegment(w http.ResponseWriter, r *http.Request) {
	// 注意：这里**不能**先走 verifyStreamFull。
	//
	// /seg 的签名是「绑定目标」的分片签名，它和频道级播放签名是两套
	// 不同内容的 HMAC，一个签名不可能同时满足两者。
	// 先要求频道级校验的话，segmentURL 生成的地址会永远 403——
	// 清单能取到、分片全被拒，表现就是"有列表但画面全黑"。
	//
	// 所以分片签名**替代**频道级校验，而不是叠加：
	// 它自己已经绑定了用途、频道、目标地址、到期时间和设备。
	id := pathInt64(r, "id")
	dk := r.URL.Query().Get("dk")
	target := r.URL.Query().Get("u")
	if target == "" {
		fail(w, http.StatusBadRequest, 1001, "缺少分片地址")
		return
	}
	// 防 SSRF：只允许 http/https
	pu, err := url.Parse(target)
	if err != nil || (pu.Scheme != "http" && pu.Scheme != "https") {
		fail(w, http.StatusBadRequest, 1001, "非法的分片地址")
		return
	}

	// R04：校验分片签名，它绑定了「用途 + 频道 + 目标地址 + 到期 + 设备」。
	// 改 u / 换频道 / 换设备 / 改到期 任一都会失败。
	exp, _ := strconv.ParseInt(r.URL.Query().Get("exp"), 10, 64)
	if err := a.signer.VerifySegment(id, target, exp, dk, r.URL.Query().Get("sig")); err != nil {
		fail(w, http.StatusForbidden, 2002, "分片地址无效或已过期")
		return
	}
	// 分片签名不携带线路信息（签名格式不能为此改），
	// 所以优先用「刚给这台设备发清单时选中的线路」。
	// 缓存过期再回落到最佳线路。

	// 这单请求是要打到 LAN 的话，留一条审计痕迹（按频道 + 时间窗去重）。
	// 解析失败不是错误：审计是附加信息，不能因为它让播放失败。
	if addr, ok := resolveTargetAddr(r.Context(), target); ok {
		a.notePrivateSegmentTarget(r.Context(), id, addr)
	}

	req, err := http.NewRequestWithContext(r.Context(), http.MethodGet, target, nil)
	if err != nil {
		fail(w, http.StatusBadGateway, 4001, err.Error())
		return
	}
	// R09：分片也要带整条线路的头。源站校验是覆盖整条链路的，
	// 不只是清单请求；只带 UA 时，要求 Referer 的源会在分片这一步 403，
	// 表现是清单能取到、画面却一片黑。
	// R13：用清单选中的线路取头，保证与清单请求是同一条线路的配置。
	var boundStream *store.Stream
	if cachedSID, okc := a.cachedManifestStream(id); okc {
		if st, serr := a.streamForPlay(r.Context(), id, cachedSID); serr == nil {
			boundStream = st
		}
	} else if st, serr := a.streamForPlay(r.Context(), id, 0); serr == nil {
		boundStream = st
	}
	for k, v := range a.streamHeaders(id, boundStream) {
		req.Header.Set(k, v)
	}
	req.Header.Set("Accept", "*/*")
	req.Header.Set("Cache-Control", "no-cache")

	// 用 a.segClient 而不是 http.DefaultClient：
	//   - 它接了 safedial，拒绝"让 NAS 打自己"与云元数据端点；
	//   - 它没有总超时（分片是流式传输，见 api.segTransport 的注释）；
	//   - 它透传 r.Context()，客户端断开时上游读取立刻取消。
	client := a.segClient
	if client == nil {
		// New() 一定会赋值；这里只防手搓 API 的测试。
		client = a.streamClient
	}
	if client == nil {
		client = http.DefaultClient
	}
	resp, err := client.Do(req)
	if err != nil {
		if safedial.IsBlockedError(err) {
			// 地址被策略拒绝是内容/配置问题，不是上游挂了：
			// 返回 403 而不是 502，免得被当成"这个台临时故障"去重试。
			fail(w, http.StatusForbidden, 2002, "分片目标地址不被允许")
			return
		}
		fail(w, http.StatusBadGateway, 4001, err.Error())
		return
	}
	defer resp.Body.Close()

	// 嗅一下是不是子清单：源里很多台是「主清单 → 子清单 → 分片」的嵌套结构。
	// 子清单里的分片地址是相对路径，必须像主清单一样改写，
	// 否则 hls.js 会拿相对路径去拼，拼出来是坏地址 —— 表现就是「这个台播不了」。
	br := bufio.NewReader(resp.Body)
	if head, _ := br.Peek(7); bytes.HasPrefix(head, []byte("#EXTM3U")) {
		body, _ := io.ReadAll(io.LimitReader(br, 4<<20))

		// R14：基准地址必须是**实际最终响应**的 URL，不是我们请求的那个。
		//
		// 源站很常见 302 到另一个目录（CDN 调度、鉴权跳板）。
		// 用请求的 target 当基准，相对路径会解析到错误目录——
		// 表现是 master 能取到、variant 却 404，用户看到"这个台播不了"。
		// http.Client 已经跟随重定向，最终地址在 resp.Request.URL。
		base := pu
		if resp.Request != nil && resp.Request.URL != nil {
			base = resp.Request.URL
		}

		// 递归深度限制：清单可以嵌套，但不能无限嵌套。
		// 恶意或损坏的源可以构造 A->B->A 的循环，把 NAS 卡在这里。
		depth, _ := strconv.Atoi(r.URL.Query().Get("depth"))
		if depth <= 0 {
			depth = 1
		}
		const maxDepth = 4

		lines := strings.Split(string(body), "\n")
		out := make([]string, 0, len(lines))
		for _, line := range lines {
			trimmed := strings.TrimSpace(line)
			if trimmed == "" || strings.HasPrefix(trimmed, "#") {
				out = append(out, rewriteURIAttrDepth(trimmed, base, a, id, dk, depth))
				continue
			}
			if u, uerr := base.Parse(trimmed); uerr == nil {
				out = append(out, a.segmentURLDepth(u.String(), id, dk, depth+1, maxDepth))
			} else {
				out = append(out, line)
			}
		}
		w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
		w.Header().Set("Cache-Control", "no-cache")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		_, _ = io.WriteString(w, strings.Join(out, "\n"))
		return
	}

	ct := resp.Header.Get("Content-Type")
	if ct == "" {
		ct = "video/mp2t"
	}

	// 体积护栏必须在**写响应头之前**判完。
	//
	// 反例（就是不能用 io.Copy + io.LimitReader 到上限的原因）：
	// 被 LimitReader 截断时 Copy 返回的是 nil（被限流不算错误），
	// 而 200 状态行早就发出去了 —— 播放器收到一个"状态码 200 的残片"，
	// 表现为花屏且**不会**触发换线重试，比一个明确的 502 难查得多。
	if resp.ContentLength > segMaxBytes {
		fail(w, http.StatusBadGateway, 4001,
			fmt.Sprintf("分片体积超出上限（%d 字节）", segMaxBytes))
		return
	}
	w.Header().Set("Content-Type", ct)
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.WriteHeader(resp.StatusCode)
	// 上游没给 Content-Length 时只能边走边数：超过上限就中断连接
	// （abortAfterWriter 里 panic(http.ErrAbortHandler)），
	// 绝不以"200 + 短体"收尾。
	if _, cerr := io.Copy(&abortAfterWriter{w: w, limit: segMaxBytes}, br); cerr != nil {
		// 只有下游断开才会走到这里（写失败）。客户端已经不在了，正常返回。
		return
	}
}

// segMaxBytes 是单个分片的体积上限。
//
// 32MB 远超正常的 HLS 分片（1080p 一片通常 2~6MB，4K 也就 10MB 上下），
// 取这么宽松是为了不误杀高码率源；它的作用是拦住"目标根本不是一个分片"
// 那种情况（比如被指到某个大文件或管理页的一大坨 HTML）。
const segMaxBytes = 32 << 20

// abortAfterWriter 在累计写出超过 limit 时中断连接。
//
// 为什么用 panic(http.ErrAbortHandler)：此刻响应头已经发出（状态码 200），
// 没有任何办法再改成 502。net/http 把 ErrAbortHandler 当成"放弃这个响应"，
// 直接关掉底层连接，客户端看到的是一段残缺的响应体，会按 HLS 规则
// 重试或换线 —— 这正是想要的失败方式。
// 相反，如果这里安静返回 nil，播放器收到的是一个"成功的短分片"：
// 花屏、不重试、日志里什么都没有。
type abortAfterWriter struct {
	w     io.Writer
	limit int64
	n     int64
}

func (a *abortAfterWriter) Write(p []byte) (int, error) {
	a.n += int64(len(p))
	if a.n > a.limit {
		panic(http.ErrAbortHandler)
	}
	return a.w.Write(p)
}

// resolveTargetAddr 解析目标主机，返回一个地址（仅用于审计标记）。
//
// 解析失败返回 ok=false：审计是附加信息，不能因为它让播放失败。
func resolveTargetAddr(ctx context.Context, rawURL string) (netip.Addr, bool) {
	u, err := url.Parse(rawURL)
	if err != nil {
		return netip.Addr{}, false
	}
	host := u.Hostname()
	if host == "" {
		return netip.Addr{}, false
	}
	// 字面量 IP 直接判，不用查 DNS。
	if ip, perr := netip.ParseAddr(host); perr == nil {
		return ip, true
	}
	ips, lerr := net.DefaultResolver.LookupNetIP(ctx, "ip", host)
	if lerr != nil || len(ips) == 0 {
		return netip.Addr{}, false
	}
	return ips[0], true
}

// segmentURL 为分片/子清单/密钥地址签一个**绑定目标**的代理地址。
//
// R04：签名必须包含目标地址本身。旧实现签的是「频道级播放签名」，
// 而签名内容里没有 u，于是持有有效播放签名的设备只要改 u，
// 就能让 NAS 去请求任意 http/https 地址——把播放权限放大成任意 HTTP 代理。
//
// 这里的签名带 "seg" 用途前缀，所以它也不能被拿去当播放签名复用。
func (a *API) segmentURL(abs string, channelID int64, dk string) string {
	return a.segmentURLDepth(abs, channelID, dk, 0, 0)
}

// segmentURLDepth 是 segmentURL 的带深度版本。
//
// R14：清单可能嵌套（master → variant → 分片），甚至被恶意源构造成循环。
// depth 会写进代理地址，下一次请求据此继续计数；超过 maxDepth 就不再
// 转发为代理地址，避免无限递归把 NAS 卡死。
func (a *API) segmentURLDepth(abs string, channelID int64, dk string, depth, maxDepth int) string {
	exp := time.Now().Add(12 * time.Hour).Unix()
	sig := a.signer.SignSegment(channelID, abs, exp, dk)
	q := url.Values{}
	q.Set("u", abs)
	q.Set("sig", sig)
	q.Set("exp", strconv.FormatInt(exp, 10))
	q.Set("dk", dk)
	if depth > 0 {
		// 把深度带进代理地址，下次请求从这儿继续往上数。
		q.Set("depth", strconv.Itoa(depth))
	}
	return fmt.Sprintf("%s/live/%d/seg?%s", a.cfg.Server.Prefix, channelID, q.Encode())
}

// rewriteURIAttr 处理 `#EXT-X-KEY:METHOD=AES-128,URI="xxx"` 这类带 URI 属性的标签。
func rewriteURIAttr(line string, base *url.URL, a *API, channelID int64, dk string) string {
	return rewriteURIAttrDepth(line, base, a, channelID, dk, 0)
}

// rewriteURIAttrDepth 是 rewriteURIAttr 的带深度版本。
//
// R14 的另一半：#EXT-X-MAP / #EXT-X-KEY / #EXT-X-MEDIA 里的 URI 也会指向
// 相对路径（初始化段、AES 密钥、子清单）。只改写裸行、不改写属性 URI 的话，
// 这些引用会相对 NAS 的 /seg 路径解析，取不到真实文件——
// 表现为"能起播但一直黑屏/解密失败"。
func rewriteURIAttrDepth(line string, base *url.URL, a *API, channelID int64, dk string, depth int) string {
	const marker = `URI="`
	idx := strings.Index(line, marker)
	if idx < 0 {
		return line
	}
	start := idx + len(marker)
	end := strings.Index(line[start:], `"`)
	if end < 0 {
		return line
	}
	raw := line[start : start+end]
	u, err := base.Parse(raw)
	if err != nil {
		return line
	}
	return line[:start] + a.segmentURLDepth(u.String(), channelID, dk, depth+1, 4) + line[start+end:]
}

func boolPtr(b bool) *bool { return &b }
