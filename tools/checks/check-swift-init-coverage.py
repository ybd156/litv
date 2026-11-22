import argparse
import os
import re
import sys

# 仓库根 = 本文件往上三级（tools/checks/<this>.py）。
# 以前这里是相对 CWD 的裸路径：从别的目录跑会直接抛 FileNotFoundError，
# 而「崩掉」和「有问题」在 CI 日志里都不像「通过」，但都不该靠运气。
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# --root：指到仓库副本上跑，用来做「回退修复后必须变红」的反证，
# 不必为了验证门禁有效去改真仓库的文件。与 check-playback-security.py 同名同义。
_ap = argparse.ArgumentParser(description='检查 iOS Models.swift 的自定义 init 是否覆盖了所有存储属性')
_ap.add_argument('--root', default=None, help='仓库根目录（默认按脚本位置推导）')
_args = _ap.parse_args()
if _args.root:
    ROOT = os.path.abspath(_args.root)

# 同上：非 UTF-8 控制台下打印中文/符号会崩，把有用的报错变成噪音。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding='utf-8', errors='replace')
    except (AttributeError, ValueError):
        pass

MODELS = os.path.join(ROOT, 'ios/LITVApp/Sources/Models.swift')
if not os.path.isfile(MODELS):
    print(f"❌ 找不到 {MODELS}")
    sys.exit(1)
src = open(MODELS, encoding='utf-8').read()

# 按顶层 struct 切块（只处理顶层，嵌套的 PlayChannel 单独算）
blocks = re.split(r'\n(?=(?:struct|enum|extension) )', src)

# 属性声明。`: T` 之前允许修饰符（private(set) / static / lazy …），
# 否则 `private(set) var token: String` 会被整条漏掉——漏掉=永远不报缺，等于门禁有个洞。
PROP_RE = re.compile(
    r'\n[ \t]+(?:(?:private|fileprivate|internal|public|open|static|class|lazy|weak|'
    r'unowned|nonisolated|override|final|mutating)(?:\([^)]*\))?[ \t]+)*'
    r'(?:var|let)[ \t]+(\w+)[ \t]*:'
)

# 一个属性声明的「类型部分」到哪里为止：碰到下一个成员声明/注释就截断，
# 免得把下一个 func 的函数体 `{` 误判成本属性的 getter。
DECL_STOP_RE = re.compile(
    r'\n[ \t]*(?:var|let|func|init|struct|enum|extension|case|typealias|subscript|//|/\*)'
)


def _brace_end(text, start):
    """从 start 之后第一个 `{` 起，返回配对 `}` 的下一位置；没有 `{` 则原样返回。

    用来跳过计算属性体：`var timeText: String { let f = … }` 里的局部
    `let f` 不是这个 struct 的属性，混进来就会造出假的「缺字段」。
    """
    i = text.find('{', start)
    if i < 0:
        return start
    depth = 0
    while i < len(text):
        ch = text[i]
        if ch == '{':
            depth += 1
        elif ch == '}':
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return len(text)


def _is_computed(tail):
    """按 Swift 语法区分存储属性与计算属性。

    存储：`var x: T` / `var x: T = 默认值` / `var x: T { didSet {…} }`
    计算：`var x: T { … }`（含一行式 `var id: String { name }`）

    判据是类型部分里 `=` 与 `{` 谁先出现：
      - 先 `=` → 存储（闭包默认值 `= { … }` 也落在这一类）
      - 先 `{` → 计算属性，不需要被 init 覆盖
    属性观察器（didSet/willSet）是唯一例外：花括号体挂在**存储**属性上。
    """
    stop = DECL_STOP_RE.search(tail)
    head = tail[:stop.start()] if stop else tail
    i_eq, i_brace = head.find('='), head.find('{')
    if i_brace < 0:
        return False
    if 0 <= i_eq < i_brace:
        return False
    body = head[i_brace:i_brace + 40]
    if 'didSet' in body or 'willSet' in body:
        return False
    return True


# 收集 struct 名与其**存储属性**（计算属性本来就不该在 init 里赋值，
# 早先版本把它们也算进去，导致 ChannelGroup.id / EpgProgram.isNow · timeText /
# TvStream.healthText 三条假红，脚本因此一直 exit 1、也没法接进 preflight）
structs = {}
computed = {}
for blk in blocks:
    m = re.match(r'(struct|enum|extension) (\w+)', blk)
    if not m:
        continue
    kind, name = m.group(1), m.group(2)
    if kind != 'struct':
        continue
    body = blk
    props, comp = [], []
    pos = 0
    while True:
        pm = PROP_RE.search(body, pos)
        if not pm:
            break
        prop = pm.group(1)
        tail = body[pm.end():pm.end() + 400]
        if _is_computed(tail):
            comp.append(prop)
            pos = _brace_end(body, pm.end())
        else:
            props.append(prop)
            pos = pm.end()
    structs[name] = props
    if comp:
        computed[name] = comp

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

# 和 check-swift-balance.py 同样的道理：发现缺字段必须非零退出，
# 否则这个脚本永远不会拦住任何东西。
if problems:
    sys.exit(1)
sys.exit(0)
