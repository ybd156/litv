package api

// 屏蔽清单：整类（分组）或单台（频道名）不下发给客户端。
//
// 与 edge（Cloudflare Worker）那边同一套语义和同一份 kv 形状：
//   GET    /api/v1/blocked  → { groups, channels, candidates }
//   POST   /api/v1/blocked  → { kind: group|channel, value }
//   DELETE /api/v1/blocked  → { kind: group|channel, value }
//
// 为什么放在 kv 而不是给频道加个 blocked 列：屏蔽是**规则**，
// 不该改源数据——源每周重建一次，任何写进 channels 表的标记都会在下一次
// 导入时被冲掉（这正是 edge 那边的历史教训）。

import (
	"encoding/json"
	"log"
	"net/http"
	"strings"

	"github.com/litv/litv/server/internal/blocklist"
)

// loadBlockList 读屏蔽清单。读不出来时按「什么都没屏蔽」处理，
// 但要把错误记下来——静默当成空清单会让人以为屏蔽失效是 bug。
func (a *API) loadBlockList(r *http.Request) blocklist.List {
	raw, okv, err := a.st.KVGet(r.Context(), blocklist.KVKey)
	if err != nil {
		log.Printf("屏蔽清单读取失败（按未屏蔽处理）：%v", err)
		return blocklist.List{}
	}
	if !okv {
		return blocklist.List{}
	}
	return blocklist.Parse(raw)
}

func (a *API) saveBlockList(r *http.Request, l blocklist.List) error {
	raw, err := l.Encode()
	if err != nil {
		return err
	}
	return a.st.KVSet(r.Context(), blocklist.KVKey, raw)
}

// blockedInfo 返回清单 + 分类候选（带「会下发的频道数」）。
//
// 候选必须来自**不过滤分类**的统计：按分类屏蔽之后，那个分类下的台
// 全部消失，若拿过滤后的结果当候选，用户解除屏蔽时就再也找不到它了。
func (a *API) blockedInfo(w http.ResponseWriter, r *http.Request) {
	l := a.loadBlockList(r)
	// 计「会下发多少台」时要排掉单台屏蔽（否则数字虚高），但不排分类。
	groups, err := a.st.ListGroupsExcluding(r.Context(), nil, l.Channels)
	if err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	cands := make([]map[string]any, 0, len(groups)+len(l.Groups))
	seen := make(map[string]bool, len(groups))
	for _, g := range groups {
		name, _ := g["name"].(string)
		count, _ := g["count"].(int)
		seen[name] = true
		cands = append(cands, map[string]any{"name": name, "count": count, "blocked": l.BlocksGroup(name)})
	}
	// 已屏蔽、但源里一台都不剩（或本来就空）的分类也要列出来，
	// 否则它在界面上凭空消失，只能去手改 kv 才能解除。
	for _, name := range l.Groups {
		if !seen[name] {
			cands = append(cands, map[string]any{"name": name, "count": 0, "blocked": true})
		}
	}
	ok(w, map[string]any{
		"groups":     l.Groups,
		"channels":   l.Channels,
		"candidates": cands,
	})
}

// decodeBlockRule 解析 {kind,value}。只认这两个字段——多传的字段
// 说明调用方和我理解的契约不一样，宁可 1001 也不要静默忽略。
func decodeBlockRule(w http.ResponseWriter, r *http.Request) (string, string, error) {
	var body struct {
		Kind  string `json:"kind"`
		Value string `json:"value"`
	}
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&body); err != nil {
		return "", "", err
	}
	return strings.TrimSpace(body.Kind), strings.TrimSpace(body.Value), nil
}

func (a *API) blockedAdd(w http.ResponseWriter, r *http.Request) {
	kind, value, err := decodeBlockRule(w, r)
	if err != nil {
		fail(w, http.StatusBadRequest, 1001, "参数错误：请求体要 {kind,value}")
		return
	}
	l := a.loadBlockList(r)
	if err := l.Add(kind, value); err != nil {
		fail(w, http.StatusBadRequest, 1001, err.Error())
		return
	}
	if err := a.saveBlockList(r, l); err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	log.Printf("屏蔽已加：kind=%s value=%q（分类 %d / 单台 %d）", kind, value, len(l.Groups), len(l.Channels))
	a.blockedInfo(w, r)
}

func (a *API) blockedRemove(w http.ResponseWriter, r *http.Request) {
	kind, value, err := decodeBlockRule(w, r)
	if err != nil {
		fail(w, http.StatusBadRequest, 1001, "参数错误：请求体要 {kind,value}")
		return
	}
	l := a.loadBlockList(r)
	if err := l.Remove(kind, value); err != nil {
		fail(w, http.StatusBadRequest, 1001, err.Error())
		return
	}
	if err := a.saveBlockList(r, l); err != nil {
		fail(w, http.StatusInternalServerError, 5001, err.Error())
		return
	}
	log.Printf("屏蔽已解：kind=%s value=%q（分类 %d / 单台 %d）", kind, value, len(l.Groups), len(l.Channels))
	a.blockedInfo(w, r)
}
