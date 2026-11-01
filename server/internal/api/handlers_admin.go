package api

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/litv/litv/server/internal/auth"
	"github.com/litv/litv/server/internal/epg"
	"github.com/litv/litv/server/internal/fetch"
	"github.com/litv/litv/server/internal/parse"
	"github.com/litv/litv/server/internal/probe"
	"github.com/litv/litv/server/internal/store"
)

// ---------- 系统 ----------

func (a *API) health(w http.ResponseWriter, r *http.Request) {
	ok(w, map[string]any{
		"version": a.version,
		"uptime":  int(time.Since(a.startAt).Seconds()),
	})
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
		"version":         a.version,
		"uptime_s":        int(time.Since(a.startAt).Seconds()),
		"active_channels": a.hub.Count(),
		"max_concurrent":  a.cfg.Stream.MaxConcurrent,
		"port":            a.cfg.Server.Port,
		"gateway_socket":  a.cfg.Server.GatewaySocket,
		"prefix":          a.cfg.Server.Prefix,
		"transcode":       a.cfg.Transcode.Enabled,
		"db_path":         a.cfg.Database.Path,
	}
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
		"hint": "日志文件位于 $TRIM_PKGVAR/litv.log",
		"lines": []string{},
	})
}

func (a *API) getConfig(w http.ResponseWriter, r *http.Request) {
	// 只暴露可安全展示的字段，绝不返回 secret / 密码
	ok(w, map[string]any{
		"port":               a.cfg.Server.Port,
		"prefix":             a.cfg.Server.Prefix,
		"max_concurrent":     a.cfg.Stream.MaxConcurrent,
		"ring_size":          a.cfg.Stream.RingSize,
		"transcode":          a.cfg.Transcode.Enabled,
		"transcode_enabled":  a.cfg.Transcode.Enabled, // 前端字段名，与 transcode 同义
		"hwaccel":            a.cfg.Transcode.HWAccel,
		"epg_enabled":        a.cfg.EPG.Enabled,
		"epg_refresh_hour":   epgRefreshHour(a.cfg.EPG.RefreshCron),
		"token_hours":        a.cfg.Auth.TokenHours,
		"log_level":          a.cfg.Log.Level,
		"allow_lan":          a.cfg.Server.Host == "0.0.0.0",
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
	var body struct {
		MaxConcurrent    *int  `json:"max_concurrent"`
		Transcode        *bool `json:"transcode"`
		TranscodeEnabled *bool `json:"transcode_enabled"`
	}
	if err := decode(r, &body); err != nil {
		fail(w, http.StatusBadRequest, 1001, "参数错误: "+err.Error())
		return
	}
	// M1：运行期可改的只有这几项；改端口需要写配置文件 + 重启，放到后续版本
	if body.MaxConcurrent != nil && *body.MaxConcurrent > 0 {
		a.cfg.Stream.MaxConcurrent = *body.MaxConcurrent
	}
	// 两个字段名同义，前端用 transcode_enabled，老客户端用 transcode
	if body.TranscodeEnabled != nil {
		a.cfg.Transcode.Enabled = *body.TranscodeEnabled
	} else if body.Transcode != nil {
		a.cfg.Transcode.Enabled = *body.Transcode
	}
	a.st.Audit(r.Context(), 0, "config.update", "system", "", r.RemoteAddr)
	ok(w, map[string]any{"saved": true, "note": "端口等配置需修改 config.yaml 后重启生效"})
}

func (a *API) activeStreams(w http.ResponseWriter, r *http.Request) {
	ok(w, a.hub.Stats())
}

// runProbe 手动触发一轮源探测（管理台「立即检测所有源」按钮）。
// 探测是并发 + 只读前 128KB，不会把带宽打满，但也不要频繁点。
func (a *API) runProbe(w http.ResponseWriter, r *http.Request) {
	pr := probe.New(a.st,
		time.Duration(a.cfg.Probe.TimeoutSec)*time.Second,
		a.cfg.Probe.Concurrency,
		a.cfg.Stream.UserAgent)
	res, err := pr.RunOnce(r.Context())
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	a.st.Audit(r.Context(), 0, "probe.run", "streams",
		formatErr("检测 %d 条，可用 %d，失效 %d", res.Checked, res.Healthy, res.Failed), r.RemoteAddr)
	ok(w, res)
}

// ---------- 源管理 ----------

func (a *API) listSources(w http.ResponseWriter, r *http.Request) {
	items, err := a.st.ListSources(r.Context())
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	ok(w, items)
}

func (a *API) createSource(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Name    string `json:"name"`
		Type    string `json:"type"`
		URL     string `json:"url"`
		Enabled bool   `json:"enabled"`
	}
	if err := decode(r, &body); err != nil {
		fail(w, http.StatusBadRequest, 1001, "参数错误: "+err.Error())
		return
	}
	if strings.TrimSpace(body.URL) == "" {
		fail(w, http.StatusBadRequest, 1001, "源地址不能为空")
		return
	}
	if body.Type == "" {
		body.Type = "auto"
	}
	id, err := a.st.CreateSource(r.Context(), store.Source{
		Name: body.Name, Type: body.Type, URL: body.URL, Enabled: true,
	})
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	ok(w, map[string]any{"id": id})
}

func (a *API) updateSource(w http.ResponseWriter, r *http.Request) {
	id := pathInt64(r, "id")
	var body struct {
		Name    string `json:"name"`
		Type    string `json:"type"`
		URL     string `json:"url"`
		Enabled bool   `json:"enabled"`
	}
	if err := decode(r, &body); err != nil {
		fail(w, http.StatusBadRequest, 1001, "参数错误: "+err.Error())
		return
	}
	err := a.st.UpdateSource(r.Context(), store.Source{
		ID: id, Name: body.Name, Type: body.Type, URL: body.URL, Enabled: body.Enabled,
	})
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	ok(w, map[string]any{"updated": true})
}

func (a *API) deleteSource(w http.ResponseWriter, r *http.Request) {
	id := pathInt64(r, "id")
	if err := a.st.DeleteSource(r.Context(), id); err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	a.st.Audit(r.Context(), 0, "source.delete", fmt.Sprint(id), "", r.RemoteAddr)
	ok(w, map[string]any{"deleted": true})
}

func (a *API) refreshSource(w http.ResponseWriter, r *http.Request) {
	id := pathInt64(r, "id")
	srcs, err := a.st.ListSources(r.Context())
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	var target *store.Source
	for i := range srcs {
		if srcs[i].ID == id {
			target = &srcs[i]
			break
		}
	}
	if target == nil {
		fail(w, http.StatusNotFound, 3001, "源不存在")
		return
	}

	items, err := a.fetchAndParse(r.Context(), *target)
	if err != nil {
		_ = a.st.MarkSourceFetched(r.Context(), id, 0, err.Error())
		fail(w, http.StatusBadGateway, 4001, "拉取失败: "+err.Error())
		return
	}

	channels := make([]store.Channel, 0, len(items))
	for _, it := range items {
		channels = append(channels, store.Channel{
			Name: it.Name, Group: it.Group, Logo: it.Logo, TvgID: it.TvgID,
			Number: it.Number, Enabled: true, PendingURL: it.URL,
		})
	}
	inserted, err := a.st.ImportChannels(r.Context(), id, channels)
	if err != nil {
		_ = a.st.MarkSourceFetched(r.Context(), id, 0, err.Error())
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	_ = a.st.MarkSourceFetched(r.Context(), id, len(items), "")
	a.st.Audit(r.Context(), 0, "source.refresh", fmt.Sprint(id), fmt.Sprintf("解析 %d 条，新增 %d 个频道", len(items), inserted), r.RemoteAddr)
	ok(w, map[string]any{"parsed": len(items), "inserted": inserted})
}

func (a *API) previewSource(w http.ResponseWriter, r *http.Request) {
	id := pathInt64(r, "id")
	srcs, err := a.st.ListSources(r.Context())
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	var target *store.Source
	for i := range srcs {
		if srcs[i].ID == id {
			target = &srcs[i]
			break
		}
	}
	if target == nil {
		fail(w, http.StatusNotFound, 3001, "源不存在")
		return
	}
	items, err := a.fetchAndParse(r.Context(), *target)
	if err != nil {
		fail(w, http.StatusBadGateway, 4001, "拉取失败: "+err.Error())
		return
	}
	limit := queryInt(r, "limit", 50)
	if limit > 500 {
		limit = 500
	}
	if len(items) > limit {
		items = items[:limit]
	}
	ok(w, map[string]any{
		"total":  len(items),
		"groups": parse.Stats(items),
		"items":  parse.SortByGroup(items),
	})
}

// fetchAndParse 是「抓取 + 解析」的公共流程。
func (a *API) fetchAndParse(ctx context.Context, src store.Source) ([]parse.Item, error) {
	res, err := fetch.Get(ctx, src.URL, fetch.DefaultOptions())
	if err != nil {
		return nil, err
	}
	typ := src.Type
	if typ == "" || typ == "auto" {
		typ = fetch.GuessType(res.FinalURL, res.ContentType)
		if typ == "" {
			typ = parse.Detect(res.Body)
		}
	}
	items, err := parse.Parse(res.Body, typ)
	if err != nil {
		return nil, err
	}
	// TVBox 形态 A 的 lives 指向外部 m3u/txt：这里再抓一层（只跟一层，防循环）
	if typ == "tvbox" && len(items) > 0 && allExternal(items) {
		sub, err := fetch.Get(ctx, items[0].URL, fetch.DefaultOptions())
		if err == nil {
			if subItems, err2 := parse.Parse(sub.Body, parse.Detect(sub.Body)); err2 == nil && len(subItems) > 0 {
				return subItems, nil
			}
		}
	}
	return items, nil
}

func allExternal(items []parse.Item) bool {
	for _, it := range items {
		if !strings.Contains(it.URL, "://") || it.Group == "" {
			return false
		}
	}
	// 只有一条且名字像"外部源"，认为是 lives 形态 A
	return len(items) == 1
}

// ---------- 频道 ----------

func (a *API) listChannels(w http.ResponseWriter, r *http.Request) {
	q := store.ChannelQuery{
		Group:   r.URL.Query().Get("group"),
		Keyword: r.URL.Query().Get("q"),
		Enabled: queryBool(r, "enabled"),
		Offset:  queryInt(r, "offset", 0),
		Limit:   queryInt(r, "limit", 50),
	}
	items, total, err := a.st.ListChannels(r.Context(), q)
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	ok(w, map[string]any{"total": total, "items": items})
}

func (a *API) listGroups(w http.ResponseWriter, r *http.Request) {
	items, err := a.st.ListGroups(r.Context())
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	ok(w, items)
}

func (a *API) createChannel(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Name    string `json:"name"`
		Group   string `json:"group"`
		Logo    string `json:"logo"`
		TvgID   string `json:"tvg_id"`
		Number  int    `json:"number"`
		Enabled bool   `json:"enabled"`
	}
	if err := decode(r, &body); err != nil {
		fail(w, http.StatusBadRequest, 1001, "参数错误: "+err.Error())
		return
	}
	if body.Group == "" {
		body.Group = "未分组"
	}
	id, err := a.st.CreateChannel(r.Context(), store.Channel{
		Name: body.Name, Group: body.Group, Logo: body.Logo, TvgID: body.TvgID,
		Number: body.Number, Enabled: body.Enabled,
	})
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	ok(w, map[string]any{"id": id})
}

func (a *API) updateChannel(w http.ResponseWriter, r *http.Request) {
	id := pathInt64(r, "id")
	var body struct {
		Name    string `json:"name"`
		Group   string `json:"group"`
		Logo    string `json:"logo"`
		TvgID   string `json:"tvg_id"`
		Number  int    `json:"number"`
		Enabled bool   `json:"enabled"`
	}
	if err := decode(r, &body); err != nil {
		fail(w, http.StatusBadRequest, 1001, "参数错误: "+err.Error())
		return
	}
	err := a.st.UpdateChannel(r.Context(), store.Channel{
		ID: id, Name: body.Name, Group: body.Group, Logo: body.Logo,
		TvgID: body.TvgID, Number: body.Number, Enabled: body.Enabled,
	})
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	ok(w, map[string]any{"updated": true})
}

func (a *API) deleteChannel(w http.ResponseWriter, r *http.Request) {
	if err := a.st.DeleteChannel(r.Context(), pathInt64(r, "id")); err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	ok(w, map[string]any{"deleted": true})
}

func (a *API) reorderChannels(w http.ResponseWriter, r *http.Request) {
	var body struct {
		IDs []int64 `json:"ids"`
	}
	if err := decode(r, &body); err != nil {
		fail(w, http.StatusBadRequest, 1001, "参数错误: "+err.Error())
		return
	}
	if err := a.st.ReorderChannels(r.Context(), body.IDs); err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	ok(w, map[string]any{"reordered": len(body.IDs)})
}

func (a *API) batchChannels(w http.ResponseWriter, r *http.Request) {
	var body struct {
		IDs    []int64 `json:"ids"`
		Action string  `json:"action"`
		Value  string  `json:"value"`
	}
	if err := decode(r, &body); err != nil {
		fail(w, http.StatusBadRequest, 1001, "参数错误: "+err.Error())
		return
	}
	n, err := a.st.BatchChannels(r.Context(), body.IDs, body.Action, body.Value)
	if err != nil {
		fail(w, http.StatusBadRequest, 1001, err.Error())
		return
	}
	ok(w, map[string]any{"affected": n})
}

func (a *API) listChannelStreams(w http.ResponseWriter, r *http.Request) {
	items, err := a.st.ListStreams(r.Context(), pathInt64(r, "id"))
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	ok(w, items)
}

func (a *API) addChannelStream(w http.ResponseWriter, r *http.Request) {
	id := pathInt64(r, "id")
	var body struct {
		URL      string `json:"url"`
		Quality  string `json:"quality"`
		Priority int    `json:"priority"`
	}
	if err := decode(r, &body); err != nil {
		fail(w, http.StatusBadRequest, 1001, "参数错误: "+err.Error())
		return
	}
	sid, err := a.st.AddStream(r.Context(), id, body.URL, body.Quality, body.Priority)
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	ok(w, map[string]any{"id": sid})
}

func (a *API) deleteChannelStream(w http.ResponseWriter, r *http.Request) {
	if err := a.st.DeleteStream(r.Context(), pathInt64(r, "sid")); err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	ok(w, map[string]any{"deleted": true})
}

func (a *API) mapChannelEPG(w http.ResponseWriter, r *http.Request) {
	id := pathInt64(r, "id")
	var body struct {
		EpgID string `json:"epg_id"`
	}
	if err := decode(r, &body); err != nil {
		fail(w, http.StatusBadRequest, 1001, "参数错误: "+err.Error())
		return
	}
	if err := a.st.SetChannelEpgID(r.Context(), id, body.EpgID); err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	ok(w, map[string]any{"mapped": true})
}

// ---------- EPG ----------

func (a *API) listEPGSources(w http.ResponseWriter, r *http.Request) {
	items, err := a.st.ListEPGSources(r.Context())
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	ok(w, items)
}

func (a *API) createEPGSource(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Name string `json:"name"`
		URL  string `json:"url"`
	}
	if err := decode(r, &body); err != nil {
		fail(w, http.StatusBadRequest, 1001, "参数错误: "+err.Error())
		return
	}
	id, err := a.st.CreateEPGSource(r.Context(), body.Name, body.URL)
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	ok(w, map[string]any{"id": id})
}

