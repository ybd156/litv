package api

// 起播与续播：起播入口、流校验（设备/线路归属）、时移、跨端续播进度。

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/litv/litv/server/internal/store"
)

// tvPlay 返回一个带签名的播放地址。终端不需要知道上游长什么样。
func (a *API) tvPlay(w http.ResponseWriter, r *http.Request) {
	slog.Info("tvPlay", "channel", pathInt64(r, "id"), "direct", r.URL.Query().Get("direct"))
	dev, _ := deviceFrom(r.Context())
	id := pathInt64(r, "id")

	ch, err := a.st.GetChannel(r.Context(), id)
	if err != nil {
		fail(w, http.StatusNotFound, 3001, "频道不存在")
		return
	}
	st, err := a.st.BestStream(r.Context(), id)
	// 终端手动切线路时用 stream_id 指定（优先于自动择优）
	if sidStr := r.URL.Query().Get("stream_id"); sidStr != "" {
		if sid, e := strconv.ParseInt(sidStr, 10, 64); e == nil && sid > 0 {
			if all, lerr := a.st.ListStreams(r.Context(), id); lerr == nil {
				for i := range all {
					if all[i].ID == sid {
						st = &all[i]
						err = nil
						break
					}
				}
			}
		}
	}
	if err != nil {
		fail(w, http.StatusNotFound, 3002, "该频道没有可用线路")
		return
	}

	kind := "ts"
	if isHLSSource(st.URL) {
		kind = "hls"
	}
	// 客户端可以要求"给我 HLS"（iOS 的 AVPlayer 只认 HLS）。
	// 上游是裸 TS 时走转封装，上游本来就是 HLS 就直接用。
	wantHLS := strings.EqualFold(r.URL.Query().Get("fmt"), "hls")
	if wantHLS && kind == "ts" {
		if a.rx != nil && a.rx.Available() {
			kind = "remux"
		}
	}

	exp := time.Now().Add(12 * time.Hour).Unix()
	// R13：把选中的线路绑进签名，relay 各入口据此使用同一条线路，
	// 不再各自 BestStream。streamID 为 0 时退化为普通签名。
	// dk 用派生值：device_key 等价于设备凭据，不能出现在地址里。
	dk := a.signer.DeriveDeviceKey(dev.DeviceKey)
	sig := a.signer.SignStream(id, st.ID, exp, dk)
	path := fmt.Sprintf("%s/live/%d/stream.ts", a.conf().Server.Prefix, id)
	switch kind {
	case "hls":
		path = fmt.Sprintf("%s/live/%d/index.m3u8", a.conf().Server.Prefix, id)
	case "remux":
		path = fmt.Sprintf("%s/live/%d/remux.m3u8", a.conf().Server.Prefix, id)
	}
	q := url.Values{}
	q.Set("sig", sig)
	q.Set("exp", strconv.FormatInt(exp, 10))
	q.Set("dk", dk)
	// stream_id 与签名一起出现：verifyStreamFull 会校验它们是否匹配。
	q.Set("stream_id", strconv.FormatInt(st.ID, 10))

	// 直连：把上游地址原样交给客户端，让它自己连源站。
	//
	// 省掉 NAS 的带宽和 CPU，起播也更快；代价是客户端要能访问到源站，
	// 且 UA/Referer 校验得客户端自己带（所以这里把头一起给出去）。
	// 只在客户端明确要（direct=1）且服务端允许时才走这条路。
	wantDirect := strings.EqualFold(r.URL.Query().Get("direct"), "1")
	if wantDirect && a.conf().Stream.DirectEnabled && kind != "remux" {
		ok(w, map[string]any{
			"channel":   map[string]any{"id": ch.ID, "name": ch.Name, "logo": ch.Logo},
			"kind":      kind,
			"mode":      "direct",
			"url":       st.URL, // 上游原始地址
			"ua":        st.UserAgent(),
			"headers":   st.Headers(),
			"expires":   exp,
			"stream_id": st.ID,
		})
		a.st.Audit(r.Context(), 0, "play.direct", fmt.Sprint(id), ch.Name, peerIPFrom(r.Context()))
		return
	}

	ok(w, map[string]any{
		"channel":   map[string]any{"id": ch.ID, "name": ch.Name, "logo": ch.Logo},
		"kind":      kind,
		"mode":      "relay",
		"url":       path + "?" + q.Encode(),
		"expires":   exp,
		"stream_id": st.ID,
		// ua：这条线路专属的 UA（来自源配置）。客户端直连上游时要带上，
		// 否则源站会 403。为空表示用默认 UA。
		"ua": st.UserAgent(),
		// headers：整条线路的请求头（已清洗）。R09 之后两端 DTO 都要解码它，
		// 让 direct/relay 的自适应切换不至于因为缺头而失败。
		"headers": a.streamHeaders(id, st),
	})

	// 记一次播放，供概览页的「今日起播次数」统计
	a.st.Audit(r.Context(), 0, "play", fmt.Sprint(id), ch.Name, peerIPFrom(r.Context()))
}

