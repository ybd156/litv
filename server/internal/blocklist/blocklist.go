// Package blocklist 管「不下发哪些频道」。
//
// 屏蔽是**规则**，不是删除：源里的数据一条不动，只是在下发前过滤掉。
// 按分组整类挡（源每周都在加新的同类频道，逐个藏跟不上），或按频道名精确挡。
//
// 为什么规则放 kv 表而不是新建表：它只有一行、改动极少、每次查询都要读，
// 建表反而多一层迁移。和 edge（Cloudflare Worker）那边存的形状保持一致，
// 两边互换部署时清单能直接用。
package blocklist

import (
	"encoding/json"
	"errors"
	"sort"
	"strings"
)

// KVKey 是屏蔽清单在 kv 表里的键。
const KVKey = "blocked"

// 参数错误：调用方据此回 1001，而不是 500。
var (
	ErrBadKind    = errors.New("kind 只支持 group 或 channel")
	ErrEmptyValue = errors.New("要屏蔽的名字不能为空")
)

// List 是屏蔽清单：整类（分组名精确匹配）与单台（频道名精确匹配）。
type List struct {
	Groups   []string `json:"groups"`
	Channels []string `json:"channels"`
}

// Parse 解析清单。存坏了按「什么都没屏蔽」处理——
// 一行脏数据不能让整个 bootstrap 崩掉，也不能让用户以为台全没了。
func Parse(raw string) List {
	var l List
	if strings.TrimSpace(raw) == "" {
		return List{}
	}
	if err := json.Unmarshal([]byte(raw), &l); err != nil {
		return List{}
	}
	l.Groups = normalize(l.Groups)
	l.Channels = normalize(l.Channels)
	return l
}

// normalize 去空白、去重、排序，保证存回去的形态稳定（便于比对与人工查看）。
func normalize(in []string) []string {
	seen := make(map[string]bool, len(in))
	out := make([]string, 0, len(in))
	for _, s := range in {
		s = strings.TrimSpace(s)
		if s == "" || seen[s] {
			continue
		}
		seen[s] = true
		out = append(out, s)
	}
	sort.Strings(out)
	return out
}

// Encode 序列化成 kv 里的那一行。空清单也照常生成 `{"groups":[],"channels":[]}`，
// 而不是存空串——两种情况读回来都是「没屏蔽」，但存成 JSON 便于人工检查。
func (l List) Encode() (string, error) {
	if l.Groups == nil {
		l.Groups = []string{}
	}
	if l.Channels == nil {
		l.Channels = []string{}
	}
	b, err := json.Marshal(l)
	if err != nil {
		return "", err
	}
	return string(b), nil
}

// Empty 判断清单是否为空。
func (l List) Empty() bool { return len(l.Groups) == 0 && len(l.Channels) == 0 }

// BlocksGroup 判断某个分组是否整类被挡。
func (l List) BlocksGroup(group string) bool { return contains(l.Groups, strings.TrimSpace(group)) }

// BlocksChannel 判断某个频道名是否被挡（精确匹配，不分大小写）。
//
// 不分大小写是有意的：同一个台在源里可能写成 `CCTV1` / `cctv1`，
// 用户屏蔽的是「这个台」，不是「这个写法」。
func (l List) BlocksChannel(name string) bool {
	n := strings.TrimSpace(name)
	if n == "" {
		return false
	}
	for _, c := range l.Channels {
		if strings.EqualFold(c, n) {
			return true
		}
	}
	return false
}

// Blocks 判断一条频道是否该被挡（分组整类 或 频道名命中）。
func (l List) Blocks(group, name string) bool {
	return l.BlocksGroup(group) || l.BlocksChannel(name)
}

// Add 加一条规则。kind 只认 group / channel；已存在时返回 nil（幂等）。
func (l *List) Add(kind, value string) error {
	value = strings.TrimSpace(value)
	if value == "" {
		return ErrEmptyValue
	}
	switch kind {
	case "group":
		if contains(l.Groups, value) {
			return nil
		}
		l.Groups = normalize(append(l.Groups, value))
	case "channel":
		if contains(l.Channels, value) {
			return nil
		}
		l.Channels = normalize(append(l.Channels, value))
	default:
		return ErrBadKind
	}
	return nil
}

// Remove 去掉一条规则。本来就没有时返回 nil（幂等）。
func (l *List) Remove(kind, value string) error {
	value = strings.TrimSpace(value)
	if value == "" {
		return ErrEmptyValue
	}
	switch kind {
	case "group":
		l.Groups = without(l.Groups, value)
	case "channel":
		l.Channels = without(l.Channels, value)
	default:
		return ErrBadKind
	}
	return nil
}

func contains(list []string, v string) bool {
	for _, s := range list {
		if s == v {
			return true
		}
	}
	return false
}

func without(list []string, v string) []string {
	out := make([]string, 0, len(list))
	for _, s := range list {
		if !strings.EqualFold(s, v) {
			out = append(out, s)
		}
	}
	return out
}
