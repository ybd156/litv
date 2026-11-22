package api

// 源管理：源的增删改查、刷新与预览、外部源抓取解析。

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"

	"github.com/litv/litv/server/internal/fetch"
	"github.com/litv/litv/server/internal/parse"
	"github.com/litv/litv/server/internal/store"
)

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

	// 先停掉这些频道还在跑的录制，再删数据。顺序很重要：只改 DB 状态停不掉
	// ffmpeg 进程，它会继续往盘上写一个"没人认识"的文件。
	// deleteChannel 早就是这么做的，删源这条路之前漏了。
	if ids, idErr := a.st.ChannelIDsBySource(r.Context(), id); idErr == nil && len(ids) > 0 {
		a.stopRecordingsForChannels(r.Context(), ids, []string{"recording"})
	}

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
	id, okID := pathID(r, "id")
	if !okID {
		fail(w, http.StatusNotFound, 3001, "源不存在")
		return
	}
	// 直接按 id 取，不再 ListSources 全表再线性找（那种写法既慢又容易漏判 not found）
	target, err := a.st.GetSource(r.Context(), id)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			fail(w, http.StatusNotFound, 3001, "源不存在")
			return
		}
		fail(w, http.StatusInternalServerError, 5001, err.Error())
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
	// total 必须是**截断前**的条数：以前先切 items 再算 len(items)，
	// 于是 total 恒 ≤ limit，前端「共 N 个频道」永远显示不全。
	total := len(items)
	if limit > 0 && total > limit {
		items = items[:limit]
	}
	ok(w, map[string]any{
		"total":  total,
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
