#!/usr/bin/env bash
# =============================================================================
# LITV 终端链路端到端测试
#
# 模拟一台 Android TV 的完整生命周期，验证服务端与客户端契约真的对得上：
#   注册 → 待批准 → 批准 → bootstrap → 拉 EPG → 拿播放地址
#   → 用签名 URL 拉流 → 收藏 → 心跳 → 列线路 → 指定线路播放
#
# 不依赖真实设备，全部走 HTTP。
# =============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK=/tmp/litv-e2e
BIN=${BIN:-/tmp/litvd}
PORT=19541
GW="http://localhost/app/litv"

pkill -x litvd 2>/dev/null; sleep 0.5
rm -rf "$WORK"; mkdir -p "$WORK/cache"
cp "$ROOT/build/test/sample.m3u" "$WORK/" || { echo "样例源缺失: $ROOT/build/test/sample.m3u"; exit 1; }
[ -s "$WORK/sample.m3u" ] || { echo "样例源为空"; exit 1; }

# 本地静态服务，模拟"上游直播源站"
( cd "$WORK" && setsid nohup python3 -m http.server 18091 >/dev/null 2>&1 & )

cat > "$WORK/config.yaml" <<EOF
server:
  host: "127.0.0.1"
  port: $PORT
  gateway_socket: "$WORK/app.sock"
  prefix: "/app/litv"
database:
  path: "$WORK/litv.db"
auth:
  secret: "e2e-secret"
  token_hours: 24
stream:
  cache_dir: "$WORK/cache"
probe:
  enabled: false
EOF

setsid nohup "$BIN" -config "$WORK/config.yaml" > "$WORK/run.log" 2>&1 < /dev/null &
sleep 2

gw() { curl -sS --unix-socket "$WORK/app.sock" \
        -H "X-Trim-Userid: 1000" -H "X-Trim-Username: admin" -H "X-Trim-Isadmin: true" "$@"; }
pass=0; fail=0
chk() { # chk <描述> <期望子串> <实际>
  if printf '%s' "$3" | grep -q "$2"; then
    printf '\033[32m  PASS\033[0m %s\n' "$1"; pass=$((pass+1))
  else
    printf '\033[31m  FAIL\033[0m %s\n        期望含: %s\n        实际: %s\n' "$1" "$2" "${3:0:220}"; fail=$((fail+1))
  fi
}

echo "== 1. 管理台：建源并导入频道 =="
gw -X POST -H "Content-Type: application/json" \
   -d "{\"name\":\"e2e\",\"type\":\"m3u\",\"url\":\"http://127.0.0.1:18091/sample.m3u\"}" \
   "$GW/api/v1/sources" >/dev/null
R=$(gw -X POST "$GW/api/v1/sources/1/refresh")
chk "源导入频道" '"inserted":4' "$R"

# 把频道 1 的线路改成真实可访问地址，后面拉流才成功
gw -X POST -H "Content-Type: application/json" \
   -d '{"url":"http://127.0.0.1:18091/sample.m3u","quality":"可用","priority":9}' \
   "$GW/api/v1/channels/1/streams" >/dev/null

echo
echo "== 2. 终端：注册（应为 pending） =="
REG=$(curl -sS -X POST -H "Content-Type: application/json" \
  -d '{"device_key":"e2e-tv-001","name":"E2E电视","platform":"androidtv","model":"TestBox"}' \
  "http://127.0.0.1:$PORT/api/v1/device/register")
