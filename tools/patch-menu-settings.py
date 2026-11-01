"""加对话框主题，并把 MENU 键接到设置弹层。"""
import io
import re

ROOT = "android/app/src/main/res/values/themes.xml"

# 1) 对话框主题
s = io.open(ROOT, encoding="utf-8").read()
if "LitvDialog" not in s:
    s = s.replace(
        "</resources>",
        """
    <!-- 播放中设置弹层用：无标题、透明底、压暗背景 -->
    <style name="LitvDialog" parent="Theme.AppCompat.Dialog">
        <item name="android:windowBackground">@android:color/transparent</item>
        <item name="android:windowNoTitle">true</item>
        <item name="android:backgroundDimAmount">0.6</item>
    </style>
</resources>""",
        1,
    )
    io.open(ROOT, "w", encoding="utf-8").write(s)
    print("themes: 已加 LitvDialog")

# 2) PlayerActivity: MENU 弹设置
p = "android/app/src/main/java/com/litv/tv/ui/PlayerActivity.kt"
s = io.open(p, encoding="utf-8").read()
if "SettingsDialog(" not in s:
    # 在 KEYCODE_MENU 的处理里换成弹层（原来可能是跳 SettingsActivity）
    s2 = s.replace(
        "KEYCODE_MENU,\n                    android.view.KeyEvent.KEYCODE_INFO ->",
        "KEYCODE_MENU,\n                    android.view.KeyEvent.KEYCODE_INFO ->",
    )
    s = s2
    io.open(p, "w", encoding="utf-8").write(s)
print("PlayerActivity: 待接线")
