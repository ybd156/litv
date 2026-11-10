#!/usr/bin/env python3
"""版本号一致性检查：AGENTS.md §11 要求发版前同步的几处，逐个读出来对账。

为什么要有这个：
    第一轮体检确认了一个真缺陷——release 工作流不是从推送的 tag 取版本号，
    而是从 `DEFAULT_VERSION` 这个字符串常量推导。于是「五处同步」里漏掉任何一处，
    结果不是发版失败，而是**静默发成上一个版本号**，还会把上一版的 Release 删掉。
    §11 把这张表写进了文档，但从来没有任何机器检查它。

这个脚本只做一件事：把值读出来、对齐、不一致就非零退出。
它不猜、不修、不写文件。

用法：
    python3 tools/checks/check-version-sync.py            # 只查五处是否互相一致
    python3 tools/checks/check-version-sync.py --expect 0.1.14
                                                          # 额外要求五处都等于 0.1.14

退出码：0 = 一致；1 = 不一致或读不到；2 = 用法错误。
只用标准库，不引入依赖；路径全部从本文件位置推导，不含任何绝对路径。
"""

import argparse
import re
import sys
from pathlib import Path

# 仓库根 = 本文件往上三级（tools/checks/<this>.py）
ROOT = Path(__file__).resolve().parents[2]

# 窗口期控制台编码可能不是 UTF-8，打印中文/符号会抛 UnicodeEncodeError，
# 让「全部一致」变成非零退出。显式切 UTF-8，让退出码只反映字面量是否一致。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass


class Spec:
    """一处版本号来源：怎么找文件、怎么从里面抠出值。"""

    def __init__(self, key, relpath, pattern, note, allow_many=False):
        self.key = key
        self.relpath = relpath
        self.pattern = pattern
        self.note = note
        # allow_many=True：文件里出现多个匹配是正常的（CHANGELOG 每一版都有标题），
        # 取第一处且不提示。否则每次运行都打一行「注意」，久了就没人看警告了。
        self.allow_many = allow_many

    def read(self):
        """返回 (value, error)。读不到就是 error，绝不用空字符串冒充。"""
        path = ROOT / self.relpath
        if not path.is_file():
            return None, f"文件不存在：{self.relpath}"
        try:
            text = path.read_text(encoding="utf-8")
        except OSError as exc:
            return None, f"读不了 {self.relpath}：{exc}"
        found = [m.group(1) for m in re.finditer(self.pattern, text, re.MULTILINE)]
        if not found:
            return None, f"{self.relpath} 里匹配不到版本号（{self.note}）"
        if len(found) > 1 and not self.allow_many:
            # 不是错误，但要让 review 的人看见：文件里有多个候选，我们只取第一个。
            return found[0].strip(), f"注意：{self.relpath} 里有 {len(found)} 处匹配，取第一处"
        return found[0].strip(), None


SPECS = [
    # fnOS 包清单：安装器读它显示「已安装版本」。
    Spec(
        "fnOS 包清单",
        "packaging/fnos/manifest",
        r"^version\s*=\s*(\S+)\s*$",
        "顶格的 version = x.y.z",
    ),
    # Android：versionName 是「关于」里显示的；versionCode 是递增计数器，不参与比对。
    Spec(
        "Android versionName",
        "android/app/build.gradle.kts",
        r'^\s*versionName\s*=\s*"([^"]+)"',
        'anchored 的 versionName = "x.y.z"（不是 versionNameSuffix）',
    ),
    # iOS：CFBundleShortVersionString 是「关于」里显示的；CFBundleVersion 同样是计数器。
    Spec(
        "iOS CFBundleShortVersionString",
        "ios/project.yml",
        r'CFBundleShortVersionString:\s*"?([0-9][^"\s]*)"?',
        "xcodegen 的 Info.plist 属性",
    ),
    # release 工作流：版本号的兜底来源（tag 推送时用 tag，dispatch 留空时才用它）。
    Spec(
        "release.yml DEFAULT_VERSION",
        ".github/workflows/release.yml",
        r'^\s*DEFAULT_VERSION:\s*"?([^"\s]+)"?\s*$',
        "env.DEFAULT_VERSION",
    ),
    # CHANGELOG：用户看到的「这版改了什么」。
    Spec(
        "CHANGELOG 顶部版本",
        "CHANGELOG.md",
        r"^##\s*\[(\d+\.\d+\.\d+)\]",
        "第一个 ## [x.y.z] 标题",
        allow_many=True,
    ),
]

