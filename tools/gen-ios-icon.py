"""生成 iOS App 图标（1024×1024）。

为什么不用现成的 ICON_256 放大：256 → 1024 会糊。
这里按同样的视觉语言（深藏青底 + 白色 LITV + 青色圆点和下划线）重新画一遍。

注意：iOS 会自己给图标加圆角遮罩，所以这里必须画**满幅方形**，
不能自己画圆角——否则会出现「圆角套圆角」的白边。
"""
from PIL import Image, ImageDraw, ImageFont

S = 1024
BG_TOP = (26, 42, 85)      # 深藏青（上）
BG_BOTTOM = (18, 30, 62)   # 更深（下）
ACCENT = (34, 211, 238)    # 青色 #22d3ee
WHITE = (255, 255, 255)

img = Image.new("RGB", (S, S), BG_TOP)
d = ImageDraw.Draw(img)

# 竖向渐变背景
for y in range(S):
    t = y / (S - 1)
    c = tuple(int(BG_TOP[i] + (BG_BOTTOM[i] - BG_TOP[i]) * t) for i in range(3))
    d.line([(0, y), (S, y)], fill=c)

# ---- LITV 字样 ----
# 字号别贪大：iOS 会给图标加圆角遮罩，左右各切掉约 8%，
# 字撑太满会被切到边缘。留足安全边距。
font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 240)
text = "LITV"
bbox = d.textbbox((0, 0), text, font=font)
tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
tx = (S - tw) / 2 - bbox[0]
ty = (S - th) / 2 - bbox[1] - 50
d.text((tx, ty), text, font=font, fill=WHITE)

# ---- 右上角青色圆点 ----
r = 44
cx, cy = S - 235, 235
d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=ACCENT)

# ---- 底部青色圆角横条 ----
bar_w, bar_h = 340, 30
bx = (S - bar_w) / 2
by = S - 285
d.rounded_rectangle([bx, by, bx + bar_w, by + bar_h], radius=bar_h // 2, fill=ACCENT)

out = "ios/LITVApp/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png"
import os
os.makedirs(os.path.dirname(out), exist_ok=True)
img.save(out, "PNG", optimize=True)
print("已生成:", out, img.size)
