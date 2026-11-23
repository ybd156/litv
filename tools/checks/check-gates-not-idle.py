#!/usr/bin/env python3
"""门禁空转体检：每条门禁都必须在「空树」里变红，否则它只是装饰。

为什么需要它
------------
本仓库已经出过两次「门禁看起来在跑，其实什么都没查」的事故：
  · `check-playback-security.py` 早就接在 preflight 里，但**一直 exit 1**，
    因为没人看，绿灯根本不存在；
  · `selftest.mjs` 的 `check(name, ok)` 少一个参数，几十处「实际值」被静默丢弃，
    失败日志只有一行 ❌ 名字，看不出哪里错。

人工体检的做法是「把脚本复制到空目录里跑一遍，必须红」。这个方法有效但靠自觉，
本脚本把它变成机械检查：空树里退出码 0 = 空转绿灯 = 本脚本失败。

怎么判「空树」
--------------
所有门禁都用 `__file__` 往上推 ROOT，所以只要把**脚本本体**复制到
`<tmp>/tools/checks/` 再运行，ROOT 就是那个空的临时目录——扫描目标全都不存在。
带参数的两条（continuation / av-abi）没法用空树判，单独给「坏输入」用例。
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile

for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

HERE = os.path.dirname(os.path.abspath(__file__))

# 只判「按路径扫描仓库」的门禁；它们必须在空树里变红。
EMPTY_TREE_CASES = [
    "check-api-contract.py",
    "check-versions.py",
    "check-field-contract.py",
    "check-error-codes.py",
    "check-playback-security.py",
    "check-swift-init-coverage.py",
    "check-swift-balance.py",
]

TIMEOUT_S = 180


def run(args, cwd):
    """返回 (退出码, 合并输出尾部)。超时按「没变红」处理。"""
    try:
        p = subprocess.run(
            args,
            cwd=cwd,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=TIMEOUT_S,
        )
    except subprocess.TimeoutExpired:
        return None, f"超时 >{TIMEOUT_S}s"
    out = (p.stdout or "") + (p.stderr or "")
    tail = "\n".join(line for line in out.strip().splitlines()[-3:])
    return p.returncode, tail


def main():
    fails = []
    tmp_root = tempfile.mkdtemp(prefix="gate-idle-")
    try:
        checks_dir = os.path.join(tmp_root, "tools", "checks")
        os.makedirs(checks_dir, exist_ok=True)

        # ---- 用例 1：按路径扫描的门禁，空树里必须非零退出 ----
        for name in EMPTY_TREE_CASES:
            src = os.path.join(HERE, name)
            if not os.path.exists(src):
                fails.append((name, "脚本不存在（清单过期？）"))
                continue
            dst = os.path.join(checks_dir, name)
            shutil.copy2(src, dst)
            code, tail = run([sys.executable, dst], cwd=tmp_root)
            if code == 0:
                fails.append((name, f"空树里退出码 0 —— 空转绿灯。输出：{tail}"))
            elif code is None:
                fails.append((name, f"空树里跑不出结论：{tail}"))
            else:
                print(f"[ok]   {name:<32} 空树退出码 {code}")
        print()

        # ---- 用例 2：续行检查必须抓得住「注释插在续行里」 ----
        name = "check-continuation.py"
        src = os.path.join(HERE, name)
        if os.path.exists(src):
            bad = os.path.join(tmp_root, "bad.sh")
            with open(bad, "w", encoding="utf-8", newline="\n") as fh:
                fh.write("curl -s \"$URL\" \\\n")
                fh.write("  # 注释插在续行里\n")
                fh.write("  -H 'X: 1'\n")
            good = os.path.join(tmp_root, "good.sh")
            with open(good, "w", encoding="utf-8", newline="\n") as fh:
                fh.write("curl -s \"$URL\" -H 'X: 1'\n")
            code_bad, tail_bad = run([sys.executable, src, bad], cwd=tmp_root)
            code_good, tail_good = run([sys.executable, src, good], cwd=tmp_root)
            if code_bad != 1:
                fails.append((name, f"坏输入应退出 1，实际 {code_bad}：{tail_bad}"))
            elif code_good != 0:
                fails.append((name, f"好输入应退出 0，实际 {code_good}：{tail_good}"))
            else:
                print(f"[ok]   {name:<32} 坏输入红 / 好输入绿")
        else:
            fails.append((name, "脚本不存在"))

        # av-abi 需要「新库目录 + 旧库目录」两个实参，且不在 preflight 里（手动工具），
        # 不纳入体检。这里只提示，避免以后有人以为它被漏掉了。
        print("[skip] check-av-abi.py               需要两个库目录实参，手动工具，不在本题范围内")
    finally:
        shutil.rmtree(tmp_root, ignore_errors=True)

    print()
    if fails:
        print("❌ 有门禁在空转（空树里也绿）：")
        for name, why in fails:
            print(f"   {name}: {why}")
        print()
        print("修法：让它在找不到扫描目标时**退出非零**并打出人话，不要 continue 过去。")
        sys.exit(1)
    print(f"✅ {len(EMPTY_TREE_CASES) + 1} 条门禁都证明了会红")
    sys.exit(0)


if __name__ == "__main__":
    main()
