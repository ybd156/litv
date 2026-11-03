"""对账：管理台请求的接口路径 vs 服务端注册的路由。

一次性把不匹配的找出来，避免一个个页面点过去才发现 404。
"""
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# ---- 1. 服务端路由 ----
api_go = os.path.join(ROOT, "server/internal/api/api.go")
src = open(api_go, encoding="utf-8").read()
routes = set()
for m in re.finditer(r'\.(Get|Post|Put|Delete|Patch)\("(/api/v1/[^"]+)"', src):
    method, path = m.group(1).upper(), m.group(2)
    routes.add((method, path))

# ---- 2. 管理台调用 ----
calls = set()
mod_dir = os.path.join(ROOT, "admin/src/api/modules")
for fn in os.listdir(mod_dir):
    if not fn.endswith(".ts"):
        continue
    text = open(os.path.join(mod_dir, fn), encoding="utf-8").read()
    # http.get<...>('path') / http.post(`path/${id}`)
    for m in re.finditer(r"http\.(get|post|put|del)<[^>]*>\(\s*[`'\"]([^`'\"]+)", text):
        verb, path = m.group(1), m.group(2)
        method = {"get": "GET", "post": "POST", "put": "PUT", "del": "DELETE"}[verb]
        # 把 ${...} 换成 {x} 以便和 chi 的 {id} 对齐
        path = re.sub(r"\$\{[^}]*\}", "{x}", path)
        calls.add((method, path, fn))

# ---- 3. 归一化后比对 ----
def norm(p):
    p = p.strip("/")
    p = re.sub(r"\{[^}]*\}", "{*}", p)
    return p

route_norm = {(m, norm(p)) for m, p in routes}

bad = []
for method, path, fn in sorted(calls):
    full = "api/v1/" + path.lstrip("/")
    if (method, norm(full)) not in route_norm:
        bad.append((method, full, fn))

print("服务端路由数:", len(routes))
print("管理台调用数:", len(calls))
print()
if bad:
    print("❌ 对不上的调用:")
    for method, path, fn in bad:
        print(f"   {method:6} /{path:38}  ({fn})")
else:
    print("✅ 全部对得上")

# B06：发现不匹配必须返回非零退出码。
#
# 以前这里到此为止，脚本永远 exit 0 —— 即使打印了 ❌，
# 挂进 CI 也是绿的，等于没有门禁。
# 一个"只打印不失败"的检查脚本比没有更糟：它给人一种已被守护的错觉。
import sys

if bad:
    sys.exit(1)
sys.exit(0)

