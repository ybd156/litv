package api

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"time"

	"github.com/litv/litv/server/internal/config"
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
	// 补齐管理台实际读取的字段名。
	//
	// 以前直接把 store.Source 原样返回：字段叫 last_fetch_at / last_error，
	// 而 SourcesView 读的是 error / status。于是「失败原因」永远不显示，
	// 概览页的「失效源」计数也恒为 0——用户看到的是「一切正常」，
	// 实际上是所有源都拉不到。
	//
	// 与 listEPGSources 同一套做法：**保留旧键**（外部脚本可能在用），
	// 同时新增视图键。新增键不与旧键重名，所以向后兼容。
	//
	// status 的取值域严格是三个值（K-2/J-3）：
	//   ok    有拉取成功的记录，且当前没有错误
	//   error last_error 非空（错误优先展示）
	//   never 从未拉取过（last_fetch_at == 0）
	// 「正在刷新」**不**进 status —— 它是行级 refreshing 布尔，唯一来源。
	// 两个表示并存会让前端的 statusOf() 命中其中一个，
	// 界面上就会出现"状态=正常 + 正在刷新"这种自相矛盾的组合。
	out := make([]map[string]any, 0, len(items))
	for _, s := range items {
		status := "ok"
		switch {
		case s.LastError != "":
			status = "error"
		case s.LastFetchAt == 0:
			status = "never"
		}
		row := map[string]any{
			"id":            s.ID,
			"name":          s.Name,
			"type":          s.Type,
			"url":           s.URL,
			"enabled":       s.Enabled,
			"channel_count": s.ChannelCount,
			"user_agent":    s.UserAgent,
			"created_at":    s.CreatedAt,
			// K-6：解析条数与库内真数分开，前端文案取 channel_count。
			"parsed_count": s.ParsedCount,
			// 管理台读取的视图键
			"error":      s.LastError,
			"status":     status,
			"refreshing": a.isRefreshing(s.ID),
			// 兼容旧键。时间口径保持整数秒（Unix 秒）。
			"last_fetch_at": s.LastFetchAt,
			"last_error":    s.LastError,
		}
		// last_result：上一次刷新的结果（五键 + 两个标志）。
		// 解析失败就只是不带这个键，不编一个空对象骗前端。
		if s.LastResultJSON != "" {
			var lr store.ImportStats
			if jerr := json.Unmarshal([]byte(s.LastResultJSON), &lr); jerr == nil {
				row["last_result"] = lr
			}
		}
		out = append(out, row)
	}
	ok(w, out)
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
	if id <= 0 {
		fail(w, http.StatusBadRequest, 1001, "源 id 非法")
		return
	}
	// 数一下会被连带删掉多少频道。
	//
	// 为什么要数：删除源的确认文案必须说得出"会一并删除 N 个频道"，
	// 否则用户只能看到一句没有数字的"确定吗"。
	// 响应里也把它带回去，前端可以核对文案与结果是否一致。
	//
	// 注意（已知问题，见 store.DeleteSource 的注释）：这里统计的是
	// "source_id 指向该源的频道数"，而由于 ImportChannels 的判重键不含
	// source_id，其中可能包含别的源也在用的频道——那个连带删除的语义
	// 要等 streams.source_id 迁移后才修得掉。
	count, _ := a.st.CountChannelsBySource(r.Context(), id)

	if err := a.st.DeleteSource(r.Context(), id); err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	a.st.Audit(r.Context(), 0, "source.delete", fmt.Sprint(id),
		fmt.Sprintf("连带删除 %d 个频道", count), peerIPFrom(r.Context()))
	ok(w, map[string]any{"deleted": true, "channels_deleted": count})
}

