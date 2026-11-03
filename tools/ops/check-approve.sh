#!/bin/bash
# 连接参数全部走环境变量，不写死任何本机路径：
#   LITV_SOCK  litvd 的 unix socket（必填）
#   LITV_DB    SQLite 数据库路径（需要查库的脚本必填）
#   LITV_BASE  HTTP 基地址，默认 http://localhost/app/litv/api/v1
S="${LITV_SOCK:?请设置 LITV_SOCK（litvd 的 unix socket 路径）}"
B="${LITV_BASE:-http://localhost/app/litv/api/v1}"
H1="X-Trim-Userid:1"; H2="X-Trim-Username:admin"; H3="X-Trim-Isadmin:true"
g() { curl -sS --unix-socket "$S" -H "$H1" -H "$H2" -H "$H3" "$@"; }

echo "--- approve 原始响应 ---"
g -X POST "http://localhost/app/litv/api/v1/devices/${LITV_DEVICE_ID:?请设置 LITV_DEVICE_ID}/approve" -w "\n[http=%{http_code}]\n"

echo
echo "--- 设备 7 状态 ---"
g "http://localhost/app/litv/api/v1/devices?status=all" > /tmp/all.json
python3 - <<'PY'
import json
d = json.load(open('/tmp/all.json'))['data']
items = d if isinstance(d, list) else d.get('items', [])
for x in items:
    if x['id'] == 7:
        print("  id=7 status=", x.get('status'), " name=", x.get('name'))
PY
