// Package epg 解析 XMLTV 节目单。
//
// XMLTV 是一个很老但非常稳定的标准，真实源里的坑主要有：
//   - 时间格式带时区偏移（`20261101200000 +0800`）
//   - 频道名与直播源频道名对不上（需要别名表 + 模糊匹配）
//   - 文件极大（几十 MB），不能整个塞进内存再解析——这里用流式解码
package epg

import (
	"encoding/xml"
	"fmt"
	"io"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"
)

// Channel 是 XMLTV 里的一个频道。
type Channel struct {
	ID          string
	DisplayName string
	Icon        string
}

// Programme 是一个节目。
type Programme struct {
	ChannelID   string
	Start       int64
	Stop        int64
	Title       string
	Description string
	Category    string
}

// Result 是一次解析的结果。
type Result struct {
	Channels   []Channel
	Programmes []Programme
}

type xmlTV struct {
	XMLName    xml.Name       `xml:"tv"`
	Channels   []xmlChannel   `xml:"channel"`
	Programmes []xmlProgramme `xml:"programme"`
}

type xmlChannel struct {
	ID          string `xml:"id,attr"`
	DisplayName []struct {
		Value string `xml:",chardata"`
		Lang  string `xml:"lang,attr"`
	} `xml:"display-name"`
	Icon struct {
		Src string `xml:"src,attr"`
	} `xml:"icon"`
}

type xmlProgramme struct {
	Start    string `xml:"start,attr"`
	Stop     string `xml:"stop,attr"`
	Channel  string `xml:"channel,attr"`
	Title    string `xml:"title"`
	Desc     string `xml:"desc"`
	Category string `xml:"category"`
}

// Parse 解析 XMLTV。maxProgrammes <= 0 表示不限制。
func Parse(r io.Reader, maxProgrammes int) (*Result, error) {
	dec := xml.NewDecoder(r)
	// 很多 XMLTV 声明 GBK 或 ISO-8859-1，Go 默认只认 UTF-8，这里强制按 UTF-8 读，
	// 上游抓取时已统一转码（见 fetch + parse.ToUTF8）。
	dec.CharsetReader = func(charset string, input io.Reader) (io.Reader, error) {
		return input, nil
	}

	res := &Result{}
	seenChannel := map[string]bool{}

	for {
		tok, err := dec.Token()
		if err == io.EOF {
			break
		}
		if err != nil {
			return res, fmt.Errorf("XMLTV 解析中断: %w", err)
		}
		se, ok := tok.(xml.StartElement)
		if !ok {
			continue
		}
		switch se.Name.Local {
		case "channel":
			var c xmlChannel
			if err := dec.DecodeElement(&c, &se); err != nil {
				continue
			}
			if c.ID == "" || seenChannel[c.ID] {
				continue
			}
			seenChannel[c.ID] = true
			name := ""
			if len(c.DisplayName) > 0 {
				name = strings.TrimSpace(c.DisplayName[0].Value)
			}
			res.Channels = append(res.Channels, Channel{ID: c.ID, DisplayName: name, Icon: c.Icon.Src})
		case "programme":
			if maxProgrammes > 0 && len(res.Programmes) >= maxProgrammes {
				// 已经够了，跳过剩余
				if err := dec.Skip(); err != nil {
					return res, nil
				}
				continue
			}
			var p xmlProgramme
			if err := dec.DecodeElement(&p, &se); err != nil {
				continue
			}
			start, err1 := parseXMLTVTime(p.Start)
			stop, err2 := parseXMLTVTime(p.Stop)
			if err1 != nil || err2 != nil || stop <= start {
				continue
			}
			res.Programmes = append(res.Programmes, Programme{
				ChannelID:   p.Channel,
				Start:       start,
				Stop:        stop,
				Title:       strings.TrimSpace(p.Title),
				Description: strings.TrimSpace(p.Desc),
				Category:    strings.TrimSpace(p.Category),
			})
		}
	}
	return res, nil
}

