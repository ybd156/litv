package api

// 实况直出与转封装：裸 TS / HLS 直出、时移回看重封装、manifest 与分片。

import (
	"bufio"
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/litv/litv/server/internal/fetch"
	"github.com/litv/litv/server/internal/safedial"
	"github.com/litv/litv/server/internal/store"
	"github.com/litv/litv/server/internal/stream"
)

// liveRemuxPlaylist 返回转封装后的 m3u8。
// 切片 URI 会被改写成 /live/{id}/remux/{seg}，复用同一套签名鉴权。
func (a *API) liveRemuxPlaylist(w http.ResponseWriter, r *http.Request) {
	id, _, sid, ok2 := a.verifyStreamFull(r)
	if !ok2 {
		fail(w, http.StatusForbidden, 2002, "播放地址无效或已过期")
		return
	}
	if a.rx == nil {
		fail(w, http.StatusServiceUnavailable, 5001, "服务端未启用转封装")
		return
	}
	// R13：用签名里绑定的线路，尊重手动选择
	st, err := a.streamForPlay(r.Context(), id, sid)
	if err != nil {
		fail(w, http.StatusNotFound, 3002, "该频道没有可用线路")
		return
	}

	// 切片 URI 会被改写为 /live/{id}/remux/{seg}，签名通过查询串补到每个 URI 上
	segPrefix := fmt.Sprintf("%s/live/%d/remux/", a.conf().Server.Prefix, id)

	// R09：转封装也必须用同一份线路头。源站校验覆盖整条链路，
	// ffmpeg 用自己的默认 UA 会被 403，表现为"iOS 播不了但电视能播"。
	data, err := a.rx.PlaylistContext(r.Context(), id, st.URL, segPrefix, a.streamHeaders(id, st))
	if err != nil {
		fail(w, http.StatusBadGateway, 4001, err.Error())
		return
	}
	// 切片请求需要带上同样的签名，这里把查询串补到每个 URI 上
	if q := r.URL.RawQuery; q != "" {
		data = appendQueryToSegments(data, q)
	}

	w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Access-Control-Allow-Origin", "*")
	_, _ = w.Write(data)
}

// liveRemuxSegment 返回一个 HLS 切片。
func (a *API) liveRemuxSegment(w http.ResponseWriter, r *http.Request) {
	id, _, ok2 := a.verifyStream(r)
	if !ok2 {
		fail(w, http.StatusForbidden, 2002, "播放地址无效或已过期")
		return
	}
	if a.rx == nil {
		fail(w, http.StatusServiceUnavailable, 5001, "服务端未启用转封装")
		return
	}
	seg := chi.URLParam(r, "seg")
	f, modTime, err := a.rx.OpenSegment(id, seg)
	if err != nil {
		fail(w, http.StatusNotFound, 3001, err.Error())
		return
	}
	defer f.Close()
	// 用 ServeContent 而不是把整个切片读进内存再 Write：
	// 它能正确处理 Range（播放器 seek/断点续传会发 Range，以前一律回 200
	// 全量，等于每次 seek 都重传整片），并自动带 Content-Length/Last-Modified。
	w.Header().Set("Content-Type", "video/mp2t")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Access-Control-Allow-Origin", "*")
	http.ServeContent(w, r, seg, modTime, f)
}

// livePlay 是个分发器：告诉客户端该走 TS 还是 HLS。
func (a *API) livePlay(w http.ResponseWriter, r *http.Request) {
	slog.Info("livePlay", "path", r.URL.Path)
	id, _, sid, ok2 := a.verifyStreamFull(r)
	if !ok2 {
		fail(w, http.StatusForbidden, 2002, "播放地址无效或已过期")
		return
	}
	// R13：用签名里绑定的线路判断该走 TS 还是 HLS，
	// 否则会把用户选中的 TS 线路判成 HLS（或反之），跳转到错误的入口。
	st, err := a.streamForPlay(r.Context(), id, sid)
	if err != nil {
		fail(w, http.StatusNotFound, 3002, "该频道没有可用线路")
		return
	}
	target := fmt.Sprintf("%s/live/%d/stream.ts?%s", a.conf().Server.Prefix, id, r.URL.RawQuery)
	if isHLSSource(st.URL) {
		target = fmt.Sprintf("%s/live/%d/index.m3u8?%s", a.conf().Server.Prefix, id, r.URL.RawQuery)
	}
	http.Redirect(w, r, target, http.StatusFound)
}

