package api

// 管理台网页播放：网页端起播用的固定设备标识与绑定码生成。

import (
	"crypto/rand"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"time"
)

// webPlay 给管理台发一个短期签名播放地址（浏览器里直接看直播）。
//
// 为什么不让管理台直接用设备 token 那套：管理台走的是飞牛网关，
// 身份是 fnOS 用户，不是设备。这里复用同一套 HMAC 签名，
// 但把有效期压到 2 小时、并且绑定一个固定的 "web" 标识。
func (a *API) webPlay(w http.ResponseWriter, r *http.Request) {
	id := pathInt64(r, "id")
	ch, err := a.st.GetChannel(r.Context(), id)
	if err != nil {
		fail(w, http.StatusNotFound, 3001, "频道不存在")
		return
	}
	st, err := a.st.BestStream(r.Context(), id)
	if err != nil {
		fail(w, http.StatusNotFound, 3002, "该频道没有可用线路")
		return
	}

	// 浏览器只能播 HLS：上游是 m3u8 就直接用，是裸 TS 就走转封装
	kind := "hls"
	if !isHLSSource(st.URL) {
		if a.rx == nil || !a.rx.Available() {
			fail(w, http.StatusServiceUnavailable, 5001,
				"该频道是裸 TS 源，浏览器播不了。请服务端配置 ffmpeg 开启转封装。")
			return
		}
		kind = "remux"
	}

	exp := time.Now().Add(2 * time.Hour).Unix()
	sig := a.signer.Sign(id, exp, webDeviceKey)
	q := url.Values{}
	q.Set("sig", sig)
	q.Set("exp", strconv.FormatInt(exp, 10))
	q.Set("dk", webDeviceKey)

	path := fmt.Sprintf("%s/live/%d/index.m3u8", a.conf().Server.Prefix, id)
	if kind == "remux" {
		path = fmt.Sprintf("%s/live/%d/remux.m3u8", a.conf().Server.Prefix, id)
	}

	ok(w, map[string]any{
		"channel": map[string]any{"id": ch.ID, "name": ch.Name, "logo": ch.Logo},
		"kind":    kind,
		"url":     path + "?" + q.Encode(),
		"expires": exp,
	})
}

// webDeviceKey 是管理台播放用的固定标识（签名里带上，便于日志区分）。
const webDeviceKey = "web-admin"

func randomCode(n int) string {
	// 用 crypto/rand，避免时间种子溢出导致负下标 panic，同时防止绑定码被预测。
	const digits = "0123456789"
	buf := make([]byte, n)
	if _, err := rand.Read(buf); err != nil {
		// 极端情况下退化为时间取模，仍然可用
		seed := time.Now().UnixNano()
		for i := range buf {
			buf[i] = digits[int(seed%10)]
			seed /= 10
		}
		return string(buf)
	}
	out := make([]byte, n)
	for i, v := range buf {
		out[i] = digits[int(v)%len(digits)]
	}
	return string(out)
}
