"""手机播放控件加文字说明。

原来只有 ⏮ ⏸ ⏭ 🔇 ⛶ 五个符号，不看说明书根本不知道哪个是换台、哪个是静音。
改成图标 + 一行小字。
"""
import io

p = "android/app/src/main/res/layout/activity_phone_home.xml"
s = io.open(p, encoding="utf-8").read()

pairs = [
    ("btnPrev", "⏮", "上一台"),
    ("btnPlayPause", "⏸", "暂停"),
    ("btnNext", "⏭", "下一台"),
    ("btnMute", "🔇", "静音"),
    ("btnFullscreen", "⛶", "全屏"),
]

for rid, icon, label in pairs:
    old = 'android:id="@+id/%s"\n                    style="@style/PhoneCtl"\n                    android:text="%s" />' % (rid, icon)
    new = ('android:id="@+id/%s"\n'
           '                    style="@style/PhoneCtl"\n'
           '                    android:text="%s\\n%s" />') % (rid, icon, label)
    if old in s:
        s = s.replace(old, new, 1)
        print(f"{rid}: 已加文字「{label}」")
    else:
        print(f"{rid}: 未匹配")

io.open(p, "w", encoding="utf-8").write(s)