// tvTimeshift 告诉终端"这个台能回看多久"，并给出带签名的回看地址。
func (a *API) tvTimeshift(w http.ResponseWriter, r *http.Request) {
	dev, _ := deviceFrom(r.Context())
	id := pathInt64(r, "id")
	if a.ts == nil || !a.ts.Enabled() {
		ok(w, map[string]any{"available_seconds": 0, "enabled": false})
		return
	}
	span := a.ts.SpanSeconds(id)
	exp := time.Now().Add(6 * time.Hour).Unix()
	dk := a.signer.DeriveDeviceKey(dev.DeviceKey)
	sig := a.signer.Sign(id, exp, dk)
	q := url.Values{}
	q.Set("sig", sig)
	q.Set("exp", strconv.FormatInt(exp, 10))
	q.Set("dk", dk)
	ok(w, map[string]any{
		"enabled":           true,
		"available_seconds": span,
		"url": fmt.Sprintf("%s/live/%d/timeshift.ts?%s",
			a.conf().Server.Prefix, id, q.Encode()),
	})
}

// liveTimeshift 从时移缓冲里读并吐给客户端。back 是"距今多少秒"。
func (a *API) liveTimeshift(w http.ResponseWriter, r *http.Request) {
	id, _, ok2 := a.verifyStream(r)
	if !ok2 {
		fail(w, http.StatusForbidden, 2002, "回看地址无效或已过期")
		return
	}
	if a.ts == nil || !a.ts.Enabled() {
		fail(w, http.StatusNotFound, 3001, "时移未开启")
		return
	}
	back, _ := strconv.Atoi(r.URL.Query().Get("back"))
	if back < 0 {
		back = 0
	}
	rd, err := a.ts.Open(id, back)
	if err != nil {
		fail(w, http.StatusNotFound, 3002, err.Error())
		return
	}
	defer rd.Close()

	flusher, canFlush := w.(http.Flusher)
	w.Header().Set("Content-Type", "video/mp2t")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.WriteHeader(http.StatusOK)

	buf := make([]byte, 64*1024)
	for {
		n, rerr := rd.Read(buf)
		if n > 0 {
			if _, werr := w.Write(buf[:n]); werr != nil {
				return
			}
			if canFlush {
				flusher.Flush()
			}
		}
		if rerr != nil {
			return
		}
		select {
		case <-r.Context().Done():
			return
		default:
		}
	}
}

