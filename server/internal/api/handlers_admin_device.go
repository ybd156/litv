package api

// 设备与绑定：设备列表、绑定码、批准/拒绝/吊销、设备配置下发。

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"time"

	"github.com/litv/litv/server/internal/store"
)

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
	id, okID := pathID(r, "id")
	if !okID {
		fail(w, http.StatusNotFound, 3001, "设备不存在")
		return
	}
	if err := a.st.ApproveDevice(r.Context(), id); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			fail(w, http.StatusNotFound, 3001, "设备不存在")
			return
		}
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	a.st.Audit(r.Context(), 0, "device.approve", fmt.Sprint(id), "", peerIPFrom(r.Context()))
	ok(w, map[string]any{"approved": true})
}

func (a *API) rejectDevice(w http.ResponseWriter, r *http.Request) {
	id, okID := pathID(r, "id")
	if !okID {
		fail(w, http.StatusNotFound, 3001, "设备不存在")
		return
	}
	if err := a.st.RevokeDevice(r.Context(), id); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			fail(w, http.StatusNotFound, 3001, "设备不存在")
			return
		}
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
	a.st.Audit(r.Context(), 0, "device.delete", fmt.Sprint(id), "", peerIPFrom(r.Context()))
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
	a.st.Audit(r.Context(), 0, "device.config", fmt.Sprint(id), string(b), peerIPFrom(r.Context()))
	ok(w, map[string]any{
		"saved":          true,
		"config_version": ver,
		"note":           "终端将在下次心跳时拉取新配置",
	})
}
