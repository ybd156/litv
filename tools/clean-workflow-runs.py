"""清理 GitHub Actions 里已删除 workflow 的历史运行记录。

为什么需要：删掉 .github/workflows/xxx.yml 只是把 workflow 从侧边栏拿掉，
它的**历史 run 仍然留在仓库的 Actions 列表里**。要彻底干净必须逐条删 run。

只删「workflow 文件已不存在」的那些 run，保留 release.yml 的记录。
"""
import json
import os
import subprocess
import sys

REPO = "ybd156/litv"
KEEP = {".github/workflows/release.yml"}
TOKEN = open(os.path.expanduser("~/.gh_token")).read().strip()
PROXY = "http://172.27.32.1:10808"


def api(path, method="GET"):
    cmd = [
        "curl", "-s", "-X", method,
        "-H", f"Authorization: Bearer {TOKEN}",
        "-H", "Accept: application/vnd.github+json",
        f"https://api.github.com/repos/{REPO}{path}",
    ]
    out = subprocess.run(cmd, capture_output=True, text=True,
                         env={**os.environ, "https_proxy": PROXY, "http_proxy": PROXY}).stdout
    return out


runs = []
page = 1
while True:
    raw = api(f"/actions/runs?per_page=100&page={page}")
    try:
        data = json.loads(raw)
    except Exception:
        print("解析失败:", raw[:200])
        sys.exit(1)
    batch = data.get("workflow_runs", [])
    if not batch:
        break
    runs.extend(batch)
    if len(batch) < 100:
        break
    page += 1

print(f"共 {len(runs)} 条运行记录")
doomed = [r for r in runs if r.get("path") not in KEEP]
print(f"待删除 {len(doomed)} 条（保留 {len(runs) - len(doomed)} 条 release.yml）")

ok = fail = 0
for r in doomed:
    out = api(f"/actions/runs/{r['id']}", method="DELETE")
    # 成功时 body 为空；失败会有 message
    if '"message"' in out:
        fail += 1
        if fail <= 3:
            print(f"  删除失败 run={r['id']} {r.get('path')}: {out[:120]}")
    else:
        ok += 1

print(f"已删除 {ok} 条，失败 {fail} 条")

left = json.loads(api("/actions/runs?per_page=5")).get("workflow_runs", [])
print("剩余最近的 run:")
for r in left:
    print(f"  {r['name']} | {r['path']} | {r['status']}")
