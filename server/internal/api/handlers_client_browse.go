package api

// 电视端浏览：bootstrap、频道/分组、EPG、收藏、线路列表——都是「看」，不起播。

import (
	"encoding/json"
	"net/http"
	"strconv"
	"time"

	"github.com/litv/litv/server/internal/store"
)

func (a *API) tvBootstrap(w http.ResponseWriter, r *http.Request) {
	dev, _ := deviceFrom(r.Context())
	ctx := r.Context()

	// 屏蔽规则在下发前生效：整类被挡住的分组不进列表，单台被挡的台不出现。
	// 这一步必须做在查询里（而不是客户端拿到后自己滤），否则分组计数
	// 和「共几个台」这些数字会和实际看到的对不上。
	bl := a.loadBlockList(r)
	groups, _ := a.st.ListGroupsExcluding(ctx, bl.Groups, bl.Channels)
	// 不再写死 500：ChannelQuery 的管理台分页上限会把第 501 个之后的台
	// 从电视端首页里悄悄丢掉。
	chans, _ := a.st.ListEnabledChannels(ctx, bl.Groups, bl.Channels)
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

	// 频道指纹（与心跳同一个算法）。失败返回空串，客户端会跳过比对。
	chVerBootstrap, _ := a.st.ChannelsVersion(ctx)

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

	// 节目单地址下发给客户端，由客户端自己下载解析。
	//
	// 为什么：节目单本质是「客户端要看的参考数据」，不是「服务端要算的状态」。
	// 客户端拿到地址后本地解析、本地缓存，切台查内存——服务端省掉一次全量
	// 解析和整库同步，客户端拿到的永远是最新数据（不用等刷新周期）。
	//
	// 注意：这里**仍然保留**服务端灌库（internal/epg + cron），因为管理台的
	// 节目单页、录制按节目名、回看这些功能都读本地库里那份。客户端优先用
	// 自己解析的结果，拿不到时才回落到 /api/v1/tv/epg/{id}。
	epgURL := ""
	for _, u := range a.conf().EPG.DefaultURLs {
		if u != "" {
			epgURL = u
			break
		}
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
		"groups": groups,
		// 与心跳同源的频道指纹：客户端拿到 bootstrap 就记下基线，
		// 之后心跳里发现变了才重拉，避免每次心跳都白拉一次列表。
		"channels_version": chVerBootstrap,
		"sources":          srcBrief,
		"channels":         chans,
		"favorites":        favs,
		"now":              nowList,
		"epg_url":          epgURL,
	})
}

func (a *API) tvChannels(w http.ResponseWriter, r *http.Request) {
	// 屏蔽规则同样在这里生效：电视端「换台列表」和 bootstrap 是两条路，
	// 只在 bootstrap 过滤会让被挡的台从换台列表里漏出来。
	bl := a.loadBlockList(r)
	chans, total, err := a.st.ListChannels(r.Context(), store.ChannelQuery{
		Group:         r.URL.Query().Get("group"),
		Limit:         queryInt(r, "limit", 500),
		Offset:        queryInt(r, "offset", 0),
		Enabled:       boolPtr(true),
		ExcludeGroups: bl.Groups,
		ExcludeNames:  bl.Channels,
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
	start, end := localDayRange(a.now(), a.conf().Server.Timezone, day)
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
		"timezone":    a.conf().Server.Timezone,
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