// parseXMLTVTime 解析 `20060102150405 +0800` 这类格式。
func parseXMLTVTime(s string) (int64, error) {
	s = strings.TrimSpace(s)
	if s == "" {
		return 0, fmt.Errorf("空时间")
	}
	// 常见格式：YYYYMMDDhhmmss 或 YYYYMMDDhhmm 或 YYYYMMDD
	layouts := []string{
		"20060102150405 -0700",
		"20060102150405",
		"200601021504 -0700",
		"200601021504",
		"20060102 -0700",
		"20060102",
	}
	for _, l := range layouts {
		if t, err := time.Parse(l, s); err == nil {
			return t.Unix(), nil
		}
	}
	// 手工兜底：取出前 14 位数字按本地时区解析
	digits := make([]rune, 0, 14)
	for _, r := range s {
		if r >= '0' && r <= '9' {
			digits = append(digits, r)
			if len(digits) == 14 {
				break
			}
		}
	}
	if len(digits) >= 8 {
		base := string(digits)
		for len(base) < 14 {
			base += "0"
		}
		if t, err := time.ParseInLocation("20060102150405", base, time.Local); err == nil {
			return t.Unix(), nil
		}
	}
	return 0, fmt.Errorf("无法解析时间: %s", s)
}

// MatchScore 计算直播频道名与 EPG 频道名的匹配分数（0~100）。
//
// 策略（从强到弱）：
//  1. 完全相等 → 100
//  2. 去掉常见后缀（HD/FHD/高清/超清/频道/-1 等）后相等 → 90
//  3. 一方包含另一方 → 70
//  4. 归一化后编辑距离相近 → 40~60
func MatchScore(channelName, epgName string) int {
	a := normalize(channelName)
	b := normalize(epgName)
	if a == "" || b == "" {
		return 0
	}
	if a == b {
		return 100
	}
	if strings.Contains(a, b) || strings.Contains(b, a) {
		// 长度差太大就降分，避免 "CCTV1" 匹配到 "CCTV1综合频道HD"。
		//
		// 必须按**字符数**而不是字节数比较：一个汉字占 3 字节，
		// 用 len() 会让 "CCTV1综合" 与 "CCTV1" 的长度差从 2 变成 6，
		// 于是明明高度相似的两个名字被判定为"差得远"，
		// 进而把同一个台的多个线路误报成映射冲突。
		diff := utf8.RuneCountInString(a) - utf8.RuneCountInString(b)
		if diff < 0 {
			diff = -diff
		}
		if diff <= 4 {
			return 70
		}
		return 50
	}
	// 数字编号相同（CCTV5 / CCTV5+ 之类）加分
	if numA, numB := digits(a), digits(b); numA != "" && numA == numB {
		return 60
	}
	return 0
}

var dropTokens = []string{
	"高清", "超清", "蓝光", "标清", "4k", "8k", "uhd", "fhd", "hd", "sd",
	"频道", "电视台", "直播", "测试", "备用", "-", "_", " ", "　",
}

func normalize(s string) string {
	s = strings.ToLower(strings.TrimSpace(s))
	for _, t := range dropTokens {
		s = strings.ReplaceAll(s, t, "")
	}
	return s
}

func digits(s string) string {
	var b strings.Builder
	for _, r := range s {
		if r >= '0' && r <= '9' {
			b.WriteRune(r)
		}
	}
	return b.String()
}

// FormatTime 把 Unix 秒格式化成 HH:MM，给节目单展示用。
func FormatTime(ts int64) string {
	return time.Unix(ts, 0).Format("15:04")
}

// ParseDurationSeconds 解析 "3600" 这类字符串。
func ParseDurationSeconds(s string) int64 {
	n, err := strconv.ParseInt(strings.TrimSpace(s), 10, 64)
	if err != nil {
		return 0
	}
	return n
}
