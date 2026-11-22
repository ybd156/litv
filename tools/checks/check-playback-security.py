#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""播放层安全不变量门禁（只读，不联网，无第三方依赖）。

## 为什么需要这个脚本

播放层有三类问题**单元测试抓不到**，因为它们不是"算错了"，而是"某一行写法"：

1. **日志泄漏**：签名流地址的 query 就是凭据（`?exp=…&sig=…`），
   direct 模式下更是用户私有源站的原始地址。已经出过两次：
   `url.take(60)` 正好吃到 `/live/12/stream.ts?sig=`；`-vvv` 让 libVLC
   自己把完整 input MRL 打进 logcat。电视盒子普遍能 adb。
2. **TLS 校验被关**：`X509TrustManager` / `HostnameVerifier` / `--tls-verify=no`
   这类东西一旦出现在播放器选项里，就是"为了能播"而拆掉唯一的安全边界。
   AGENTS.md §6 明令禁止，且历史上有人真的会这么"修"。
3. **原生 header 注入**：libVLC / mpv 的请求头是拼进原生请求的，
   服务端清洗过的 header 在客户端再拼一次，注入面在 native 层。

`tools/checks/` 里的其它脚本都是这个思路（`check-api-contract.py` 就是
因为长期没人跑而坏掉且无人知晓）。所以必须接进 CI 的 preflight job。

## 设计约束

- **零假阳性**：命中即真问题。宁可漏，不可让 main 因为误报变红——
  一次误报就会让人把这个门禁当噪音关掉。
- 所有命中都给出 `文件:行号: 原文`，能直接跳过去改。
- 二进制文件（`jniLibs/*.so`）跳过：里面出现 `X509TrustManager` 之类的
  字符串是 libmpv/libVLC 自带的 OpenSSL，不是我们的代码。

用法：
    python3 tools/checks/check-playback-security.py [--root DIR]

