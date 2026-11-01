"""设置页加「界面模式」行：自动 / 电视 / 手机。

自动判定在部分电视盒子上会失灵（它们同时上报触摸屏和窄屏宽），
结果电视上跑出手机界面。给个手动开关最稳。
"""
import io
import re

# 1) 布局：插在「操作习惯」分区的最后一行（rowNumberTimeout 之后）
p = "android/app/src/main/res/layout/activity_settings.xml"
s = io.open(p, encoding="utf-8").read()
if "rowUiMode" not in s:
    m = re.search(
        r'(<LinearLayout\s+android:id="@\+id/rowNumberTimeout".*?</LinearLayout>)',
        s, re.S,
    )
    assert m, "找不到 rowNumberTimeout 块"
    row = m.group(1)
    new_row = row.replace("rowNumberTimeout", "rowUiMode").replace(
        'android:text="数字键跳台等待"', 'android:text="界面模式"'
    ).replace("valueNumberTimeout", "valueUiMode")
    s = s.replace(row, row + "\n\n        " + new_row, 1)
    io.open(p, "w", encoding="utf-8").write(s)
    print("布局：已加 rowUiMode")
else:
    print("布局：已有")

# 2) Activity
p = "android/app/src/main/java/com/litv/tv/ui/SettingsActivity.kt"
s = io.open(p, encoding="utf-8").read()
if "valueUiMode" not in s:
    s = s.replace(
        "    private lateinit var valueNumberTimeout: TextView",
        "    private lateinit var valueNumberTimeout: TextView\n    private lateinit var valueUiMode: TextView",
        1,
    )
    s = s.replace(
        "        valueNumberTimeout = findViewById(R.id.valueNumberTimeout)",
        "        valueNumberTimeout = findViewById(R.id.valueNumberTimeout)\n"
        "        valueUiMode = findViewById(R.id.valueUiMode)",
        1,
    )
    anchor = "        // 设备信息（只读）"
    block = """        bindCycle(R.id.rowUiMode, valueUiMode, listOf("auto", "tv", "phone"),
            app.prefs.uiMode) { v ->
            app.prefs.uiMode = v
            labelUiMode(v)
        }

"""
    assert anchor in s, "找不到插入点"
    s = s.replace(anchor, block + anchor, 1)

    # 初值
    s = s.replace(
        "        valueAutoPlay.text = if (app.prefs.autoPlayLast) \"开（打开就看）\" else \"关（先进列表）\"",
        "        valueAutoPlay.text = if (app.prefs.autoPlayLast) \"开（打开就看）\" else \"关（先进列表）\"\n"
        "        labelUiMode(app.prefs.uiMode)",
        1,
    )

    # 标签函数
    s = s.rstrip()
    assert s.endswith("}")
    s = s[:-1] + """
    private fun labelUiMode(v: String) {
        valueUiMode.text = when (v) {
            "tv" -> "电视"
            "phone" -> "手机"
            else -> "自动"
        }
    }
}
"""
    io.open(p, "w", encoding="utf-8").write(s)
    print("Activity：已加 rowUiMode")
else:
    print("Activity：已有")
