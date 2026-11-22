package api

// 频道管理：频道/分组的增删改、排序与批量操作、线路、EPG 映射。

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"strings"

	"github.com/litv/litv/server/internal/store"
)

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
	// 路径参数非法 → 404，不要拿 0 去删（见 pathID 注释）
	sid, okID := pathID(r, "sid")
	if !okID {
		fail(w, http.StatusNotFound, 3002, "线路不存在")
		return
	}
	if err := a.st.DeleteStream(r.Context(), sid); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			fail(w, http.StatusNotFound, 3002, "线路不存在")
			return
		}
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