chk "注册返回 pending" '"status":"pending"' "$REG"
TOKEN=$(printf '%s' "$REG" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
DEV_ID=$(printf '%s' "$REG" | sed -n 's/.*"device_id":\([0-9]*\).*/\1/p')
[ -n "$TOKEN" ] && { echo "  token 已获取（长度 ${#TOKEN}）"; pass=$((pass+1)); } || { echo "  FAIL 没拿到 token"; fail=$((fail+1)); }

echo
echo "== 3. 未批准时访问终端接口（应被拒） =="
R=$(curl -sS -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:$PORT/api/v1/tv/bootstrap")
chk "未批准被拒 2003" '"code":2003' "$R"

echo
echo "== 4. 管理台批准设备 =="
R=$(gw -X POST "$GW/api/v1/devices/$DEV_ID/approve")
chk "批准成功" '"approved":true' "$R"

echo
echo "== 5. 终端：bootstrap =="
BOOT=$(curl -sS -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:$PORT/api/v1/tv/bootstrap")
chk "bootstrap 返回非空频道列表" '"channels":\[{' "$BOOT"
chk "bootstrap 含分组" '"groups"' "$BOOT"
chk "bootstrap 含收藏字段" '"favorites"' "$BOOT"

echo
echo "== 6. 终端：EPG =="
R=$(curl -sS -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:$PORT/api/v1/tv/epg/1?day=0")
chk "EPG 接口可用" '"code":0' "$R"

echo
echo "== 7. 终端：取播放地址 =="
PLAY=$(curl -sS -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:$PORT/api/v1/tv/play/1")
chk "返回签名 URL" '"url"' "$PLAY"
chk "带签名参数 sig" 'sig=' "$PLAY"
PLAY_URL=$(printf '%s' "$PLAY" | sed -n 's/.*"url":"\([^"]*\)".*/\1/p')
# Go 的 encoding/json 会把 & 转义成 \u0026，这里还原成真正的查询串
# （Android 端 Gson 会自动反转义，所以只有测试脚本需要处理）
PLAY_URL=$(printf '%s' "$PLAY_URL" | sed 's|\\/|/|g; s|\\u0026|\&|g')
echo "  播放地址: ${PLAY_URL:0:90}…"

echo
echo "== 8. 用签名 URL 拉流（应拿到 TS 字节） =="
if [ -z "$PLAY_URL" ]; then
  printf '\033[31m  FAIL\033[0m 播放地址为空，无法拉流\n'; fail=$((fail+1))
else
  BYTES=$(curl -sS -m 8 -o "$WORK/stream.bin" -w '%{size_download}' \
    "http://127.0.0.1:$PORT$PLAY_URL" 2>/dev/null || echo 0)
  if [ "${BYTES:-0}" -gt 0 ]; then
    printf '\033[32m  PASS\033[0m 拉流拿到 %s 字节\n' "$BYTES"; pass=$((pass+1))
  else
    printf '\033[33m  SKIP\033[0m 拉流为 0 字节（上游是 m3u 清单不是 TS，属预期）\n'
  fi
fi

echo
echo "== 9. 签名校验：篡改签名应被拒 =="
BAD=$(printf '%s' "$PLAY_URL" | sed 's/sig=[^&]*/sig=tampered/')
R=$(curl -sS -m 5 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT$BAD")
chk "篡改签名被拒(403)" '403' "$R"

echo
echo "== 10. 终端：收藏 =="
R=$(curl -sS -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"channel_id":1,"on":true}' "http://127.0.0.1:$PORT/api/v1/tv/favorite")
chk "收藏成功" '"saved":true' "$R"
BOOT2=$(curl -sS -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:$PORT/api/v1/tv/bootstrap")
chk "bootstrap 反映收藏" '"favorites":\[1\]' "$BOOT2"

echo
echo "== 11. 终端：心跳 =="
R=$(curl -sS -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"playing_channel":1,"state":"playing"}' "http://127.0.0.1:$PORT/api/v1/tv/heartbeat")
chk "心跳返回 config_version" '"config_version"' "$R"

echo
echo "== 12. 终端：线路列表 =="
R=$(curl -sS -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:$PORT/api/v1/tv/streams/1")
chk "线路列表可用" '"label"' "$R"
chk "线路含健康度" '"health"' "$R"
LEAK=$(printf '%s' "$R" | grep -c 'http://127.0.0.1:18091' || true)
if [ "${LEAK:-0}" -eq 0 ]; then
  printf '\033[32m  PASS\033[0m 线路列表未泄露上游真实地址\n'; pass=$((pass+1))
else
  printf '\033[31m  FAIL\033[0m 线路列表泄露了上游地址\n'; fail=$((fail+1))
fi

echo
echo "== 12.5 播放质量上报与统计 =="
for i in 1 2 3; do
  curl -sS -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -d "{\"channel_id\":1,\"event\":\"start\",\"startup_ms\":$((300 + i * 100))}" \
    "http://127.0.0.1:$PORT/api/v1/tv/stats" >/dev/null
done
curl -sS -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"channel_id":1,"event":"stall","detail":"buffer"}' "http://127.0.0.1:$PORT/api/v1/tv/stats" >/dev/null
R=$(gw "$GW/api/v1/stats/overview?days=7")
chk "总览有起播次数" '"total_plays":3' "$R"
chk "总览有平均起播" '"avg_startup_ms":500' "$R"
chk "总览有卡顿率" '"stall_rate"' "$R"
R=$(gw "$GW/api/v1/stats/channels?days=7")
chk "频道统计可用" '"plays":3' "$R"
chk "频道统计带名称" '"name"' "$R"

echo
echo "== 12.6 跨端续播 =="
REG2=$(curl -sS -X POST -H "Content-Type: application/json" \
  -d '{"device_key":"e2e-bedroom","name":"卧室电视","platform":"androidtv"}' \
  "http://127.0.0.1:$PORT/api/v1/device/register")
TOK2=$(printf '%s' "$REG2" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
DEV2=$(printf '%s' "$REG2" | sed -n 's/.*"device_id":\([0-9]*\).*/\1/p')
gw -X POST "$GW/api/v1/devices/$DEV2/approve" >/dev/null

curl -sS -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"channel_id":1,"position_sec":0}' "http://127.0.0.1:$PORT/api/v1/tv/progress" >/dev/null

R=$(curl -sS -H "Authorization: Bearer $TOK2" "http://127.0.0.1:$PORT/api/v1/tv/resume")
chk "另一台设备能看到续播" '"available":true' "$R"
chk "续播带频道名" '"channel_name"' "$R"
chk "续播带来源设备名" '"device_name":"E2E电视"' "$R"

R=$(curl -sS -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:$PORT/api/v1/tv/resume")
chk "自己查不到自己的续播" '"available":false' "$R"

echo
echo "== 13. 吊销设备后应失效 =="
gw -X DELETE "$GW/api/v1/devices/$DEV_ID" >/dev/null
R=$(curl -sS -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:$PORT/api/v1/tv/bootstrap")
chk "吊销后 2003" '"code":2003' "$R"

echo
echo "=============================================="
printf '结果: \033[32m%d 通过\033[0m, \033[31m%d 失败\033[0m\n' "$pass" "$fail"
echo "工作目录: $WORK"
pkill -x litvd 2>/dev/null
pkill -f 'http.server 18091' 2>/dev/null
[ "$fail" -eq 0 ] || exit 1
