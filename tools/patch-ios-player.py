"""把 PlayerView 里对 `channel` 的引用改成 `active`，让换台生效。

`channel` 是进来时传进来的那个（let），换台后必须用 `active`
（= switched ?? channel），否则界面和请求都还指向旧台。
"""
import io

p = "ios/LITVApp/Sources/PlayerView.swift"
s = io.open(p, encoding="utf-8").read()

pairs = [
    ("Text(channel.name).font(.headline)", "Text(active.name).font(.headline)"),
    ("Text(channel.group).font(.caption)", "Text(active.group).font(.caption)"),
    ("$0.id == channel.id", "$0.id == active.id"),
    ("channelId: channel.id", "channelId: active.id"),
]
n = 0
for old, new in pairs:
    c = s.count(old)
    if c:
        s = s.replace(old, new)
        n += c
io.open(p, "w", encoding="utf-8").write(s)
print("替换", n, "处 channel -> active")