func (a *API) deleteEPGSource(w http.ResponseWriter, r *http.Request) {
	if err := a.st.DeleteEPGSource(r.Context(), pathInt64(r, "id")); err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	ok(w, map[string]any{"deleted": true})
}

func (a *API) listEPGChannels(w http.ResponseWriter, r *http.Request) {
	items, err := a.st.ListEPGChannels(r.Context())
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	ok(w, items)
}

func (a *API) refreshEPG(w http.ResponseWriter, r *http.Request) {
	srcs, err := a.st.ListEPGSources(r.Context())
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	type result struct {
		ID       int64  `json:"id"`
		Channels int    `json:"channels"`
		Programs int    `json:"programs"`
		Error    string `json:"error,omitempty"`
	}
	results := make([]result, 0, len(srcs))
	for _, src := range srcs {
		if !src.Enabled {
			continue
		}
		r0 := result{ID: src.ID}
		res, ferr := fetch.Get(r.Context(), src.URL, fetch.DefaultOptions())
		if ferr != nil {
			r0.Error = ferr.Error()
			_ = a.st.MarkEPGSynced(r.Context(), src.ID, ferr.Error())
			results = append(results, r0)
			continue
		}
		parsed, perr := epg.Parse(strings.NewReader(string(parse.ToUTF8(res.Body))), 200000)
		if perr != nil && (parsed == nil || len(parsed.Programmes) == 0) {
			r0.Error = perr.Error()
			_ = a.st.MarkEPGSynced(r.Context(), src.ID, perr.Error())
			results = append(results, r0)
			continue
		}
		idMap := map[string]int64{}
		for _, ch := range parsed.Channels {
			id, e := a.st.UpsertEPGChannel(r.Context(), src.ID, ch.ID, ch.DisplayName, ch.Icon)
			if e == nil {
				idMap[ch.ID] = id
			}
		}
		byChannel := map[int64][]store.EPGProgram{}
		for _, p := range parsed.Programmes {
			cid, ok := idMap[p.ChannelID]
			if !ok {
				continue
			}
			byChannel[cid] = append(byChannel[cid], store.EPGProgram{
				StartAt: p.Start, EndAt: p.Stop, Title: p.Title, Description: p.Description, Category: p.Category,
			})
		}
		for cid, progs := range byChannel {
			_ = a.st.ReplacePrograms(r.Context(), cid, progs)
			r0.Programs += len(progs)
		}
		r0.Channels = len(parsed.Channels)
		_ = a.st.MarkEPGSynced(r.Context(), src.ID, "")
		results = append(results, r0)
	}
	ok(w, results)
}

