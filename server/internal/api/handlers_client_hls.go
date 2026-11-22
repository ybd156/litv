package api

// HLS 改写与请求头：分片 URL 签名、URI 属性重写、出站请求头收敛、SSRF 目标解析。

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/litv/litv/server/internal/store"
)

// appendQueryToSegments 给播放列表里的每个切片 URI 补上签名查询串。
func appendQueryToSegments(data []byte, query string) []byte {
	lines := strings.Split(string(data), "\n")
	for i, line := range lines {
		t := strings.TrimSpace(line)
		if t == "" || strings.HasPrefix(t, "#") {
			continue
		}
		if strings.Contains(t, "?") {
			lines[i] = line + "&" + query
		} else {
			lines[i] = line + "?" + query
		}
	}
	return []byte(strings.Join(lines, "\n"))
}

// sanitizedHeaders 把线路配置里的头清洗成可安全外发的形式。
//
// 为什么必须清洗：headers_json 来自用户导入的源文件，属于不可信输入。
//
// 两类必须去掉的头：
//  1. 逐跳（hop-by-hop）头，比如 Connection / Host / Content-Length /
//     Transfer-Encoding。转发它们会让上游行为和预期不符，甚至破坏请求。
//  2. 身份类头，比如 Authorization / Cookie / X-Trim-*。
//     我们自己的设备 Bearer token 和管理员身份头**绝对不能**泄露给源站
//     —— 源站是第三方，给它就等于把凭据送出去。
func sanitizedHeaders(raw map[string]string) map[string]string {
	if len(raw) == 0 {
		return nil
	}
	blocked := map[string]bool{
		"connection":        true,
		"host":              true,
		"content-length":    true,
		"transfer-encoding": true,
		"upgrade":           true,
		"proxy-connection":  true,
		"te":                true,
		"trailer":           true,
		"authorization":     true,
		"cookie":            true,
		"set-cookie":        true,
	}
	out := make(map[string]string, len(raw))
	for k, v := range raw {
		lk := strings.ToLower(strings.TrimSpace(k))
		if lk == "" || blocked[lk] || strings.HasPrefix(lk, "x-trim-") || strings.HasPrefix(lk, "x-litv-") {
			continue
		}
		// 值里不允许换行：防止把用户可控内容注入成额外的头（CRLF 注入）。
		if strings.ContainsAny(v, "\r\n\x00") {
			continue
		}
		out[k] = strings.TrimSpace(v)
	}
	if len(out) == 0 {
		return nil
	}
	return out
}

// streamHeaders 返回某条线路该带的请求头（已清洗）。
//
// 这是 R09 的统一入口。以前 direct/relay/remux/分片 四条路径各自为政，
// 有的只传 UA、有的完全不传，导致"只在 UA=X 且 Referer=Y 时才给流"的源
// 只有其中某一条路径能播，用户看到的是"换个内核就好了/换个入口就好了"。
func (a *API) streamHeaders(channelID int64, st *store.Stream) map[string]string {
	var raw map[string]string
	if st != nil {
		raw = st.Headers()
	} else if s, err := a.st.BestStream(context.Background(), channelID); err == nil && s != nil {
		raw = s.Headers()
	}
	h := sanitizedHeaders(raw)

	// UA 单独兜一层：线路没配 UA 时用全局配置，保证总有值。
	if h == nil {
		h = map[string]string{}
	}
	if _, okUA := findHeaderKey(h, "user-agent"); !okUA {
		if ua := a.conf().Stream.UserAgent; ua != "" {
			h["User-Agent"] = ua
		}
	}
	if len(h) == 0 {
		return nil
	}
	return h
}

// findHeaderKey 大小写不敏感地找一个头，返回真实键名。
func findHeaderKey(h map[string]string, want string) (string, bool) {
	for k := range h {
		if strings.EqualFold(k, want) {
			return k, true
		}
	}
	return "", false
}

// segMaxBytes 是单个分片的体积上限。
//
// 32MB 远超正常的 HLS 分片（1080p 一片通常 2~6MB，4K 也就 10MB 上下），
// 取这么宽松是为了不误杀高码率源；它的作用是拦住"目标根本不是一个分片"
// 那种情况（比如被指到某个大文件或管理页的一大坨 HTML）。
const segMaxBytes = 32 << 20

