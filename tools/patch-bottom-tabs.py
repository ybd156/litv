"""把底部菜单栏加到设置页和遥控器页，并接线。

之前只有首页有底部栏，遥控器/设置打开就是"另一个页面"——用户会懵。
做法：把两页的根布局从 ScrollView 包成「LinearLayout(vertical) + ScrollView + include 底部栏」。
"""
import io

TABS_INCLUDE = """
    <include layout="@layout/view_bottom_tabs" />
"""


def wrap(path: str, root_tag: str) -> None:
    s = io.open(path, encoding="utf-8").read()
    if "view_bottom_tabs" in s:
        print(path, ": 已有底部栏")
        return

    # 1) 根标签换成 LinearLayout，里面放原来的 ScrollView
    old_open = "<" + root_tag + " xmlns:android=\"http://schemas.android.com/apk/res/android\""
    assert old_open in s, f"{path}: 找不到根标签 {root_tag}"

    # 取出 ScrollView 的命名空间声明，挪到外层 LinearLayout
    head_end = s.index(">", s.index(old_open)) + 1
    inner = s[head_end:]

    # 去掉 ScrollView 自己的结束标签
    close = "</" + root_tag + ">"
    assert close in inner, f"{path}: 找不到 {close}"
    inner = inner[: inner.rindex(close)]

    out = (
        '<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:layout_width="match_parent"\n'
        '    android:layout_height="match_parent"\n'
        '    android:background="@color/bg"\n'
        '    android:orientation="vertical">\n'
        + "<" + root_tag + "\n"
        '    android:layout_width="match_parent"\n'
        '    android:layout_height="0dp"\n'
        '    android:layout_weight="1">'
        + inner
        + close
        + "\n"
        + TABS_INCLUDE
        + "</LinearLayout>\n"
    )
    io.open(path, "w", encoding="utf-8").write(out)
    print(path, ": 已加底部栏")


wrap("android/app/src/main/res/layout/activity_settings.xml", "ScrollView")
wrap("android/app/src/main/res/layout/activity_remote.xml", "ScrollView")

# ---- 接线 ----
for path, tab in [
    ("android/app/src/main/java/com/litv/tv/ui/SettingsActivity.kt", "TAB_SETTINGS"),
    ("android/app/src/main/java/com/litv/tv/ui/RemoteActivity.kt", "TAB_REMOTE"),
]:
    s = io.open(path, encoding="utf-8").read()
    if "BottomTabs.wire" in s:
        print(path, ": 已接线")
        continue
    anchor = "        setContentView(R.layout."
    i = s.index(anchor)
    j = s.index("\n", i) + 1
    s = s[:j] + f"        BottomTabs.wire(this, BottomTabs.{tab})\n" + s[j:]
    io.open(path, "w", encoding="utf-8").write(s)
    print(path, ": 已接线")
