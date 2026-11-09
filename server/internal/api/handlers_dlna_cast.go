package api

import (
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"time"

	"github.com/litv/litv/server/internal/dlna"
)

// ---------- DLNA 投屏（控制端） ----------

// tvDLNARenderers 列出局域网里可投屏的设备。
//
// 由服务端做发现的原因：iOS 14+ 对本地网络多播有严格限制，
// SSDP 需要 Apple 单独批准的 multicast 权限（要审核，自签包拿不到）。
// NAS 和电视在同一局域网，由它发 M-SEARCH 没有任何限制，
// 手机端只需要调这个接口拿列表——两端（安卓/iOS）逻辑还能完全一致。
func (a *API) tvDLNARenderers(w http.ResponseWriter, r *http.Request) {
	if _, okDev := deviceFrom(r.Context()); !okDev {
		fail(w, http.StatusUnauthorized, 2003, "设备未绑定")
		return
	}
	list, err := dlna.DiscoverRenderers(r.Context(), 3*time.Second)
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	ok(w, map[string]any{"total": len(list), "items": list})
}

// tvDLNACast 让指定设备播放某个频道。
//
// 给电视的地址是服务端自己的中转地址（带签名、带线路绑定），
// 不是源站地址——电视不一定能直连源站，而 NAS 一定能。
func (a *API) tvDLNACast(w http.ResponseWriter, r *http.Request) {
	dev, okDev := deviceFrom(r.Context())
	if !okDev {
		fail(w, http.StatusUnauthorized, 2003, "设备未绑定")
		return
	}
	var body struct {
		ChannelID  int64  `json:"channel_id"`
		ControlURL string `json:"control_url"`
		Name       string `json:"name"`
	}
	if err := decode(r, &body); err != nil {
		fail(w, http.StatusBadRequest, 1001, "参数错误: "+err.Error())
		return
	}
	if body.ChannelID <= 0 || body.ControlURL == "" {
		fail(w, http.StatusBadRequest, 1001, "channel_id 和 control_url 都不能为空")
		return
	}

	ch, err := a.st.GetChannel(r.Context(), body.ChannelID)
	if err != nil {
		fail(w, http.StatusNotFound, 3001, "频道不存在")
		return
	}
	st, err := a.st.BestStream(r.Context(), ch.ID)
	if err != nil {
		fail(w, http.StatusNotFound, 3002, "该频道没有可用线路")
		return
	}

	// 给电视的播放地址：服务端中转，签名 6 小时有效。
	// dk 用派生值：投屏目标（电视）是共享设备，绝不能让它读到 device_key。
	exp := a.now().Add(6 * time.Hour).Unix()
	dk := a.signer.DeriveDeviceKey(dev.DeviceKey)
	sig := a.signer.SignStream(ch.ID, st.ID, exp, dk)
	q := url.Values{}
	q.Set("sig", sig)
	q.Set("exp", strconv.FormatInt(exp, 10))
	q.Set("dk", dk)
	q.Set("stream_id", strconv.FormatInt(st.ID, 10))
	mediaURL := fmt.Sprintf("http://%s:%d/live/%d/stream.ts?%s",
		dlna.LanIPv4(), a.cfg.Server.Port, ch.ID, q.Encode())

	if err := dlna.Cast(r.Context(), body.ControlURL, ch.Name, mediaURL); err != nil {
		fail(w, http.StatusBadGateway, 5002, "投屏失败: "+err.Error())
		return
	}
	a.st.Audit(r.Context(), 0, "dlna.cast", body.Name, ch.Name, r.RemoteAddr)
	ok(w, map[string]any{
		"cast":       true,
		"channel":    ch.Name,
		"renderer":   body.Name,
		"stream_url": mediaURL,
	})
}

// tvDLNAStop 让设备停止投屏。
func (a *API) tvDLNAStop(w http.ResponseWriter, r *http.Request) {
	if _, okDev := deviceFrom(r.Context()); !okDev {
		fail(w, http.StatusUnauthorized, 2003, "设备未绑定")
		return
	}
	var body struct {
		ControlURL string `json:"control_url"`
	}
	if err := decode(r, &body); err != nil {
		fail(w, http.StatusBadRequest, 1001, "参数错误: "+err.Error())
		return
	}
	if body.ControlURL == "" {
		fail(w, http.StatusBadRequest, 1001, "control_url 不能为空")
		return
	}
	if err := dlna.Stop(r.Context(), body.ControlURL); err != nil {
		fail(w, http.StatusBadGateway, 5002, "停止失败: "+err.Error())
		return
	}
	ok(w, map[string]any{"stopped": true})
}
