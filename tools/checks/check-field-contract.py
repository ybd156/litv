#!/usr/bin/env python3
"""跨端字段级契约检查（信封之外的「字段/单位/语义」那一层）。

为什么要有它：docs/design/01-API.md 第六节自己写着
「该检查器只比对路径与方法，**不校验 JSON 字段、不校验请求体形状**。
字段级契约目前没有自动校验，只能靠 Go handler 与 TS/Kotlin/Swift 类型人工对齐。」
而人工对齐已经漂了：iOS 的 PlayInfo 解不出 stream_id、HeartbeatResult 解不出
channels_version；Android 把「秒」塞进了名字叫 watch_ms 的字段。
路径对账能保证「不 404」，保证不了「字段解出来是对的」。

四类检查，全部是静态检查（不编译、不联网、不依赖第三方库）：

  R1 清单字段缺失      —— tools/checks/contract/client_fields.json 里 requiredIn
                          列到的每一端，必须能在**承载该 DTO 的那个文件**里找到该字段。
                          故意不扫 DecodeFixtures / mock：那里出现字段名不代表模型能解出来。
  R2 毫秒字段收秒值    —— `xxxMs = yyySec()`。这类错误不报错、不崩，只让指标错 1000 倍。
  R3 QoE path 用错维度 —— path 必须是封装（ts|hls|remux），不能是链路（direct|relay|timeshift）。
  R4 注释里的单位漂移  —— 只告警，不阻断。

用法：
    python tools/checks/check-field-contract.py
    python tools/checks/check-field-contract.py --verbose

有 ERROR 返回 1；只有 WARN 返回 0。

**已接进 CI**（`.github/workflows/build-check.yml` 的 preflight job）。

⚠️ **前提：本脚本是清单驱动的。** R1/R5 只检查 `client_fields.json` 里登记过的字段
—— 新增线上字段却忘了先登记，这两个检查会一起沉默（不是报错，是压根不看）。
所以加字段的流程是：**先改 `tools/checks/contract/client_fields.json`，再改实现**。
（这是有意的取舍：不登记就不检查，比"猜哪些字段该检查"更可预测。）
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

ROOT = Path(__file__).resolve().parents[2]
MANIFEST_REL = "tools/checks/contract/client_fields.json"

KOTLIN_DIR = ROOT / "android/app/src/main/java/com/litv/tv"
SWIFT_DIR = ROOT / "ios/LITVApp/Sources"
SKIP_DIRS = {"build", "dist", "generated", "intermediates", ".gradle", ".git", "node_modules"}

ERRORS: list[str] = []
WARNS: list[str] = []


def set_root(root: Path) -> None:
    """允许把检查指向另一棵树（worktree / 临时副本）。

    为什么需要：门禁脚本自己也要能被验证。只在主工作区跑，永远只能证明
    「它现在会红」；要证明「修完会绿」，得能指向一份改好的副本。
    """
    global ROOT, MANIFEST, KOTLIN_DIR, SWIFT_DIR
    ROOT = root.resolve()
    MANIFEST = ROOT / MANIFEST_REL
    KOTLIN_DIR = ROOT / "android/app/src/main/java/com/litv/tv"
    SWIFT_DIR = ROOT / "ios/LITVApp/Sources"


MANIFEST = ROOT / MANIFEST_REL


def err(rule: str, where: str, msg: str) -> None:
    ERRORS.append(f"[{rule}] {where}: {msg}")


def warn(rule: str, where: str, msg: str) -> None:
    WARNS.append(f"[{rule}] {where}: {msg}")


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


def strip_comments(text: str) -> str:
    """去掉 // 与 /* */ 注释，保留行号（把注释替换成空格）。

    为什么必须去：Models.swift 的文档注释里就写着
    `{"…","stream_id":…}` —— 那是**说明**，不是字段声明。
    不剥注释的检查器会把这行当成「模型已经支持 stream_id」，于是漏掉真问题。
    这正是本仓库反复出现的「测试绿但没测到东西」的同一类错误。
    """
    # 块注释：整段替换成等量换行，保持行号不变
    def _block(m: re.Match[str]) -> str:
        return "\n" * m.group(0).count("\n")

    text = re.sub(r"/\*.*?\*/", _block, text, flags=re.S)
    # 行注释：只去掉 // 之后的部分（不碰字符串里的 //，够用且不误伤 URL 常量）
    out_lines = []
    for line in text.split("\n"):
        idx = line.find("//")
        if idx >= 0:
            head = line[:idx]
            # 引号数为奇数说明 // 在字符串里，保留
            if head.count('"') % 2 == 0:
                line = head
        out_lines.append(line)
    return "\n".join(out_lines)


def rel(p: Path) -> str:
    try:
        return p.relative_to(ROOT).as_posix()
    except ValueError:
        return p.as_posix()


def line_of(text: str, pos: int) -> int:
    return text.count("\n", 0, pos) + 1


def walk_sources(root: Path, suffix: str) -> list[Path]:
    out: list[Path] = []
    if not root.exists():
        return out
    for p in root.rglob(f"*{suffix}"):
        if any(part in SKIP_DIRS for part in p.parts):
            continue
        out.append(p)
    return sorted(out)


# ---------------------------------------------------------------- R1 清单字段
# Kotlin/Gson 的两种合法映射：
#   显式：@SerializedName("stream_id") val streamId
#   隐式：属性名与线上名一致 —— val kind / var url
KOTLIN_EXPLICIT = '@SerializedName("{name}")'
KOTLIN_IMPLICIT = re.compile(r"\b(?:val|var)\s+{name}\s*:")


def kotlin_declares(text: str, name: str) -> bool:
    if KOTLIN_EXPLICIT.format(name=name) in text:
        return True
    # 请求体用字典字面量构造时（StatsPayload 的 body["watch_ms"] = ...）
    if f'["{name}"]' in text:
        return True
    return re.search(KOTLIN_IMPLICIT.pattern.format(name=re.escape(name)), text) is not None


def swift_declares(text: str, name: str) -> bool:
    """Swift/Codable 的两种合法映射，作用域限定在承载 DTO 的那个文件内。

    1) 显式键：case streamId = "stream_id"，或字典字面量 "watch_ms":
    2) 隐式键：CodingKeys 里 case kind, url, expires（逗号列表），或未自定义 CodingKeys
       时合成解码按属性名匹配 —— 这里只认「同名 case / 同名属性」，不做猜测。
    """
    if f'"{name}"' in text:
        return True

    # case a, b, c / case a = "x", b = "y"
    for m in re.finditer(r"\bcase\s+([A-Za-z0-9_,\s=\"\.]+?)(?=\n|\}|;)", text):
        body = m.group(1)
        for piece in body.split(","):
            piece = piece.strip()
            if not piece:
                continue
            ident = piece.split("=")[0].strip()
            if ident == name:
                return True
    # 没有 CodingKeys 时，合成解码按属性名匹配
    return re.search(rf"\b(?:var|let)\s+{re.escape(name)}\s*:", text) is not None


def check_manifest() -> None:
    if not MANIFEST.exists():
        err("R1", "tools/checks/contract/client_fields.json", "清单文件不存在")
        return
    try:
        doc = json.loads(read(MANIFEST))
    except json.JSONDecodeError as e:
        err("R1", "tools/checks/contract/client_fields.json", f"清单不是合法 JSON：{e}")
        return

    for ep in doc.get("endpoints", []):
        ep_id = ep.get("id", "?")
        go_rel = ep.get("goHandler", "")
        kt_rel = ep.get("kotlinFile", "")
        sw_rel = ep.get("swiftFile", "")

        go_path = ROOT / go_rel
        kt_path = ROOT / kt_rel
        sw_path = ROOT / sw_rel

        go_src = read(go_path) if go_path.is_file() else ""
        kt_src = strip_comments(read(kt_path)) if kt_path.is_file() else ""
        sw_src = strip_comments(read(sw_path)) if sw_path.is_file() else ""

        if not go_src:
            err("R1", ep_id, f"goHandler 文件不存在或为空：{go_rel}")
        if not kt_src:
            err("R1", ep_id, f"kotlinFile 不存在或为空：{kt_rel}")
        if not sw_src:
            err("R1", ep_id, f"swiftFile 不存在或为空：{sw_rel}")

        for f in ep.get("fields", []):
            name = f["name"]
            want = set(f.get("requiredIn", []))

            if "go" in want and go_src:
                # Go 侧两种写法都合法：struct tag 或 map 字面量键
                if f'json:"{name}"' not in go_src and f'"{name}":' not in go_src:
                    err("R1", f"{go_rel} ({ep_id})", f'服务端没有下发字段 "{name}"')

            if "kotlin" in want and kt_src:
                if not kotlin_declares(kt_src, name):
                    err(
                        "R1",
                        f"{kt_rel} ({ep_id})",
                        f'Kotlin DTO 解不出字段 "{name}"'
                        f"（既没有 @SerializedName，也没有同名属性）",
                    )

            if "swift" in want and sw_src:
                if not swift_declares(sw_src, name):
                    err(
                        "R1",
                        f"{sw_rel} ({ep_id})",
                        f'Swift 模型解不出字段 "{name}"'
                        f"（CodingKeys 与属性名都没有；服务端发了也白给）",
                    )
                else:
                    # 声明了不等于解出来了：手写 init(from:) 会覆盖合成解码器
                    check_swift_custom_init(ep_id, sw_rel, sw_src, name)


# ---------------------------------------------------------------- R2 毫秒/秒
# 只抓「整个实参就是那个秒值」的写法：
#   watchMs = watchedSec(),      ← 命中
#   watchMs = watchedSec() * 1000 ← 不命中（已经换算过，是对的）
MS_ASSIGN = re.compile(
    r"\b(\w*[Mm]s)\s*=\s*(\w*(?:Sec|Secs|Seconds)\w*)\s*\(\s*\)\s*(?=[,)\n])"
)


def check_ms_seconds() -> None:
    for p in walk_sources(KOTLIN_DIR, ".kt"):
        text = read(p)
        for m in MS_ASSIGN.finditer(text):
            err(
                "R2",
                f"{rel(p)}:{line_of(text, m.start())}",
                f"字段 `{m.group(1)}` 名字是毫秒，实参 `{m.group(2)}()` 是秒——"
                f"上报值小 1000 倍，rebuffer_ratio 的分母被放大 1000 倍",
            )


# ---------------------------------------------------------------- R3 path 维度
def check_qoe_path() -> None:
    for p in walk_sources(KOTLIN_DIR, ".kt"):
        text = read(p)
        for m in re.finditer(r"\bpath\s*=\s*(currentPlayMode|info\.mode)\b", text):
            err(
                "R3",
                f"{rel(p)}:{line_of(text, m.start())}",
                "QoE path 收到链路语义（direct/relay），应为封装（ts/hls/remux）",
            )

    for p in walk_sources(SWIFT_DIR, ".swift"):
        text = read(p)
        aliases = {"playMode"}
        for m in re.finditer(r"\blet\s+(\w+)\s*=\s*(?:self\.)?playMode\b", text):
            aliases.add(m.group(1))
        for alias in sorted(aliases):
            for m in re.finditer(rf"\bpath:\s*{re.escape(alias)}\b", text):
                err(
                    "R3",
                    f"{rel(p)}:{line_of(text, m.start())}",
                    f"QoE path 收到 `{alias}`（= playMode，链路语义），"
                    f"应为 info.kind（ts/hls/remux）",
                )


# ---------------------------------------------------------------- R4 单位注释
def check_unit_comments() -> None:
    for p in walk_sources(KOTLIN_DIR, ".kt"):
        text = read(p)
        for m in re.finditer(r"watchMs[^\n]{0,80}?（(秒|毫秒)）", text):
            if m.group(1) == "秒":
                warn(
                    "R4",
                    f"{rel(p)}:{line_of(text, m.start())}",
                    "watchMs 注释写「秒」，而服务端 handlers_client.go 按毫秒夹取"
                    "（maxDurationMs = 86_400_000）——注释与契约不一致",
                )


# ---------------------------------------------------------------- R5 手写解码器
def snake_to_camel(name: str) -> str:
    parts = name.split("_")
    return parts[0] + "".join(p[:1].upper() + p[1:] for p in parts[1:] if p)


def _brace_body(text: str, start: int) -> str:
    """从 start 处的第一个 '{' 开始，返回配平的 body（不含外层花括号）。"""
    i = text.find("{", start)
    if i < 0:
        return ""
    depth = 0
    for j in range(i, len(text)):
        ch = text[j]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return text[i + 1:j]
    return text[i + 1:]


def swift_type_bodies(text: str) -> dict[str, list[str]]:
    """收集 `struct X ... { }` 与 `extension X { }` 的 body，按类型名归组。"""
    out: dict[str, list[str]] = {}
    for m in re.finditer(
        r"\b(?:struct|extension)\s+([A-Za-z_]\w*)", text
    ):
        name = m.group(1)
        body = _brace_body(text, m.end())
        if body:
            out.setdefault(name, []).append(body)
    return out


def check_swift_custom_init(ep_id: str, sw_rel: str, sw_src: str, name: str) -> None:
    """手写了 `init(from:)` 的 DTO，字段必须在那里面被**真的解出来**。

    为什么需要这条：`PlayInfo` 有手写 `init(from:)`，合成的解码器根本不参与。
    只往 `CodingKeys` 加一行 `case streamId = "stream_id"`，
    字段会**永远静默保持默认值 0**——而所有"字段存在"的 grep 检查全部通过。
    这就是"假修复"：门禁绿了，线上仍然是 0。
    """
    camel = snake_to_camel(name)
    bodies = swift_type_bodies(sw_src)

    # 找出「用自己的 CodingKeys 声明了这个字段」的类型。
    #
    # 只认 `case` 行，不认 `var <camel>: ...` 属性声明 —— 后者会误伤**计算属性**：
    # `ChannelGroup` 有 `var id: String { name }`（`Identifiable` 要求），
    # 它不在 CodingKeys 里、也不该被解码，按属性名判定就会假阳性地报
    # "字段 id 没在 init(from:) 里解出"。
    # 门禁的假阳性比漏报更贵：报一次假警，下一个人就会把它关掉。
    owners = [
        t for t, bs in bodies.items()
        if any(re.search(rf"\bcase\b[^\n]*\b{re.escape(camel)}\b", b) for b in bs)
    ]
    for owner in owners:
        scoped = "\n".join(bodies[owner])
        if "init(from decoder" not in scoped:
            continue  # 用合成解码器，CodingKeys 就够
        decoded = (
            re.search(rf"litv\(\s*\.{re.escape(camel)}\b", scoped)
            or re.search(rf"forKey:\s*\.{re.escape(camel)}\b", scoped)
        )
        if not decoded:
            err(
                "R5",
                f"{sw_rel} ({ep_id})",
                f'类型 `{owner}` 有手写 init(from:)，但字段 "{name}" 没在里面解出——'
                f"（{camel}）会永远保持默认值，CodingKeys 写了也没用",
            )


def main() -> int:
    argv = sys.argv[1:]
    if "--root" in argv:
        i = argv.index("--root")
        if i + 1 >= len(argv):
            print("--root 需要一个目录参数", file=sys.stderr)
            return 2
        set_root(Path(argv[i + 1]))
    for a in argv:
        if a.startswith("--root="):
            set_root(Path(a.split("=", 1)[1]))

    check_manifest()
    check_ms_seconds()
    check_qoe_path()
    check_unit_comments()

    print("跨端字段契约检查")
    print(f"  根目录：{ROOT}")
    print(f"  清单：{rel(MANIFEST)}")
    print()

    if WARNS:
        print(f"警告 {len(WARNS)} 条（不阻断）：")
        for w in WARNS:
            print(f"  WARN {w}")
        print()

    if ERRORS:
        print(f"失败 {len(ERRORS)} 条：")
        for e in ERRORS:
            print(f"  FAIL {e}")
        print()
        print("这些不是风格问题：每一条都让「服务端发了 / 客户端解了 / 指标算了」之一出错。")
        return 1

    print("✅ 字段级契约一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