// tvSaveProgress 终端上报"我在看哪个台、看到第几秒"。
func (a *API) tvSaveProgress(w http.ResponseWriter, r *http.Request) {
	dev, okDev := deviceFrom(r.Context())
	if !okDev {
		fail(w, http.StatusUnauthorized, 2003, "设备未绑定")
		return
	}
	var body struct {
		ChannelID   int64 `json:"channel_id"`
		PositionSec int   `json:"position_sec"`
	}
	if err := decode(r, &body); err != nil {
		fail(w, http.StatusBadRequest, 1001, "参数错误: "+err.Error())
		return
	}
	if body.ChannelID <= 0 {
		fail(w, http.StatusBadRequest, 1001, "channel_id 不能为空")
		return
	}
	_ = a.st.UpsertProgress(r.Context(), dev.ID, body.ChannelID, body.PositionSec)
	ok(w, map[string]any{"saved": true})
}

// tvResume 返回"最近在别的设备上看过的频道"，用于跨端续播提示。
func (a *API) tvResume(w http.ResponseWriter, r *http.Request) {
	dev, okDev := deviceFrom(r.Context())
	if !okDev {
		fail(w, http.StatusUnauthorized, 2003, "设备未绑定")
		return
	}
	rp, err := a.st.LatestResume(r.Context(), dev.ID)
	if err != nil {
		// 没有记录不是错误，返回空让客户端安静处理
		ok(w, map[string]any{"available": false})
		return
	}
	// 时移能不能覆盖到这个位置，决定了客户端是"从偏移处播"还是"直接看直播"
	tsAvail := 0
	if a.ts != nil && a.ts.Enabled() {
		tsAvail = a.ts.SpanSeconds(rp.ChannelID)
	}
	ok(w, map[string]any{
		"available":           true,
		"channel_id":          rp.ChannelID,
		"channel_name":        rp.ChannelName,
		"channel_logo":        rp.ChannelLogo,
		"group":               rp.Group,
		"position_sec":        rp.PositionSec,
		"device_name":         rp.DeviceName,
		"updated_at":          rp.UpdatedAt,
		"age_seconds":         int(time.Now().Unix() - rp.UpdatedAt),
		"timeshift_available": tsAvail,
	})
}

// verifyStream 校验签名，返回频道 ID 与设备标识。
func (a *API) verifyStream(r *http.Request) (int64, string, bool) {
	id, dk, _, ok := a.verifyStreamFull(r)
	return id, dk, ok
}

// verifyStreamFull 与 verifyStream 相同，另外返回签名里绑定的线路 id。
//
// R13：线路选择必须由签名承载。只靠查询参数 stream_id 的话，
// 攻击者可以随便改它去探测其他线路；更重要的是各拉流入口
// 需要知道"用户到底选了哪条"，否则又会各自 BestStream。
func (a *API) verifyStreamFull(r *http.Request) (id int64, dk string, streamID int64, ok bool) {
	id = pathInt64(r, "id")
	q := r.URL.Query()
	exp, _ := strconv.ParseInt(q.Get("exp"), 10, 64)
	// 查询参数里的 stream_id 只是"待校验的候选值"，
	// 只有签名里真的绑定了它才算数（见 VerifyStreamSig）。
	cand, _ := strconv.ParseInt(q.Get("stream_id"), 10, 64)
	gotSID, err := a.signer.VerifyStreamSig(id, cand, exp, q.Get("dk"), q.Get("sig"))
	if err != nil {
		return 0, "", 0, false
	}
	return id, q.Get("dk"), gotSID, true
}

// streamForPlay 解析本次请求该用哪条线路。
//
// 优先用签名里绑定的线路（R13）；没有绑定就回落到 BestStream。
// 绑定的线路必须属于该频道，否则说明签名被跨频道复用，直接拒绝。
func (a *API) streamForPlay(ctx context.Context, channelID, boundStreamID int64) (*store.Stream, error) {
	if boundStreamID > 0 {
		all, err := a.st.ListStreams(ctx, channelID)
		if err != nil {
			return nil, err
		}
		for i := range all {
			if all[i].ID == boundStreamID {
				return &all[i], nil
			}
		}
		// 绑定线路不属于这个频道：签名不可信。
		return nil, fmt.Errorf("线路 %d 不属于频道 %d", boundStreamID, channelID)
	}
	return a.st.BestStream(ctx, channelID)
}
