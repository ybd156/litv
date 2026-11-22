package api

// EPG 源与节目单：EPG 源的增删改、导入、刷新，以及频道列表。

import (
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/litv/litv/server/internal/store"
)

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
	a.st.Audit(r.Context(), 0, "epg.source.update", fmt.Sprint(id), src.Name, peerIPFrom(r.Context()))
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
	maxBytes := a.conf().EPG.MaxBytes()
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

	dir := filepath.Join(filepath.Dir(a.conf().Database.Path), "epg-import")
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
	a.st.Audit(r.Context(), 0, "epg.import", fmt.Sprint(id), srcName, peerIPFrom(r.Context()))
	ok(w, map[string]any{"id": id, "result": res})
}

func (a *API) deleteEPGSource(w http.ResponseWriter, r *http.Request) {
	id, okID := pathID(r, "id")
	if !okID {
		fail(w, http.StatusNotFound, 3002, "节目单源不存在")
		return
	}
	if err := a.st.DeleteEPGSource(r.Context(), id); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			fail(w, http.StatusNotFound, 3002, "节目单源不存在")
			return
		}
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
