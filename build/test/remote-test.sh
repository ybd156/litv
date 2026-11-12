#!/usr/bin/env bash
# 遥控通道验证：电视端连 WS 待命 → 手机端发指令 → 电视端收到
#
# 自带服务端，不依赖外部环境（之前的版本假设 19551 已有服务在跑，
# 被别的测试 pkill 掉就失败了）。
set -uo pipefail

PORT=19551
W=/tmp/litv-rc
BASE="http://127.0.0.1:$PORT"
GWURL="http://localhost/app/litv/api/v1"
BIN=${BIN:-/tmp/litvd}
G="curl -sS --unix-socket $W/app.sock -H X-Trim-Userid:1000 -H X-Trim-Username:admin -H X-Trim-Isadmin:true"

# 起一个干净的服务端
pkill -x litvd 2>/dev/null; sleep 0.5
rm -rf "$W"; mkdir -p "$W/cache"
cat > "$W/config.yaml" <<EOF
server:
  host: "127.0.0.1"
  port: $PORT
  gateway_socket: "$W/app.sock"
  prefix: "/app/litv"
database:
  path: "$W/litv.db"
auth:
  secret: "rc-secret"
stream:
  cache_dir: "$W/cache"
probe:
  enabled: false
EOF
setsid nohup "$BIN" -config "$W/config.yaml" > "$W/run.log" 2>&1 < /dev/null &
sleep 2
curl -sS -o /dev/null "http://127.0.0.1:$PORT/api/v1/health" || { echo "服务端没起来"; exit 1; }

# 每次都建全新设备，避免 device_key 重复导致拿不到 token
SUF=$(date +%s)

reg() { # reg <前缀> <名字> <平台>
  curl -sS -X POST -H "Content-Type: application/json" \
    -d "{\"device_key\":\"$1\",\"name\":\"$2\",\"platform\":\"$3\"}" \
    "$BASE/api/v1/device/register"
}

TVREG=$(reg "tv$SUF" "测试电视" "androidtv")
T1=$(printf '%s' "$TVREG" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
TV_ID=$(printf '%s' "$TVREG" | sed -n 's/.*"device_id":\([0-9]*\).*/\1/p')

PHREG=$(reg "ph$SUF" "测试手机" "android")
T2=$(printf '%s' "$PHREG" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
PH_ID=$(printf '%s' "$PHREG" | sed -n 's/.*"device_id":\([0-9]*\).*/\1/p')
echo "电视 id=$TV_ID  手机 id=$PH_ID"

$G -X POST "$GWURL/devices/$TV_ID/approve" >/dev/null
$G -X POST "$GWURL/devices/$PH_ID/approve" >/dev/null

# 同步最新的 WS 客户端源码
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cp "$ROOT/build/test/wstv.go" /tmp/wsc/wstv.go

cd /tmp/wsc || exit 1
rm -f /tmp/wsc/tv.log
setsid nohup go run wstv.go "ws://127.0.0.1:$PORT/api/v1/tv/ws" "$T1" > /tmp/wsc/tv.log 2>&1 < /dev/null &
sleep 10

echo "=== 电视端连接状态 ==="
head -3 /tmp/wsc/tv.log

echo
echo "=== 手机视角：可遥控目标 ==="
curl -sS -H "Authorization: Bearer $T2" "$BASE/api/v1/tv/remote/targets"; echo

echo
echo "=== 手机发两条指令 ==="
curl -sS -X POST -H "Authorization: Bearer $T2" -H "Content-Type: application/json" \
  -d '{"type":"key","key":"down"}' "$BASE/api/v1/tv/remote/$TV_ID"; echo
curl -sS -X POST -H "Authorization: Bearer $T2" -H "Content-Type: application/json" \
  -d '{"type":"play","channel_id":3}' "$BASE/api/v1/tv/remote/$TV_ID"; echo
sleep 3

echo
echo "=== 电视端收到的全部消息 ==="
cat /tmp/wsc/tv.log

echo
if grep -q '"key":"down"' /tmp/wsc/tv.log && grep -q '"channel_id":3' /tmp/wsc/tv.log; then
  printf '\033[32m遥控通道验证通过\033[0m\n'
else
  printf '\033[31m遥控通道验证失败\033[0m\n'
fi
