"""遥控器页加滚动容器。

加了底部菜单栏之后内容高度不够，方向键的「下」被截掉了。
把内容包进 ScrollView（weight=1），底部栏固定不动。
"""
import io

p = "android/app/src/main/res/layout/activity_remote.xml"
s = io.open(p, encoding="utf-8").read()

if "<ScrollView" not in s:
    marker = '<include layout="@layout/view_bottom_tabs" />'
    assert marker in s, "找不到底部栏 include"

    # 根 LinearLayout 的开标签之后开始包
    root_end = s.index(">", s.index("<LinearLayout xmlns:android")) + 1
    inc_at = s.index(marker)

    inner = s[root_end:inc_at]

    wrapped = (
        "\n\n    <ScrollView\n"
        '        android:layout_width="match_parent"\n'
        '        android:layout_height="0dp"\n'
        '        android:layout_weight="1"\n'
        '        android:fillViewport="true"\n'
        '        android:scrollbars="none">\n\n'
        "        <LinearLayout\n"
        '            android:layout_width="match_parent"\n'
        '            android:layout_height="wrap_content"\n'
        '            android:orientation="vertical">'
        + inner
        + "\n        </LinearLayout>\n    </ScrollView>\n\n    "
    )
    s = s[:root_end] + wrapped + s[inc_at:]
    io.open(p, "w", encoding="utf-8").write(s)
    print("activity_remote.xml: 已加 ScrollView")
else:
    print("已有 ScrollView")
