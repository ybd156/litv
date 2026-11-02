"""把绑定页端口输入框加宽到 88dp（放得下 28027 五位数）。"""
import io
import re

PAT = r'android:id="@\+id/portInput"[\s\S]{0,300}?android:layout_width="(\d+)dp"'

for p in [
    "android/app/src/main/res/layout/activity_bind.xml",
    "android/app/src/main/res/layout-port/activity_bind.xml",
]:
    s = io.open(p, encoding="utf-8").read()
    m = re.search(PAT, s)
    if not m:
        print(p, ": 未匹配")
        continue
    old = m.group(0)
    new = old.replace(
        'android:layout_width="%sdp"' % m.group(1),
        'android:layout_width="88dp"',
    )
    s = s.replace(old, new, 1)
    io.open(p, "w", encoding="utf-8").write(s)
    print(p, ": 端口框 -> 88dp")
