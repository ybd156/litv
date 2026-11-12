// Package parse 负责解析各种直播源格式。
//
// 支持的格式（按实际生态使用频率排序）：
//  1. M3U / M3U8 播放列表（含 #EXTINF 扩展属性、#EXTGRP 分组）
//  2. TXT 格式（`分组名,#genre#` + `频道名,地址`）—— 国内 IPTV 最常见的土办法
//  3. TVBox `lives` 配置 JSON（两种互不兼容的形态，见 ParseTVBox）
//  4. 通用 JSON 数组（[{"name":..,"url":..}]）
//
// 脏数据处理是这个包的核心价值：真实世界的源里 BOM、GBK、重复频道、空行、
// 行尾注释、`$` 说明后缀、`$!` 白名单标记、名字里带逗号……全都会出现。
package parse

import (
	"bytes"
	"encoding/json"
	"fmt"
	"regexp"
	"sort"
	"strings"
	"unicode/utf8"

	"golang.org/x/text/encoding/simplifiedchinese"
	"golang.org/x/text/transform"
)

// Item 是解析出来的一个频道条目（可能对应多条线路）。
type Item struct {
	// json tag 必须和管理台的 TS 类型（admin/src/api/types.ts 的 PreviewItem）一致。
	// 之前没写 tag，Go 默认序列化成 Name/Group/URL/Logo，
	// 前端读 item.name 全是 undefined —— 表现就是「源预览列表一片空白」。
	Name   string `json:"name"`
	Group  string `json:"group"`
	URL    string `json:"url"`
	Logo   string `json:"logo"`
	TvgID  string `json:"tvg_id"`
	Number int    `json:"number"`
}

// ToUTF8 把可能的 GBK/BOM 内容转成 UTF-8。
// 国内大量 TXT 源是 GBK 编码，直接按 UTF-8 读会全是乱码。
func ToUTF8(b []byte) []byte {
	b = bytes.TrimPrefix(b, []byte{0xEF, 0xBB, 0xBF}) // UTF-8 BOM
	if utf8.Valid(b) {
		return b
	}
	// 尝试 GBK（GB18030 是超集，覆盖面更广）
	if out, _, err := transform.Bytes(simplifiedchinese.GB18030.NewDecoder(), b); err == nil {
		return out
	}
	// 兜底：把非法字节替换掉，至少不崩
	return bytes.ToValidUTF8(b, []byte(""))
}

// Detect 猜测格式。
func Detect(raw []byte) string {
	b := bytes.TrimPrefix(raw, []byte{0xEF, 0xBB, 0xBF})
	trimmed := bytes.TrimSpace(b)
	if len(trimmed) == 0 {
		return "unknown"
	}
	switch trimmed[0] {
	case '{', '[':
		// JSON：如果含 "lives" 就当 TVBox，否则当通用 JSON
		if bytes.Contains(trimmed, []byte(`"lives"`)) {
			return "tvbox"
		}
		return "json"
	}
	head := string(trimmed)
	if len(head) > 512 {
		head = head[:512]
	}
	if strings.Contains(head, "#EXTM3U") || strings.Contains(head, "#EXTINF") {
		return "m3u"
	}
	if strings.Contains(head, "#genre#") {
		return "txt"
	}
	// 兜底：看有没有 "名字,http" 这种行
	for _, line := range strings.Split(head, "\n") {
		if strings.Contains(line, ",") && strings.Contains(line, "://") {
			return "txt"
		}
	}
	return "unknown"
}

// Parse 按格式解析。
func Parse(raw []byte, typ string) ([]Item, error) {
	text := string(ToUTF8(raw))
	if typ == "" || typ == "auto" {
		typ = Detect(raw)
	}
	switch typ {
	case "m3u", "m3u8":
		return ParseM3U(text), nil
	case "txt":
		return ParseTXT(text), nil
	case "tvbox":
		return ParseTVBox(raw)
	case "json":
		return ParseJSON(raw)
	default:
		// 最后再猜一次：能按 M3U 解出东西就当 M3U
		if items := ParseM3U(text); len(items) > 0 {
			return items, nil
		}
		if items := ParseTXT(text); len(items) > 0 {
			return items, nil
		}
		return nil, fmt.Errorf("无法识别的源格式")
	}
}

var (
	reAttr = regexp.MustCompile(`([a-zA-Z0-9_-]+)="([^"]*)"`)
	// 频道名尾部的 `$` 说明后缀，例如「CCTV1$高清」；`$!` 表示白名单标记，入库前必须剥离
	reDollarSuffix = regexp.MustCompile(`\$[^,]*$`)
)

