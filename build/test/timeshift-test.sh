#!/usr/bin/env bash
# 时移/回看验证：边看边录 → 能查到可回看时长 → 能拉到回看数据
#
# 用一个本地大文件冒充直播流（内容不需要是合法 TS，
# 这里只验证"录制→索引→回读"这条链路）。
set -uo pipefail

PORT=19561
W=/tmp/litv-ts
BASE="http://127.0.0.1:$PORT"
GWURL="http://localhost/app/litv/api/v1"
BIN=${BIN:-/tmp/litvd}
G="curl -sS --unix-socket $W/app.sock -H X-Trim-Userid:1000 -H X-Trim-Username:admin -H X-Trim-Isadmin:true"

pkill -x litvd 2>/dev/null
# 清掉上一轮残留的静态服务，否则端口被占会静默失败（表现为上游 404）
pkill -f "http[.]server" 2>/dev/null
sleep 0.5
rm -rf "$W"; mkdir -p "$W/cache" "$W/www"

# 造一个 8MB 的"直播流"（188 字节包长的整数倍，模拟 TS）
dd if=/dev/urandom of="$W/www/live.ts" bs=188 count=45000 status=none
ls -lh "$W/www/live.ts"

cat > "$W/config.yaml" <<EOF
server:
  host: "127.0.0.1"
  port: $PORT
  gateway_socket: "$W/app.sock"
  prefix: "/app/litv"
database:
  path: "$W/litv.db"
auth:
  secret: "ts-secret"
stream:
  cache_dir: "$W/cache"
probe:
  enabled: false
timeshift:
  enabled: true
  segment_mb: 2
  max_segments: 4
EOF

( cd "$W/www" && setsid nohup python3 -m http.server 18099 >/dev/null 2>&1 & )
setsid nohup "$BIN" -config "$W/config.yaml" > "$W/run.log" 2>&1 < /dev/null &
sleep 2

pass=0; fail=0
chk() { if printf '%s' "$3" | grep -q "$2"; then printf '\033[32m  PASS\033[0m %s\n' "$1"; pass=$((pass+1));
        else printf '\033[31m  FAIL\033[0m %s\n        期望: %s\n        实际: %s\n' "$1" "$2" "${3:0:200}"; fail=$((fail+1)); fi; }

echo "== 准备频道 =="
$G -X POST -H "Content-Type: application/json" \
   -d "{\"name\":\"ts\",\"type\":\"m3u\",\"url\":\"http://127.0.0.1:18099/live.ts\"}" "$GWURL/sources" >/dev/null
# 直接建一个频道并挂线路（源是裸 ts，走 txt/m3u 解析器不合适）
CID=$($G -X POST -H "Content-Type: application/json" \
   -d '{"name":"测试台","group":"测试","enabled":true}' "$GWURL/channels" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
$G -X POST -H "Content-Type: application/json" \
   -d '{"url":"http://127.0.0.1:18099/live.ts","quality":"本地","priority":0}' \
   "$GWURL/channels/$CID/streams" >/dev/null
echo "频道 id=$CID"

echo
echo "== 注册并批准一台终端 =="
SUF=$(date +%s)
REG=$(curl -sS -X POST -H "Content-Type: application/json" \
  -d "{\"device_key\":\"ts$SUF\",\"name\":\"时移测试\",\"platform\":\"androidtv\"}" "$BASE/api/v1/device/register")
TOK=$(printf '%s' "$REG" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
DID=$(printf '%s' "$REG" | sed -n 's/.*"device_id":\([0-9]*\).*/\1/p')
$G -X POST "$GWURL/devices/$DID/approve" >/dev/null

echo
echo "== 开始观看（触发扇出，从而触发录制） =="
PLAY=$(curl -sS -H "Authorization: Bearer $TOK" "$BASE/api/v1/tv/play/$CID")
PURL=$(printf '%s' "$PLAY" | sed -n 's/.*"url":"\([^"]*\)".*/\1/p' | sed 's|\\/|/|g; s|\\u0026|\&|g')
chk "拿到播放地址" 'live/' "$PURL"
# 拉 3 秒就断开，让服务端持续读上游
curl -sS -m 3 -o /dev/null "http://127.0.0.1:$PORT$PURL" 2>/dev/null || true
sleep 3

echo
echo "== 查询时移可用性 =="
TSINFO=$(curl -sS -H "Authorization: Bearer $TOK" "$BASE/api/v1/tv/timeshift/$CID")
chk "时移已启用" '"enabled":true' "$TSINFO"
chk "有可回看时长" '"available_seconds"' "$TSINFO"
SPAN=$(printf '%s' "$TSINFO" | sed -n 's/.*"available_seconds":\([0-9]*\).*/\1/p')
echo "  可回看 ${SPAN:-0} 秒"

echo
echo "== 拉回看数据 =="
TURL=$(printf '%s' "$TSINFO" | sed -n 's/.*"url":"\([^"]*\)".*/\1/p' | sed 's|\\/|/|g; s|\\u0026|\&|g')
if [ -n "$TURL" ]; then
  BYTES=$(curl -sS -m 5 -o "$W/ts.bin" -w '%{size_download}' "http://127.0.0.1:$PORT${TURL}&back=1" 2>/dev/null || echo 0)
  if [ "${BYTES:-0}" -gt 0 ]; then
    printf '\033[32m  PASS\033[0m 回看拉到 %s 字节\n' "$BYTES"; pass=$((pass+1))
  else
    printf '\033[31m  FAIL\033[0m 回看为 0 字节\n'; fail=$((fail+1))
  fi
else
  printf '\033[31m  FAIL\033[0m 没拿到回看地址\n'; fail=$((fail+1))
fi

echo
echo "== 录制文件落盘情况 =="
find "$W/cache/timeshift" -name '*.ts' -printf '%f %s\n' 2>/dev/null | head -6

echo
echo "=============================================="
printf '结果: \033[32m%d 通过\033[0m, \033[31m%d 失败\033[0m\n' "$pass" "$fail"
pkill -x litvd 2>/dev/null
[ "$fail" -eq 0 ] || exit 1