// ---------- 设备 ----------

func (a *API) listDevices(w http.ResponseWriter, r *http.Request) {
	items, err := a.st.ListDevices(r.Context(), r.URL.Query().Get("status"))
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	now := time.Now().Unix()
	// 转成前端契约里的视图：补 online / last_heartbeat_at / ip / app_version，
	// 并且不回传 device_key（那是客户端的稳定标识，属于敏感信息）。
	out := make([]map[string]any, 0, len(items))
	for _, d := range items {
		var cfg map[string]any
		if d.ConfigJSON != "" && d.ConfigJSON != "{}" {
			_ = json.Unmarshal([]byte(d.ConfigJSON), &cfg)
		}
		online := d.Status == "bound" && now-d.LastSeen <= 90
		out = append(out, map[string]any{
			"id":                d.ID,
			"name":              d.Name,
			"platform":          d.Platform,
			"model":             d.Model,
			"status":            d.Status,
			"online":            online,
			"last_heartbeat_at": d.LastSeen,
			"last_seen":         d.LastSeen,
			"bound_at":          d.BoundAt,
			"created_at":        d.CreatedAt,
			"ip":                "",
			"app_version":       "",
			"config":            cfg,
		})
	}
	ok(w, out)
}

func (a *API) createBindCode(w http.ResponseWriter, r *http.Request) {
	var body struct {
		DeviceID int64 `json:"device_id"`
	}
	_ = decode(r, &body)
	code := randomCode(6)
	if err := a.st.CreateBindCode(r.Context(), code, body.DeviceID, 300); err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	ok(w, map[string]any{"code": code, "expires_in": 300})
}

