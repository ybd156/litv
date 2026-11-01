package api

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/gorilla/websocket"

	"github.com/go-chi/chi/v5"

	"github.com/litv/litv/server/internal/auth"
	"github.com/litv/litv/server/internal/fetch"
	"github.com/litv/litv/server/internal/remote"
	"github.com/litv/litv/server/internal/store"
)

// ---------- 终端注册 ----------

// deviceRegister 终端首次注册：客户端带设备唯一标识来换 token，此时状态是 pending，
// 需要在管理台批准后才能使用。这就是「绑定码」流程的服务端一半。
func (a *API) deviceRegister(w http.ResponseWriter, r *http.Request) {
	var body struct {
		DeviceKey string `json:"device_key"`
		Name      string `json:"name"`
		Platform  string `json:"platform"`
		Model     string `json:"model"`
		Code      string `json:"code"` // 可选：直接带绑定码则跳过审批
	}
	if err := decode(r, &body); err != nil {
		fail(w, http.StatusBadRequest, 1001, "参数错误: "+err.Error())
		return
	}
	if body.DeviceKey == "" {
		fail(w, http.StatusBadRequest, 1001, "device_key 不能为空")
		return
	}
	if body.Platform == "" {
		body.Platform = "android"
	}

	plain, hash, err := auth.NewToken()
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	id, err := a.st.CreateDevice(r.Context(), body.DeviceKey, hash, body.Name, body.Platform, body.Model)
	if err != nil {
		// 已存在同一 device_key：说明是重装/重启，直接返回 pending 让用户重新审批
		fail(w, http.StatusConflict, 2003, "该设备已注册，请在管理台重新批准或使用绑定码")
		return
	}

	status := "pending"
	if body.Code != "" {
		if did, cerr := a.st.ConsumeBindCode(r.Context(), body.Code); cerr == nil && did == id {
			_ = a.st.ApproveDevice(r.Context(), id)
			status = "bound"
		}
	}

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
	chans, _, _ := a.st.ListChannels(ctx, store.ChannelQuery{Limit: 500, Enabled: boolPtr(true)})
	favs, _ := a.st.ListFavorites(ctx, dev.ID)

	// 当前节目：批量取，避免 TV 端首页 N 次请求
	type nowItem struct {
		ChannelID int64  `json:"channel_id"`
		Title     string `json:"title"`
		Start     int64  `json:"start"`
		End       int64  `json:"end"`
	}
	nowList := make([]nowItem, 0, len(chans))
	now := time.Now().Unix()
	for _, c := range chans {
		if c.EpgID == "" {
			continue
		}
		progs, err := a.st.ProgramsByXmltvID(ctx, c.EpgID, now-3600, now+3600)
		if err != nil || len(progs) == 0 {
			continue
		}
		for _, p := range progs {
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
		"channels":  chans,
		"favorites": favs,
		"now":       nowList,
	})
}

