"""导入时顺带清理同一条线路的历史重复行。

之前的 bug 导致同一频道堆了多条「同路径、不同签名」的线路，
旧的那些签名已过期，点开就干等。这里在导入结束前按 urlKey 去重，只保留最新一条。
"""
import io

p = "server/internal/store/repo.go"
s = io.open(p, encoding="utf-8").read()

# 在 ImportChannels 的循环结束后、返回前插入去重
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
	if _, err := tx.ExecContext(ctx, `
		DELETE FROM streams
		WHERE id NOT IN (
			SELECT MAX(id) FROM streams GROUP BY channel_id, url
		)
		AND id NOT IN (
			SELECT keep_id FROM (
				SELECT channel_id, url, MAX(id) AS keep_id
				FROM streams GROUP BY channel_id, substr(url, 1, instr(url || '?', '?'))
			)
		)`); err != nil {
		// 去重失败不影响导入结果，记下来就行
		_ = err
	}
"""

s = s.replace(anchor, dedupe, 1)
io.open(p, "w", encoding="utf-8").write(s)
print("ImportChannels: 已加去重清理")