func (a *API) approveDevice(w http.ResponseWriter, r *http.Request) {
	id := pathInt64(r, "id")
	if err := a.st.ApproveDevice(r.Context(), id); err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	a.st.Audit(r.Context(), 0, "device.approve", fmt.Sprint(id), "", r.RemoteAddr)
	ok(w, map[string]any{"approved": true})
}

func (a *API) rejectDevice(w http.ResponseWriter, r *http.Request) {
	if err := a.st.RevokeDevice(r.Context(), pathInt64(r, "id")); err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	ok(w, map[string]any{"rejected": true})
}

func (a *API) revokeDevice(w http.ResponseWriter, r *http.Request) {
	if err := a.st.RevokeDevice(r.Context(), pathInt64(r, "id")); err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	ok(w, map[string]any{"revoked": true})
}

func (a *API) setDeviceConfig(w http.ResponseWriter, r *http.Request) {
	id := pathInt64(r, "id")
	var body map[string]any
	if err := decode(r, &body); err != nil {
		fail(w, http.StatusBadRequest, 1001, "参数错误: "+err.Error())
		return
	}
	b, _ := json.Marshal(body)
	if err := a.st.SetDeviceConfig(r.Context(), id, string(b)); err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	ok(w, map[string]any{"saved": true})
}

