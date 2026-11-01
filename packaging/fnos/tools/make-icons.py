#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 LITV 应用图标（占位图，后续可替换为设计稿）。

官方《图标》规范：
  - 包根需要 ICON.PNG(64x64) 与 ICON_256.PNG(256x256)
  - PNG/JPG、sRGB、单文件 <= 1024KB
  - 画布是完整正方形，但**视觉主体应是圆角矩形**，不要直角满铺
  - 64x64 下仍要能看清主体

本脚本一次生成 4 个文件（包图标 + 桌面入口图标）：
  ICON.PNG / ICON_256.PNG
  app/ui/images/icon_64.png / icon_256.png

用法（在 packaging/fnos 下）：
  python3 tools/make-icons.py
"""

import os
import sys

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:
    sys.exit("需要 Pillow：pip install Pillow")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)  # packaging/fnos

# 配色：深蓝底 + 青绿高光，贴近"直播/媒体"的观感
BG_TOP = (16, 24, 40)       # #101828
BG_BOTTOM = (30, 64, 175)   # #1E40AF
ACCENT = (34, 211, 238)     # #22D3EE
TEXT = (255, 255, 255)

FONT_CANDIDATES = [
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
    "/usr/share/fonts/truetype/ubuntu/Ubuntu-B.ttf",
]


def load_font(size):
    for path in FONT_CANDIDATES:
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def vertical_gradient(size, top, bottom):
    """逐行插值生成竖向渐变（不引入 numpy 依赖）。"""
    w, h = size
    img = Image.new("RGB", (1, h))
    px = img.load()
    for y in range(h):
        t = y / max(1, h - 1)
        px[0, y] = (
            int(top[0] + (bottom[0] - top[0]) * t),
            int(top[1] + (bottom[1] - top[1]) * t),
            int(top[2] + (bottom[2] - top[2]) * t),
        )
    return img.resize((w, h), Image.BILINEAR)


def make_icon(size):
    # 4x 超采样后缩小，边缘更干净（尤其是 64px 下的圆角与文字）
    scale = 4
    s = size * scale
    canvas = Image.new("RGBA", (s, s), (0, 0, 0, 0))

    # 圆角矩形主体：留 ~7% 内边距，符合"不要满铺"的规范
    pad = int(s * 0.07)
    radius = int(s * 0.22)
    body = vertical_gradient((s - 2 * pad, s - 2 * pad), BG_TOP, BG_BOTTOM).convert("RGBA")

    mask = Image.new("L", body.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, body.size[0] - 1, body.size[1] - 1], radius=radius, fill=255)

    canvas.paste(body, (pad, pad), mask)
    d = ImageDraw.Draw(canvas)

    # 底部一条青色高光，暗示"播放进度条"
    bar_h = max(2, int(s * 0.035))
    bar_y = s - pad - int(s * 0.17)
    d.rounded_rectangle(
        [pad + int(s * 0.10), bar_y, pad + int(s * 0.52), bar_y + bar_h],
        radius=bar_h // 2,
        fill=ACCENT + (255,),
    )

    # 主文字 LITV
    font = load_font(int(s * 0.30))
    text = "LITV"
    bbox = d.textbbox((0, 0), text, font=font)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    tx = (s - tw) // 2 - bbox[0]
    ty = int(s * 0.46) - th // 2 - bbox[1]
    d.text((tx, ty), text, font=font, fill=TEXT + (255,))

    # 右上角一个小圆点（"live" 指示灯）
    r = int(s * 0.045)
    cx, cy = s - pad - int(s * 0.13), pad + int(s * 0.13)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=ACCENT + (255,))

    return canvas.resize((size, size), Image.LANCZOS)


def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, "PNG", optimize=True)
    kb = os.path.getsize(path) / 1024.0
    print(f"  {path}  {img.size[0]}x{img.size[1]}  {kb:.1f} KB")
    if kb > 1024:
        print("  ⚠️ 超过官方 1024KB 限制")


def main():
    print("生成 LITV 图标：")
    save(make_icon(64), os.path.join(ROOT, "ICON.PNG"))
    save(make_icon(256), os.path.join(ROOT, "ICON_256.PNG"))
    # 桌面入口图标（app/ui/config 里 icon = images/icon_{0}.png）
    save(make_icon(64), os.path.join(ROOT, "app", "ui", "images", "icon_64.png"))
    save(make_icon(256), os.path.join(ROOT, "app", "ui", "images", "icon_256.png"))
    print("完成。")


if __name__ == "__main__":
    main()
