package api

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"time"

	"github.com/litv/litv/server/internal/config"
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
		"version":  a.version,
		"uptime_s": int(time.Since(a.startAt).Seconds()),
		// 管理台读的是 uptime_seconds，这里同时给两个名字，省得再对一次契约
		"uptime_seconds":  int(time.Since(a.startAt).Seconds()),
		"active_channels": a.hub.Count(),
		"active_streams":  a.hub.Count(),
		"max_concurrent":  a.cfg.Stream.MaxConcurrent,
		"arch":            runtime.GOARCH,
		"port":            a.cfg.Server.Port,
		"gateway_socket":  a.cfg.Server.GatewaySocket,
		"prefix":          a.cfg.Server.Prefix,
		"transcode":       a.cfg.Transcode.Enabled,
		"db_path":         a.cfg.Database.Path,
	}
	// 实际端口可能和配置不同（被占用时自动顺延），TV 要填的是这个
	if a.actualPort > 0 {
		data["actual_port"] = a.actualPort
		data["port_changed"] = a.actualPort != a.cfg.Server.Port
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
		"port":              a.cfg.Server.Port,
		"prefix":            a.cfg.Server.Prefix,
		"max_concurrent":    a.cfg.Stream.MaxConcurrent,
		"ring_size":         a.cfg.Stream.RingSize,
		"transcode":         a.cfg.Transcode.Enabled,
		"transcode_enabled": a.cfg.Transcode.Enabled, // 前端字段名，与 transcode 同义
		"hwaccel":           a.cfg.Transcode.HWAccel,
		"epg_enabled":       a.cfg.EPG.Enabled,
		"epg_refresh_hour":  epgRefreshHour(a.cfg.EPG.RefreshCron),
		"token_hours":       a.cfg.Auth.TokenHours,
		"log_level":         a.cfg.Log.Level,
		"allow_lan":         a.cfg.Server.Host == "0.0.0.0",
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

	// 记录改前的值，用于判断哪些需要重启才生效。
	oldPort := a.cfg.Server.Port
	oldHost := a.cfg.Server.Host
	oldEPGCron := a.cfg.EPG.RefreshCron

	if body.MaxConcurrent != nil {
		a.cfg.Stream.MaxConcurrent = *body.MaxConcurrent
	}
	// 两个字段名同义，前端用 transcode_enabled，老客户端用 transcode
	if body.TranscodeEnabled != nil {
		a.cfg.Transcode.Enabled = *body.TranscodeEnabled
	} else if body.Transcode != nil {
		a.cfg.Transcode.Enabled = *body.Transcode
	}
	if body.Port != nil {
		a.cfg.Server.Port = *body.Port
	}
	if body.AllowLAN != nil {
		if *body.AllowLAN {
			a.cfg.Server.Host = "0.0.0.0"
		} else {
			a.cfg.Server.Host = "127.0.0.1"
		}
	}
	if body.EPGRefreshHour != nil {
		// cron 是 "分 时 日 月 周"；管理台只给一个整点，这里固定为整点触发。
		a.cfg.EPG.RefreshCron = fmt.Sprintf("0 %d * * *", *body.EPGRefreshHour)
	}
	if body.LogLevel != nil {
		lv := config.NormalizeLogLevel(*body.LogLevel)
		a.cfg.Log.Level = lv
		// 日志级别是少数能在运行期真正生效的项。
		config.SetLogLevel(lv)
	}

	// 校验通过的配置要真正落盘，否则重启后全部丢失（这是之前的另一个缺陷）。
	if err := a.cfg.Save(a.cfgPath); err != nil {
		// 落盘失败就不要继续声称已保存：内存里改了但文件没改，
		// 重启后会出现"设置看起来成功、实际回滚"的诡异状态。
		fail(w, http.StatusInternalServerError, 5001, "保存配置失败: "+err.Error())
		return
	}

	// 明确告诉前端哪些字段需要重启，不要用"保存成功"含糊带过。
	restartNeeded := []string{}
	if body.Port != nil && *body.Port != oldPort {
		restartNeeded = append(restartNeeded, "port")
	}
	if body.AllowLAN != nil && a.cfg.Server.Host != oldHost {
		restartNeeded = append(restartNeeded, "allow_lan")
	}
	if body.EPGRefreshHour != nil && a.cfg.EPG.RefreshCron != oldEPGCron {
		restartNeeded = append(restartNeeded, "epg_refresh_hour")
	}

	a.st.Audit(r.Context(), 0, "config.update", "system", strings.Join(restartNeeded, ","), r.RemoteAddr)
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
		Name      string `json:"name"`
		Type      string `json:"type"`
		URL       string `json:"url"`
		Enabled   bool   `json:"enabled"`
		UserAgent string `json:"user_agent"`
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
		UserAgent: strings.TrimSpace(body.UserAgent),
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
		Name      string `json:"name"`
		Type      string `json:"type"`
		URL       string `json:"url"`
		Enabled   bool   `json:"enabled"`
		UserAgent string `json:"user_agent"`
	}
	if err := decode(r, &body); err != nil {
		fail(w, http.StatusBadRequest, 1001, "参数错误: "+err.Error())
		return
	}
	err := a.st.UpdateSource(r.Context(), store.Source{
		ID: id, Name: body.Name, Type: body.Type, URL: body.URL, Enabled: body.Enabled,
		UserAgent: strings.TrimSpace(body.UserAgent),
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
	// 用源专属 UA：不少源站校验 UA，不带就 403 或返回空列表
	opt := fetch.DefaultOptions()
	if ua := strings.TrimSpace(src.UserAgent); ua != "" {
		opt.UserAgent = ua
	}
	res, err := fetch.Get(ctx, src.URL, opt)
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
		sub, err := fetch.Get(ctx, items[0].URL, opt)
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
		Group:    r.URL.Query().Get("group"),
		Keyword:  r.URL.Query().Get("q"),
		Enabled:  queryBool(r, "enabled"),
		SourceID: int64(queryInt(r, "source_id", 0)),
	}
	// 分页参数兼容两套命名：管理台传 page/size，老脚本传 offset/limit。
	//
	// 以前只认 offset/limit，而管理台传的是 page/size —— 两个都被忽略，
	// 于是永远返回第一页 50 条：用户看到「只有 50 个台」，翻页也没反应。
	size := queryInt(r, "size", 0)
	if size <= 0 {
		size = queryInt(r, "limit", 50)
	}
	offset := queryInt(r, "offset", 0)
	if offset <= 0 {
		if page := queryInt(r, "page", 1); page > 1 {
			if size <= 0 {
				size = 50
			}
			offset = (page - 1) * size
		}
	}
	q.Limit = size
	q.Offset = offset

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
	// 补齐管理台要的字段。
	//
	// 以前直接把 store.EPGSource 原样返回，字段名是 last_sync / last_error，
	// 而面板读的是 last_refresh_at / error / channel_count / program_count。
	// 结果：明明刷新过，界面也永远显示「还没拉过」，频道数节目条数永远是「—」，
	// 状态永远是「正常」（错误信息根本没被展示）。
	stats, _ := a.st.EPGSourceStats(r.Context())
	out := make([]map[string]any, 0, len(items))
	for _, s := range items {
		st := stats[s.ID]
		out = append(out, map[string]any{
			"id":      s.ID,
			"name":    s.Name,
			"url":     s.URL,
			"enabled": s.Enabled,
			// 管理台实际读取的字段名
			"last_refresh_at": s.LastSync,
			"last_attempt_at": s.LastAttempt,
			"channel_count":   st.Channels,
			"program_count":   st.Programs,
			"error":           s.LastError,
			// 兼容旧字段名（外部脚本可能在用）
			"last_sync":  s.LastSync,
			"last_error": s.LastError,
		})
	}
	ok(w, out)
}