// ParseM3U 解析 M3U/M3U8。
func ParseM3U(text string) []Item {
	var items []Item
	var cur *Item
	var pendingGroup string

	flush := func(u string) {
		u = strings.TrimSpace(u)
		if u == "" || strings.HasPrefix(u, "#") {
			return
		}
		if cur == nil {
			// 没有 EXTINF 直接跟地址：退化成「地址当名字」
			items = append(items, Item{Name: u, Group: orDefault(pendingGroup, "未分组"), URL: u})
			return
		}
		cur.URL = u
		if cur.Group == "" {
			cur.Group = orDefault(pendingGroup, "未分组")
		}
		items = append(items, *cur)
		cur = nil
	}

	for _, rawLine := range strings.Split(text, "\n") {
		line := strings.TrimSpace(strings.TrimSuffix(rawLine, "\r"))
		if line == "" {
			continue
		}
		switch {
		case strings.HasPrefix(line, "#EXTINF"):
			it := Item{}
			for _, m := range reAttr.FindAllStringSubmatch(line, -1) {
				key, val := strings.ToLower(m[1]), m[2]
				switch key {
				case "tvg-id":
					it.TvgID = val
				case "tvg-logo", "logo":
					it.Logo = val
				case "group-title", "group":
					it.Group = val
				case "tvg-chno", "channel-number":
					fmt.Sscanf(val, "%d", &it.Number)
				}
			}
			// 名字在最后一个逗号之后
			if idx := strings.LastIndex(line, ","); idx >= 0 {
				it.Name = strings.TrimSpace(line[idx+1:])
			}
			it.Name = cleanName(it.Name)
			if it.Name == "" {
				it.Name = "未命名"
			}
			cur = &it
		case strings.HasPrefix(line, "#EXTGRP:"):
			pendingGroup = strings.TrimSpace(strings.TrimPrefix(line, "#EXTGRP:"))
			if cur != nil && cur.Group == "" {
				cur.Group = pendingGroup
			}
		case strings.HasPrefix(line, "#"):
			// 其它标签（#EXTVLCOPT 等）忽略
			continue
		default:
			flush(line)
		}
	}
	return dedupe(items)
}

// ParseTXT 解析 `分组,#genre#` / `频道名,地址` 格式。
func ParseTXT(text string) []Item {
	var items []Item
	group := "未分组"
	for _, rawLine := range strings.Split(text, "\n") {
		line := strings.TrimSpace(strings.TrimSuffix(rawLine, "\r"))
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		// 分组行：`央视频道,#genre#`
		if strings.Contains(line, "#genre#") {
			g := strings.TrimSpace(strings.SplitN(line, ",", 2)[0])
			g = strings.TrimSuffix(g, "#genre#")
			g = strings.TrimSpace(g)
			if g != "" {
				group = g
			}
			continue
		}
		name, url := splitNameURL(line)
		if url == "" {
			continue
		}
		items = append(items, Item{
			Name:  cleanName(name),
			Group: group,
			URL:   url,
		})
	}
	return dedupe(items)
}

// splitNameURL 把 `名字,地址` 拆开。
// 难点：名字里可能带逗号，地址也可能是 `rtp://239.1.1.1:5002` 这种。
// 策略：从右往左找第一个后面紧跟协议头的逗号。
func splitNameURL(line string) (name, url string) {
	idx := -1
	for i := len(line) - 1; i >= 0; i-- {
		if line[i] != ',' {
			continue
		}
		rest := strings.TrimSpace(line[i+1:])
		if looksLikeURL(rest) {
			idx = i
			break
		}
	}
	if idx < 0 {
		// 退化：按第一个逗号拆
		parts := strings.SplitN(line, ",", 2)
		if len(parts) != 2 {
			return "", ""
		}
		return parts[0], strings.TrimSpace(parts[1])
	}
	return strings.TrimSpace(line[:idx]), strings.TrimSpace(line[idx+1:])
}

func looksLikeURL(s string) bool {
	for _, p := range []string{"http://", "https://", "rtsp://", "rtmp://", "rtp://", "udp://", "rtp2://", "p3p://", "p2p://", "mitv://", "igmp://", "file://"} {
		if strings.HasPrefix(strings.ToLower(s), p) {
			return true
		}
	}
	return false
}

