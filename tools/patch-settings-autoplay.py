"""设置页加「开机自动播放上次频道」开关。"""
import io
import re

# 1) 布局：在 rowReverse 之后插入一行
p = "android/app/src/main/res/layout/activity_settings.xml"
s = io.open(p, encoding="utf-8").read()

if "rowAutoPlay" not in s:
    m = re.search(
        r'(<LinearLayout\s+android:id="@\+id/rowReverse".*?</LinearLayout>)',
        s, re.S,
    )
    assert m, "找不到 rowReverse 块"
    row = m.group(1)
    new_row = row.replace("rowReverse", "rowAutoPlay").replace(
        'android:text="换台方向反转"', 'android:text="开机自动播放上次频道"'
    ).replace("valueReverse", "valueAutoPlay")
    s = s.replace(row, row + "\n\n        " + new_row, 1)
    io.open(p, "w", encoding="utf-8").write(s)
    print("布局：已加 rowAutoPlay")
else:
    print("布局：已有")

# 2) Activity：绑定 + 字段
p = "android/app/src/main/java/com/litv/tv/ui/SettingsActivity.kt"
s = io.open(p, encoding="utf-8").read()

if "valueAutoPlay" not in s:
    s = s.replace(
        "    private lateinit var valueReverse: TextView",
        "    private lateinit var valueReverse: TextView\n    private lateinit var valueAutoPlay: TextView",
        1,
    )
    s = s.replace(
        "        valueReverse = findViewById(R.id.valueReverse)",
        "        valueReverse = findViewById(R.id.valueReverse)\n"
        "        valueAutoPlay = findViewById(R.id.valueAutoPlay)",
        1,
    )
    anchor = """        bindCycle(R.id.rowNumberTimeout, valueNumberTimeout,"""
    block = """        bindCycle(R.id.rowAutoPlay, valueAutoPlay, listOf("off", "on"),
            if (app.prefs.autoPlayLast) "on" else "off") { v ->
            app.prefs.autoPlayLast = (v == "on")
            valueAutoPlay.text = if (v == "on") "开（打开就看）" else "关（先进列表）"
        }
"""
    assert anchor in s, "找不到 bindCycle 插入点"
    s = s.replace(anchor, block + anchor, 1)
    io.open(p, "w", encoding="utf-8").write(s)
    print("Activity：已绑定 rowAutoPlay")
else:
    print("Activity：已有")
