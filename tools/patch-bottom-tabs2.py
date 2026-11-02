"""遥控器页底部栏 + 两页接线（设置页的布局已经处理过了）。"""
import io

# 1) 遥控器布局：根已经是 LinearLayout(vertical)，直接追加 include
p = "android/app/src/main/res/layout/activity_remote.xml"
s = io.open(p, encoding="utf-8").read()
if "view_bottom_tabs" not in s:
    # 根上的 padding 会让底部栏也缩进，挪掉；内边距交给内容区
    s = s.replace('    android:orientation="vertical"\n    android:padding="20dp">',
                  '    android:orientation="vertical">', 1)
    idx = s.rindex("</LinearLayout>")
    s = s[:idx] + '\n    <include layout="@layout/view_bottom_tabs" />\n' + s[idx:]
    io.open(p, "w", encoding="utf-8").write(s)
    print("activity_remote.xml: 已加底部栏")
else:
    print("activity_remote.xml: 已有")

# 2) 接线
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