// tvboxLives 兼容 TVBox 两种 lives 形态。
//
// 形态 A（FongMi 式，lives 元素带 type/url）：
//
//	{"lives":[{"name":"组名","type":0,"url":"http://x.m3u","epg":"http://x.xml"}]}
//
// 形态 B（takagen99 内联式，lives 元素带 group/channels）：
//
//	{"lives":[{"group":"组名","channels":[{"name":"CCTV1","urls":["http://a","http://b"]}]}]}
//
// 源码用 lives.contains("type") 分流，这里用同样的判据。
func ParseTVBox(raw []byte) ([]Item, error) {
	var doc struct {
		Lives json.RawMessage `json:"lives"`
	}
	if err := json.Unmarshal(raw, &doc); err != nil {
		return nil, fmt.Errorf("解析 TVBox JSON 失败: %w", err)
	}
	if len(doc.Lives) == 0 {
		return nil, fmt.Errorf("TVBox 配置里没有 lives 字段")
	}

	var probe []map[string]json.RawMessage
	if err := json.Unmarshal(doc.Lives, &probe); err != nil {
		return nil, fmt.Errorf("lives 字段格式不正确: %w", err)
	}
	if len(probe) == 0 {
		return nil, fmt.Errorf("lives 为空")
	}

	var items []Item
	for _, live := range probe {
		_, hasType := live["type"]
		if hasType {
			// 形态 A：url 指向外部 m3u/txt，交给上层继续抓取
			var name, url string
			_ = json.Unmarshal(live["name"], &name)
			_ = json.Unmarshal(live["url"], &url)
			if url != "" {
				items = append(items, Item{Name: orDefault(name, "外部源"), Group: orDefault(name, "未分组"), URL: url})
			}
			continue
		}
		// 形态 B：内联频道
		var group string
		_ = json.Unmarshal(live["group"], &group)
		if group == "" {
			_ = json.Unmarshal(live["name"], &group)
		}
		var chans []struct {
			Name string   `json:"name"`
			URLs []string `json:"urls"`
			URL  string   `json:"url"`
		}
		if rawChans, ok := live["channels"]; ok {
			_ = json.Unmarshal(rawChans, &chans)
		}
		for _, c := range chans {
			urls := c.URLs
			if len(urls) == 0 && c.URL != "" {
				urls = []string{c.URL}
			}
			for _, u := range urls {
				items = append(items, Item{
					Name:  cleanName(c.Name),
					Group: orDefault(group, "未分组"),
					URL:   strings.TrimSpace(u),
				})
			}
		}
	}
	if len(items) == 0 {
		return nil, fmt.Errorf("lives 里没有解析出任何频道")
	}
	return items, nil
}

// ParseJSON 解析通用 JSON：数组或 {"channels":[...]} / {"list":[...]}。
func ParseJSON(raw []byte) ([]Item, error) {
	type jc struct {
		Name  string `json:"name"`
		Title string `json:"title"`
		URL   string `json:"url"`
		Group string `json:"group"`
		Logo  string `json:"logo"`
		TvgID string `json:"tvg_id"`
	}
	var arr []jc
	if err := json.Unmarshal(raw, &arr); err != nil {
		var wrapper struct {
			Channels []jc `json:"channels"`
			List     []jc `json:"list"`
		}
		if err2 := json.Unmarshal(raw, &wrapper); err2 != nil {
			return nil, fmt.Errorf("无法解析 JSON 源: %v / %v", err, err2)
		}
		arr = append(wrapper.Channels, wrapper.List...)
	}
	var items []Item
	for _, c := range arr {
		name := orDefault(c.Name, c.Title)
		if name == "" || c.URL == "" {
			continue
		}
		items = append(items, Item{
			Name:  cleanName(name),
			Group: orDefault(c.Group, "未分组"),
			URL:   c.URL,
			Logo:  c.Logo,
			TvgID: c.TvgID,
		})
	}
	return dedupe(items), nil
}

// cleanName 清洗频道名：去掉 `$` 说明后缀、首尾空白、零宽字符。
func cleanName(s string) string {
	s = strings.TrimSpace(s)
	s = strings.ReplaceAll(s, "\u200b", "")
	s = strings.ReplaceAll(s, "\ufeff", "")
	if idx := strings.Index(s, "$"); idx > 0 {
		s = s[:idx]
	}
	s = strings.TrimSpace(s)
	// 有些源会在名字里塞 `#genre#` 残留
	s = strings.ReplaceAll(s, "#genre#", "")
	return strings.TrimSpace(s)
}

// dedupe 去重：同名同组只留第一条（多条线路由调用方从 URL 列表里补）。
func dedupe(items []Item) []Item {
	seen := make(map[string]int, len(items))
	out := make([]Item, 0, len(items))
	for _, it := range items {
		if it.Name == "" || it.URL == "" {
			continue
		}
		key := it.Group + "\x00" + it.Name
		if idx, ok := seen[key]; ok {
			// 已存在同名频道：如果 URL 不同，作为附加线路保留为独立条目，
			// 由 ImportChannels 按 name+group 合并进 streams。
			if out[idx].URL != it.URL {
				out = append(out, it)
			}
			continue
		}
		seen[key] = len(out)
		out = append(out, it)
	}
	return out
}

// Stats 返回分组统计，供预览接口展示。
func Stats(items []Item) map[string]int {
	m := map[string]int{}
	for _, it := range items {
		m[it.Group]++
	}
	return m
}

// SortByGroup 让预览结果按分组聚合，方便人眼检查。
func SortByGroup(items []Item) []Item {
	out := make([]Item, len(items))
	copy(out, items)
	sort.SliceStable(out, func(i, j int) bool {
		if out[i].Group != out[j].Group {
			return out[i].Group < out[j].Group
		}
		return out[i].Name < out[j].Name
	})
	return out
}

func orDefault(v, def string) string {
	if strings.TrimSpace(v) == "" {
		return def
	}
	return v
}
