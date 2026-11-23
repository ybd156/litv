"""对账：管理台请求的接口路径 vs 服务端注册的路由。

一次性把不匹配的找出来，避免一个个页面点过去才发现 404。
"""
import os
import re
import sys

# Windows 的控制台默认编码是 GBK，打印 ✅ / ❌ 会抛 UnicodeEncodeError，
# 于是「全部对得上」也变成非零退出——本地跑的人会误判成接口对不上。
# 显式切到 UTF-8：让退出码只反映接口是否真的对得上，而不是控制台能不能画勾。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

# 仓库根 = 本文件往上三级（tools/checks/<this>.py）。
#
# 这里曾经只往上两级，于是 ROOT 落在 tools/ 上，去拼
# tools/server/internal/api/api.go —— 文件不存在，直接 AttributeError/FileNotFoundError。
# 换句话说：这个检查器从放进 tools/checks/ 的那天起就跑不起来，
# 而它没接线进 CI，所以「跑不起来」这件事没有任何人会发现。
# 门禁脚本的路径推导必须自己先被验证一次，否则它守护的一切都是幻觉。
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def need(path, what):
    """被守护的文件/目录不存在时给出**看得懂**的失败，而不是一段 traceback。

    门禁体检（复制到空树里跑）时，以前这里抛
    `FileNotFoundError: .../server/internal/api/api.go`：红是红了，但读日志的人
    只看到 Python 栈，容易判成"脚本坏了"，而不是"扫描目标被改名/移走了"。
    """
    if not os.path.exists(path):
        print(f"❌ 找不到{what}：{path}")
        print("   扫描目标被改名/移走了？先修路径，不要把这个检查从 preflight 里摘掉。")
        sys.exit(1)
    return path


# ---- 1. 服务端路由 ----
api_go = need(os.path.join(ROOT, "server/internal/api/api.go"), "服务端路由表")
src = open(api_go, encoding="utf-8").read()
routes = set()
for m in re.finditer(r'\.(Get|Post|Put|Delete|Patch)\("(/api/v1/[^"]+)"', src):
    method, path = m.group(1).upper(), m.group(2)
    routes.add((method, path))

# ---- 2. 管理台调用 ----
calls = set()
mod_dir = need(os.path.join(ROOT, "admin/src/api/modules"), "管理台 API 模块目录")
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

# ---- 2.5 边缘独有接口 ----
#
# 边缘降级部署（`edge/` 里的 Cloudflare Worker）实现了几个 **NAS 服务端没有** 的
# 接口。管理台是同一份代码，在边缘形态下会调它们——所以「app 里有、api.go 里没有」
# 对这一小组是**正常**的，不是漏注册。
#
# 白名单不能变成「随便加一条就能绕过门禁」的后门：每条都必须在
# `edge/src/index.ts` 里真的找得到路由代码，否则脚本照样报错。
#
# 另一个方向的保护：这些页面在管理台里标了 `meta.edgeOnly`，
# NAS 部署时 router.beforeEach 会把它们踢回首页——所以这些调用在 NAS 上不会发生。
EDGE_ONLY = {
    # 屏蔽清单：按分类整类 / 按频道名挡住不下发。
    # NAS 侧没有这个概念（那边的「隐藏」是逐个频道写 channel_overrides）。
    ("GET", "api/v1/blocked"),
    ("POST", "api/v1/blocked"),
    ("DELETE", "api/v1/blocked"),
    # 设置自省：绑定码（打码 / 明文）、管理台入口、是否配了管理台密码。
    # NAS 的身份来自飞牛网关，没有「绑定码」这回事，也没有自定义入口。
    ("GET", "api/v1/admin/settings"),
}

edge_src_path = os.path.join(ROOT, "edge/src/index.ts")
edge_src = ""
if os.path.exists(edge_src_path):
    edge_src = open(edge_src_path, encoding="utf-8").read()


def edge_implements(path):
    """白名单条目必须在 edge Worker 里真的被路由到，否则白名单就是空头支票。"""
    return ('"/' + path + '"') in edge_src or ("`/" + path + "`") in edge_src


bad = []
for method, path, fn in sorted(calls):
    full = "api/v1/" + path.lstrip("/")
    key = (method, norm(full))
    if key in route_norm or key in EDGE_ONLY:
        continue
    bad.append((method, full, fn))

# 白名单里写了、但 edge 侧其实没实现的条目：多半是改了路径忘了同步，必须报出来
unbacked = sorted(k for k in EDGE_ONLY if not edge_implements(k[1]))

# ---- 4. 分层门禁：视图/组件里不许直接发请求 ----
#
# 52 条调用早就在 api/modules 了，但 LiveView / LoginView 还各有一条直连，
# 而本脚本原先只扫 modules —— **盲区和绕过点是同一个根因**。
# 这里把「谁能发请求」变成机械约束：只有 api/http.ts 与 api/modules/**、api/mock/** 可以。
ALLOW_DIRECT = (
    os.path.join("admin", "src", "api", "modules") + os.sep,
    os.path.join("admin", "src", "api", "mock") + os.sep,
)
ALLOW_FILES = {os.path.join("admin", "src", "api", "http.ts")}


def strip_comments(text):
    """去掉注释再匹配：注释里写「request() 会把 msg 放进 message」不算违规。"""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"(?m)^\s*//.*$", "", text)
    text = re.sub(r"(?m)\s//[^\n]*$", "", text)
    # Vue 模板里的 <!-- --> 也一并去掉
    text = re.sub(r"<!--.*?-->", "", text, flags=re.S)
    return text


violations = []
src_root = need(os.path.join(ROOT, "admin", "src"), "管理台源码目录")
for dirpath, _dirnames, filenames in os.walk(src_root):
    for fn in filenames:
        if not fn.endswith((".ts", ".vue")):
            continue
        path = os.path.join(dirpath, fn)
        rel = os.path.relpath(path, ROOT)
        if any(rel.startswith(d) for d in ALLOW_DIRECT) or rel in ALLOW_FILES:
            continue
        with open(path, encoding="utf-8", errors="replace") as fh:
            text = strip_comments(fh.read())
        for lineno, line in enumerate(text.splitlines(), 1):
            if re.search(r"(?<![\w.])request\s*[<(]", line) or re.search(r"(?<![\w.])fetch\s*\(", line):
                violations.append((rel, lineno, line.strip()[:80]))

print("服务端路由数:", len(routes))
print("管理台调用数:", len(calls))
print("边缘独有接口数:", len(EDGE_ONLY))
print("直连请求违规数:", len(violations))
print()
if unbacked:
    print("❌ 白名单里这些路径在 edge/src/index.ts 找不到路由（白名单不能空头开）：")
    for method, path in unbacked:
        print(f"   {method:6} /{path}")
if bad:
    print("❌ 对不上的调用:")
    for method, path, fn in bad:
        print(f"   {method:6} /{path:38}  ({fn})")
if violations:
    print("❌ 视图/组件里直接发请求（应搬到 admin/src/api/modules）：")
    for rel, lineno, line in violations:
        print(f"   {rel}:{lineno}  {line}")
if not bad and not unbacked and not violations:
    print("✅ 全部对得上")

# B06：发现不匹配必须返回非零退出码。
#
# 以前这里到此为止，脚本永远 exit 0 —— 即使打印了 ❌，
# 挂进 CI 也是绿的，等于没有门禁。
# 一个"只打印不失败"的检查脚本比没有更糟：它给人一种已被守护的错觉。
if bad or unbacked or violations:
    sys.exit(1)
sys.exit(0)