// refreshSource 触发一次源刷新（A2：异步 + 单飞）。
//
// 契约变更（第二批）：
//   - 旧：同步跑完，200 + {parsed, inserted}；
//   - 新：立刻 202 + {status:"running"}，结果去 GET /sources 每行的 last_result 看。
//
// 为什么必须异步：一次大源的抓取+解析+对齐要几十秒到几分钟。
// 同步 handler 会让管理台一直转圈，用户自然会连点几次——每次都是
// 一个完整的写事务，SQLite 的写又是串行的，于是 TV 端的小写操作
// （play_events / watch_progress）全排到 busy_timeout 上。
// 用户描述里的"刷新时反而卡死"就是这么来的。
func (a *API) refreshSource(w http.ResponseWriter, r *http.Request) {
	id := pathInt64(r, "id")
	if id <= 0 {
		fail(w, http.StatusBadRequest, 1001, "源 id 非法")
		return
	}
	src, err := a.st.GetSource(r.Context(), id)
	if err != nil {
		// 404 而不是 500：这个 id 根本不存在
		fail(w, http.StatusNotFound, 3001, "源不存在")
		return
	}

	started, busy := a.startSourceRefresh(r.Context(), *src)
	if busy {
		// 全局只有一个写槽：别的源正在刷新时立刻告诉客户端等一会儿，
		// 而不是让他排队（排队等于把写锁争用拉长）。
		w.Header().Set("Retry-After", "5")
		fail(w, http.StatusTooManyRequests, 2005, "已有刷新正在进行，请 5 秒后重试")
		return
	}

	// started=false 且 busy=false：这个源已经在刷（单飞，不排队、不重跑）。
	// 两种情况对客户端是同一件事——"正在跑，去轮询结果"。
	// 这里不返回 task_id：没有任何端点按 id 查任务，多一个可猜的 id
	// 只会给"只读账号读别人源的刷新结果"开一条路（requireAdmin 放行所有 GET）。
	_ = started
	okStatus(w, http.StatusAccepted, map[string]any{"status": "running"})
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
	// 首跳地址与 TVBox 的二次引用走同一套策略（safedial 默认档）：
	// 放行 LAN，拒本机/链路本地/元数据端点。
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
	// TVBox 形态 A 的 lives 指向外部 m3u/txt：这里再抓一层（只跟一层，防循环）。
	// 这一跳的地址来自远端内容，同样被 DialContext 层的地址策略覆盖。
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
		// 管理台默认**要**看到「上游已移除」的频道，才能把它标出来
		// （否则用户只看到"台少了"，不知道为什么）。
		// 传 include_missing=0 可以只看在线的。
		IncludeMissing: queryBoolDefaultTrue(r, "include_missing"),
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
	if id <= 0 {
		fail(w, http.StatusBadRequest, 1001, "频道 id 非法")
		return
	}
	// 全部用指针：**缺失 = 不改这一列**。
	//
	// 以前是非指针字段，缺省即零值，再配上 store.UpdateChannel 的全量覆盖，
	// 前端只发 {"enabled":false} 会把名称/分组/台标/tvg_id/台号全清空。
	//
	// epg_id 刻意不在这个请求体里：EPG 映射只走 PUT /channels/{id}/epg。
	// 加进来就会让"改个分组"顺手冲掉已经对好的节目单。
	var body struct {
		Name    *string `json:"name"`
		Group   *string `json:"group"`
		Logo    *string `json:"logo"`
		TvgID   *string `json:"tvg_id"`
		Number  *int    `json:"number"`
		Enabled *bool   `json:"enabled"`
	}
	// decode 开了 DisallowUnknownFields：传了未知字段（包括 epg_id）会 400。
	// 这是故意的——静默忽略未知字段会让"前端以为改了、后端没改"这种 bug
	// 一直藏着，直到用户发现节目单又没了。
	if err := decode(r, &body); err != nil {
		fail(w, http.StatusBadRequest, 1001, "参数错误: "+err.Error())
		return
	}
	// 名称不允许被清空：频道名是列表里唯一的可读标识，
	// 清空后前端只能显示一行空白，用户再也找不到这个台。
	if body.Name != nil && strings.TrimSpace(*body.Name) == "" {
		fail(w, http.StatusBadRequest, 1001, "频道名称不能为空")
		return
	}
	err := a.st.PatchChannel(r.Context(), id, store.ChannelPatch{
		Name:    body.Name,
		Group:   body.Group,
		Logo:    body.Logo,
		TvgID:   body.TvgID,
		Number:  body.Number,
		Enabled: body.Enabled,
	})
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			fail(w, http.StatusNotFound, 3001, "频道不存在")
			return
		}
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	ok(w, map[string]any{"updated": true})
}

