package api

// 手机当遥控：WebSocket 通道与遥控指令中转、可用遥控目标列表。

import (
	"net/http"

	"github.com/gorilla/websocket"
	"github.com/litv/litv/server/internal/remote"
)

var wsUpgrader = websocket.Upgrader{
	ReadBufferSize:  1024,
	WriteBufferSize: 1024,
	// 家庭局域网自用：不校验 Origin（电视/手机的 Origin 各不相同）
	CheckOrigin: func(r *http.Request) bool { return true },
}

// tvWS 电视端建立遥控通道，连上后一直挂着等指令。
func (a *API) tvWS(w http.ResponseWriter, r *http.Request) {
	dev, okDev := deviceFrom(r.Context())
	if !okDev {
		fail(w, http.StatusUnauthorized, 2003, "设备未绑定")
		return
	}
	ws, err := wsUpgrader.Upgrade(w, r, nil)
	if err != nil {
		// Upgrade 失败时它已经写过响应了，这里不能再写
		return
	}
	a.remote.Register(dev.ID, ws)
}

// tvRemote 手机端发一条遥控指令给指定电视。
func (a *API) tvRemote(w http.ResponseWriter, r *http.Request) {
	from, okDev := deviceFrom(r.Context())
	if !okDev {
		fail(w, http.StatusUnauthorized, 2003, "设备未绑定")
		return
	}
	target := pathInt64(r, "id")
	if target == from.ID {
		fail(w, http.StatusBadRequest, 1001, "不能遥控自己")
		return
	}
	var cmd remote.Command
	if err := decode(r, &cmd); err != nil {
		fail(w, http.StatusBadRequest, 1001, "参数错误: "+err.Error())
		return
	}
	if cmd.Type == "" {
		cmd.Type = "key"
	}
	cmd.From = from.Name
	if !a.remote.Send(target, cmd) {
		fail(w, http.StatusNotFound, 3001, "目标设备不在线（电视上的 LITV 是否打开？）")
		return
	}
	ok(w, map[string]any{"sent": true})
}

// remoteTargets 列出可被遥控的设备（已绑定 + 是否在线）。
func (a *API) remoteTargets(w http.ResponseWriter, r *http.Request) {
	me, _ := deviceFrom(r.Context())
	items, err := a.st.ListDevices(r.Context(), "bound")
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	out := make([]map[string]any, 0, len(items))
	for _, d := range items {
		if me != nil && d.ID == me.ID {
			continue
		}
		out = append(out, map[string]any{
			"id":       d.ID,
			"name":     d.Name,
			"platform": d.Platform,
			"model":    d.Model,
			"online":   a.remote.IsOnline(d.ID),
		})
	}
	ok(w, out)
}
