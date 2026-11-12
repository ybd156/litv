// Package ffmpeg 放「拼 ffmpeg 命令行参数」的公共逻辑。
//
// 单独成包是因为转封装（remux）和录制（record）都要把线路请求头交给
// ffmpeg，而这段逻辑是有安全含义的（CRLF 注入），不该在两个地方各写一份
// 再慢慢漂移。
package ffmpeg

import (
	"sort"
	"strings"
)

// HeadersArg 把线路请求头拼成 ffmpeg `-headers` 参数的值。
//
// ffmpeg 要求的格式是每条 "Key: value\r\n"，整体作为一个参数字符串。
// 键必须稳定排序，否则同样的头每次拼出不同顺序，会让 ffmpeg 的缓存
// 和日志出现无意义的抖动。
//
// 含 CRLF 的值直接丢弃：用户导入的源文件是不可信输入，
// 放行就等于允许往 ffmpeg 的请求里注入额外头。空值的头没有意义，也一并丢掉。
func HeadersArg(headers map[string]string) string {
	if len(headers) == 0 {
		return ""
	}
	keys := make([]string, 0, len(headers))
	for k, v := range headers {
		if k == "" || v == "" || strings.ContainsAny(k, ":\r\n") || strings.ContainsAny(v, "\r\n\x00") {
			continue
		}
		keys = append(keys, k)
	}
	if len(keys) == 0 {
		return ""
	}
	sort.Strings(keys)
	var b strings.Builder
	for _, k := range keys {
		b.WriteString(k)
		b.WriteString(": ")
		b.WriteString(headers[k])
		b.WriteString("\r\n")
	}
	return b.String()
}

// SplitUserAgent 把 User-Agent 从请求头里摘出来。
//
// ffmpeg 有独立的 -user_agent 参数；同时再放进 -headers 里会出现两个 UA，
// 有些源站会因此直接拒绝。
func SplitUserAgent(headers map[string]string) (ua string, rest map[string]string) {
	if len(headers) == 0 {
		return "", nil
	}
	rest = make(map[string]string, len(headers))
	for k, v := range headers {
		if strings.EqualFold(k, "User-Agent") {
			ua = v
			continue
		}
		rest[k] = v
	}
	if len(rest) == 0 {
		rest = nil
	}
	return ua, rest
}
