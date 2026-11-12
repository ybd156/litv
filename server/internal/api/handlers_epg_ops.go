package api

import (
	"fmt"
	"net/http"
	"time"

	"github.com/litv/litv/server/internal/store"
)

// ---------- EPG 运维（D1–D5） ----------

// epgStats 返回 EPG 总览统计（D1）。
func (a *API) epgStats(w http.ResponseWriter, r *http.Request) {
	st, err := a.st.EPGStats(r.Context())
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	// 覆盖天数：从最早节目到最晚结束，换算成天。
	// 用户真正关心的是「我这套节目单能撑几天」，不是两个时间戳。
	var coveredDays int
	if st.LatestEnd > st.EarliestStart && st.EarliestStart > 0 {
		coveredDays = int((st.LatestEnd - st.EarliestStart) / 86400)
	}
	ok(w, map[string]any{
		"sources":         st.Sources,
		"enabled_sources": st.EnabledSources,
		"channels":        st.Channels,
		"programs":        st.Programs,
		"earliest_start":  st.EarliestStart,
		"latest_end":      st.LatestEnd,
		"covered_days":    coveredDays,
		"last_sync":       st.LastSync,
		"db_bytes":        st.DBBytes,
		"keep_days":       a.cfg.EPG.KeepDays,
	})
}

// epgCleanup 手动清理过期节目（D2）。
func (a *API) epgCleanup(w http.ResponseWriter, r *http.Request) {
	var body struct {
		KeepDays *int `json:"keep_days"`
	}
	// 空 body 视为「按配置的 keep_days 清理」，不报错
	_ = decode(r, &body)

	days := a.cfg.EPG.KeepDays
	if body.KeepDays != nil {
		days = *body.KeepDays
	}
	if days < 0 {
		days = 0
	}
	cutoff := time.Now().AddDate(0, 0, -days).Unix()
	n, err := a.st.PurgeOldPrograms(r.Context(), cutoff)
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	a.st.Audit(r.Context(), 0, "epg.cleanup", fmt.Sprint(days),
		fmt.Sprintf("删除 %d 条过期节目", n), r.RemoteAddr)
	ok(w, map[string]any{
		"deleted":   n,
		"keep_days": days,
		"note":      fmt.Sprintf("已删除 %d 条结束时间早于 %d 天前的节目", n, days),
	})
}

// epgRefreshLog 返回最近的 EPG 操作审计（D3）。
func (a *API) epgRefreshLog(w http.ResponseWriter, r *http.Request) {
	limit := queryInt(r, "limit", 100)
	items, err := a.st.ListAuditByPrefix(r.Context(), "epg.", limit)
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	ok(w, items)
}

// epgSearch 按节目名搜索（D5）。
func (a *API) epgSearch(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query().Get("q")
	limit := queryInt(r, "limit", 50)
	items, err := a.st.SearchPrograms(r.Context(), q, limit)
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	ok(w, map[string]any{"total": len(items), "items": items})
}

// auditEPGRefresh 把一次刷新结果写进审计（D3）。
//
// 只记摘要，不记每个源的完整错误栈：审计是给人翻的，
// 太啰嗦反而找不到重点。详细错误在 EPG 源列表的「状态」列里。
func (a *API) auditEPGRefresh(r *http.Request, scope string, results []EPGRefreshResult) {
	okCount, failCount, channels, programs := 0, 0, 0, 0
	for _, res := range results {
		if res.OK {
			okCount++
			channels += res.Channels
			programs += res.Programs
		} else if res.Error != "" {
			failCount++
		}
	}
	detail := fmt.Sprintf("成功 %d / 失败 %d；频道 %d，节目 %d",
		okCount, failCount, channels, programs)
	a.st.Audit(r.Context(), 0, "epg.refresh."+scope, "", detail, r.RemoteAddr)
}

// epgAutomap 按频道名自动匹配（B1/B2），返回完整报告。
func (a *API) epgAutomap(w http.ResponseWriter, r *http.Request) {
	rep, err := a.st.AutoMapChannelsReport(r.Context())
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	a.st.Audit(r.Context(), 0, "epg.automap", "",
		fmt.Sprintf("扫描 %d，匹配 %d，未匹配 %d", rep.Scanned, rep.Matched, len(rep.Unmatched)),
		r.RemoteAddr)
	ok(w, rep)
}

// epgMapping 返回未匹配频道与映射冲突（B5/B6），供映射面板一次拉齐。
func (a *API) epgMapping(w http.ResponseWriter, r *http.Request) {
	unmatched, err := a.st.ListUnmatchedChannels(r.Context())
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	conflicts, _ := a.st.MappingConflicts(r.Context())
	ok(w, map[string]any{
		"unmatched": unmatched,
		"conflicts": conflicts,
	})
}

// epgMapBatch 批量设置映射（B3）。epg_id 传空字符串表示解除映射。
func (a *API) epgMapBatch(w http.ResponseWriter, r *http.Request) {
	var body struct {
		ChannelIDs []int64 `json:"channel_ids"`
		EpgID      string  `json:"epg_id"`
	}
	if err := decode(r, &body); err != nil {
		fail(w, http.StatusBadRequest, 1001, "参数错误: "+err.Error())
		return
	}
	if len(body.ChannelIDs) == 0 {
		fail(w, http.StatusBadRequest, 1001, "channel_ids 不能为空")
		return
	}
	if len(body.ChannelIDs) > 1000 {
		fail(w, http.StatusBadRequest, 1001, "一次最多 1000 个频道")
		return
	}
	n, err := a.st.BatchSetChannelEpgID(r.Context(), body.ChannelIDs, body.EpgID)
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	a.st.Audit(r.Context(), 0, "epg.map.batch", body.EpgID,
		fmt.Sprintf("更新 %d 个频道", n), r.RemoteAddr)
	ok(w, map[string]any{"updated": n})
}

// epgMapByNumber 按台号批量匹配（B4）。
func (a *API) epgMapByNumber(w http.ResponseWriter, r *http.Request) {
	rep, err := a.st.MapByNumber(r.Context())
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	a.st.Audit(r.Context(), 0, "epg.map.number", "",
		fmt.Sprintf("扫描 %d，匹配 %d", rep.Scanned, rep.Matched), r.RemoteAddr)
	ok(w, rep)
}

var _ = store.EPGStats{}