退出码：0 = 通过；1 = 有违规，**或扫描范围本身有问题**（目录不存在 / 一个文件都没扫到）。
"""

from __future__ import annotations

import argparse
import os
import re
import sys

# ---------------------------------------------------------------- 扫描范围

# 只扫播放层与网络凭据相关的源码目录。整仓扫会把文档、测试、门禁脚本
# 自己都扫进去（它们必然提到这些关键词），那是误报的主要来源。
SCAN_DIRS = [
    "android/app/src/main/java/com/litv/tv/player",
    "android/app/src/main/java/com/litv/tv/data",
    "android/app/src/main/java/com/litv/tv/ui",
    "ios/LITVApp/Sources",
]

TEXT_EXT = {".kt", ".java", ".swift"}
SKIP_DIR_PARTS = {"build", ".gradle", "node_modules", "generated", "intermediates"}


def walk(root: str):
    """返回 (命中的源文件列表, 不存在的扫描目录列表)。

    **不存在的目录必须回报**，不能静默跳过：如果只是 `continue`，
    那么哪天目录被改名/搬家（这个仓库真的发生过两次大拆分），
    这个门禁就会"扫了 0 个文件"然后一路绿灯——比没有门禁更糟。
    """
    files = []
    missing = []
    for rel in SCAN_DIRS:
        base = os.path.join(root, rel)
        if not os.path.isdir(base):
            missing.append(rel)
            continue
        for dirpath, dirnames, filenames in os.walk(base):
            dirnames[:] = [d for d in dirnames if d not in SKIP_DIR_PARTS]
            for name in filenames:
                if os.path.splitext(name)[1] not in TEXT_EXT:
                    continue
                files.append(os.path.join(dirpath, name))
    return files, missing


# ---------------------------------------------------------------- 规则
#
# 每条规则是 (id, 说明, 判据, 建议)。判据是"命中即违规"的正则；
# 需要排除注释行的规则自己处理（见 LOG_URL_TAKE 的 note 字段）。

RULES = [
    (
        "S1",
        "日志里出现原始 url（签名参数 / 私有源站地址会进 logcat）",
        # 只匹配**真正把 url 拼进日志**的写法；`Redact.url(url)` 不匹配，
        # 因为它拿走的是 Redact 的返回值，不是裸 url。
        re.compile(r'\+\s*(?:url|fullUrl|playUrl|rawUrl)\s*\.\s*take\s*\('),
        "改用 Redact.url(url)：只保留 scheme+host+port 与「有没有 query」。",
    ),
    (
        "S2",
        "日志里用字符串拼接打了 url（未脱敏）",
        re.compile(r'Log\.[vdiew]\(\s*[^,]+,\s*"[^"]*"\s*\+\s*(?:url|fullUrl|playUrl|rawUrl)\b'),
        "改用 Redact.url(url)，或只打 scheme+host。",
    ),
    (
        "S3",
        "libVLC 详细日志没有 DEBUG 门禁（release 会把完整 MRL 打进 logcat）",
        # 只要该行没有 BuildConfig.DEBUG，就是无条件开启
        re.compile(r'^\s*add\(\s*"-vv+' r'"\s*\)\s*$'),
        '改成 if (BuildConfig.DEBUG) add("-vvv")。',
    ),
    (
        "S4",
        "关闭 TLS 证书 / 主机名校验",
        re.compile(
            r"X509TrustManager|HostnameVerifier|checkServerTrusted|allowAllHostname"
            r"|TrustAllCerts|no-ssl-cert-verify|tls-verify\s*=\s*no|ssl-cert-verify\s*=\s*0"
        ),
        "绝不关闭证书与主机名校验（AGENTS §6）。源站证书有问题就让用户换线路。",
    ),
    (
        "S5",
        "把 Authorization / Cookie 传给原生播放器（服务端已清洗，客户端不该再拼）",
        # 只抓"往播放器 header 里塞 Authorization"的写法
        re.compile(r'headers\s*\[\s*"(?i:authorization|cookie)"\s*\]\s*='),
        "播放器请求头不得带 Authorization / Cookie；需要鉴权走签名地址。",
    ),
    (
        "S6",
        "日志用了 Kotlin/Swift 字符串模板打 url（S1/S2 只抓 '+' 拼接，会漏这种写法）",
        # `Log.d(TAG, "prepare $url")` / `"…${url}…"` —— 拼接型规则抓不到
        re.compile(r'Log\.[vdiew]\([^)\n]*\$\{?(?:url|fullUrl|playUrl|rawUrl)\b'),
        "改用 Redact.url(url)。模板插值同样会把签名参数原样打进 logcat。",
    ),
]

# 这些行即使是违规形态也放行：注释、文档字符串、门禁脚本自身的说明。
def is_comment(line: str) -> bool:
    s = line.lstrip()
    return s.startswith("//") or s.startswith("*") or s.startswith("/*") or s.startswith("#")


def main() -> int:
    # Windows 控制台默认 GBK，打印 ✅ / 中文标点会 UnicodeEncodeError。
    # 门禁脚本崩掉等于门禁消失，所以这里显式改成 UTF-8（管道里也一样）。
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[attr-defined]
        except (AttributeError, ValueError):
            pass

    ap = argparse.ArgumentParser(description="播放层安全不变量门禁")
    ap.add_argument("--root", default=None, help="仓库根目录（默认按脚本位置推导）")
    args = ap.parse_args()

    root = args.root or os.path.abspath(
        os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
    )

    hits: list[tuple[str, str, str, int, str, str]] = []
    scanned = 0
    files, missing = walk(root)
    for path in files:
        scanned += 1
        rel = os.path.relpath(path, root).replace("\\", "/")
        try:
            with open(path, encoding="utf-8", errors="replace") as f:
                lines = f.read().split("\n")
        except OSError as e:  # 读不了就明确报出来，不要静默跳过
            print(f"  跳过（读取失败）：{rel}: {e}", file=sys.stderr)
            continue
        for no, line in enumerate(lines, start=1):
            if is_comment(line):
                continue
            for rid, desc, pat, advice in RULES:
                if pat.search(line):
                    hits.append((rid, desc, rel, no, line.strip(), advice))

    print("播放层安全不变量检查")
    print(f"  根目录：{root}")
    print(f"  扫描：{scanned} 个源文件（{'、'.join(SCAN_DIRS)}）")
    print()

    # 「零个文件 = 零个结论」：这两条必须在判 hits 之前，否则空树也会绿灯。
    if missing:
        print("❌ 扫描目录不存在，这个门禁等于没跑：")
        for rel in missing:
            print(f"     - {rel}")
        print("   如果目录是改名/搬家了，请同步更新本脚本的 SCAN_DIRS；")
        print("   不要为了让 CI 变绿而把这里改成 continue（那就成了空转绿灯）。")
        return 1
    if scanned == 0:
        print("❌ 一个源文件都没扫到：判据没有任何输入，不能当作通过。")
        return 1

    if not hits:
        print("✅ 未发现违规（S1-S6 全部通过）")
        return 0

    print(f"违规 {len(hits)} 条：")
    for rid, desc, rel, no, text, advice in hits:
        print(f"  FAIL [{rid}] {rel}:{no}")
        print(f"        {desc}")
        print(f"        原文：{text[:160]}")
        print(f"        修法：{advice}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
