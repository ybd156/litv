package api

import "strings"

// isHLSSource 判断一条上游线路是不是 HLS（m3u8）。
//
// 这是全包**唯一**的封装判据。用到它的地方有五处，改判据只改这一个函数：
//   - handlers_client_play.go  /tv/play 计算下发给客户端的 kind
//   - handlers_client_live.go  liveTS 的 302 目标（TS 还是 index.m3u8）
//   - handlers_client_live.go  转封装候选筛选（只挑 HLS 线路去取 manifest）
//   - handlers_admin_web.go    管理台网页播放（浏览器只认 HLS）
//   - dlna_source.go           DLNA 投屏地址（电视多半只认 HLS）
//
// 以前这段判据在五个地方各写了一遍 `strings.Contains(strings.ToLower(u), ".m3u8")`，
// 改一处就漏四处。
//
// 已知局限（docs/refactor 里登记过，不是这里能修的）：
// 判据只有 URL 后缀。**不带 .m3u8 的 HLS** —— 例如 `/live/123?fmt=hls`，
// 或只能靠响应头 `Content-Type: application/vnd.apple.mpegurl` 区分的地址 ——
// 会被判成裸 TS，于是要么被丢进转封装，要么按 TS 直出；客户端把 HLS 当 TS 播，
// 表现就是黑屏 / 无声。
//
// 为什么不在这一层"顺手修好"：真正的封装类型只有解析阶段知道（它拉过 playlist、
// 见过 #EXTM3U）。要根治得让 parse 把真实封装落进 streams 表，再由这里读库判断；
// 那是 schema 迁移 + 存量回填 + 三端客户端改造，量级完全不同。在此之前，
// 这里宁可保持"一处、可预期、有注释"的后缀判据，也不要在五处各猜一套。
func isHLSSource(rawURL string) bool {
	return strings.Contains(strings.ToLower(rawURL), ".m3u8")
}