// liveTS 是核心：把上游 TS 字节流转给终端，多终端共用一路上游。
func (a *API) liveTS(w http.ResponseWriter, r *http.Request) {
	id, _, sid, ok2 := a.verifyStreamFull(r)
	if !ok2 {
		fail(w, http.StatusForbidden, 2002, "播放地址无效或已过期")
		return
	}
	// R13：用签名里绑定的线路，尊重手动选择
	st, err := a.streamForPlay(r.Context(), id, sid)
	if err != nil {
		fail(w, http.StatusNotFound, 3002, "该频道没有可用线路")
		return
	}

	// B02：先订阅再写响应头。
	//
	// 原来的顺序是 WriteHeader(200) 之后才 Subscribe，于是订阅失败
	// （比如并发流已达上限）只能静默 return——用户看到的是 200 加一片空白，
	// 完全不知道发生了什么。现在把订阅挪到写头之前，失败能返回正确的状态码。
	//
	// 带上这条线路专属的头：不传的话源站校验 UA 会直接 403，
	// 表现为"有画面没声音"或直接黑屏。
	sub, subErr := a.hub.Subscribe(r.Context(), id, st.URL, st.Headers())
	if subErr != nil {
		if errors.Is(subErr, stream.ErrTooManyStreams) {
			fail(w, http.StatusServiceUnavailable, 3003,
				"并发流已达上限，请稍后再试或调高「并发上限」")
			return
		}
		fail(w, http.StatusBadGateway, 4001, "订阅上游失败: "+subErr.Error())
		return
	}

	flusher, canFlush := w.(http.Flusher)
	w.Header().Set("Content-Type", "video/mp2t")
	w.Header().Set("Cache-Control", "no-cache, no-store")
	w.Header().Set("Connection", "keep-alive")
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.WriteHeader(http.StatusOK)

	for {
		select {
		case <-r.Context().Done():
			return
		case chunk, open := <-sub.C:
			if !open {
				return
			}
			if _, werr := w.Write(chunk); werr != nil {
				return
			}
			if canFlush {
				flusher.Flush()
			}
		}
	}
}

// liveManifest 代理 HLS 清单，并把分片地址改写成我们自己的出口（防上游防盗链、防源泄露）。
func (a *API) liveManifest(w http.ResponseWriter, r *http.Request) {
	id, dk, sid, ok2 := a.verifyStreamFull(r)
	if !ok2 {
		fail(w, http.StatusForbidden, 2002, "播放地址无效或已过期")
		return
	}
	// 逐条线路尝试：源里常有一批签名过期的死线路，
	// 只试第一条的话，碰到死的就整个台播不了。
	streams, serr := a.st.ListStreams(r.Context(), id)
	if serr != nil || len(streams) == 0 {
		fail(w, http.StatusNotFound, 3002, "该频道没有可用线路")
		return
	}

	// R13：用户手动选了线路就优先试它。
	// 仍然保留"失败再试别的"——死线路不该让整个台播不了，
	// 但"能用哪条"的第一顺位必须是用户的选择，否则切了等于没切。
	if sid > 0 {
		for i := range streams {
			if streams[i].ID == sid {
				first := streams[i]
				copy(streams[1:], streams[0:i])
				streams[0] = first
				break
			}
		}
	}

	// 同一频道的并发请求合并成一次上游抓取。
	// 多台设备同时看同一个台时，以前每个客户端每 2–6 秒各抓一次清单，
	// 既压上游，也把 UpdateStreamHealth 变成高频写库。
	slot := a.manifestSlotFor(id)
	slot.mu.Lock()
	defer slot.mu.Unlock()
	// 命中条件里带上 sid：用户手动切了线路时不能用别的线路的缓存，
	// 否则「切了等于没切」——清单里的分片地址还是旧线路的。
	// sid=0（没指定）时按频道复用，这才是多设备同时看的主路径。
	if slot.fresh(time.Now()) && (sid == 0 || slot.streamID == sid) {
		a.writeManifest(w, manifestSnapshot{body: slot.body, base: slot.base, streamID: slot.streamID}, id, dk)
		return
	}

	var (
		res     *fetch.Result
		st      *store.Stream
		lastErr error
	)
	for i := range streams {
		cand := &streams[i]
		if !isHLSSource(cand.URL) {
			lastErr = fmt.Errorf("不是 HLS 线路")
			continue
		}
		// 单条线路等 15 秒：源站冷请求有时要 8-10 秒，给太短会误判成死线路。
		// 等满默认的 20 秒就没时间试下一条了。
		cctx, cancel := context.WithTimeout(r.Context(), 15*time.Second)
		opt := fetch.DefaultOptions()
		// R09：整条线路的请求头都要带上，不只是 UA。
		// 只在 UA=X 且 Referer=Y 时才给流的源，缺任何一条都是 403。
		opt.Headers = a.streamHeaders(id, cand)
		if ua := cand.UserAgent(); ua != "" {
			opt.UserAgent = ua
		} else if a.conf().Stream.UserAgent != "" {
			opt.UserAgent = a.conf().Stream.UserAgent
		}
		got, ferr := fetch.Get(cctx, cand.URL, opt)
		cancel()
		if ferr != nil {
			lastErr = ferr
			// 记一笔不健康，让 BestStream 下次把它排后面
			_ = a.st.UpdateStreamHealth(r.Context(), cand.ID, -1, 0)
			continue
		}
		res, st = got, cand
		_ = a.st.UpdateStreamHealth(r.Context(), cand.ID, 1, int(got.ElapsedMs))
		break
	}
	if res == nil || st == nil {
		msg := "拉取清单失败"
		if lastErr != nil {
			msg += ": " + lastErr.Error()
		}
		slog.Warn("manifest 失败", "channel", id, "err", msg)
		fail(w, http.StatusBadGateway, 4001, msg)
		return
	}
	slog.Info("manifest 成功", "channel", id, "stream", st.ID, "size", len(res.Body))
	base, _ := url.Parse(res.FinalURL)
	slot.body = res.Body
	slot.base = base
	slot.streamID = st.ID
	slot.at = time.Now()
	a.writeManifest(w, manifestSnapshot{body: res.Body, base: base, streamID: st.ID}, id, dk)
}

