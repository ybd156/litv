#!/usr/bin/env python3
"""检查发版版本号在所有位置是否一致。

AGENTS.md §11 列了五处必须同步的位置。漏掉任何一处，用户看到的
"关于"页版本就会和实际产物对不上——这种问题只有发版后才暴露，
所以在 build-check 里提前拦住。

用法：
    python3 tools/checks/check-versions.py
    python3 tools/checks/check-versions.py --expect 0.1.14

`--expect` 用于发版：把 tag 推出来的版本号和这五处对齐。
只比"五处互相一致"是不够的——它们可能全都停在旧版本，那样打 tag 发出去的
包「关于」页会显示旧版本号，而 Release 标题是新版本号（0.1.13 之前踩过）。

有不一致时返回 1。所有路径都相对仓库根目录推出来，不依赖当前目录。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# (文件, 提取版本号的正则, 位置说明)
CHECKS = [
    ("packaging/fnos/manifest", r"^version\s*=\s*(\S+)", "version"),
    ("android/app/build.gradle.kts", r'versionName\s*=\s*"([^"]+)"', "versionName"),
    ("ios/project.yml", r'CFBundleShortVersionString:\s*"?([^"\s]+)"?', "CFBundleShortVersionString"),
    (".github/workflows/release.yml", r'DEFAULT_VERSION:\s*"?([^"\s]+)"?', "DEFAULT_VERSION"),
    ("CHANGELOG.md", r"^##\s*\[([0-9][^\]]*)\]", "最新的 ## [x.y.z]"),
]


def extract(rel: str, pattern: str) -> str | None:
    path = ROOT / rel
    if not path.exists():
        print(f"[错误] 找不到文件：{rel}")
        return None
    text = path.read_text(encoding="utf-8", errors="replace")
    m = re.search(pattern, text, re.M)
    if not m:
        return None
    return m.group(1).strip().strip("\"'")


def parse_expect(argv: list[str]) -> str | None:
    """取出 --expect 的值（兼容 --expect=1.2.3 与 --expect 1.2.3 两种写法）。"""
    for i, arg in enumerate(argv):
        if arg == "--expect" and i + 1 < len(argv):
            return argv[i + 1].strip().lstrip("v")
        if arg.startswith("--expect="):
            return arg.split("=", 1)[1].strip().lstrip("v")
    return None


def main() -> int:
    expect = parse_expect(sys.argv[1:])
    found: dict[str, str] = {}
    missing = False
    for rel, pattern, label in CHECKS:
        value = extract(rel, pattern)
        if not value:
            print(f"[错误] {rel}: 找不到 {label}")
            missing = True
            continue
        found[rel] = value
        print(f"{rel}: {label} = {value}")

    if missing:
        return 1

    versions = set(found.values())
    if len(versions) != 1:
        print("[错误] 版本号不一致（AGENTS.md §11 要求这五处同步）：")
        for rel, value in found.items():
            print(f"   {value:<10} {rel}")
        return 1

    actual = versions.pop()
    if expect and actual != expect:
        # 这一条比"五处自洽"更重要：五处可以一起停在旧版本，
        # 那样 tag 是新版本、产物里的「关于」却是旧版本。
        print(f"[错误] 本次发版版本是 {expect}，但仓库里五处都是 {actual}。")
        print("       发版前请同步：manifest / versionName / CFBundleShortVersionString /")
        print("       release.yml DEFAULT_VERSION / CHANGELOG 顶部。")
        return 1

    suffix = f"（与本次发版 {expect} 一致）" if expect else ""
    print(f"[OK] 版本号一致：{actual}{suffix}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
