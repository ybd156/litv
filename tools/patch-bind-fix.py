"""绑定页三处修：标题、默认端口、按钮配色。

1. 手机上标题写"绑定这台电视"——设备类型是手机时应该说"绑定这台设备"
2. 默认端口还是 9527（服务端已改 28027，而且 9527 常被别的 IPTV 应用占用）
3. "绑定"按钮是深灰底，应该是主题色（其他页面都用 accent）
"""
import io
import re

# ---------- 1. 布局：标题 + 端口提示 + 按钮 ----------
for p in [
    "android/app/src/main/res/layout/activity_bind.xml",
    "android/app/src/main/res/layout-port/activity_bind.xml",
]:
    try:
        s = io.open(p, encoding="utf-8").read()
    except FileNotFoundError:
        continue
    before = s
    # 标题
    s = s.replace("绑定这台电视", "绑定这台设备")
    # 端口提示文案里的 9527 -> 28027
    s = s.replace("端口默认 9527", "端口默认 28027")
    s = s.replace("tv.example.com:9527", "tv.example.com:28027")
    s = s.replace('android:text="9527"', 'android:text="28027"')
    # 按钮配色：把深灰背景换成 accent
    s = s.replace(
        'android:id="@+id/btnBind"\n            android:layout_width="match_parent"\n            android:layout_height="wrap_content"\n            android:background="@drawable/bg_card"',
        'android:id="@+id/btnBind"\n            android:layout_width="match_parent"\n            android:layout_height="wrap_content"\n            android:background="@drawable/bg_accent_button"\n            android:textColor="#000000"',
    )
    if s != before:
        io.open(p, "w", encoding="utf-8").write(s)
        print(p, ": 已更新")
    else:
        print(p, ": 无需改动（或未匹配）")

# ---------- 2. 按钮背景 drawable ----------
p = "android/app/src/main/res/drawable/bg_accent_button.xml"
io.open(p, "w", encoding="utf-8").write("""<?xml version="1.0" encoding="utf-8"?>
<!-- 主按钮：主题色底 + 黑字，和设置页/登录页的按钮一致 -->
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <solid android:color="@color/accent" />
    <corners android:radius="10dp" />
</shape>
""")
print("已加 bg_accent_button")

# ---------- 3. BindActivity 默认端口 ----------
p = "android/app/src/main/java/com/litv/tv/ui/BindActivity.kt"
s = io.open(p, encoding="utf-8").read()
if '"9527"' in s:
    s = s.replace('"9527"', '"28027"')
    io.open(p, "w", encoding="utf-8").write(s)
    print("BindActivity: 默认端口改 28027")

# ---------- 4. iOS 同步 ----------
p = "ios/LITVApp/Sources/BindView.swift"
try:
    s = io.open(p, encoding="utf-8").read()
    if 'defaultPort = "9527"' in s:
        s = s.replace('defaultPort = "9527"', 'defaultPort = "28027"')
        io.open(p, "w", encoding="utf-8").write(s)
        print("iOS BindView: 默认端口改 28027")
except FileNotFoundError:
    pass
