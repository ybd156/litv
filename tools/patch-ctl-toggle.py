"""暂停/播放、静音/取消静音 的切换态也带上文字。"""
import io

p = "android/app/src/main/java/com/litv/tv/ui/PhoneHomeActivity.kt"
s = io.open(p, encoding="utf-8").read()

pairs = [
    ('btnPlayPause.text = "\u23f8"', 'btnPlayPause.text = "\u23f8\\n\u6682\u505c"'),
    ('btnPlayPause.text = "\u25b6"', 'btnPlayPause.text = "\u25b6\\n\u64ad\u653e"'),
    ('btnMute.text = "\U0001f507"', 'btnMute.text = "\U0001f507\\n\u9759\u97f3"'),
    ('btnMute.text = "\U0001f50a"', 'btnMute.text = "\U0001f50a\\n\u53d6\u6d88\u9759\u97f3"'),
]
n = 0
for old, new in pairs:
    if old in s:
        s = s.replace(old, new)
        n += 1
io.open(p, "w", encoding="utf-8").write(s)
print(f"改了 {n} 处切换态文案")