func (a *API) deleteChannel(w http.ResponseWriter, r *http.Request) {
	id := pathInt64(r, "id")
	if id <= 0 {
		fail(w, http.StatusBadRequest, 1001, "频道 id 非法")
		return
	}
	// 先停掉这个频道还在跑的录制，再删数据。
	// 顺序很重要：只改 DB 状态停不掉 ffmpeg 进程，
	// 它会继续往盘上写一个"没人认识"的文件（store.deleteChannelsTx 已把那行改成 canceled）。
	a.stopRecordingsForChannels(r.Context(), []int64{id}, []string{"recording"})
	if err := a.st.DeleteChannel(r.Context(), id); err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	ok(w, map[string]any{"deleted": true})
}

// stopRecordingsForChannels 停掉这些频道下状态命中的录制进程。
//
// 失败只记日志，不阻断删除：停不掉一个进程不该让"删频道"整体不可用，
// 而且真停不掉时 deleteChannelsTx 仍会把行置 canceled，
// 调度器不会再去开新的。
func (a *API) stopRecordingsForChannels(ctx context.Context, channelIDs []int64, statuses []string) {
	if a.rec == nil {
		return
	}
	ids, err := a.st.RecordingIDsByChannels(ctx, channelIDs, statuses)
	if err != nil {
		slog.Warn("查询频道关联录制失败", "err", err)
		return
	}
	for _, id := range ids {
		a.rec.Stop(id)
	}
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
	// 批量删除：先停掉在跑的录制进程，再走事务删数据。
	if body.Action == "delete" {
		a.stopRecordingsForChannels(r.Context(), body.IDs, []string{"recording"})
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
	if id <= 0 {
		fail(w, http.StatusBadRequest, 1001, "频道 id 非法")
		return
	}
	var body struct {
		EpgID string `json:"epg_id"`
	}
	if err := decode(r, &body); err != nil {
		fail(w, http.StatusBadRequest, 1001, "参数错误: "+err.Error())
		return
	}
	if err := a.st.SetChannelEpgID(r.Context(), id, body.EpgID); err != nil {
		// 0 行受影响以前回 200 {"mapped":true}，调用方会以为映射成功。
		if errors.Is(err, store.ErrNotFound) {
			fail(w, http.StatusNotFound, 3001, "频道不存在")
			return
		}
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
			// A5/A7/A8
			"priority":   s.Priority,
			"user_agent": s.UserAgent,
			"referer":    s.Referer,
			"kind":       s.Kind,
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

// epgSourceBody 是新增/编辑共用的请求体。
type epgSourceBody struct {
	Name      string `json:"name"`
	URL       string `json:"url"`
	Enabled   *bool  `json:"enabled"`
	Priority  *int   `json:"priority"`
	UserAgent string `json:"user_agent"`
	Referer   string `json:"referer"`
	Kind      string `json:"kind"`
}

func (b epgSourceBody) toSource() store.EPGSource {
	s := store.EPGSource{
		Name:      strings.TrimSpace(b.Name),
		URL:       strings.TrimSpace(b.URL),
		Enabled:   true,
		UserAgent: strings.TrimSpace(b.UserAgent),
		Referer:   strings.TrimSpace(b.Referer),
		Kind:      strings.TrimSpace(b.Kind),
	}
	if b.Enabled != nil {
		s.Enabled = *b.Enabled
	}
	if b.Priority != nil {
		s.Priority = *b.Priority
	}
	if s.Kind == "" {
		s.Kind = "url"
	}
	return s
}

func (a *API) createEPGSource(w http.ResponseWriter, r *http.Request) {
	var body epgSourceBody
	if err := decode(r, &body); err != nil {
		fail(w, http.StatusBadRequest, 1001, "参数错误: "+err.Error())
		return
	}
	src := body.toSource()
	if src.Name == "" {
		src.Name = "EPG 源"
	}
	if src.URL == "" {
		fail(w, http.StatusBadRequest, 1001, "地址不能为空")
		return
	}
	id, err := a.st.CreateEPGSourceEx(r.Context(), src)
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	ok(w, map[string]any{"id": id})
}

// updateEPGSource 编辑源（A4）。
func (a *API) updateEPGSource(w http.ResponseWriter, r *http.Request) {
	id := pathInt64(r, "id")
	var body epgSourceBody
	if err := decode(r, &body); err != nil {
		fail(w, http.StatusBadRequest, 1001, "参数错误: "+err.Error())
		return
	}
	src := body.toSource()
	src.ID = id
	if src.Name == "" || src.URL == "" {
		fail(w, http.StatusBadRequest, 1001, "名称和地址不能为空")
		return
	}
	if err := a.st.UpdateEPGSource(r.Context(), src); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			fail(w, http.StatusNotFound, 3001, "EPG 源不存在")
			return
		}
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	a.st.Audit(r.Context(), 0, "epg.source.update", fmt.Sprint(id), src.Name, r.RemoteAddr)
	ok(w, map[string]any{"saved": true})
}

// refreshOneEPGSource 刷新单个源（A2）。
func (a *API) refreshEPGSource(w http.ResponseWriter, r *http.Request) {
	id := pathInt64(r, "id")
	res, err := a.RefreshOneEPG(r.Context(), id)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			fail(w, http.StatusNotFound, 3001, "EPG 源不存在")
			return
		}
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	// 刷新失败不是 5xx：源本身有问题，返回 200 带明细让界面展示原因
	a.auditEPGRefresh(r, "one", []EPGRefreshResult{res})
	ok(w, res)
}

// importEPG 导入本地 XMLTV 文件（A8）。
//
// 上传的文件落到数据目录下的 epg-import/，并建一个 kind=file 的源，
// 之后走与远程源完全相同的刷新路径（含解析、优先级、保留策略）。
func (a *API) importEPG(w http.ResponseWriter, r *http.Request) {
	// 限制请求体大小，避免上传把内存吃光
	maxBytes := a.cfg.EPG.MaxBytes()
	if maxBytes <= 0 {
		maxBytes = 64 << 20
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxBytes+(1<<20))
	if err := r.ParseMultipartForm(8 << 20); err != nil {
		fail(w, http.StatusBadRequest, 1001, "上传内容无法解析: "+err.Error())
		return
	}
	file, hdr, err := r.FormFile("file")
	if err != nil {
		fail(w, http.StatusBadRequest, 1001, "缺少文件字段 file")
		return
	}
	defer file.Close()

	dir := filepath.Join(filepath.Dir(a.cfg.Database.Path), "epg-import")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		fail(w, http.StatusInternalServerError, 5001, "创建导入目录失败: "+err.Error())
		return
	}
	name := fmt.Sprintf("import-%d.xml", time.Now().Unix())
	path := filepath.Join(dir, name)
	out, err := os.Create(path)
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, "写入文件失败: "+err.Error())
		return
	}
	written, copyErr := io.Copy(out, io.LimitReader(file, maxBytes))
	closeErr := out.Close()
	if copyErr != nil || closeErr != nil {
		_ = os.Remove(path)
		fail(w, http.StatusInternalServerError, 5001, "写入文件失败")
		return
	}
	if written == 0 {
		_ = os.Remove(path)
		fail(w, http.StatusBadRequest, 1001, "文件是空的")
		return
	}

	srcName := strings.TrimSpace(r.FormValue("name"))
	if srcName == "" {
		srcName = hdr.Filename
	}
	id, err := a.st.CreateEPGSourceEx(r.Context(), store.EPGSource{
		Name: srcName, URL: path, Enabled: true, Kind: "file",
	})
	if err != nil {
		_ = os.Remove(path)
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	// 导入即刷新，用户不用再点一次
	res, _ := a.RefreshOneEPG(r.Context(), id)
	a.st.Audit(r.Context(), 0, "epg.import", fmt.Sprint(id), srcName, r.RemoteAddr)
	ok(w, map[string]any{"id": id, "result": res})
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
	// 统一走 RefreshAllEPG：调度器（A1）与手动刷新共用同一套逻辑
	// （重试、UA/Referer、体积上限、优先级、明细），避免两条路径行为漂移。
	results := a.RefreshAllEPG(r.Context())
	a.auditEPGRefresh(r, "all", results)
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
			"ip":                d.IP,
			"app_version":       d.AppVersion,
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

// revokeDevice 对应 DELETE /devices/{id}，语义是**删除**，不是仅吊销。
//
// 之前只把 status 改成 revoked，行还在，管理台上点删除看不到任何变化。
func (a *API) revokeDevice(w http.ResponseWriter, r *http.Request) {
	id := pathInt64(r, "id")
	if err := a.st.DeleteDevice(r.Context(), id); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			fail(w, http.StatusNotFound, 3001, "设备不存在")
			return
		}
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	a.st.Audit(r.Context(), 0, "device.delete", fmt.Sprint(id), "", r.RemoteAddr)
	ok(w, map[string]any{"deleted": true})
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
	if id <= 0 {
		fail(w, http.StatusBadRequest, 1001, "录制 id 非法")
		return
	}
	rec, err := a.st.GetRecording(r.Context(), id)
	if err != nil {
		fail(w, http.StatusNotFound, 3001, "录制不存在")
		return
	}
	if a.rec != nil {
		a.rec.Stop(id)
	}

	// 只删我们自己的录制文件，且必须**证明**这个路径属于录制目录。
	//
	// 修复前这里是：
	//   strings.HasPrefix(Clean(rec.Path), Clean(a.cfg.Record.Dir))
	// 两个叠加的问题：
	//   1. cfg.Record.Dir 在默认配置下是空的（main 把兜底目录放进局部变量，
	//      从不回写 cfg），Clean("") == "."，前缀判断恒为假 →
	//      文件永久残留，而 DB 行被删掉了，用户再也找不到它；
	//   2. 即使目录不为空，字符串前缀也不是目录包含关系：
	//      dir=/data/litv/record 时 /data/litv/record-old/x.ts 同样满足前缀。
	//
	// 现在改为 filepath.Rel（见 recordPathInside），并且失败时**不删数据库行**：
	// 删了行就只剩一个查不到的孤儿文件，用户既看不到占用也删不掉。
	if rec.Path != "" {
		if !a.recordPathInside(rec.Path) {
			slog.Warn("录制文件不在录制目录内，拒绝删除",
				"recording", id, "file", filepath.Base(rec.Path))
			fail(w, http.StatusConflict, 3003, "录制文件不在录制目录内，已保留记录")
			return
		}
		// 符号链接一律不跟随：宁可留一个文件，也不要删到它指向的地方去。
		if fi, lerr := os.Lstat(rec.Path); lerr == nil && fi.Mode()&os.ModeSymlink != 0 {
			slog.Warn("录制文件是符号链接，拒绝删除",
				"recording", id, "file", filepath.Base(rec.Path))
			fail(w, http.StatusConflict, 3003, "录制文件是符号链接，已保留记录")
			return
		}
		if rerr := os.Remove(rec.Path); rerr != nil && !os.IsNotExist(rerr) {
			slog.Warn("删除录制文件失败",
				"recording", id, "file", filepath.Base(rec.Path), "err", rerr)
			fail(w, http.StatusInternalServerError, 5001, "删除录制文件失败")
			return
		}
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

// recordPathInside 判断 path 是否确实位于录制目录之内。
//
// 用 filepath.Rel 而不是 strings.HasPrefix：前缀是**字符串**关系，不是目录关系。
// dir=/data/litv/record 时，/data/litv/record-old/x.ts 也满足字符串前缀，
// 但它根本不在录制目录里。
//
// 根目录为空时一律返回 false（fail closed）：拿不到根目录就无从证明归属，
// 宁可保留文件并报错，也不要凭猜测删东西。
//
// 同时拒绝 rel == "."（path 就是根目录本身，是个目录不是文件）
// 与任何以 ".." 开头的相对路径（在根目录之外）。
func (a *API) recordPathInside(path string) bool {
	root := strings.TrimSpace(a.recordDir)
	if root == "" {
		return false
	}
	rootAbs, err := filepath.Abs(filepath.Clean(root))
	if err != nil {
		return false
	}
	pAbs, err := filepath.Abs(filepath.Clean(path))
	if err != nil {
		return false
	}
	rel, err := filepath.Rel(rootAbs, pAbs)
	if err != nil {
		return false
	}
	if filepath.IsAbs(rel) {
		// Windows 上跨盘符时 Rel 会返回绝对路径。
		return false
	}
	if rel == "." || rel == ".." || strings.HasPrefix(rel, ".."+string(os.PathSeparator)) {
		return false
	}
	return true
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
