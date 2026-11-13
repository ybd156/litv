#!/usr/bin/env python3
"""把 README 里的「当前版本」和「下载」表同步成 GitHub 上最新的 Release。

为什么需要：这两个位置是写死的版本号和直链（`releases/download/vX.Y.Z/文件名`），
发版后不更新就会 404 或指向不存在的产物。手工维护必然漏——
已经漏过一次（README 写 tv-arm64/phone-*，实际 release 里是旧的 arm64 命名）。

做法：在 README 里留两对标记，脚本只替换标记之间的内容：

    <!-- AUTO:VERSION:START -->  ...  <!-- AUTO:VERSION:END -->
    <!-- AUTO:DOWNLOAD:START --> ...  <!-- AUTO:DOWNLOAD:END -->

用法：

    python3 tools/ops/update-downloads.py            # 改 README
    python3 tools/ops/update-downloads.py --check    # 只检查，有差异就退出 1

环境变量 `GITHUB_TOKEN` 可选：公开仓库不带也能跑，带了能提高限流额度。
"""
import json
import os
import re
import sys
import urllib.request

REPO = "ybd156/litv"
README = "README.md"

VER_START = "<!-- AUTO:VERSION:START -->"
VER_END = "<!-- AUTO:VERSION:END -->"
DL_START = "<!-- AUTO:DOWNLOAD:START"
DL_END = "<!-- AUTO:DOWNLOAD:END -->"


def api(url):
    req = urllib.request.Request(url, headers={
        "Accept": "application/vnd.github+json",
        "User-Agent": "litv-readme-updater",
    })
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def human_size(n):
    return "%.1f MB" % (n / 1048576.0)


def purpose(name):
    """按文件名推断用途。要同时兼容新旧两套命名。"""
    n = name.lower()
    if n == "sha256sums.txt":
        return "校验和"
    if n.endswith(".ipa"):
        return "iOS（需自签）"
    if n.endswith(".fpk"):
        if "x86" in n:
            return "飞牛 NAS（x86_64）"
        if "arm" in n:
            return "飞牛 NAS（ARM）"
        return "飞牛 NAS"
    if n.endswith(".apk"):
        # 新命名带 tv-/phone- 前缀，旧命名只有架构后缀
        if "tv-armv7" in n:
            return "**电视 / 盒子，32 位**（Android 9 及以下装这个）"
        if "tv-arm64" in n:
            return "电视 / 盒子，64 位"
        if "phone-armv7" in n:
            return "手机，32 位（2018 年前的老机型）"
        if "phone-arm64" in n:
            return "手机，64 位（绝大多数手机装这个）"
        if "armv7" in n:
            return "**电视 / 盒子，32 位**（Android 9 及以下装这个）"
        if "arm64" in n:
            return "64 位设备（新电视 / 手机）"
        if "x86" in n:
            return "模拟器（x86）"
        return "Android"
    return ""


# 排序：先安卓（电视优先），再 iOS，再 NAS，最后校验和
ORDER = ["tv-armv7", "tv-arm64", "armv7", "arm64", "phone-armv7",
         "phone-arm64", ".ipa", "x86", "arm_", "SHA256SUMS"]


def rank(name):
    n = name.lower()
    for i, key in enumerate(ORDER):
        if key.lower() in n:
            return i
    return len(ORDER)


def build_download_block(rel):
    tag = rel["tag_name"]
    version = tag.lstrip("v")
    url_base = "https://github.com/%s/releases/download/%s" % (REPO, tag)
    assets = sorted(rel.get("assets", []), key=lambda a: rank(a["name"]))

    lines = [
        "当前发布版 **[%s](https://github.com/%s/releases/tag/%s)**，点文件名直接下载："
        % (tag, REPO, tag),
        "",
        "| 产物 | 大小 | 用途 |",
        "|---|---|---|",
    ]
    for a in assets:
        name = a["name"]
        size = "—" if name.upper().startswith("SHA256") else human_size(a["size"])
        lines.append("| [`%s`](%s/%s) | %s | %s |"
                     % (name, url_base, name, size, purpose(name)))
    lines += [
        "",
        "**不知道装哪个**：在设备上跑 `adb shell getprop ro.product.cpu.abi`",
        "（或看系统信息），`armeabi-v7a` 装 armv7，`arm64-v8a` 装 arm64。",
        "",
        "旧版本在 [Releases 列表](https://github.com/%s/releases)。" % REPO,
    ]
    return "\n".join(lines), version


def replace_block(text, start_marker, end_marker, body):
    i = text.find(start_marker)
    j = text.find(end_marker)
    if i < 0 or j < 0:
        raise SystemExit("README 里找不到标记 %s / %s" % (start_marker, end_marker))
    if j < i:
        raise SystemExit("标记顺序反了：%s 出现在 %s 之前" % (end_marker, start_marker))
    # start_marker 可能带注释尾巴，保留它原样
    line_end = text.index("\n", i)
    keep = text[i:line_end]
    return text[:i] + keep + "\n" + body + "\n" + text[j:]


def main():
    check_only = "--check" in sys.argv

    old = open(README, encoding="utf-8").read()

    # README 的版本段与下载段已移除（2026-11-13）。没有标记块就没有要同步的东西，
    # 直接跳过——不能因为标记缺失就让发版工作流失败。
    has_dl = DL_START in old and DL_END in old
    has_ver = VER_START in old and VER_END in old
    if not has_dl and not has_ver:
        print("README 里没有 AUTO 标记块（版本段/下载段已移除），无需同步")
        return 0

    rel = api("https://api.github.com/repos/%s/releases/latest" % REPO)
    if not rel.get("assets"):
        raise SystemExit("最新 Release 没有任何产物，拒绝生成空表")

    new = old

    body, version = build_download_block(rel)
    new = replace_block(new, DL_START, DL_END, body)

    ver_line = ("当前版本：**%s** · [下载](https://github.com/%s/releases/latest)"
                % (version, REPO))
    new = replace_block(new, VER_START, VER_END, ver_line)

    if new == old:
        print("README 已是最新（%s），无需改动" % rel["tag_name"])
        return 0

    if check_only:
        print("README 与最新 Release（%s）不一致，需要更新：" % rel["tag_name"])
        for a in rel.get("assets", []):
            print("  ", a["name"])
        return 1

    open(README, "w", encoding="utf-8", newline="\n").write(new)
    print("README 已同步到 %s（%d 个产物）" % (rel["tag_name"], len(rel["assets"])))
    return 0


if __name__ == "__main__":
    sys.exit(main())
