"""liveSegment 支持嵌套 HLS 清单。

问题：源里很多台是嵌套结构（主清单 → 子清单 → 分片）。
liveManifest 会改写主清单里的地址，但子清单是当"分片"走 liveSegment 拿的，
原来原样透传，子清单里的相对路径就没人改写了 —— hls.js 拼出坏地址，
表现就是"这些台播不了"。只有单层清单的台（比如 MCP 那批）正常。

改法：用 bufio.Peek 嗅一下前 7 字节，是 #EXTM3U 就按清单改写（和 liveManifest 同一套逻辑），
      否则照旧流式透传。
"""
import io

p = "server/internal/api/handlers_client.go"
s = io.open(p, encoding="utf-8").read()

# 1) liveSegment 需要 dk 来签子清单里的地址
s = s.replace(
    """func (a *API) liveSegment(w http.ResponseWriter, r *http.Request) {
	id, _, ok2 := a.verifyStream(r)""",
    """func (a *API) liveSegment(w http.ResponseWriter, r *http.Request) {
	id, dk, ok2 := a.verifyStream(r)""",
    1,
)

# 2) 透传改成「先嗅清单」
old = """	ct := resp.Header.Get("Content-Type")
	if ct == "" {
		ct = "video/mp2t"
	}
	w.Header().Set("Content-Type", ct)
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.WriteHeader(resp.StatusCode)
	_, _ = io.Copy(w, resp.Body)
	_ = id
}"""

new = """	// 嗅一下是不是子清单：源里很多台是「主清单 → 子清单 → 分片」的嵌套结构。
	// 子清单里的分片地址是相对路径，必须像主清单一样改写，
	// 否则 hls.js 会拿相对路径去拼，拼出来是坏地址 —— 表现就是「这个台播不了」。
	br := bufio.NewReader(resp.Body)
	if head, _ := br.Peek(7); bytes.HasPrefix(head, []byte("#EXTM3U")) {
		body, _ := io.ReadAll(io.LimitReader(br, 4<<20))
		base, perr := url.Parse(target)
		if perr == nil {
			lines := strings.Split(string(body), "\\n")
			out := make([]string, 0, len(lines))
			for _, line := range lines {
				trimmed := strings.TrimSpace(line)
				if trimmed == "" || strings.HasPrefix(trimmed, "#") {
					out = append(out, rewriteURIAttr(trimmed, base, a, id, dk))
					continue
				}
				if u, uerr := base.Parse(trimmed); uerr == nil {
					out = append(out, a.segmentURL(u.String(), id, dk))
				} else {
					out = append(out, line)
				}
			}
			w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
			w.Header().Set("Cache-Control", "no-cache")
			w.Header().Set("Access-Control-Allow-Origin", "*")
			_, _ = io.WriteString(w, strings.Join(out, "\\n"))
			return
		}
		// 解析失败就退回普通分片处理
		w.Header().Set("Content-Type", "video/mp2t")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		_, _ = w.Write(body)
		return
	}

	ct := resp.Header.Get("Content-Type")
	if ct == "" {
		ct = "video/mp2t"
	}
	w.Header().Set("Content-Type", ct)
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.WriteHeader(resp.StatusCode)
	_, _ = io.Copy(w, br)
}"""

assert old in s, "找不到 liveSegment 的透传段"
s = s.replace(old, new, 1)

# 3) 补 import
for pkg in ("bufio", "bytes"):
    if f'"{pkg}"' not in s:
        s = s.replace('import (\n', f'import (\n\t"{pkg}"\n', 1)

io.open(p, "w", encoding="utf-8").write(s)
print("liveSegment: 已支持嵌套清单")