func (a *API) createEPGSource(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Name string `json:"name"`
		URL  string `json:"url"`
		// 管理台表单会带 enabled。服务端建源时一律启用（enabled=1），
		// 但这个字段必须能收下——decode 用了 DisallowUnknownFields，
		// 多一个字段就直接 400「unknown field "enabled"」。
		Enabled *bool `json:"enabled"`
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
	// 同步完立刻按频道名自动匹配一次：源 M3U 大多没有 tvg-id，
	// 不自动匹配的话 channels.epg_id 全空，电视端节目单永远是空的。
	_, _ = a.st.AutoMapChannels(r.Context())
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
	// R17：递增 config_version，终端心跳据此发现配置变了。
	// 回传新版本，管理台能显示"已下发，版本 N"，
	// 而不是只说"已保存"——那会让用户以为已经生效。
	ver, _, err := a.st.SetDeviceConfigVersioned(r.Context(), id, string(b))
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			fail(w, http.StatusNotFound, 3001, "设备不存在")
			return
		}
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	a.st.Audit(r.Context(), 0, "device.config", fmt.Sprint(id), string(b), r.RemoteAddr)
	ok(w, map[string]any{
		"saved":          true,
		"config_version": ver,
		"note":           "终端将在下次心跳时拉取新配置",
	})
}

