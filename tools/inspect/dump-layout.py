"""解 APK 里的二进制布局，输出可读控件树。

aapt2 的 xmltree 输出靠**缩进**表示层级（E: 行前导空格数），
所以直接按缩进算深度，比跟踪开闭标签可靠。
"""
import os
import re
import subprocess
import sys

BT = os.path.expanduser("~/android-dev/sdk/build-tools/35.0.0")
AAPT2 = os.path.join(BT, "aapt2")

WANT = re.compile(r":(textSize|orientation|gravity|layout_weight|layout_width|layout_height|text|src|background|focusable|visibility)\(")


def build_map(apk):
    out = subprocess.run([AAPT2, "dump", "resources", apk],
                         capture_output=True, text=True, timeout=300).stdout
    mapping = {}
    cur = None
    for line in out.split("\n"):
        m = re.match(r"\s+resource 0x[0-9a-f]+ layout/(\S+)", line)
        if m:
            cur = m.group(1)
            continue
        m = re.search(r"\(file\) (\S+) type=XML", line)
        if m and cur:
            mapping[cur] = m.group(1)
            cur = None
    return mapping


def dump_tree(apk, path, max_nodes=90):
    out = subprocess.run(
        [AAPT2, "dump", "xmltree", "--file", path, apk],
        capture_output=True, text=True, timeout=120,
    ).stdout

    rows = []
    n = 0
    for raw in out.split("\n"):
        if not raw.strip():
            continue
        indent = len(raw) - len(raw.lstrip())
        s = raw.strip()

        m = re.match(r"E: (\S+)", s)
        if m:
            depth = max(0, (indent - 4) // 2)
            tag = m.group(1).split("(")[0].strip().split(".")[-1]
            rows.append([depth, tag, []])
            n += 1
            if n > max_nodes:
                rows.append([depth, "...(截断)", []])
                break
            continue

        m = re.match(r"A: .*?:(\w+)\(0x[0-9a-f]+\)=(.*)", s)
        if m and rows:
            key, val = m.group(1), m.group(2).strip()
            if key in ("textSize", "orientation", "gravity", "layout_weight",
                       "layout_width", "layout_height", "text", "focusable", "visibility"):
                if key == "orientation":
                    val = {"0": "H", "1": "V"}.get(val, val)
                elif key in ("layout_width", "layout_height"):
                    val = {"-1": "match", "-2": "wrap", "0.000000dp": "0dp"}.get(val, val)
                rows[-1][2].append(f"{key}={val}")

    lines = []
    for depth, tag, attrs in rows:
        a = ("  [" + " ".join(attrs[:5]) + "]") if attrs else ""
        lines.append("  " * depth + tag + a)
    return "\n".join(lines)


if __name__ == "__main__":
    apk = sys.argv[1]
    m = build_map(apk)
    print(f"{os.path.basename(apk)}: {len(m)} 个布局")
    for name in sys.argv[2:]:
        path = m.get(name)
        print("=" * 66)
        print(f"### {name}  ->  {path}")
        print("=" * 66)
        if not path:
            print("  (没找到)")
            continue
        print(dump_tree(apk, path))
        print()
