"""把去重从 SQL 改成 Go 实现。

原来的 SQL 用 GROUP BY substr(...) 同时 select 裸列 url，
SQLite 里这是未定义行为，结果就是一条都没删掉。
改成在 Go 里按 (channel_id, urlKey) 分组，保留 id 最大的那条。
"""
import io
import re

p = "server/internal/store/repo.go"
s = io.open(p, encoding="utf-8").read()

# 删掉之前那段没生效的 SQL 去重
start = s.find("\t// 清理历史重复：同一频道下「主机+路径」相同的多条线路只保留最新一条。")
if start != -1:
    end = s.find("\n\n", s.find("_ = err\n\t}", start))
    if end == -1:
        end = s.find("\t}\n", start) + 3
    s = s[:start] + s[end:]

anchor = """			inserted++
		default:
			return inserted, err
		}
	}
"""
assert anchor in s, "找不到导入循环结尾"

dedupe = anchor + """
	// 清理历史重复：同一频道下「主机+路径」相同的多条线路只保留最新一条。
	// 老版本用完整 URL 判重，签名一变就多插一条，积了一堆过期线路。
	if derr := dedupeStreams(ctx, tx); derr != nil {
		// 去重失败不影响导入本身
		_ = derr
	}
"""

s = s.replace(anchor, dedupe, 1)

# 追加辅助函数
if "func dedupeStreams(" not in s:
    s = s.rstrip() + """

// dedupeStreams 清理同一频道下「主机+路径」相同的重复线路，只保留 id 最大的那条。
//
// 为什么会有重复：老版本按完整 URL 判重，而源站地址的签名在 query 里、
// 每次刷新都变，于是每刷一次就多一条，旧的那条带着过期签名变成死线路。
func dedupeStreams(ctx context.Context, tx *sql.Tx) error {
	rows, err := tx.QueryContext(ctx, `SELECT id, channel_id, url FROM streams ORDER BY id`)
	if err != nil {
		return err
	}
	type key struct {
		ch int64
		k  string
	}
	keep := map[key]int64{}
	var drop []int64
	for rows.Next() {
		var id, ch int64
		var u string
		if rows.Scan(&id, &ch, &u) != nil {
			continue
		}
		kk := key{ch, urlKey(u)}
		if prev, ok := keep[kk]; ok {
			drop = append(drop, prev)
		}
		keep[kk] = id
	}
	rows.Close()
	for _, id := range drop {
		if _, err := tx.ExecContext(ctx, `DELETE FROM streams WHERE id=?`, id); err != nil {
			return err
		}
	}
	return nil
}
"""

io.open(p, "w", encoding="utf-8").write(s)
print("已改用 Go 实现去重")
