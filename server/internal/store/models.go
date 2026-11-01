package store

import (
	"encoding/json"
	"strings"
)

// 本文件只放数据模型，不放查询逻辑（查询见 repo_*.go）。

// User 对应飞牛账号（来自网关注入的 X-Trim-* 头）。
type User struct {
	ID        int64  `json:"id"`
	FnosUID   string `json:"fnos_uid"`
	Username  string `json:"username"`
	IsAdmin   bool   `json:"is_admin"`
	CreatedAt int64  `json:"created_at"`
	LastSeen  int64  `json:"last_seen"`
}

// Source 是一个订阅源。
type Source struct {
	ID           int64  `json:"id"`
	Name         string `json:"name"`
	Type         string `json:"type"`
	URL          string `json:"url"`
	Enabled      bool   `json:"enabled"`
	LastFetchAt  int64  `json:"last_fetch_at"`
	LastError    string `json:"last_error"`
	ChannelCount int    `json:"channel_count"`
	CreatedAt    int64  `json:"created_at"`
	// UserAgent 这个源专属的 UA。
	//
	// 很多源站会校验 UA：不带就返回 403 或者给个空列表。
	// 全局 UA 只能覆盖一种情况，所以按源配，导入时写进每条线路的 headers_json，
	// 之后不管是服务端代理还是客户端直连，都带这个 UA。
	UserAgent string `json:"user_agent"`
}

// Channel 是编排后的频道。
type Channel struct {
	ID         int64  `json:"id"`
	Number     int    `json:"number"`
	Name       string `json:"name"`
	Group      string `json:"group"`
	Logo       string `json:"logo"`
	TvgID      string `json:"tvg_id"`
	EpgID      string `json:"epg_id"`
	Enabled    bool   `json:"enabled"`
	Sort       int    `json:"sort"`
	SourceID   int64  `json:"source_id"`
	StreamCnt  int    `json:"stream_count"`
	CreatedAt  int64  `json:"created_at"`

	// PendingURL 只在「解析结果 → 入库」之间传递首条线路地址，不对外暴露。
	PendingURL string `json:"-"`
}

// Stream 是频道的一条线路。
type Stream struct {
	ID          int64  `json:"id"`
	ChannelID   int64  `json:"channel_id"`
	URL         string `json:"url"`
	Quality     string `json:"quality"`
	Priority    int    `json:"priority"`
	HeadersJSON string `json:"headers_json"`
	Health      int    `json:"health"`
	LatencyMs   int    `json:"latency_ms"`
	LastCheck   int64  `json:"last_check"`
}

// Headers 把 headers_json 解成 map（解析失败返回空 map，不报错）。
//
// 目前主要用来带 User-Agent：源站校验 UA 时，导入阶段就写进 headers_json，
// 之后不管是服务端代理还是客户端直连，都从这里取。
func (v Stream) Headers() map[string]string {
	out := map[string]string{}
	if v.HeadersJSON == "" || v.HeadersJSON == "{}" {
		return out
	}
	_ = json.Unmarshal([]byte(v.HeadersJSON), &out)
	return out
}

// UserAgent 返回这条线路该用的 UA（没有则返回空，调用方回落到全局配置）。
func (v Stream) UserAgent() string {
	for k, val := range v.Headers() {
		if strings.EqualFold(k, "User-Agent") {
			return val
		}
	}
	return ""
}

// Device 是一台终端。
type Device struct {
	ID         int64  `json:"id"`
	DeviceKey  string `json:"device_key"`
	Name       string `json:"name"`
	Platform   string `json:"platform"`
	Model      string `json:"model"`
	Status     string `json:"status"`
	ConfigJSON string `json:"config_json"`
	BoundAt    int64  `json:"bound_at"`
	LastSeen   int64  `json:"last_seen"`
	CreatedAt  int64  `json:"created_at"`
}

// EPGSource 是节目单来源。
type EPGSource struct {
	ID        int64  `json:"id"`
	Name      string `json:"name"`
	URL       string `json:"url"`
	Enabled   bool   `json:"enabled"`
	LastSync  int64  `json:"last_sync"`
	LastError string `json:"last_error"`
}

// EPGChannel 是 XMLTV 里的频道。
type EPGChannel struct {
	ID          int64  `json:"id"`
	SourceID    int64  `json:"source_id"`
	XmltvID     string `json:"xmltv_id"`
	DisplayName string `json:"display_name"`
	Icon        string `json:"icon"`
}

// EPGProgram 是一个节目。
type EPGProgram struct {
	ID           int64  `json:"id"`
	EpgChannelID int64  `json:"epg_channel_id"`
	StartAt      int64  `json:"start_at"`
	EndAt        int64  `json:"end_at"`
	Title        string `json:"title"`
	Description  string `json:"description"`
	Category     string `json:"category"`
}