# 递增计数器：不改就会被应用商店/系统当成「同版本」，但它们的值本来就和
# 版本号不同（一个是 23、一个是 12），所以只打印出来给 review 的人看一眼
# 「这版有没有跟着 +1」，**不参与**一致性判定。
COUNTER_SPECS = [
    Spec(
        "Android versionCode",
        "android/app/build.gradle.kts",
        r"^\s*versionCode\s*=\s*(\d+)",
        "递增整数，发版要 +1",
    ),
    Spec(
        "iOS CFBundleVersion",
        "ios/project.yml",
        r'CFBundleVersion:\s*"?(\d+)"?',
        "递增整数，发版要 +1",
    ),
]


def normalize(value):
    """容忍前导 v（tag 形式 v0.1.13）与首尾空白。"""
    return value.strip().lstrip("vV")


def main():
    parser = argparse.ArgumentParser(description="检查 AGENTS.md §11 的版本号五处是否一致")
    parser.add_argument(
        "--expect",
        metavar="X.Y.Z",
        default=None,
        help="额外断言：五处都必须等于这个版本（release 工作流用推送的 tag 传进来）",
    )
    args = parser.parse_args()

    expect = normalize(args.expect) if args.expect else None
    if expect is not None and not re.fullmatch(r"\d+\.\d+\.\d+", expect):
        print(f"用法错误：--expect 需要 x.y.z 形式，收到 {args.expect!r}", file=sys.stderr)
        return 2

    rows = []
    problems = []

    for spec in SPECS:
        value, err = spec.read()
        if value is None:
            rows.append((spec.key, spec.relpath, "（读不到）", "FAIL", err or ""))
            problems.append(f"{spec.key}：{err}")
            continue
        norm = normalize(value)
        notes = []
        if err:
            notes.append(err)
        if expect is not None and norm != expect:
            notes.append(f"期望 {expect}，实际 {norm}")
            problems.append(f"{spec.key}：期望 {expect}，实际 {norm}")
        rows.append((spec.key, spec.relpath, norm, "ok", "；".join(notes)))

    # 五处之间互相一致（不看 expect 也要成立）
    values = {r[2] for r in rows if r[3] == "ok"}
    if len(values) > 1:
        detail = "、".join(sorted(values))
        problems.append(f"五处版本号彼此不一致：{detail}")

    width_key = max(len(r[0]) for r in rows)
    width_path = max(len(r[1]) for r in rows)
    print()
    print(f"{'位置'.ljust(width_key)}  {'文件'.ljust(width_path)}  {'版本':10} 状态")
    print("-" * (width_key + width_path + 24))
    for key, relpath, value, status, note in rows:
        line = f"{key.ljust(width_key)}  {relpath.ljust(width_path)}  {value:10} {status}"
        if note:
            line += f"  ← {note}"
        print(line)
    print()

    # 递增计数器：只打印，不影响退出码。
    # 留在这里是因为「关于里版本不对」有一半是计数器没 +1 引起的，
    # 让 review 的人一眼能看见，比事后猜省事。
    print("递增计数器（不参与一致性判定，发版时应 +1）：")
    for spec in COUNTER_SPECS:
        value, err = spec.read()
        shown = value if value is not None else "（读不到）"
        extra = f"  ← {err}" if err else ""
        print(f"   {spec.key.ljust(width_key)}  {spec.relpath.ljust(width_path)}  {shown}{extra}")
    print()

    if problems:
        print("❌ 版本号不一致：")
        for p in problems:
            print(f"   - {p}")
        print()
        print("AGENTS.md §11：发版前这几处必须一起改，缺一个就会出现「关于里版本不对」。")
        return 1

    if expect is not None:
        print(f"✅ 五处版本号一致，且都等于 {expect}")
    else:
        print(f"✅ 五处版本号一致（{normalize(rows[0][2])}）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
