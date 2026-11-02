"""把右侧设置菜单接进去。"""
import io

# 1) 样式
p = "android/app/src/main/res/values/styles.xml"
s = io.open(p, encoding="utf-8").read()
if "SettingsMenuItem" not in s:
    s = s.replace("</resources>", """
    <!-- 右侧设置菜单的一项 -->
    <style name="SettingsMenuItem">
        <item name="android:layout_width">match_parent</item>
        <item name="android:layout_height">wrap_content</item>
        <item name="android:layout_marginVertical">3dp</item>
        <item name="android:paddingVertical">13dp</item>
        <item name="android:gravity">center</item>
        <item name="android:textColor">@color/text_primary</item>
        <item name="android:textSize">16sp</item>
        <item name="android:background">@drawable/bg_focusable</item>
        <item name="android:focusable">true</item>
        <item name="android:clickable">true</item>
    </style>
</resources>""")
    io.open(p, "w", encoding="utf-8").write(s)
    print("样式 SettingsMenuItem 已加")

# 2) 主布局引入
p = "android/app/src/main/res/layout/activity_player.xml"
s = io.open(p, encoding="utf-8").read()
if "view_settings_menu" not in s:
    i = s.rindex("</FrameLayout>")
    s = s[:i] + '\n    <include layout="@layout/view_settings_menu" />\n' + s[i:]
    io.open(p, "w", encoding="utf-8").write(s)
    print("activity_player.xml 已引入设置菜单")
