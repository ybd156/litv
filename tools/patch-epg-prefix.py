"""EPG 自动匹配加前缀兜底。

精确匹配只能命中 31/223，因为源里写「CCTV1综合」而 EPG 里写「CCTV1」。
加一条前缀匹配：EPG 名是频道名的前缀，且频道名后面那个字符不是数字
（否则 CCTV1 会把 CCTV10 也吃掉）。
"""
import io

p = "server/internal/store/repo_probe.go"
s = io.open(p, encoding="utf-8").read()

old = """	updated := 0
	for _, c := range pending {
		if xmltv, ok := index[normChannelName(c.name)]; ok {
			if _, err := s.db.ExecContext(ctx,
				`UPDATE channels SET epg_id=? WHERE id=? AND epg_id=''`, xmltv, c.id); err == nil {
				updated++
			}
		}
	}
	return updated, nil
}"""

new = """	// 前缀匹配用：按长度从长到短排，优先匹配更具体的名字
	keys := make([]string, 0, len(index))
	for k := range index {
		keys = append(keys, k)
	}
	sort.Slice(keys, func(i, j int) bool { return len(keys[i]) > len(keys[j]) })

	updated := 0
	for _, c := range pending {
		n := normChannelName(c.name)
		xmltv, ok := index[n]
		if !ok {
			// 前缀兜底：「cctv1综合」应该匹配到 EPG 的「cctv1」
			for _, k := range keys {
				if k == "" || !strings.HasPrefix(n, k) {
					continue
				}
				// 前缀后面紧跟数字就不算（cctv1 不能吃掉 cctv10）
				rest := n[len(k):]
				if rest != "" && rest[0] >= '0' && rest[0] <= '9' {
					continue
				}
				xmltv, ok = index[k], true
				break
			}
		}
		if ok {
			if _, err := s.db.ExecContext(ctx,
				`UPDATE channels SET epg_id=? WHERE id=? AND epg_id=''`, xmltv, c.id); err == nil {
				updated++
			}
		}
	}
	return updated, nil
}"""

assert old in s, "找不到匹配循环"
s = s.replace(old, new, 1)

if '"sort"' not in s:
    s = s.replace('\t"strings"\n', '\t"sort"\n\t"strings"\n', 1)

io.open(p, "w", encoding="utf-8").write(s)
print("已加前缀兜底匹配")
