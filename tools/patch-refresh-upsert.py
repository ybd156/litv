"""修「刷新源不更新线路地址」。

原逻辑：拿完整 URL 判重。但源站的 URL 带签名，每次刷新签名都变，
所以判重永远不命中 -> 每刷新一次就给同一频道**追加**一条新线路，
旧线路带着过期签名留在库里，变成点开就干等的死线路。
（数据库里 cctv1-MCP 已经有 stream=132 和 226 两条，就是这么来的）

改成：按「主机+路径」判重（忽略 query 里的签名），命中就原地更新 URL，
这样签名轮换能正常刷新，线路数量也不会无限增长。
"""
import io

p = "server/internal/store/repo.go"
s = io.open(p, encoding="utf-8").read()

old = """		case err == nil:
			// 已存在：只补一条线路（同 URL 不重复加）
			var n int
			_ = tx.QueryRowContext(ctx, `SELECT COUNT(*) FROM streams WHERE channel_id=? AND url=?`, existID, c.firstURL()).Scan(&n)
			if n == 0 && c.firstURL() != "" {
				if _, err := tx.ExecContext(ctx, `INSERT INTO streams(channel_id,url,priority,health,headers_json) VALUES(?,?,?,?,?)`,
					existID, c.firstURL(), 0, -1, headersJSON); err != nil {
					return inserted, err
				}
			}"""

new = """		case err == nil:
			// 已存在：按「主机+路径」判重，命中就原地更新。
			//
			// 不能拿完整 URL 判重：源站地址带签名，每次刷新签名都变，
			// 用完整 URL 比会一直不命中，于是每刷一次就多一条线路，
			// 旧的那条带着过期签名留在库里变成死线路。
			nu := c.firstURL()
			if nu == "" {
				continue
			}
			wantKey := urlKey(nu)
			var existStreamID int64
			var existURL string
			rows, qerr := tx.QueryContext(ctx, `SELECT id,url FROM streams WHERE channel_id=?`, existID)
			if qerr == nil {
				for rows.Next() {
					var sid int64
					var u string
					if rows.Scan(&sid, &u) == nil && urlKey(u) == wantKey {
						existStreamID, existURL = sid, u
						break
					}
				}
				rows.Close()
			}
			if existStreamID > 0 {
				if existURL != nu {
					// 签名变了：更新地址，并把健康状态重置为未知等下次探测
					if _, err := tx.ExecContext(ctx,
						`UPDATE streams SET url=?, headers_json=?, health=0 WHERE id=?`,
						nu, headersJSON, existStreamID); err != nil {
						return inserted, err
					}
				}
			} else {
				if _, err := tx.ExecContext(ctx, `INSERT INTO streams(channel_id,url,priority,health,headers_json) VALUES(?,?,?,?,?)`,
					existID, nu, 0, -1, headersJSON); err != nil {
					return inserted, err
				}
			}"""

assert old in s, "找不到导入时的判重段"
s = s.replace(old, new, 1)

# 补一个 urlKey 辅助函数
if "func urlKey(" not in s:
    s = s.rstrip() + """

// urlKey 取 URL 的「主机+路径」，丢掉 query 和 fragment。
//
// 用途：源站地址的签名放在 query 里，每次刷新都会变；
// 判重必须忽略签名，否则同一路信号会被当成新线路反复追加。
func urlKey(raw string) string {
	u, err := url.Parse(raw)
	if err != nil {
		return raw
	}
	return u.Scheme + "://" + u.Host + u.Path
}
"""

io.open(p, "w", encoding="utf-8").write(s)
print("ImportChannels: 判重改为按主机+路径")
