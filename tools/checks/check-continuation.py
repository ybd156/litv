#!/usr/bin/env python3
"""
检查 shell 脚本里有没有「注释插进反斜杠续行命令」的问题。

这个坑在这个项目里踩了三次，每次都浪费一轮 10 分钟的 CI：

    PKG_CONFIG_PATH=xxx \\
    # 说明文字
    meson setup ...

反斜杠续行会把下一行拼上来，而那行是注释，于是：

    PKG_CONFIG_PATH=xxx # 说明文字 meson setup ...
                      ^ 到这里就结束了，meson 根本没带上环境变量

或者更直接：

    -Dlibmpv=true \\
    # 说明
    -Dc_args=xxx \\
        -> 报 "-Dc_args=xxx: command not found"

用法：
    check-continuation.py <file1> [file2 ...]

退出码 0 = 通过；1 = 有问题。
"""
import sys


def check(path):
    with open(path, encoding="utf-8") as f:
        lines = f.read().split("\n")

    bad = []
    for i, line in enumerate(lines):
        if i == 0:
            continue
        # 当前行是注释，且上一行以反斜杠结尾（续行）
        if line.lstrip().startswith("#") and lines[i - 1].rstrip().endswith("\\"):
            bad.append((i + 1, line.strip()[:60], lines[i - 1].strip()[:60]))

    return bad


def main():
    if len(sys.argv) < 2:
        print("用法: check-continuation.py <file> [file...]")
        return 2

    total = 0
    for path in sys.argv[1:]:
        try:
            bad = check(path)
        except OSError as e:
            print("读取失败 %s: %s" % (path, e))
            total += 1
            continue
        if bad:
            print("%s: 发现 %d 处注释插在续行命令里" % (path, len(bad)))
            for lineno, comment, prev in bad:
                print("  第 %d 行是注释: %s" % (lineno, comment))
                print("    但上一行以反斜杠续行: %s" % prev)
            total += len(bad)

    if total:
        print()
        print("结论：有 %d 处问题，注释必须放在整条命令之前" % total)
        return 1

    print("续行检查通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
