"""HLS 清单拉取失败时自动换下一条线路。

问题：一个频道常有多条线路，其中一部分是死的（源站签名过期，302 到演示视频）。
原来的 liveManifest 只取 BestStream 一条，拉到死线路就干等 20 秒超时再报 502，
表现就是「这个台播不了」——而实际上同一频道别的线路是好的。

改法：按健康度顺序逐条尝试，每条给 6 秒，成功即用；
      失败的线路标记为不健康，下次 BestStream 会排到后面。
"""
import io
import re

p = "server/internal/api/handlers_client.go"
s = io.open(p, encoding="utf-8").read()

old = """	st, err := a.st.BestStream(r.Context(), id)
	if err != nil {
		fail(w, http.StatusNotFound, 3002, "该频道没有可用线路")
		return
	}
	if !strings.Contains(strings.ToLower(st.URL), ".m3u8") {
		fail(w, http.StatusBadRequest, 3002, "该频道是裸 TS 源，请使用 stream.ts")
		return
	}

	// 必须带线路专属 UA，否则源站会返回演示内容或者直接拒绝
	res, err := fetch.Get(r.Context(), st.URL, a.streamFetchOptions(r.Context(), id))
	if err != nil {
		fail(w, http.StatusBadGateway, 4001, "拉取清单失败: "+err.Error())
		return
	}"""

new = """	// 逐条线路尝试：源里常有一批签名过期的死线路，
	// 只试第一条的话，碰到死的就整个台播不了。
	streams, serr := a.st.ListStreams(r.Context(), id)
	if serr != nil || len(streams) == 0 {
		fail(w, http.StatusNotFound, 3002, "该频道没有可用线路")
		return
	}

	var (
		res     *fetch.Result
		st      *store.Stream
		lastErr error
	)
	for i := range streams {
		cand := &streams[i]
		if !strings.Contains(strings.ToLower(cand.URL), ".m3u8") {
			lastErr = fmt.Errorf("不是 HLS 线路")
			continue
		}
		// 单条线路只等 6 秒：死线路通常一直挂着不返回，
		// 等满默认的 20 秒就没时间试下一条了。
		cctx, cancel := context.WithTimeout(r.Context(), 6*time.Second)
		opt := fetch.DefaultOptions()
		if ua := cand.UserAgent(); ua != "" {
			opt.UserAgent = ua
		} else if a.cfg.Stream.UserAgent != "" {
			opt.UserAgent = a.cfg.Stream.UserAgent
		}
		got, ferr := fetch.Get(cctx, cand.URL, opt)
		cancel()
		if ferr != nil {
			lastErr = ferr
			// 记一笔不健康，让 BestStream 下次把它排后面
			_ = a.st.SetStreamHealth(r.Context(), cand.ID, -1, 0)
			continue
		}
		res, st = got, cand
		_ = a.st.SetStreamHealth(r.Context(), cand.ID, 1, int(got.ElapsedMs))
		break
	}
	if res == nil || st == nil {
		msg := "拉取清单失败"
		if lastErr != nil {
			msg += ": " + lastErr.Error()
		}
		fail(w, http.StatusBadGateway, 4001, msg)
		return
	}"""

assert old in s, "找不到 liveManifest 的选流段"
s = s.replace(old, new, 1)
io.open(p, "w", encoding="utf-8").write(s)
print("liveManifest: 已改成逐条线路尝试")
