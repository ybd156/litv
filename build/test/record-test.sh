#!/usr/bin/env bash
# 录制中心验证：建任务 → 调度器到点开录 → 产出文件 → 能下载 → 能删除
#
# 调度器每 20 秒 tick 一次，所以这个测试会等一会儿。
set -uo pipefail

PORT=19581
W=/tmp/litv-rec
BASE="http://127.0.0.1:$PORT"
GWURL="http://localhost/app/litv/api/v1"
BIN=${BIN:-/tmp/litvd}
FF=${FF:-$HOME/tools/ffmpeg/ffmpeg}
G="curl -sS --unix-socket $W/app.sock -H X-Trim-Userid:1000 -H X-Trim-Username:admin -H X-Trim-Isadmin:true"

pass=0; fail=0
chk() { if printf '%s' "$3" | grep -q "$2"; then printf '\033[32m  PASS\033[0m %s\n' "$1"; pass=$((pass+1));
        else printf '\033[31m  FAIL\033[0m %s\n        期望: %s\n        实际: %s\n' "$1" "$2" "${3:0:200}"; fail=$((fail+1)); fi; }

[ -x "$FF" ] || { echo "缺少 ffmpeg: $FF"; exit 2; }

pkill -x litvd 2>/dev/null
# 清掉上一轮残留的静态服务，否则端口被占会静默失败（表现为上游 404）
pkill -f "http[.]server" 2>/dev/null
sleep 0.5
rm -rf "$W"; mkdir -p "$W/cache" "$W/www" "$W/rec"

echo "== 生成 90 秒测试流 =="
"$FF" -hide_banner -loglevel error \
  -f lavfi -i "testsrc2=size=320x180:rate=15" -f lavfi -i "sine=frequency=440" \
  -t 90 -c:v libx264 -preset ultrafast -g 30 -c:a aac -b:a 48k \
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
  secret: "rec-secret"
stream:
  cache_dir: "$W/cache"
probe:
  enabled: false
timeshift:
  enabled: false
remux:
  enabled: false
record:
  enabled: true
  dir: "$W/rec"
transcode:
  enabled: false
  ffmpeg: "$FF"
EOF

( cd "$W/www" && setsid nohup python3 -m http.server 18098 >/dev/null 2>&1 & )
setsid nohup "$BIN" -config "$W/config.yaml" > "$W/run.log" 2>&1 < /dev/null &
sleep 2
curl -sS -o /dev/null "$BASE/api/v1/health" || { echo "服务端没起来"; tail -5 "$W/run.log"; exit 1; }

echo
echo "== 建频道 =="
CID=$($G -X POST -H "Content-Type: application/json" \
   -d '{"name":"录制测试台","group":"测试","enabled":true}' "$GWURL/channels" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
$G -X POST -H "Content-Type: application/json" \
   -d "{\"url\":\"http://127.0.0.1:18098/live.ts\",\"quality\":\"本地\",\"priority\":0}" \
   "$GWURL/channels/$CID/streams" >/dev/null
echo "频道 id=$CID"

echo
echo "== 列表应可用且 available=true =="
R=$($G "$GWURL/recordings")
chk "录制中心可用" '"available":true' "$R"

echo
echo "== 建录制任务（12 秒）=="
R=$($G -X POST -H "Content-Type: application/json" \
  -d "{\"channel_id\":$CID,\"name\":\"测试录制\",\"duration_sec\":12}" "$GWURL/recordings")
chk "任务已创建" '"id"' "$R"
RID=$(printf '%s' "$R" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
echo "  录制 id=$RID"

echo
echo "== 等调度器开录（最多 45 秒）=="
started=0
for i in $(seq 1 15); do
  sleep 3
  R=$($G "$GWURL/recordings")
  if printf '%s' "$R" | grep -q '"status":"recording"'; then
    printf '\033[32m  PASS\033[0m 第 %d 次轮询时已开录\n' "$i"; pass=$((pass+1)); started=1; break
  fi
  if printf '%s' "$R" | grep -q '"status":"finished"'; then
    printf '\033[32m  PASS\033[0m 已录完（任务短，可能没捕捉到 recording 状态）\n'; pass=$((pass+1)); started=1; break
  fi
done
[ "$started" = "1" ] || { printf '\033[31m  FAIL\033[0m 45 秒内没开录\n'; fail=$((fail+1)); }

echo
echo "== 等录完（12 秒 + 余量）=="
sleep 20
R=$($G "$GWURL/recordings")
chk "状态变为 finished" '"status":"finished"' "$R"
chk "有文件大小" '"size":[1-9]' "$R"

echo
echo "== 下载录制文件 =="
CODE=$(curl -sS -o "$W/dl.ts" -w '%{http_code}' --unix-socket "$W/app.sock" \
  -H "X-Trim-Userid: 1000" -H "X-Trim-Username: admin" -H "X-Trim-Isadmin: true" \
  "http://localhost/app/litv/api/v1/recordings/$RID/file")
SZ=$(stat -c %s "$W/dl.ts" 2>/dev/null || echo 0)
if [ "$CODE" = "200" ] && [ "${SZ:-0}" -gt 1000 ]; then
  printf '\033[32m  PASS\033[0m 下载到 %s 字节\n' "$SZ"; pass=$((pass+1))
else
  printf '\033[31m  FAIL\033[0m 下载失败 HTTP=%s size=%s\n' "$CODE" "$SZ"; fail=$((fail+1))
fi
FIRST=$(od -An -tx1 -N1 "$W/dl.ts" 2>/dev/null | tr -d ' \n')
chk "录制文件是合法 TS（首字节 47）" '47' "$FIRST"

echo
echo "== 磁盘上的文件 =="
ls -lh "$W/rec"/ 2>/dev/null | tail -3

echo
echo "== 删除录制 =="
R=$($G -X DELETE "$GWURL/recordings/$RID")
chk "删除成功" '"deleted":true' "$R"
LEFT=$(ls "$W/rec"/*.ts 2>/dev/null | wc -l)
if [ "$LEFT" -eq 0 ]; then
  printf '\033[32m  PASS\033[0m 文件已随记录删除\n'; pass=$((pass+1))
else
  printf '\033[33m  WARN\033[0m 目录里还剩 %s 个文件\n' "$LEFT"
fi

echo
echo "=============================================="
printf '结果: \033[32m%d 通过\033[0m, \033[31m%d 失败\033[0m\n' "$pass" "$fail"
pkill -x litvd 2>/dev/null
[ "$fail" -eq 0 ] || exit 1