// ---------- 密码登录（网关不可用时的应急入口） ----------

func (a *API) passwordLogin(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Password string `json:"password"`
	}
	if err := decode(r, &body); err != nil {
		fail(w, http.StatusBadRequest, 1001, "参数错误")
		return
	}
	if a.cfg.Auth.AdminPassword == "" || body.Password != a.cfg.Auth.AdminPassword {
		fail(w, http.StatusUnauthorized, 2001, "密码错误")
		return
	}
	plain, hash, err := auth.NewToken()
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	// 应急登录复用 device 表，platform=admin
	id, err := a.st.CreateDevice(r.Context(), "admin-"+plain[:8], hash, "应急管理员", "admin", "server")
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	_ = a.st.ApproveDevice(r.Context(), id)
	ok(w, map[string]any{"token": plain})
}

func randomCode(n int) string {
	// 用 crypto/rand，避免时间种子溢出导致负下标 panic，同时防止绑定码被预测。
	const digits = "0123456789"
	buf := make([]byte, n)
	if _, err := rand.Read(buf); err != nil {
		// 极端情况下退化为时间取模，仍然可用
		seed := time.Now().UnixNano()
		for i := range buf {
			buf[i] = digits[int(seed%10)]
			seed /= 10
		}
		return string(buf)
	}
	out := make([]byte, n)
	for i, v := range buf {
		out[i] = digits[int(v)%len(digits)]
	}
	return string(out)
}
