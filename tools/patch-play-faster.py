"""换台起播优化：聚焦预取 + 缩短回退超时。

问题：换台要等「请求播放地址」+「引擎 prepare」两段，再加上引擎失败回退的 6 秒超时。
优化：
  1. 焦点落到某个台时预取播放地址并缓存，按 OK 时直接用（省一次网络往返）
  2. 回退超时 6s -> 3s（起不来的引擎不该拖这么久）
"""
import io

# ---------- 1) Api 里加播放地址缓存 + 预取 ----------
p = "android/app/src/main/java/com/litv/tv/data/Api.kt"
s = io.open(p, encoding="utf-8").read()

if "prefetchPlay" not in s:
    anchor = "    /**\n     * 取播放地址。"
    cache = '''    /**
     * 播放地址缓存：聚焦时预取，按 OK 直接用。
     *
     * 换台的感知延迟主要是「一次网络往返 + 引擎 prepare」。
     * 焦点在列表上移动时就把地址取回来，按 OK 时只剩 prepare，快一截。
     * 服务端签名有效期 12 小时，这里只缓存 5 分钟，够用且不会用到过期地址。
     */
    private val playCache = HashMap<Long, Pair<PlayInfo, Long>>()

    /** 预取播放地址（失败静默，按 OK 时还会再取一次） */
    fun prefetchPlay(channelId: Long, direct: Boolean) {
        val hit = playCache[channelId]
        if (hit != null && System.currentTimeMillis() - hit.second < 5 * 60_000) return
        val params = buildList {
            if (direct) add("direct=1")
        }
        val q = if (params.isEmpty()) "" else "?" + params.joinToString("&")
        call("/api/v1/tv/play/$channelId$q", clazz = PlayInfo::class.java,
            onOk = { info ->
                if (info != null) playCache[channelId] = info to System.currentTimeMillis()
            },
            onErr = { /* 预取失败不影响正常流程 */ })
    }

    /** 取播放地址。'''
    assert anchor in s, "找不到 play 的注释锚点"
    s = s.replace(anchor, cache, 1)
    io.open(p, "w", encoding="utf-8").write(s)
    print("Api: 已加 prefetchPlay + 缓存")

# play() 优先用缓存
s = io.open(p, encoding="utf-8").read()
old = """        val params = buildList {
            if (streamId > 0) add("stream_id=$streamId")
            if (direct) add("direct=1")
        }"""
new = """        // 有预取的地址就直接用，省一次网络往返
        if (streamId == 0) {
            val hit = playCache[channelId]
            if (hit != null && System.currentTimeMillis() - hit.second < 5 * 60_000) {
                onOk(hit.first)
                return
            }
        }
        val params = buildList {
            if (streamId > 0) add("stream_id=$streamId")
            if (direct) add("direct=1")
        }"""
if old in s and "有预取的地址就直接用" not in s:
    s = s.replace(old, new, 1)
    # 拿到结果后写缓存
    s = s.replace(
        'onOk = { if (it != null) onOk(it) else onErr("空数据") }, onErr = onErr)',
        'onOk = { info ->\n'
        '                if (info != null) {\n'
        '                    if (streamId == 0) playCache[channelId] = info to System.currentTimeMillis()\n'
        '                    onOk(info)\n'
        '                } else onErr("空数据")\n'
        '            }, onErr = onErr)',
        1,
    )
    io.open(p, "w", encoding="utf-8").write(s)
    print("Api: play() 已接缓存")

# ---------- 2) 回退超时 6s -> 3s ----------
p = "android/app/src/main/java/com/litv/tv/player/PlayerManager.kt"
s = io.open(p, encoding="utf-8").read()
if "START_TIMEOUT_MS = 6000L" in s:
    s = s.replace(
        "const val START_TIMEOUT_MS = 6000L",
        "// 起不来的内核不该拖 6 秒——3 秒足够判断，超时就换下一个\n        const val START_TIMEOUT_MS = 3000L",
        1,
    )
    io.open(p, "w", encoding="utf-8").write(s)
    print("PlayerManager: 回退超时 6s -> 3s")

# ---------- 3) TV 列表聚焦时预取 ----------
p = "android/app/src/main/java/com/litv/tv/ui/HomeActivity.kt"
s = io.open(p, encoding="utf-8").read()
if "prefetchPlay" not in s:
    s = s.replace(
        "            onFocus = { ch -> loadEpg(ch) },",
        "            onFocus = { ch ->\n"
        "                loadEpg(ch)\n"
        "                // 焦点到哪个台就预取哪个台的播放地址，按 OK 时直接起播\n"
        "                app.api.prefetchPlay(ch.id, app.prefs.directMode != \"relay\")\n"
        "            },",
        1,
    )
    io.open(p, "w", encoding="utf-8").write(s)
    print("HomeActivity: 聚焦预取已接")
