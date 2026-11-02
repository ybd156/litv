"""修「点一下卡住，切走再回来才播」。

现象：某个台偶尔点进去一直转圈，切到别的台再切回来就正常了。

根因：hub 里每个频道维护一个上游连接，Subscribe 的复用条件是
「存在 且 未关闭 且 URL 相同」。如果上游卡住了（TCP 半开、源站不推数据）
但没被标记 closed，新订阅者会挂到这个死连接上，永远等不到数据。
切走之后所有订阅者退出、上游被回收，再回来才新建连接，于是又好了。

改法：给上游记一个「最后一次收到数据的时间」。
Subscribe 时如果这个上游超过 15 秒没出过数据（或者建了 20 秒一个字节都没来），
就判定为卡死，关掉重建。
"""
import io

p = "server/internal/stream/hub.go"
s = io.open(p, encoding="utf-8").read()

# 1) 加字段
s = s.replace(
    """	started time.Time
	bytesIn atomic.Int64""",
    """	started time.Time
	bytesIn atomic.Int64
	// 最后一次收到上游数据的 UnixNano。0 = 还没收到过。
	// 用来判断「连接还开着但已经不推数据了」这种半死状态。
	lastData atomic.Int64""",
    1,
)

# 2) 读到数据时打点
s = s.replace(
    "			u.bytesIn.Add(int64(n))",
    "			u.bytesIn.Add(int64(n))\n			u.lastData.Store(time.Now().UnixNano())",
    1,
)

# 3) Subscribe 的复用条件加上「是否卡死」
old = """	h.mu.Lock()
	up, ok := h.ups[channelID]
	if !ok || up.closed || up.URL != url {"""
new = """	h.mu.Lock()
	up, ok := h.ups[channelID]

	// 上游卡死判定：连接还开着，但已经不推数据了。
	// 不重建的话，新订阅者会挂在这个死连接上一直转圈。
	stale := false
	if ok && up != nil && !up.closed {
		now := time.Now()
		if last := up.lastData.Load(); last > 0 {
			stale = now.Sub(time.Unix(0, last)) > 15*time.Second
		} else if now.Sub(up.started) > 20*time.Second {
			// 建了 20 秒一个字节都没收到
			stale = true
		}
	}

	if !ok || up.closed || up.URL != url || stale {"""
assert old in s, "找不到 Subscribe 的复用判断"
s = s.replace(old, new, 1)

io.open(p, "w", encoding="utf-8").write(s)
print("hub: 已加卡死上游重建")
