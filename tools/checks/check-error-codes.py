"""对账：服务端会返回的错误码 vs 管理台能翻译的错误码。

为什么需要它：`fail(w, status, CODE, msg)` 里的 CODE 是前端唯一能用来
区分「该重试 / 该重新登录 / 该去改配置」的信号。服务端新增一个码而
`errors.ts` 没跟，用户看到的就是「请求失败（错误码 3003）」——
信息量为零，而且只有真的撞上才会被发现。

判定：
- 服务端出现过的码，**必须**在 errors.ts 里有对应文案（否则 exit 1）
- errors.ts 多出来的码只提示不失败：边缘部署（Worker）有几个 NAS 没有的码，
  例如 2006「边缘模式不支持这个功能」。
"""
import glob
import os
import re
import sys

for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
API_DIR = os.path.join(ROOT, "server", "internal", "api")
ERRORS_TS = os.path.join(ROOT, "admin", "src", "api", "errors.ts")

# 1. 服务端：fail(...) 的第三个参数、Code: 字面量、以及 *code* = NNNN 常量
go_codes = {}


def note(code, where):
    go_codes.setdefault(code, set()).add(where)


for path in glob.glob(os.path.join(API_DIR, "*.go")):
    if path.endswith("_test.go"):
        continue
    name = os.path.basename(path)
    with open(path, encoding="utf-8") as fh:
        text = fh.read()
    for m in re.finditer(r"fail\(\s*w\s*,\s*[^,]+,\s*(\d{3,4})\s*,", text):
        note(int(m.group(1)), name)
    for m in re.finditer(r"\bCode:\s*(\d{3,4})\b", text):
        note(int(m.group(1)), name)
    for m in re.finditer(r"(\w*[Cc]ode\w*)\s*(?::\s*\w+\s*)?=\s*(\d{3,4})\b", text):
        note(int(m.group(2)), f"{name}:{m.group(1)}")

# 2. 管理台：ERROR_MESSAGES 的键
with open(ERRORS_TS, encoding="utf-8") as fh:
    ts = fh.read()
ts_codes = {int(x) for x in re.findall(r"^\s*(\d{3,4}):", ts, re.M)}

missing = sorted(set(go_codes) - ts_codes)
extra = sorted(ts_codes - set(go_codes))

print(f"服务端码 {len(go_codes)} 个 / 管理台码 {len(ts_codes)} 个")
if missing:
    print("\n❌ 服务端会返回、但管理台没有文案的码：")
    for c in missing:
        print(f"   {c}  <- {', '.join(sorted(go_codes[c]))}")
if extra:
    print("\n提示：管理台多出来的码（可能是边缘部署专用，不算失败）：")
    for c in extra:
        print(f"   {c}")

if missing:
    print("\n修法：在 admin/src/api/errors.ts 的 ERROR_MESSAGES 里补上对应文案。")
    sys.exit(1)
print("\n✅ 服务端错误码都有对应文案")
