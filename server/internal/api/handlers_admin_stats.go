package api

// 播放质量统计：概览与分频道统计（只读聚合）。

import (
	"net/http"
)

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
