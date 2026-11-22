package api

// 录制中心：录制的增删停、路径归属校验、录制文件下发。

import (
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/litv/litv/server/internal/store"
)

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
	a.st.Audit(r.Context(), 0, "record.create", fmt.Sprint(id), name, peerIPFrom(r.Context()))
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
	//   strings.HasPrefix(Clean(rec.Path), Clean(a.conf().Record.Dir))
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
//
// ★ 读侧必须和删侧一样证明「路径属于录制目录」。
// 以前这里只 os.Stat 一下就直接 ServeFile：只要 DB 里的 rec.Path 被写坏
// （历史 bug / 导入 / 手工改库），这个接口就成了**任意本地文件下载**。
// 用 404 而不是 403：不额外泄露「这个路径存不存在」。
func (a *API) recordingFile(w http.ResponseWriter, r *http.Request) {
	id, okID := pathID(r, "id")
	if !okID {
		fail(w, http.StatusNotFound, 3001, "录制不存在")
		return
	}
	rec, err := a.st.GetRecording(r.Context(), id)
	if err != nil {
		fail(w, http.StatusNotFound, 3001, "录制不存在")
		return
	}
	if rec.Path == "" {
		fail(w, http.StatusNotFound, 3001, "录制文件还没生成")
		return
	}
	if !a.recordPathInside(rec.Path) {
		slog.Warn("录制文件不在录制目录内，拒绝读取",
			"recording", id, "file", filepath.Base(rec.Path))
		fail(w, http.StatusNotFound, 3001, "录制文件不存在")
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
