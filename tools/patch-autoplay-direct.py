"""开机直接进播放页：从缓存拿到上次频道就立刻跳，不等网络。"""
import io

p = "android/app/src/main/java/com/litv/tv/ui/HomeActivity.kt"
s = io.open(p, encoding="utf-8").read()

# 1) 缓存渲染完就自动播（不等网络），把「闪一下列表」压到最短
old = """        setupLists()
        renderCache()
        refresh()
        loadResume()"""
new = """        setupLists()
        renderCache()

        // 开机直接进播放页：电视就该"打开就看"。
        // 先拿缓存里的频道立刻跳，不等网络——否则会先闪一下列表。
        if (app.prefs.autoPlayLast && !autoPlayed) {
            val last = allChannels.firstOrNull { it.id == app.prefs.lastChannelId }
            if (last != null) {
                autoPlayed = true
                openPlayer(last)
            }
        }

        refresh()
        loadResume()"""
assert old in s, "找不到 onCreate 插入点"
s = s.replace(old, new, 1)

# 2) 网络回来后如果缓存里没有上次的台，再补一次
old2 = """        if (!fromCache && !autoPlayed) {
            autoPlayed = true
            val last = allChannels.firstOrNull { it.id == app.prefs.lastChannelId }
            if (app.prefs.autoPlayLast && last != null) {
                openPlayer(last)
            }
        }"""
new2 = """        if (!fromCache && !autoPlayed) {
            autoPlayed = true
            val last = allChannels.firstOrNull { it.id == app.prefs.lastChannelId }
            if (app.prefs.autoPlayLast && last != null) openPlayer(last)
        }"""
assert old2 in s, "找不到 applyData 插入点"
s = s.replace(old2, new2, 1)

io.open(p, "w", encoding="utf-8").write(s)
print("HomeActivity: 开机直进播放页")