func (a *API) tvChannels(w http.ResponseWriter, r *http.Request) {
	chans, total, err := a.st.ListChannels(r.Context(), store.ChannelQuery{
		Group:  r.URL.Query().Get("group"),
		Limit:  queryInt(r, "limit", 500),
		Offset: queryInt(r, "offset", 0),
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
	base := time.Now().Truncate(24 * time.Hour).AddDate(0, 0, day)
	progs, err := a.st.ProgramsByXmltvID(r.Context(), ch.EpgID, base.Unix(), base.Add(24*time.Hour).Unix())
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	ok(w, map[string]any{"channel": ch, "programs": progs})
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
	}
	_ = decode(r, &body)
	_ = a.st.TouchDevice(r.Context(), dev.ID)
	ok(w, map[string]any{
		"config_version": dev.BoundAt, // 配置有变化时递增，终端据此决定是否重新拉 bootstrap
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
	sig := a.signer.Sign(id, exp, dev.DeviceKey)
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
	q.Set("dk", dev.DeviceKey)

	// 直连：把上游地址原样交给客户端，让它自己连源站。
	//
	// 省掉 NAS 的带宽和 CPU，起播也更快；代价是客户端要能访问到源站，
	// 且 UA/Referer 校验得客户端自己带（所以这里把头一起给出去）。
	// 只在客户端明确要（direct=1）且服务端允许时才走这条路。
	wantDirect := strings.EqualFold(r.URL.Query().Get("direct"), "1")
	if wantDirect && a.cfg.Stream.DirectEnabled && kind != "remux" {
		ok(w, map[string]any{
			"channel": map[string]any{"id": ch.ID, "name": ch.Name, "logo": ch.Logo},
			"kind":    kind,
			"mode":    "direct",
			"url":     st.URL, // 上游原始地址
			"ua":      st.UserAgent(),
			"headers": st.Headers(),
			"expires": exp,
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
	sig := a.signer.Sign(id, exp, dev.DeviceKey)
	q := url.Values{}
	q.Set("sig", sig)
	q.Set("exp", strconv.FormatInt(exp, 10))
	q.Set("dk", dev.DeviceKey)
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
	id, _, ok2 := a.verifyStream(r)
	if !ok2 {
		fail(w, http.StatusForbidden, 2002, "播放地址无效或已过期")
		return
	}
	if a.rx == nil {
		fail(w, http.StatusServiceUnavailable, 5001, "服务端未启用转封装")
		return
	}
	st, err := a.st.BestStream(r.Context(), id)
	if err != nil {
		fail(w, http.StatusNotFound, 3002, "该频道没有可用线路")
		return
	}

	// 切片 URI 会被改写为 /live/{id}/remux/{seg}，签名通过查询串补到每个 URI 上
	segPrefix := fmt.Sprintf("%s/live/%d/remux/", a.cfg.Server.Prefix, id)

	data, err := a.rx.Playlist(id, st.URL, segPrefix)
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
	data, err := a.rx.Segment(id, seg)
	if err != nil {
		fail(w, http.StatusNotFound, 3001, err.Error())
		return
	}
	w.Header().Set("Content-Type", "video/mp2t")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Access-Control-Allow-Origin", "*")
	_, _ = w.Write(data)
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
		"available":          true,
		"channel_id":         rp.ChannelID,
		"channel_name":       rp.ChannelName,
		"channel_logo":       rp.ChannelLogo,
		"group":              rp.Group,
		"position_sec":       rp.PositionSec,
		"device_name":        rp.DeviceName,
		"updated_at":         rp.UpdatedAt,
		"age_seconds":        int(time.Now().Unix() - rp.UpdatedAt),
		"timeshift_available": tsAvail,
	})
}

// ---------- 流出口 ----------

// verifyStream 校验签名，返回频道 ID 与设备标识。
func (a *API) verifyStream(r *http.Request) (int64, string, bool) {
	id := pathInt64(r, "id")
	q := r.URL.Query()
	exp, _ := strconv.ParseInt(q.Get("exp"), 10, 64)
	if err := a.signer.Verify(id, exp, q.Get("dk"), q.Get("sig")); err != nil {
		return 0, "", false
	}
	return id, q.Get("dk"), true
}

// livePlay 是个分发器：告诉客户端该走 TS 还是 HLS。
func (a *API) livePlay(w http.ResponseWriter, r *http.Request) {
	id, _, ok2 := a.verifyStream(r)
	if !ok2 {
		fail(w, http.StatusForbidden, 2002, "播放地址无效或已过期")
		return
	}
	st, err := a.st.BestStream(r.Context(), id)
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
	id, dk, ok2 := a.verifyStream(r)
	if !ok2 {
		fail(w, http.StatusForbidden, 2002, "播放地址无效或已过期")
		return
	}
	st, err := a.st.BestStream(r.Context(), id)
	if err != nil {
		fail(w, http.StatusNotFound, 3002, "该频道没有可用线路")
		return
	}

	flusher, canFlush := w.(http.Flusher)
	w.Header().Set("Content-Type", "video/mp2t")
	w.Header().Set("Cache-Control", "no-cache, no-store")
	w.Header().Set("Connection", "keep-alive")
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.WriteHeader(http.StatusOK)

	// 带上这条线路专属的头（主要是源配置里的 User-Agent）。
	// 不传的话源站校验 UA 会直接 403，表现为"有画面没声音"或直接黑屏。
	sub, err := a.hub.Subscribe(r.Context(), id, st.URL, st.Headers())
	if err != nil {
		return
	}
	_ = dk

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
func (a *API) liveManifest(w http.ResponseWriter, r *http.Request) {
	id, dk, ok2 := a.verifyStream(r)
	if !ok2 {
		fail(w, http.StatusForbidden, 2002, "播放地址无效或已过期")
		return
	}
	st, err := a.st.BestStream(r.Context(), id)
	if err != nil {
		fail(w, http.StatusNotFound, 3002, "该频道没有可用线路")
		return
	}
	if !strings.Contains(strings.ToLower(st.URL), ".m3u8") {
		fail(w, http.StatusBadRequest, 3002, "该频道是裸 TS 源，请使用 stream.ts")
		return
	}

	res, err := fetch.Get(r.Context(), st.URL, fetch.DefaultOptions())
	if err != nil {
		fail(w, http.StatusBadGateway, 4001, "拉取清单失败: "+err.Error())
		return
	}
	base, _ := url.Parse(res.FinalURL)
	lines := strings.Split(string(res.Body), "\n")
	out := make([]string, 0, len(lines))
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if trimmed == "" {
			out = append(out, line)
			continue
		}
		if strings.HasPrefix(trimmed, "#") {
			// 处理 #EXT-X-KEY / #EXT-X-MAP 里的 URI
			out = append(out, rewriteURIAttr(trimmed, base, a, id, dk))
			continue
		}
		// 分片或子清单
		u, err := base.Parse(trimmed)
		if err != nil {
			out = append(out, line)
			continue
		}
		out = append(out, a.segmentURL(u.String(), id, dk))
	}
	w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Access-Control-Allow-Origin", "*")
	_, _ = io.WriteString(w, strings.Join(out, "\n"))
}

// liveSegment 代理 HLS 分片（含子清单）。
func (a *API) liveSegment(w http.ResponseWriter, r *http.Request) {
	id, _, ok2 := a.verifyStream(r)
	if !ok2 {
		fail(w, http.StatusForbidden, 2002, "播放地址无效或已过期")
		return
	}
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

	req, err := http.NewRequestWithContext(r.Context(), http.MethodGet, target, nil)
	if err != nil {
		fail(w, http.StatusBadGateway, 4001, err.Error())
		return
	}
	req.Header.Set("User-Agent", a.cfg.Stream.UserAgent)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		fail(w, http.StatusBadGateway, 4001, err.Error())
		return
	}
	defer resp.Body.Close()

	ct := resp.Header.Get("Content-Type")
	if ct == "" {
		ct = "video/mp2t"
	}
	w.Header().Set("Content-Type", ct)
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.WriteHeader(resp.StatusCode)
	_, _ = io.Copy(w, resp.Body)
	_ = id
}

func (a *API) segmentURL(abs string, channelID int64, dk string) string {
	exp := time.Now().Add(12 * time.Hour).Unix()
	sig := a.signer.Sign(channelID, exp, dk)
	q := url.Values{}
	q.Set("u", abs)
	q.Set("sig", sig)
	q.Set("exp", strconv.FormatInt(exp, 10))
	q.Set("dk", dk)
	return fmt.Sprintf("%s/live/%d/seg?%s", a.cfg.Server.Prefix, channelID, q.Encode())
}

// rewriteURIAttr 处理 `#EXT-X-KEY:METHOD=AES-128,URI="xxx"` 这类带 URI 属性的标签。
func rewriteURIAttr(line string, base *url.URL, a *API, channelID int64, dk string) string {
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
	return line[:start] + a.segmentURL(u.String(), channelID, dk) + line[start+end:]
}

func boolPtr(b bool) *bool { return &b }
