import glob
import os
import re


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
for p in sorted(glob.glob('ios/LITVApp/Sources/*.swift')):
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
