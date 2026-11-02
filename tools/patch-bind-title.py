"""绑定页收尾：标题文案 + 端口框宽度（放得下 28027 五位数）。"""
import io
import re

# 1) 标题
p = "android/app/src/main/res/values/strings.xml"
s = io.open(p, encoding="utf-8").read()
if "绑定这台电视" in s:
    s = s.replace("绑定这台电视", "绑定这台设备")
    io.open(p, "w", encoding="utf-8").write(s)
    print("strings: 标题已改")

# 2) 端口框加宽
for p in [
    "android/app/src/main/res/layout/activity_bind.xml",
    "android/app/src/main/res/layout-port/activity_bind.xml",
]:
    try:
        s = io.open(p, encoding="utf-8").read()
    except FileNotFoundError:
        continue
    m = re.search(r'android:id="@\+id/port"[\s\S]{0,260}?android:layout_width="(\d+)dp"', s)
    if m:
        old = m.group(0)
        new = old.replace('android:layout_width="%sdp"' % m.group(1),
                          'android:layout_width="88dp"')
        s = s.replace(old, new, 1)
        io.open(p, "w", encoding="utf-8").write(s)
        print(p, ": 端口框 -> 88dp")
    else:
        print(p, ": 没匹配到 port 框")
