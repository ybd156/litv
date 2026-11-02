"""起播超时 + 超时重试次数 做成设置项。

背景：3 秒的起播超时在慢设备/走代理时会误判——Exo 其实只是还没 READY，
就被当成"起不来"降级到 VLC 了。超时和重试都该让用户自己调。
"""
import io

# ---------- 1. Prefs ----------
p = "android/app/src/main/java/com/litv/tv/data/Prefs.kt"
s = io.open(p, encoding="utf-8").read()

if "startTimeoutMs" not in s:
    s = s.replace(
        """    var aspect: String""",
        """    /** 起播超时（毫秒）。超过这个时间还没 READY 就换下一个内核。 */
    var startTimeoutMs: Int
        get() = sp.getInt(K_START_TIMEOUT, 5000)
        set(v) = sp.edit().putInt(K_START_TIMEOUT, v).apply()

    /** 超时后重试几次再换内核。网络抖动时多试几次比直接降级好。 */
    var retryCount: Int
        get() = sp.getInt(K_RETRY, 1)
        set(v) = sp.edit().putInt(K_RETRY, v).apply()

    var aspect: String""",
        1,
    )
    s = s.replace(
        '        const val K_BUFFER = "buffer_ms"',
        '        const val K_BUFFER = "buffer_ms"\n'
        '        const val K_START_TIMEOUT = "start_timeout_ms"\n'
        '        const val K_RETRY = "retry_count"',
        1,
    )
    io.open(p, "w", encoding="utf-8").write(s)
    print("Prefs: 已加 startTimeoutMs / retryCount")

# ---------- 2. PlayerManager 用配置值 ----------
p = "android/app/src/main/java/com/litv/tv/player/PlayerManager.kt"
s = io.open(p, encoding="utf-8").read()

if "prefs.startTimeoutMs" not in s:
    s = s.replace(
        "        main.postDelayed(startTimeout, START_TIMEOUT_MS)",
        "        // 超时和重试次数都从设置里读，不再写死\n"
        "        main.postDelayed(startTimeout, prefs.startTimeoutMs.toLong())",
        1,
    )
    # 超时回调里加重试
    old_timeout = """    private val startTimeout = Runnable {"""
    if old_timeout in s:
        s = s.replace(
            "    private val startTimeout = Runnable {\n",
            "    /** 本次内核已经重试过几次 */\n"
            "    private var retried = 0\n\n"
            "    private val startTimeout = Runnable {\n",
            1,
        )
        # 在「尝试下一个」之前插入重试判断
        s = s.replace(
            '            chainIndex++',
            '            if (retried < prefs.retryCount) {\n'
            '                // 还没到重试上限，同一个内核再来一次（网络抖动能救回来）\n'
            '                retried++\n'
            '                android.util.Log.w("PlayerManager", "内核起播超时，重试第 $retried 次")\n'
            '                startEngine()\n'
            '                return@Runnable\n'
            '            }\n'
            '            retried = 0\n'
            '            chainIndex++',
            1,
        )
    io.open(p, "w", encoding="utf-8").write(s)
    print("PlayerManager: 已接入超时/重试配置")
