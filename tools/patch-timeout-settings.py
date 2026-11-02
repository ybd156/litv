"""设置页加「起播超时」和「超时重试次数」两项。

超时：3/5/8/12 秒可选，慢设备或走代理时可以调大。
重试：0/1/2/3 次，网络抖动时多试几次比直接降级到别的内核好。
"""
import io
import re

# ---------- 布局 ----------
p = "android/app/src/main/res/layout/activity_settings.xml"
s = io.open(p, encoding="utf-8").read()

if "rowStartTimeout" not in s:
    m = re.search(r'(<LinearLayout\s+android:id="@\+id/rowUiMode".*?</LinearLayout>)', s, re.S)
    assert m, "找不到 rowUiMode 作为模板"
    base = m.group(1)

    row_timeout = (base.replace("rowUiMode", "rowStartTimeout")
                       .replace("valueUiMode", "valueStartTimeout")
                       .replace('android:text="界面模式"', 'android:text="起播超时"'))
    row_retry = (base.replace("rowUiMode", "rowRetry")
                     .replace("valueUiMode", "valueRetry")
                     .replace('android:text="界面模式"', 'android:text="超时重试次数"'))

    s = s.replace(base, base + "\n\n        " + row_timeout + "\n\n        " + row_retry, 1)
    io.open(p, "w", encoding="utf-8").write(s)
    print("布局: 已加 rowStartTimeout / rowRetry")

# ---------- Activity ----------
p = "android/app/src/main/java/com/litv/tv/ui/SettingsActivity.kt"
s = io.open(p, encoding="utf-8").read()

if "valueStartTimeout" not in s:
    s = s.replace(
        "    private lateinit var valueUiMode: TextView",
        "    private lateinit var valueUiMode: TextView\n"
        "    private lateinit var valueStartTimeout: TextView\n"
        "    private lateinit var valueRetry: TextView",
        1,
    )
    s = s.replace(
        "        valueUiMode = findViewById(R.id.valueUiMode)",
        "        valueUiMode = findViewById(R.id.valueUiMode)\n"
        "        valueStartTimeout = findViewById(R.id.valueStartTimeout)\n"
        "        valueRetry = findViewById(R.id.valueRetry)",
        1,
    )
    anchor = "        bindCycle(R.id.rowUiMode, valueUiMode"
    block = """        // 起播超时：慢设备/走代理时 3 秒会误判成"起不来"，这里可调
        bindCycle(R.id.rowStartTimeout, valueStartTimeout,
            listOf("3000", "5000", "8000", "12000"), app.prefs.startTimeoutMs.toString()) { v ->
            app.prefs.startTimeoutMs = v.toIntOrNull() ?: 5000
            labelStartTimeout(app.prefs.startTimeoutMs)
        }

        // 超时重试次数：网络抖动时多试几次比直接换内核好
        bindCycle(R.id.rowRetry, valueRetry,
            listOf("0", "1", "2", "3"), app.prefs.retryCount.toString()) { v ->
            app.prefs.retryCount = v.toIntOrNull() ?: 1
            labelRetry(app.prefs.retryCount)
        }

"""
    assert anchor in s, "找不到 rowUiMode 的 bindCycle"
    s = s.replace(anchor, block + anchor, 1)

    # 初值
    s = s.replace(
        "        labelUiMode(app.prefs.uiMode)",
        "        labelUiMode(app.prefs.uiMode)\n"
        "        labelStartTimeout(app.prefs.startTimeoutMs)\n"
        "        labelRetry(app.prefs.retryCount)",
        1,
    )

    # 标签函数
    s = s.rstrip()
    assert s.endswith("}")
    s = s[:-1] + """
    private fun labelStartTimeout(ms: Int) {
        valueStartTimeout.text = "${ms / 1000} 秒"
    }

    private fun labelRetry(n: Int) {
        valueRetry.text = if (n == 0) "不重试" else "$n 次"
    }
}
"""
    io.open(p, "w", encoding="utf-8").write(s)
    print("Activity: 已接线")
