import glob
import os
import re
import sys

# 仓库根 = 本文件往上三级（tools/checks/<this>.py）。
# 以前这里的 glob 用的是相对路径，于是**从别的目录运行就会一个文件都匹配不到**，
# 循环体一次都不执行，最后打印「不平衡文件: 无」并返回 0 ——
# 看起来是通过，实际什么都没检查。检查器必须自己先证明「我确实检查了东西」。
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# 控制台编码不是 UTF-8 时（Windows GBK），打印 ❌ 会让脚本以
# UnicodeEncodeError 崩掉 —— 结论是对的（非零退出），但人看不到原因。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding='utf-8', errors='replace')
    except (AttributeError, ValueError):
        pass


def strip_swift(src: str) -> str:
    out = []
    i = 0
    n = len(src)
    while i < n:
        ch = src[i]
        # 多行字符串 """ 或 #"""（Swift raw string）
        if src.startswith('#"""', i):
            j = src.find('"""#', i + 4)
            i = n if j < 0 else j + 4
            out.append('""')
            continue
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            i = n if j < 0 else j + 3
            out.append('""')
            continue
        if ch == '"':
            j = i + 1
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == '"':
                    j += 1
                    break
                j += 1
            i = j
            out.append('""')
            continue
        if src.startswith('//', i):
            j = src.find('\n', i)
            i = n if j < 0 else j
            continue
        if src.startswith('/*', i):
            j = src.find('*/', i + 2)
            i = n if j < 0 else j + 2
            continue
        out.append(ch)
        i += 1
    return ''.join(out)


bad = []
FILES = sorted(glob.glob(os.path.join(ROOT, 'ios/LITVApp/Sources/*.swift')))
if not FILES:
    print(f"❌ 一个 Swift 文件都没找到（{os.path.join(ROOT, 'ios/LITVApp/Sources')}）")
    print("   零个文件 = 零个结论。不要把这种情况当成通过。")
    sys.exit(1)

for p in FILES:
    src = open(p, encoding='utf-8').read()
    t = strip_swift(src)
    pairs = {
        '{}': (t.count('{'), t.count('}')),
        '()': (t.count('('), t.count(')')),
        '[]': (t.count('['), t.count(']')),
    }
    ok = all(a == b for a, b in pairs.values())
    if not ok:
        bad.append((os.path.basename(p), pairs))
    print(f"{os.path.basename(p):24} {pairs} {'OK' if ok else 'MISMATCH'}")

print()
print("不平衡文件:", bad if bad else "无")

# 发现问题必须非零退出。以前这里到此为止，永远是 0 ——
# 那样的检查脚本挂进 CI 只是个装饰品。
if bad:
    sys.exit(1)
sys.exit(0)