// ---------- 播放质量统计 ----------

func (a *API) statsOverview(w http.ResponseWriter, r *http.Request) {
	days := queryInt(r, "days", 7)
	st, err := a.st.StatsOverview(r.Context(), days)
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	ok(w, st)
}

func (a *API) statsChannels(w http.ResponseWriter, r *http.Request) {
	days := queryInt(r, "days", 7)
	limit := queryInt(r, "limit", 20)
	items, err := a.st.StatsByChannel(r.Context(), days, limit)
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	ok(w, items)
}

// ---------- 录制中心 ----------

func (a *API) listRecordings(w http.ResponseWriter, r *http.Request) {
	items, err := a.st.ListRecordings(r.Context(), r.URL.Query().Get("status"), queryInt(r, "limit", 100))
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	total, bytes, _ := a.st.RecordingStats(r.Context())
	ok(w, map[string]any{
		"items":       items,
		"total_files": total,
		"total_bytes": bytes,
		"available":   a.rec != nil && a.rec.Available(),
	})
}

// createRecording 新建录制任务。
// 立即录制：start_at 留空（默认现在），duration_min 必填。
// 预约录制：start_at 传 EPG 里的节目开始时间。
func (a *API) createRecording(w http.ResponseWriter, r *http.Request) {
	var body struct {
		ChannelID   int64  `json:"channel_id"`
		Name        string `json:"name"`
		StartAt     int64  `json:"start_at"`
		DurationMin int    `json:"duration_min"`
		// DurationSec 优先于 DurationMin，方便脚本/测试精确控制
		DurationSec int `json:"duration_sec"`
	}
	if err := decode(r, &body); err != nil {
		fail(w, http.StatusBadRequest, 1001, "参数错误: "+err.Error())
		return
	}
	if body.ChannelID <= 0 {
		fail(w, http.StatusBadRequest, 1001, "channel_id 不能为空")
		return
	}
	if a.rec == nil || !a.rec.Available() {
		fail(w, http.StatusServiceUnavailable, 5001, "录制不可用：服务端未配置 ffmpeg")
		return
	}
	durSec := body.DurationSec
	if durSec <= 0 {
		if body.DurationMin <= 0 {
			body.DurationMin = 30
		}
		durSec = body.DurationMin * 60
	}
	if durSec < 5 {
		fail(w, http.StatusBadRequest, 1001, "录制时长至少 5 秒")
		return
	}
	if durSec > 8*3600 {
		fail(w, http.StatusBadRequest, 1001, "单次录制最长 8 小时")
		return
	}
	start := body.StartAt
	if start <= 0 {
		start = time.Now().Unix()
	}
	if start < time.Now().Unix()-60 {
		fail(w, http.StatusBadRequest, 1001, "开始时间已过去")
		return
	}

	name := body.Name
	if name == "" {
		if ch, err := a.st.GetChannel(r.Context(), body.ChannelID); err == nil {
			name = ch.Name + " " + time.Unix(start, 0).Format("01-02 15:04")
		} else {
			name = "录制 " + time.Unix(start, 0).Format("01-02 15:04")
		}
	}

	id, err := a.st.CreateRecording(r.Context(), store.Recording{
		ChannelID: body.ChannelID,
		Name:      name,
		StartAt:   start,
		EndAt:     start + int64(durSec),
	})
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	a.st.Audit(r.Context(), 0, "record.create", fmt.Sprint(id), name, r.RemoteAddr)
	ok(w, map[string]any{"id": id, "start_at": start, "end_at": start + int64(durSec)})
}

