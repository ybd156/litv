package api

import (
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

// manifestTTL 是上游 HLS 清单的缓存时间。
//
// HLS 的刷新间隔通常 2–6 秒；同一个频道多台设备同时看时，每秒都会各自
// 去抓一次上游清单。缓存 2 秒既省上游带宽，也把 UpdateStreamHealth
// 从「每客户端一次」降成「每 2 秒最多一次」。
//
// 为什么只缓存**上游原文**：改写后的清单里每个分片地址都带着请求者的
// 设备标识签名。缓存改写结果等于把 A 的签名地址发给 B。
const manifestTTL = 2 * time.Second

// manifestSlot 是某个频道的清单缓存槽。
//
// mu 同时充当 single-flight：同一频道并发请求里只有第一个真正去抓上游，
// 其余的等它抓完直接读缓存。
type manifestSlot struct {
	mu sync.Mutex

	body     []byte
	base     *url.URL
	streamID int64
	at       time.Time
}

// fresh 调用方必须持有 mu。
func (s *manifestSlot) fresh(now time.Time) bool {
	return len(s.body) > 0 && now.Sub(s.at) <= manifestTTL
}

// manifestSnapshot 是写响应时用的快照（不持锁）。
type manifestSnapshot struct {
	body     []byte
	base     *url.URL
	streamID int64
}

// manifestSlotFor 取频道对应的缓存槽。
//
// 频道很多时旧槽不会自己消失，所以偶尔清一次，避免长期运行后
// map 无限增长（每个槽最多留一份清单原文）。
func (a *API) manifestSlotFor(channelID int64) *manifestSlot {
	a.manMu.Lock()
	defer a.manMu.Unlock()
	if a.manSlots == nil {
		a.manSlots = make(map[int64]*manifestSlot)
	}
	if len(a.manSlots) > 512 {
		cutoff := time.Now().Add(-10 * time.Minute)
		for id, s := range a.manSlots {
			s.mu.Lock()
			stale := s.at.Before(cutoff)
			s.mu.Unlock()
			if stale {
				delete(a.manSlots, id)
			}
		}
	}
	if s, ok := a.manSlots[channelID]; ok {
		return s
	}
	s := &manifestSlot{}
	a.manSlots[channelID] = s
	return s
}

// cachedManifestStream 返回最近一次成功拉取清单时选中的线路。
//
// 分片签名里没有线路信息（签名格式不能为了这个改），但分片必须带
// **同一条线路**的请求头：要求 Referer 的源会在分片这一步 403，
// 表现是清单能取到、画面却一片黑。
func (a *API) cachedManifestStream(channelID int64) (int64, bool) {
	a.manMu.Lock()
	slot, ok := a.manSlots[channelID]
	a.manMu.Unlock()
	if !ok {
		return 0, false
	}
	slot.mu.Lock()
	defer slot.mu.Unlock()
	if !slot.fresh(time.Now()) {
		return 0, false
	}
	return slot.streamID, true
}

// writeManifest 把缓存的清单原文改写成经过本服务代理的地址。
func (a *API) writeManifest(w http.ResponseWriter, m manifestSnapshot, channelID int64, dk string) {
	base := m.base
	if base == nil {
		base = &url.URL{}
	}
	lines := strings.Split(string(m.body), "\n")
	out := make([]string, 0, len(lines))
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if trimmed == "" {
			out = append(out, line)
			continue
		}
		if strings.HasPrefix(trimmed, "#") {
			// 处理 #EXT-X-KEY / #EXT-X-MAP 里的 URI
			out = append(out, rewriteURIAttr(trimmed, base, a, channelID, dk))
			continue
		}
		// 分片或子清单
		u, err := base.Parse(trimmed)
		if err != nil {
			out = append(out, line)
			continue
		}
		out = append(out, a.segmentURL(u.String(), channelID, dk))
	}
	w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Access-Control-Allow-Origin", "*")
	_, _ = io.WriteString(w, strings.Join(out, "\n"))
}
