package api

import (
	"context"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/litv/litv/server/internal/dlna"
)

// ---------- DLNA 投屏（控制端） ----------

// 投屏设备发现的缓存参数。
const (
	// rendererRefreshTTL 是「列表页多久重新扫一次 SSDP」。
	// 单次 SSDP 扫描要 ~3 秒，不缓存的话每打开一次投屏面板就等三秒。
	rendererRefreshTTL = 60 * time.Second

	// rendererTrustTTL 是「一个 control_url 在多长时间内仍被视为可信」。
	//
	// 必须明显长于 refreshTTL：用户可能在面板上停留几分钟才点投屏，
	// 期间没有发起过刷新。超过这个窗口就 fail-closed（要求重新扫一次），
	// 不给"很久以前发现过的任意地址"长期开绿灯。
	rendererTrustTTL = 10 * time.Minute
)

// tvDLNARenderers 列出局域网里可投屏的设备。
//
// 由服务端做发现的原因：iOS 14+ 对本地网络多播有严格限制，
// SSDP 需要 Apple 单独批准的 multicast 权限（要审核，自签包拿不到）。
// NAS 和电视在同一局域网，由它发 M-SEARCH 没有任何限制，
// 手机端只需要调这个接口拿列表——两端（安卓/iOS）逻辑还能完全一致。
//
// 结果会缓存 rendererRefreshTTL：这个接口的返回值同时是 cast/stop 的
// 白名单来源，所以必须和它们共用同一份数据。
func (a *API) tvDLNARenderers(w http.ResponseWriter, r *http.Request) {
	if _, okDev := deviceFrom(r.Context()); !okDev {
		fail(w, http.StatusUnauthorized, 2003, "设备未绑定")
		return
	}
	list := a.discoverRenderers(r.Context(), false)
	ok(w, map[string]any{"total": len(list), "items": list})
}

// discoverRenderers 返回投屏设备列表，按需刷新缓存。
//
// force=true 表示忽略缓存强制重扫（管理台/用户主动"刷新"时用）。
func (a *API) discoverRenderers(ctx context.Context, force bool) []dlna.Renderer {
	a.rendererMu.Lock()
	defer a.rendererMu.Unlock()

	fresh := !a.rendererAt.IsZero() && time.Since(a.rendererAt) < rendererRefreshTTL
	if !force && fresh {
		return a.rendererList
	}
	// 扫描要 3 秒，持锁会挡住并发的 cast/stop 校验。
	// 这里刻意接受这个代价：扫描本身是串行资源（同一网段多播），
	// 并发两次扫描只会互相干扰；而 cast/stop 的等待上限就是 3 秒。
	list, err := dlna.DiscoverRenderers(ctx, 3*time.Second)
	if err != nil {
		// 扫描失败不清空旧缓存：局域网一时抖一下不该让已经发现的设备立刻不可用。
		// 返回旧值（可能为空）由调用方决定怎么处理。
		return a.rendererList
	}
	a.rendererList = list
	a.rendererAt = time.Now()
	return list
}

// rendererTrusted 判断 control_url 是否是本进程近期真的发现过的设备。
//
// 为什么必须做这件事：control_url 来自客户端请求体。不校验的话，
// 任何一台已绑定的设备（家人手机也算）都能让 NAS 向任意地址
// POST 一段 SOAP 请求——那是一个现成的 SSRF 跳板，
// 而投屏功能根本不需要访问"没被发现过的地址"。
//
// 比较用**规范化**后的 scheme+host+port+path：
//   - 大小写、默认端口（http:80）要归一，否则同一台设备换个写法就匹配不上；
//   - 必须比 path：只比 host 会让同一台设备上的任意 UPnP 服务都通过。
//
// 没有缓存（从未发现过任何设备）时返回 false —— fail closed。
func (a *API) rendererTrusted(controlURL string) bool {
	want, ok := normalizeControlURL(controlURL)
	if !ok {
		return false
	}
	a.rendererMu.Lock()
	defer a.rendererMu.Unlock()
	if a.rendererAt.IsZero() || time.Since(a.rendererAt) > rendererTrustTTL {
		return false
	}
	for _, rd := range a.rendererList {
		got, okc := normalizeControlURL(rd.ControlURL)
		if okc && got == want {
			return true
		}
	}
	return false
}

// normalizeControlURL 把 control_url 归一成 "scheme://host:port/path"。
//
// 解析失败、scheme 不是 http/https、没有 host 的都返回 ok=false。
func normalizeControlURL(raw string) (string, bool) {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || u.Host == "" {
		return "", false
	}
	scheme := strings.ToLower(u.Scheme)
	if scheme != "http" && scheme != "https" {
		return "", false
	}
	host := strings.ToLower(u.Hostname())
	port := u.Port()
	if port == "" {
		if scheme == "http" {
			port = "80"
		} else {
			port = "443"
		}
	}
	path := u.EscapedPath()
	if path == "" {
		path = "/"
	}
	return scheme + "://" + host + ":" + port + path, true
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
	// control_url 必须在"本进程近期真的发现过"的设备列表里。
	//
	// 不做这件事的话，任何一台已绑定设备都能让 NAS 向任意地址 POST 一段
	// SOAP——那是现成的 SSRF 跳板。投屏功能完全不需要访问没被发现过的地址，
	// 所以这里 fail closed：没缓存就 400，让客户端先去拉一次设备列表。
	if !a.rendererTrusted(body.ControlURL) {
		fail(w, http.StatusBadRequest, 1001,
			"该投屏设备不在最近发现的列表里，请重新打开投屏设备列表")
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

	// 给电视的播放地址：服务端中转，签名 6 小时有效
	exp := a.now().Add(6 * time.Hour).Unix()
	sig := a.signer.SignStream(ch.ID, st.ID, exp, dev.DeviceKey)
	q := url.Values{}
	q.Set("sig", sig)
	q.Set("exp", strconv.FormatInt(exp, 10))
	q.Set("dk", dev.DeviceKey)
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
	// 和 cast 一样要过白名单：只修 cast 等于留一半——
	// stop 同样是一次"由 NAS 向指定地址 POST"的出站请求。
	if !a.rendererTrusted(body.ControlURL) {
		fail(w, http.StatusBadRequest, 1001,
			"该投屏设备不在最近发现的列表里，请重新打开投屏设备列表")
		return
	}
	if err := dlna.Stop(r.Context(), body.ControlURL); err != nil {
		fail(w, http.StatusBadGateway, 5002, "停止失败: "+err.Error())
		return
	}
	ok(w, map[string]any{"stopped": true})
}
