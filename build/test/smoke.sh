#!/usr/bin/env bash
# LITV 服务端冒烟测试：一条命令跑完全链路。
# 用法：bash build/test/smoke.sh
set -uo pipefail

# 仓库根目录 = 本脚本往上两级，不写死任何绝对路径
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK=/tmp/litv-test
GW_BASE="http://localhost/app/litv"
TCP_BASE="http://127.0.0.1:19527"

pkill -f 'litvd -config' 2>/dev/null
pkill -f 'http.server 18080' 2>/dev/null
sleep 0.5
rm -rf "$WORK"; mkdir -p "$WORK/cache"
cp "$ROOT/build/test/config.yaml" "$WORK/"
cp "$ROOT/build/test/sample.m3u" "$ROOT/build/test/sample.txt" "$WORK/"

# 起一个本地静态服务，模拟"远端源站"
cd "$WORK" && setsid nohup python3 -m http.server 18080 >/dev/null 2>&1 &
# 起 LITV
setsid nohup /tmp/litvd -config "$WORK/config.yaml" >"$WORK/run.log" 2>&1 &
sleep 2

gw() { curl -sS --unix-socket "$WORK/app.sock" \
        -H "X-Trim-Userid: 1000" -H "X-Trim-Username: admin" -H "X-Trim-Isadmin: true" "$@"; }
jq_or_cat() { if command -v jq >/dev/null; then jq -c . 2>/dev/null || cat; else cat; fi; }

echo "### 1. 健康检查"
curl -sS "$TCP_BASE/api/v1/health"; echo

echo; echo "### 2. 网关身份"
gw "$GW_BASE/api/v1/system/me"; echo

echo; echo "### 3. 创建 M3U 源"
gw -X POST -H "Content-Type: application/json" \
   -d '{"name":"测试M3U","type":"m3u","url":"http://127.0.0.1:18080/sample.m3u"}' \
   "$GW_BASE/api/v1/sources"; echo

echo; echo "### 4. 创建 TXT 源"
gw -X POST -H "Content-Type: application/json" \
   -d '{"name":"测试TXT","type":"txt","url":"http://127.0.0.1:18080/sample.txt"}' \
   "$GW_BASE/api/v1/sources"; echo

echo; echo "### 5. 预览 M3U 源（前 10 条，验证 \$ 后缀清洗与分组）"
gw "$GW_BASE/api/v1/sources/1/preview?limit=10"; echo

echo; echo "### 6. 刷新两个源"
gw -X POST "$GW_BASE/api/v1/sources/1/refresh"; echo
gw -X POST "$GW_BASE/api/v1/sources/2/refresh"; echo

echo; echo "### 7. 分组统计"
gw "$GW_BASE/api/v1/groups"; echo

echo; echo "### 8. 频道列表（前 3 条）"
gw "$GW_BASE/api/v1/channels?limit=3"; echo

echo; echo "### 9. 频道线路"
gw "$GW_BASE/api/v1/channels/1/streams"; echo

echo; echo "### 10. 终端注册（应返回 pending）"
curl -sS -X POST -H "Content-Type: application/json" \
   -d '{"device_key":"tv-living-room-001","name":"客厅电视","platform":"androidtv","model":"MiBox"}' \
   "$TCP_BASE/api/v1/device/register"; echo

echo; echo "### 11. 未批准设备访问（应被拒 2003）"
curl -sS -X POST "$TCP_BASE/api/v1/tv/heartbeat" -H "Authorization: Bearer invalid-token"; echo

echo; echo "### 12. 管理台批准设备 1"
gw -X POST "$GW_BASE/api/v1/devices/1/approve"; echo

echo; echo "### 13. 设备列表"
gw "$GW_BASE/api/v1/devices"; echo

echo; echo "### 14. 生成绑定码"
gw -X POST -H "Content-Type: application/json" -d '{"device_id":0}' "$GW_BASE/api/v1/devices/bindcode"; echo

echo; echo "### 15. 运行时状态"
gw "$GW_BASE/api/v1/system/info"; echo

echo; echo "### 16. 服务端日志尾部"
tail -6 "$WORK/run.log"

echo; echo "### DONE"