func (a *API) deleteRecording(w http.ResponseWriter, r *http.Request) {
	id := pathInt64(r, "id")
	rec, err := a.st.GetRecording(r.Context(), id)
	if err != nil {
		fail(w, http.StatusNotFound, 3001, "录制不存在")
		return
	}
	if a.rec != nil {
		a.rec.Stop(id)
	}
	// 只删我们自己的录制文件，不碰目录里的其它东西
	if rec.Path != "" && strings.HasPrefix(filepath.Clean(rec.Path), filepath.Clean(a.cfg.Record.Dir)) {
		_ = os.Remove(rec.Path)
	}
	if err := a.st.DeleteRecording(r.Context(), id); err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	ok(w, map[string]any{"deleted": true})
}

func (a *API) stopRecording(w http.ResponseWriter, r *http.Request) {
	id := pathInt64(r, "id")
	if a.rec == nil {
		fail(w, http.StatusServiceUnavailable, 5001, "录制未启用")
		return
	}
	a.rec.Stop(id)
	ok(w, map[string]any{"stopped": true})
}

// recordingFile 把录制文件吐给客户端。
// 用 http.ServeFile 而不是自己读：它自带 Range 支持，播放器才能拖动进度条。
func (a *API) recordingFile(w http.ResponseWriter, r *http.Request) {
	id := pathInt64(r, "id")
	rec, err := a.st.GetRecording(r.Context(), id)
	if err != nil {
		fail(w, http.StatusNotFound, 3001, "录制不存在")
		return
	}
	if rec.Path == "" {
		fail(w, http.StatusNotFound, 3001, "录制文件还没生成")
		return
	}
	if _, err := os.Stat(rec.Path); err != nil {
		fail(w, http.StatusNotFound, 3001, "录制文件不存在")
		return
	}
	w.Header().Set("Content-Type", "video/mp2t")
	w.Header().Set("Content-Disposition",
		fmt.Sprintf("inline; filename=%q", filepath.Base(rec.Path)))
	http.ServeFile(w, r, rec.Path)
}

// webPlay 给管理台发一个短期签名播放地址（浏览器里直接看直播）。
//
// 为什么不让管理台直接用设备 token 那套：管理台走的是飞牛网关，
// 身份是 fnOS 用户，不是设备。这里复用同一套 HMAC 签名，
// 但把有效期压到 2 小时、并且绑定一个固定的 "web" 标识。
func (a *API) webPlay(w http.ResponseWriter, r *http.Request) {
	id := pathInt64(r, "id")
	ch, err := a.st.GetChannel(r.Context(), id)
	if err != nil {
		fail(w, http.StatusNotFound, 3001, "频道不存在")
		return
	}
	st, err := a.st.BestStream(r.Context(), id)
	if err != nil {
		fail(w, http.StatusNotFound, 3002, "该频道没有可用线路")
		return
	}

	// 浏览器只能播 HLS：上游是 m3u8 就直接用，是裸 TS 就走转封装
	kind := "hls"
	if !strings.Contains(strings.ToLower(st.URL), ".m3u8") {
		if a.rx == nil || !a.rx.Available() {
			fail(w, http.StatusServiceUnavailable, 5001,
				"该频道是裸 TS 源，浏览器播不了。请服务端配置 ffmpeg 开启转封装。")
			return
		}
		kind = "remux"
	}

	exp := time.Now().Add(2 * time.Hour).Unix()
	sig := a.signer.Sign(id, exp, webDeviceKey)
	q := url.Values{}
	q.Set("sig", sig)
	q.Set("exp", strconv.FormatInt(exp, 10))
	q.Set("dk", webDeviceKey)

	path := fmt.Sprintf("%s/live/%d/index.m3u8", a.cfg.Server.Prefix, id)
	if kind == "remux" {
		path = fmt.Sprintf("%s/live/%d/remux.m3u8", a.cfg.Server.Prefix, id)
	}

	ok(w, map[string]any{
		"channel": map[string]any{"id": ch.ID, "name": ch.Name, "logo": ch.Logo},
		"kind":    kind,
		"url":     path + "?" + q.Encode(),
		"expires": exp,
	})
}

// webDeviceKey 是管理台播放用的固定标识（签名里带上，便于日志区分）。
const webDeviceKey = "web-admin"

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