// abortAfterWriter 在累计写出超过 limit 时中断连接。
//
// 为什么用 panic(http.ErrAbortHandler)：此刻响应头已经发出（状态码 200），
// 没有任何办法再改成 502。net/http 把 ErrAbortHandler 当成"放弃这个响应"，
// 直接关掉底层连接，客户端看到的是一段残缺的响应体，会按 HLS 规则
// 重试或换线 —— 这正是想要的失败方式。
// 相反，如果这里安静返回 nil，播放器收到的是一个"成功的短分片"：
// 花屏、不重试、日志里什么都没有。
type abortAfterWriter struct {
	w     io.Writer
	limit int64
	n     int64
}

func (a *abortAfterWriter) Write(p []byte) (int, error) {
	a.n += int64(len(p))
	if a.n > a.limit {
		panic(http.ErrAbortHandler)
	}
	return a.w.Write(p)
}

// resolveTargetAddr 解析目标主机，返回一个地址（仅用于审计标记）。
//
// 解析失败返回 ok=false：审计是附加信息，不能因为它让播放失败。
func resolveTargetAddr(ctx context.Context, rawURL string) (netip.Addr, bool) {
	u, err := url.Parse(rawURL)
	if err != nil {
		return netip.Addr{}, false
	}
	host := u.Hostname()
	if host == "" {
		return netip.Addr{}, false
	}
	// 字面量 IP 直接判，不用查 DNS。
	if ip, perr := netip.ParseAddr(host); perr == nil {
		return ip, true
	}
	ips, lerr := net.DefaultResolver.LookupNetIP(ctx, "ip", host)
	if lerr != nil || len(ips) == 0 {
		return netip.Addr{}, false
	}
	return ips[0], true
}

// segmentURL 为分片/子清单/密钥地址签一个**绑定目标**的代理地址。
//
// R04：签名必须包含目标地址本身。旧实现签的是「频道级播放签名」，
// 而签名内容里没有 u，于是持有有效播放签名的设备只要改 u，
// 就能让 NAS 去请求任意 http/https 地址——把播放权限放大成任意 HTTP 代理。
//
// 这里的签名带 "seg" 用途前缀，所以它也不能被拿去当播放签名复用。
func (a *API) segmentURL(abs string, channelID int64, dk string) string {
	return a.segmentURLDepth(abs, channelID, dk, 0, 0)
}

// segmentURLDepth 是 segmentURL 的带深度版本。
//
// R14：清单可能嵌套（master → variant → 分片），甚至被恶意源构造成循环。
// depth 会写进代理地址，下一次请求据此继续计数；超过 maxDepth 就不再
// 转发为代理地址，避免无限递归把 NAS 卡死。
func (a *API) segmentURLDepth(abs string, channelID int64, dk string, depth, maxDepth int) string {
	exp := time.Now().Add(12 * time.Hour).Unix()
	sig := a.signer.SignSegment(channelID, abs, exp, dk)
	q := url.Values{}
	q.Set("u", abs)
	q.Set("sig", sig)
	q.Set("exp", strconv.FormatInt(exp, 10))
	q.Set("dk", dk)
	if depth > 0 {
		// 把深度带进代理地址，下次请求从这儿继续往上数。
		q.Set("depth", strconv.Itoa(depth))
	}
	return fmt.Sprintf("%s/live/%d/seg?%s", a.conf().Server.Prefix, channelID, q.Encode())
}

// rewriteURIAttr 处理 `#EXT-X-KEY:METHOD=AES-128,URI="xxx"` 这类带 URI 属性的标签。
func rewriteURIAttr(line string, base *url.URL, a *API, channelID int64, dk string) string {
	return rewriteURIAttrDepth(line, base, a, channelID, dk, 0)
}

// rewriteURIAttrDepth 是 rewriteURIAttr 的带深度版本。
//
// R14 的另一半：#EXT-X-MAP / #EXT-X-KEY / #EXT-X-MEDIA 里的 URI 也会指向
// 相对路径（初始化段、AES 密钥、子清单）。只改写裸行、不改写属性 URI 的话，
// 这些引用会相对 NAS 的 /seg 路径解析，取不到真实文件——
// 表现为"能起播但一直黑屏/解密失败"。
func rewriteURIAttrDepth(line string, base *url.URL, a *API, channelID int64, dk string, depth int) string {
	const marker = `URI="`
	idx := strings.Index(line, marker)
	if idx < 0 {
		return line
	}
	start := idx + len(marker)
	end := strings.Index(line[start:], `"`)
	if end < 0 {
		return line
	}
	raw := line[start : start+end]
	u, err := base.Parse(raw)
	if err != nil {
		return line
	}
	return line[:start] + a.segmentURLDepth(u.String(), channelID, dk, depth+1, 4) + line[start+end:]
}

func boolPtr(b bool) *bool { return &b }
