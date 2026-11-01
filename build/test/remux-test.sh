#!/usr/bin/env bash
# 裸 TS → HLS 转封装验证
#
# 为什么要这个功能：iOS 的 AVPlayer 播不了裸 MPEG-TS，只认 HLS。
# 国内大量 IPTV 源是裸 TS，所以服务端必须能"只转封装不转码"地吐 HLS。
#
# 本测试用 ffmpeg 自己生成一段合法 TS 当上游，再让服务端把它转成 HLS，
# 校验播放列表结构与切片内容。
set -uo pipefail

PORT=19571
W=/tmp/litv-remux
BASE="http://127.0.0.1:$PORT"
GWURL="http://localhost/app/litv/api/v1"
BIN=${BIN:-/tmp/litvd}
FF=${FF:-$HOME/tools/ffmpeg/ffmpeg}
G="curl -sS --unix-socket $W/app.sock -H X-Trim-Userid:1000 -H X-Trim-Username:admin -H X-Trim-Isadmin:true"

pass=0; fail=0
chk() { if printf '%s' "$3" | grep -q "$2"; then printf '\033[32m  PASS\033[0m %s\n' "$1"; pass=$((pass+1));
        else printf '\033[31m  FAIL\033[0m %s\n        期望: %s\n        实际: %s\n' "$1" "$2" "${3:0:220}"; fail=$((fail+1)); fi; }

if [ ! -x "$FF" ]; then
  echo "缺少 ffmpeg: $FF"
  echo "先跑：tools/setup-ffmpeg-wsl.sh"
  exit 2
fi

pkill -x litvd 2>/dev/null
# 清掉上一轮残留的静态服务，否则端口被占会静默失败（表现为上游 404）
pkill -f "http[.]server" 2>/dev/null
sleep 0.5
rm -rf "$W"; mkdir -p "$W/cache" "$W/www"

echo "== 生成测试用 TS（60 秒，H.264 + AAC）=="
"$FF" -hide_banner -loglevel error \
  -f lavfi -i "testsrc2=size=640x360:rate=25" \
  -f lavfi -i "sine=frequency=440:sample_rate=48000" \
  -t 60 -c:v libx264 -preset ultrafast -tune zerolatency -g 50 \
  -c:a aac -b:a 64k -f mpegts "$W/www/live.ts" 2>&1 | tail -3
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
  secret: "remux-secret"
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
transcode:
  enabled: false
  ffmpeg: "$FF"
EOF

( cd "$W/www" && setsid nohup python3 -m http.server 18097 >/dev/null 2>&1 & )
setsid nohup "$BIN" -config "$W/config.yaml" > "$W/run.log" 2>&1 < /dev/null &
sleep 2
curl -sS -o /dev/null "$BASE/api/v1/health" || { echo "服务端没起来"; cat "$W/run.log"; exit 1; }

echo
echo "== 建频道 =="
CID=$($G -X POST -H "Content-Type: application/json" \
   -d '{"name":"转封装测试台","group":"测试","enabled":true}' "$GWURL/channels" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
$G -X POST -H "Content-Type: application/json" \
   -d "{\"url\":\"http://127.0.0.1:18097/live.ts\",\"quality\":\"本地TS\",\"priority\":0}" \
   "$GWURL/channels/$CID/streams" >/dev/null
echo "频道 id=$CID"

SUF=$(date +%s)
REG=$(curl -sS -X POST -H "Content-Type: application/json" \
  -d "{\"device_key\":\"rx$SUF\",\"name\":\"iOS模拟\",\"platform\":\"ios\"}" "$BASE/api/v1/device/register")
TOK=$(printf '%s' "$REG" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
DID=$(printf '%s' "$REG" | sed -n 's/.*"device_id":\([0-9]*\).*/\1/p')
$G -X POST "$GWURL/devices/$DID/approve" >/dev/null

echo
echo "== 请求 HLS（fmt=hls）=="
PLAY=$(curl -sS -H "Authorization: Bearer $TOK" "$BASE/api/v1/tv/play/$CID?fmt=hls")
chk "返回 kind=remux" '"kind":"remux"' "$PLAY"
PURL=$(printf '%s' "$PLAY" | sed -n 's/.*"url":"\([^"]*\)".*/\1/p' | sed 's|\\/|/|g; s|\\u0026|\&|g')
echo "  播放地址: ${PURL:0:100}"

echo
echo "== 拉播放列表（首次会拉起 ffmpeg，可能要等几秒）=="
PL=$(curl -sS -m 25 "http://127.0.0.1:$PORT$PURL")
chk "是合法 m3u8" '#EXTM3U' "$PL"
chk "有切片条目" '#EXTINF' "$PL"
chk "切片地址已改写" "/remux/seg" "$PL"
SEG=$(printf '%s' "$PL" | grep -v '^#' | grep -v '^$' | head -1)
echo "  首个切片: ${SEG:0:100}"

echo
echo "== 拉切片 =="
if [ -n "$SEG" ]; then
  curl -sS -m 15 -o "$W/seg.ts" "http://127.0.0.1:$PORT$SEG"
  SZ=$(stat -c %s "$W/seg.ts" 2>/dev/null || echo 0)
  if [ "${SZ:-0}" -gt 1000 ]; then
    printf '\033[32m  PASS\033[0m 切片 %s 字节\n' "$SZ"; pass=$((pass+1))
  else
    printf '\033[31m  FAIL\033[0m 切片只有 %s 字节\n' "$SZ"; fail=$((fail+1))
  fi
  # TS 同步字节必须是 0x47
  FIRST=$(od -An -tx1 -N1 "$W/seg.ts" 2>/dev/null | tr -d ' \n')
  chk "切片是合法 TS（首字节 47）" '47' "$FIRST"
else
  printf '\033[31m  FAIL\033[0m 播放列表里没有切片\n'; fail=$((fail+1))
fi

echo
echo "== 非法切片名应被拒 =="
CODE=$(curl -sS -m 5 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/app/litv/live/$CID/remux/..%2f..%2fetc%2fpasswd?$(printf '%s' "$PURL" | sed 's/.*?//')")
if [ "$CODE" = "404" ] || [ "$CODE" = "403" ] || [ "$CODE" = "400" ]; then
  printf '\033[32m  PASS\033[0m 路径穿越被拒（HTTP %s）\n' "$CODE"; pass=$((pass+1))
else
  printf '\033[33m  WARN\033[0m 路径穿越返回 %s（检查是否需要加固）\n' "$CODE"
fi

echo
echo "== 转封装进程 =="
pgrep -af "ffmpeg.*hls" | head -2 | cut -c1-120

echo
echo "=============================================="
printf '结果: \033[32m%d 通过\033[0m, \033[31m%d 失败\033[0m\n' "$pass" "$fail"
pkill -x litvd 2>/dev/null
[ "$fail" -eq 0 ] || exit 1