// liveSegment 代理 HLS 分片（含子清单）。
func (a *API) liveSegment(w http.ResponseWriter, r *http.Request) {
	// 注意：这里**不能**先走 verifyStreamFull。
	//
	// /seg 的签名是「绑定目标」的分片签名，它和频道级播放签名是两套
	// 不同内容的 HMAC，一个签名不可能同时满足两者。
	// 先要求频道级校验的话，segmentURL 生成的地址会永远 403——
	// 清单能取到、分片全被拒，表现就是"有列表但画面全黑"。
	//
	// 所以分片签名**替代**频道级校验，而不是叠加：
	// 它自己已经绑定了用途、频道、目标地址、到期时间和设备。
	id := pathInt64(r, "id")
	dk := r.URL.Query().Get("dk")
	target := r.URL.Query().Get("u")
	if target == "" {
		fail(w, http.StatusBadRequest, 1001, "缺少分片地址")
		return
	}
	// 防 SSRF：只允许 http/https
	pu, err := url.Parse(target)
	if err != nil || (pu.Scheme != "http" && pu.Scheme != "https") {
		fail(w, http.StatusBadRequest, 1001, "非法的分片地址")
		return
	}

	// R04：校验分片签名，它绑定了「用途 + 频道 + 目标地址 + 到期 + 设备」。
	// 改 u / 换频道 / 换设备 / 改到期 任一都会失败。
	exp, _ := strconv.ParseInt(r.URL.Query().Get("exp"), 10, 64)
	if err := a.signer.VerifySegment(id, target, exp, dk, r.URL.Query().Get("sig")); err != nil {
		fail(w, http.StatusForbidden, 2002, "分片地址无效或已过期")
		return
	}
	// 分片签名不携带线路信息（签名格式不能为此改），
	// 所以优先用「刚给这台设备发清单时选中的线路」。
	// 缓存过期再回落到最佳线路。

	// 这单请求是要打到 LAN 的话，留一条审计痕迹（按频道 + 时间窗去重）。
	// 解析失败不是错误：审计是附加信息，不能因为它让播放失败。
	if addr, ok := resolveTargetAddr(r.Context(), target); ok {
		a.notePrivateSegmentTarget(r.Context(), id, addr)
	}

	req, err := http.NewRequestWithContext(r.Context(), http.MethodGet, target, nil)
	if err != nil {
		fail(w, http.StatusBadGateway, 4001, err.Error())
		return
	}
	// R09：分片也要带整条线路的头。源站校验是覆盖整条链路的，
	// 不只是清单请求；只带 UA 时，要求 Referer 的源会在分片这一步 403，
	// 表现是清单能取到、画面却一片黑。
	// R13：用清单选中的线路取头，保证与清单请求是同一条线路的配置。
	var boundStream *store.Stream
	if cachedSID, okc := a.cachedManifestStream(id); okc {
		if st, serr := a.streamForPlay(r.Context(), id, cachedSID); serr == nil {
			boundStream = st
		}
	} else if st, serr := a.streamForPlay(r.Context(), id, 0); serr == nil {
		boundStream = st
	}
	for k, v := range a.streamHeaders(id, boundStream) {
		req.Header.Set(k, v)
	}
	req.Header.Set("Accept", "*/*")
	req.Header.Set("Cache-Control", "no-cache")

	// 用 a.segClient 而不是 http.DefaultClient：
	//   - 它接了 safedial，拒绝"让 NAS 打自己"与云元数据端点；
	//   - 它没有总超时（分片是流式传输，见 api.segTransport 的注释）；
	//   - 它透传 r.Context()，客户端断开时上游读取立刻取消。
	client := a.segClient
	if client == nil {
		// New() 一定会赋值；这里只防手搓 API 的测试。
		client = a.streamClient
	}
	if client == nil {
		client = http.DefaultClient
	}
	resp, err := client.Do(req)
	if err != nil {
		if safedial.IsBlockedError(err) {
			// 地址被策略拒绝是内容/配置问题，不是上游挂了：
			// 返回 403 而不是 502，免得被当成"这个台临时故障"去重试。
			fail(w, http.StatusForbidden, 2002, "分片目标地址不被允许")
			return
		}
		fail(w, http.StatusBadGateway, 4001, err.Error())
		return
	}
	defer resp.Body.Close()

	// 嗅一下是不是子清单：源里很多台是「主清单 → 子清单 → 分片」的嵌套结构。
	// 子清单里的分片地址是相对路径，必须像主清单一样改写，
	// 否则 hls.js 会拿相对路径去拼，拼出来是坏地址 —— 表现就是「这个台播不了」。
	br := bufio.NewReader(resp.Body)
	if head, _ := br.Peek(7); bytes.HasPrefix(head, []byte("#EXTM3U")) {
		body, _ := io.ReadAll(io.LimitReader(br, 4<<20))

		// R14：基准地址必须是**实际最终响应**的 URL，不是我们请求的那个。
		//
		// 源站很常见 302 到另一个目录（CDN 调度、鉴权跳板）。
		// 用请求的 target 当基准，相对路径会解析到错误目录——
		// 表现是 master 能取到、variant 却 404，用户看到"这个台播不了"。
		// http.Client 已经跟随重定向，最终地址在 resp.Request.URL。
		base := pu
		if resp.Request != nil && resp.Request.URL != nil {
			base = resp.Request.URL
		}

		// 递归深度限制：清单可以嵌套，但不能无限嵌套。
		// 恶意或损坏的源可以构造 A->B->A 的循环，把 NAS 卡在这里。
		depth, _ := strconv.Atoi(r.URL.Query().Get("depth"))
		if depth <= 0 {
			depth = 1
		}
		const maxDepth = 4

		lines := strings.Split(string(body), "\n")
		out := make([]string, 0, len(lines))
		for _, line := range lines {
			trimmed := strings.TrimSpace(line)
			if trimmed == "" || strings.HasPrefix(trimmed, "#") {
				out = append(out, rewriteURIAttrDepth(trimmed, base, a, id, dk, depth))
				continue
			}
			if u, uerr := base.Parse(trimmed); uerr == nil {
				out = append(out, a.segmentURLDepth(u.String(), id, dk, depth+1, maxDepth))
			} else {
				out = append(out, line)
			}
		}
		w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
		w.Header().Set("Cache-Control", "no-cache")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		_, _ = io.WriteString(w, strings.Join(out, "\n"))
		return
	}

	ct := resp.Header.Get("Content-Type")
	if ct == "" {
		ct = "video/mp2t"
	}

	// 体积护栏必须在**写响应头之前**判完。
	//
	// 反例（就是不能用 io.Copy + io.LimitReader 到上限的原因）：
	// 被 LimitReader 截断时 Copy 返回的是 nil（被限流不算错误），
	// 而 200 状态行早就发出去了 —— 播放器收到一个"状态码 200 的残片"，
	// 表现为花屏且**不会**触发换线重试，比一个明确的 502 难查得多。
	if resp.ContentLength > segMaxBytes {
		fail(w, http.StatusBadGateway, 4001,
			fmt.Sprintf("分片体积超出上限（%d 字节）", segMaxBytes))
		return
	}
	w.Header().Set("Content-Type", ct)
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.WriteHeader(resp.StatusCode)
	// 上游没给 Content-Length 时只能边走边数：超过上限就中断连接
	// （abortAfterWriter 里 panic(http.ErrAbortHandler)），
	// 绝不以"200 + 短体"收尾。
	if _, cerr := io.Copy(&abortAfterWriter{w: w, limit: segMaxBytes}, br); cerr != nil {
		// 只有下游断开才会走到这里（写失败）。客户端已经不在了，正常返回。
		return
	}
}
