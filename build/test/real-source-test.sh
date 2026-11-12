#!/usr/bin/env bash
# 真实源验证：强制校验 UA 的 HLS 源能不能正常播放 + EPG .xml.gz 能不能解
#
# 用的是用户提供的真实源（带 userid/sign/auth_token），UA 必须完全一致。
# 判断标准：带正确 UA 时上游会 302 到真实频道；UA 不对则 302 到演示视频。
set -uo pipefail

PORT=19641
W=/tmp/litv-real
BIN=${BIN:-/tmp/litvd}
GW="http://localhost/app/litv/api/v1"
G="curl -sS --unix-socket $W/app.sock -H X-Trim-Userid:1000 -H X-Trim-Username:admin -H X-Trim-Isadmin:true"

SRC_URL="https://live.ottiptv.cc/iptv.m3u?userid=8773389203&sign=bcbc82fb50fba265a173d6433465c590e3d4c5b5089b71966fa523abbfa3b6a11f0fa8741d55cfeb22d7f20e8011d4a8404e04ac3db3f318497ee8c28d07081a81a0a4e7ec9d2a&auth_token=383af1942bd2740ee0799b9166b83904"
UA="okHttp/Mod-1.5.0.0"
EPG_URL="https://epg.zsdc.eu.org/t.xml.gz"

pass=0; fail=0
chk() { if printf '%s' "$3" | grep -q "$2"; then printf '\033[32m  PASS\033[0m %s\n' "$1"; pass=$((pass+1));
        else printf '\033[31m  FAIL\033[0m %s\n        期望含: %s\n        实际: %s\n' "$1" "$2" "${3:0:200}"; fail=$((fail+1)); fi; }

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
  secret: "real-test"
stream:
  cache_dir: "$W/cache"
  direct_enabled: true
probe:
  enabled: false
timeshift:
  enabled: false
remux:
  enabled: false
record:
  enabled: false
EOF

setsid nohup "$BIN" -config "$W/config.yaml" > "$W/run.log" 2>&1 < /dev/null &
sleep 2
curl -sS -o /dev/null "http://127.0.0.1:$PORT/api/v1/health" || { echo "服务端没起来"; tail -5 "$W/run.log"; exit 1; }

echo "== 1. 建源（带 UA）=="
SID=$($G -X POST -H "Content-Type: application/json" \
  -d "{\"name\":\"UA强校验源\",\"type\":\"m3u\",\"url\":\"$SRC_URL\",\"user_agent\":\"$UA\"}" \
  "$GW/sources" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
echo "  源 id=$SID"

echo
echo "== 2. 导入 =="
R=$($G -X POST "$GW/sources/$SID/refresh")
chk "解析出频道" '"parsed":[1-9]' "$R"
echo "  $R"

echo
echo "== 3. UA 落进线路 =="
HJ=$(python3 -c "
import sqlite3
c=sqlite3.connect('$W/litv.db')
r=c.execute('SELECT headers_json FROM streams LIMIT 1').fetchone()
print(r[0] if r else '')
")
chk "headers_json 带正确 UA" 'okHttp/Mod-1.5.0.0' "$HJ"

echo
echo "== 4. 建终端并批准 =="
REG=$($G -X POST -H "Content-Type: application/json" \
  -d '{"device_key":"dk-real","name":"测试机","platform":"android"}' "$GW/device/register")
TOKEN=$(printf '%s' "$REG" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
DID=$(printf '%s' "$REG" | sed -n 's/.*"device_id":\([0-9]*\).*/\1/p')
[ -n "$DID" ] && $G -X POST "$GW/devices/$DID/approve" >/dev/null
CID=$(printf '%s' "$($G "$GW/channels")" | sed -n 's/.*"id":\([0-9]*\).*/\1/p' | head -1)
echo "  频道 id=$CID"

echo
echo "== 5. 取播放地址 =="
P=$(curl -sS -m 10 -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:$PORT/api/v1/tv/play/$CID")
chk "带回 UA" 'okHttp/Mod-1.5.0.0' "$P"
URL=$(printf '%s' "$P" | sed -n 's/.*"url":"\([^"]*\)".*/\1/p' | sed 's|\\/|/|g; s|\\u0026|\&|g')

echo
echo "== 6. 服务端中转拉清单（关键）=="
M=$(curl -sS -m 30 --unix-socket "$W/app.sock" -H "X-Trim-Userid: 1000" \
  -H "X-Trim-Username: admin" -H "X-Trim-Isadmin: true" "http://localhost$URL")
chk "拿到 m3u8" '#EXTM3U' "$M"
# 关键判断：UA 对了上游给真实频道；不对会给 jsdelivr 的演示视频
if printf '%s' "$M" | grep -q "jsdelivr.net"; then
  printf '\033[31m  FAIL\033[0m 拿到的是演示视频（UA 没生效）\n'; fail=$((fail+1))
else
  printf '\033[32m  PASS\033[0m 不是演示视频（UA 生效）\n'; pass=$((pass+1))
fi
if printf '%s' "$M" | grep -q "5gtv.com.cn\|alicdn\|\.ts"; then
  printf '\033[32m  PASS\033[0m 指向真实流\n'; pass=$((pass+1))
else
  printf '\033[33m  WARN\033[0m 清单里没看到预期域名\n'
fi

echo
echo "== 7. 经服务端拉一个分片 =="
SEG=$(printf '%s' "$M" | grep -v '^#' | grep -v '^$' | head -1)
if [ -n "$SEG" ]; then
  SZ=$(curl -sS -m 25 --unix-socket "$W/app.sock" -H "X-Trim-Userid: 1000" \
    -H "X-Trim-Username: admin" -H "X-Trim-Isadmin: true" "http://localhost$SEG" -o "$W/seg.bin" -w "%{size_download}")
  if [ "${SZ:-0}" -gt 5000 ]; then
    printf '\033[32m  PASS\033[0m 分片 %s 字节\n' "$SZ"; pass=$((pass+1))
  else
    printf '\033[31m  FAIL\033[0m 分片只有 %s 字节\n' "$SZ"; fail=$((fail+1))
  fi
else
  printf '\033[31m  FAIL\033[0m 清单里没有分片\n'; fail=$((fail+1))
fi

echo
echo "== 8. EPG .xml.gz 能不能解 =="
E=$($G -X POST -H "Content-Type: application/json" \
  -d "{\"name\":\"EPG测试\",\"url\":\"$EPG_URL\"}" "$GW/epg/sources")
chk "建 EPG 源成功" '"id"' "$E"
RF=$($G -X POST "$GW/epg/refresh")
echo "  $RF"
chk "拉回节目单频道数 > 0" '"channels":[1-9]' "$RF"

echo
echo "=============================================="
printf '结果: \033[32m%d 通过\033[0m, \033[31m%d 失败\033[0m\n' "$pass" "$fail"
pkill -x litvd 2>/dev/null
[ "$fail" -eq 0 ] || exit 1
