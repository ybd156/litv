import re

src = open('ios/LITVApp/Sources/Models.swift', encoding='utf-8').read()

# 按顶层 struct 切块（只处理顶层，嵌套的 PlayChannel 单独算）
blocks = re.split(r'\n(?=(?:struct|enum|extension) )', src)

# 收集 struct 名与其属性
structs = {}
for blk in blocks:
    m = re.match(r'(struct|enum|extension) (\w+)', blk)
    if not m:
        continue
    kind, name = m.group(1), m.group(2)
    if kind != 'struct':
        continue
    body = blk
    # 到第一个顶层 } 前的属性声明：var/let name: Type
    props = re.findall(r'\n\s+(?:var|let)\s+(\w+)\s*:', body)
    structs[name] = props

# 收集 extension 里的 init(from:) 覆盖字段
covered = {}
for blk in blocks:
    m = re.match(r'extension (\w+)', blk)
    if not m:
        continue
    name = m.group(1)
    if 'init(from decoder' not in blk:
        continue
    fields = set(re.findall(r'\b(\w+)\s*=\s*(?:try|c\.litv|c\.decode)', blk))
    fields |= set(re.findall(r'\bc\.litv\(\.(\w+)', blk))
    fields |= set(re.findall(r'forKey:\s*\.(\w+)', blk))
    covered.setdefault(name, set()).update(fields)

print(f"{'struct':22} {'props':6} {'covered':8} 结论")
print('-' * 60)
problems = []
for name, props in structs.items():
    if not props:
        continue
    cov = covered.get(name)
    if cov is None:
        print(f"{name:22} {len(props):<6} {'(无 init)':8} 用合成解码")
        continue
    missing = [p for p in props if p not in cov]
    status = 'OK' if not missing else f'缺 {missing}'
    print(f"{name:22} {len(props):<6} {len(cov):<8} {status}")
    if missing:
        problems.append((name, missing))

print()
print("需要修的问题:", problems if problems else "无")
