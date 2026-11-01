#!/usr/bin/env bash
# Web 端直接看：验证管理台能拿到可播的 HLS 地址，并且经网关能真的拉到流
#
# 模拟的就是浏览器：走 /app/litv 前缀（飞牛网关），拿 m3u8，再拉切片。
set -uo pipefail

PORT=19601
W=/tmp/litv-web
BASE="http://127.0.0.1:$PORT"
GWURL="http://localhost/app/litv/api/v1"
BIN=${BIN:-/tmp/litvd}
FF=${FF:-$HOME/tools/ffmpeg/ffmpeg}
G="curl -sS --unix-socket $W/app.sock -H X-Trim-Userid:1000 -H X-Trim-Username:admin -H X-Trim-Isadmin:true"

pass=0; fail=0
chk() { if printf '%s' "$3" | grep -q "$2"; then printf '\033[32m  PASS\033[0m %s\n' "$1"; pass=$((pass+1));
        else printf '\033[31m  FAIL\033[0m %s\n        期望: %s\n        实际: %s\n' "$1" "$2" "${3:0:220}"; fail=$((fail+1)); fi; }

[ -x "$FF" ] || { echo "缺少 ffmpeg: $FF"; exit 2; }

pkill -x litvd 2>/dev/null
# 清掉上一轮残留的静态服务，否则端口被占会静默失败（表现为上游 404）
pkill -f "http[.]server" 2>/dev/null
sleep 0.5
rm -rf "$W"; mkdir -p "$W/cache" "$W/www"

echo "== 生成 60 秒 TS 上游 =="
"$FF" -hide_banner -loglevel error \
  -f lavfi -i "testsrc2=size=640x360:rate=25" -f lavfi -i "sine=frequency=440" \
  -t 60 -c:v libx264 -preset ultrafast -g 50 -c:a aac -b:a 64k \
  -f mpegts "$W/www/live.ts" 2>&1 | tail -2
ls -lh "$W/www/live.ts" | awk '{print "  生成:", $5}'

cat > "$W/config.yaml" <<EOF
server:
  host: "127.0.0.1"
  port: $PORT
  gateway_socket: "$W/app.sock"
  prefix: "/app/litv"
database:
  path: "$W/litv.db"
auth:
  secret: "web-secret"
stream:
  cache_dir: "$W/cache"
probe:
  enabled: false
timeshift:
  enabled: false
remux:
  enabled: true
  segment_seconds: 2
  playlist_size: 5
  idle_timeout_seconds: 30
record:
  enabled: false
transcode:
  enabled: false
  ffmpeg: "$FF"
EOF

( cd "$W/www" && setsid nohup python3 -m http.server 18094 >/dev/null 2>&1 & )
setsid nohup "$BIN" -config "$W/config.yaml" > "$W/run.log" 2>&1 < /dev/null &
sleep 2
curl -sS -o /dev/null "$BASE/api/v1/health" || { echo "服务端没起来"; tail -5 "$W/run.log"; exit 1; }

echo
echo "== 建频道（裸 TS 上游）=="
CID=$($G -X POST -H "Content-Type: application/json" \
   -d '{"name":"Web测试台","group":"测试","enabled":true}' "$GWURL/channels" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
$G -X POST -H "Content-Type: application/json" \
   -d "{\"url\":\"http://127.0.0.1:18094/live.ts\",\"quality\":\"本地\",\"priority\":0}" \
   "$GWURL/channels/$CID/streams" >/dev/null
echo "频道 id=$CID"

echo
echo "== 管理台取播放地址 =="
R=$($G "$GWURL/web/play/$CID")
chk "裸 TS 走转封装" '"kind":"remux"' "$R"
chk "返回播放地址" '"url"' "$R"
WURL=$(printf '%s' "$R" | sed -n 's/.*"url":"\([^"]*\)".*/\1/p' | sed 's|\\/|/|g; s|\\u0026|\&|g')
echo "  地址: ${WURL:0:95}"

echo
echo "== 经网关拉 m3u8（模拟浏览器）=="
PL=$(curl -sS -m 25 --unix-socket "$W/app.sock" -H "X-Trim-Userid: 1000" \
  -H "X-Trim-Username: admin" -H "X-Trim-Isadmin: true" "http://localhost$WURL")
chk "是合法 m3u8" '#EXTM3U' "$PL"
chk "有切片" '#EXTINF' "$PL"

echo
echo "== 经网关拉切片 =="
SEG=$(printf '%s' "$PL" | grep -v '^#' | grep -v '^$' | head -1)
if [ -n "$SEG" ]; then
  curl -sS -m 15 --unix-socket "$W/app.sock" -H "X-Trim-Userid: 1000" \
    -H "X-Trim-Username: admin" -H "X-Trim-Isadmin: true" \
    "http://localhost$SEG" -o "$W/seg.ts"
  SZ=$(stat -c %s "$W/seg.ts" 2>/dev/null || echo 0)
  if [ "${SZ:-0}" -gt 1000 ]; then
    printf '\033[32m  PASS\033[0m 网关转发切片 %s 字节\n' "$SZ"; pass=$((pass+1))
  else
    printf '\033[31m  FAIL\033[0m 切片只有 %s 字节\n' "$SZ"; fail=$((fail+1))
  fi
  FIRST=$(od -An -tx1 -N1 "$W/seg.ts" 2>/dev/null | tr -d ' \n')
  chk "切片是合法 TS" '47' "$FIRST"
else
  printf '\033[31m  FAIL\033[0m 播放列表里没有切片\n'; fail=$((fail+1))
fi

echo
echo "== 无管理员身份应被拒 =="
CODE=$(curl -sS -o /dev/null -w '%{http_code}' --unix-socket "$W/app.sock" "$GWURL/web/play/$CID")
if [ "$CODE" = "401" ] || [ "$CODE" = "403" ]; then
  printf '\033[32m  PASS\033[0m 未认证被拒（HTTP %s）\n' "$CODE"; pass=$((pass+1))
else
  printf '\033[31m  FAIL\033[0m 未认证却返回 %s\n' "$CODE"; fail=$((fail+1))
fi

echo
echo "=============================================="
printf '结果: \033[32m%d 通过\033[0m, \033[31m%d 失败\033[0m\n' "$pass" "$fail"
pkill -x litvd 2>/dev/null
[ "$fail" -eq 0 ] || exit 1
