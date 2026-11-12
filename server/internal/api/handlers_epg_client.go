package api

import (
	"errors"
	"fmt"
	"net/http"
	"time"

	"github.com/litv/litv/server/internal/store"
)

// ---------- 节目单相关的终端能力（C5 提醒 / C6 预约录制） ----------

// tvCreateReminder 设置节目开播提醒（C5）。
func (a *API) tvCreateReminder(w http.ResponseWriter, r *http.Request) {
	dev, okDev := deviceFrom(r.Context())
	if !okDev {
		fail(w, http.StatusUnauthorized, 2003, "设备未绑定")
		return
	}
	var body struct {
		ChannelID int64  `json:"channel_id"`
		Title     string `json:"title"`
		StartAt   int64  `json:"start_at"`
	}
	if err := decode(r, &body); err != nil {
		fail(w, http.StatusBadRequest, 1001, "参数错误: "+err.Error())
		return
	}
	if body.ChannelID <= 0 {
		fail(w, http.StatusBadRequest, 1001, "channel_id 不能为空")
		return
	}
	now := a.now().Unix()
	// 允许 1 分钟容差：客户端时钟可能略快，节目也可能刚好开始
	if body.StartAt < now-60 {
		fail(w, http.StatusBadRequest, 1001, "节目已经开始，无法设置提醒")
		return
	}
	if body.StartAt > now+7*86400 {
		fail(w, http.StatusBadRequest, 1001, "只能提醒 7 天内的节目")
		return
	}

	ch, err := a.st.GetChannel(r.Context(), body.ChannelID)
	if err != nil {
		fail(w, http.StatusNotFound, 3001, "频道不存在")
		return
	}
	title := body.Title
	if title == "" {
		title = "节目"
	}
	id, err := a.st.CreateReminder(r.Context(), dev.ID, ch.ID, ch.Name, title, body.StartAt)
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	ok(w, map[string]any{
		"id":           id,
		"channel_name": ch.Name,
		"title":        title,
		"start_at":     body.StartAt,
		"note":         "开播时服务端会推送提醒",
	})
}

// tvListReminders 列出本设备的提醒（C5）。
func (a *API) tvListReminders(w http.ResponseWriter, r *http.Request) {
	dev, okDev := deviceFrom(r.Context())
	if !okDev {
		fail(w, http.StatusUnauthorized, 2003, "设备未绑定")
		return
	}
	items, err := a.st.ListReminders(r.Context(), dev.ID)
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	ok(w, map[string]any{"total": len(items), "items": items})
}

// tvDeleteReminder 取消提醒（C5）。
func (a *API) tvDeleteReminder(w http.ResponseWriter, r *http.Request) {
	dev, okDev := deviceFrom(r.Context())
	if !okDev {
		fail(w, http.StatusUnauthorized, 2003, "设备未绑定")
		return
	}
	id := pathInt64(r, "id")
	if err := a.st.DeleteReminder(r.Context(), dev.ID, id); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			fail(w, http.StatusNotFound, 3001, "提醒不存在")
			return
		}
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	ok(w, map[string]any{"deleted": true})
}

// tvCreateRecording 从节目单直接预约录制（C6）。
//
// 与管理台的 createRecording 共用同一套校验和存储，
// 差别只在身份：终端用设备 token，不需要管理员权限。
func (a *API) tvCreateRecording(w http.ResponseWriter, r *http.Request) {
	if _, okDev := deviceFrom(r.Context()); !okDev {
		fail(w, http.StatusUnauthorized, 2003, "设备未绑定")
		return
	}
	var body struct {
		ChannelID   int64  `json:"channel_id"`
		Name        string `json:"name"`
		StartAt     int64  `json:"start_at"`
		DurationSec int    `json:"duration_sec"`
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

	dur := body.DurationSec
	if dur <= 0 {
		dur = 1800
	}
	if dur < 5 {
		fail(w, http.StatusBadRequest, 1001, "录制时长至少 5 秒")
		return
	}
	if dur > 8*3600 {
		fail(w, http.StatusBadRequest, 1001, "单次录制最长 8 小时")
		return
	}
	start := body.StartAt
	if start <= 0 {
		start = a.now().Unix()
	}
	if start < a.now().Unix()-60 {
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
		EndAt:     start + int64(dur),
	})
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	a.st.Audit(r.Context(), 0, "record.create.device", fmt.Sprint(id), name, r.RemoteAddr)
	ok(w, map[string]any{"id": id, "name": name, "start_at": start, "end_at": start + int64(dur)})
}
