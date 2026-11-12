#!/usr/bin/env bash
# 源级 UA + 直连播放 验证
#
# 验的是一条完整链路：
#   建源（带 UA）→ 导入 → UA 落进线路 headers_json → 抓取时真的带了 UA
#   → tvPlay 直连模式返回上游地址 → 中转模式返回站内地址
set -uo pipefail

PORT=19621
UP=18092
W=/tmp/litv-uadirect
BIN=${BIN:-/tmp/litvd}
GW="http://localhost/app/litv/api/v1"
UA="MyIPTV/9.9 (TestAgent)"
G="curl -sS --unix-socket $W/app.sock -H X-Trim-Userid:1000 -H X-Trim-Username:admin -H X-Trim-Isadmin:true"

pass=0; fail=0
chk() { if printf '%s' "$3" | grep -q "$2"; then printf '\033[32m  PASS\033[0m %s\n' "$1"; pass=$((pass+1));
        else printf '\033[31m  FAIL\033[0m %s\n        期望含: %s\n        实际: %s\n' "$1" "$2" "${3:0:220}"; fail=$((fail+1)); fi; }

pkill -x litvd 2>/dev/null
pkill -f "http[.]server" 2>/dev/null
sleep 0.5
rm -rf "$W"; mkdir -p "$W/cache" "$W/www"

# 上游：记下收到的 UA，顺便提供一个 m3u
cat > "$W/www/ua.py" <<'PY'
import http.server, socketserver, sys, json
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        ua = self.headers.get('User-Agent', '')
        open('/tmp/litv-uadirect/seen_ua.txt', 'a').write(ua + "\n")
        if self.path.startswith('/a.m3u'):
            body = b"#EXTM3U\n#EXTINF:-1,UA\xe6\xb5\x8b\xe8\xaf\x95\xe5\x8f\xb0\nhttp://127.0.0.1:18092/live.ts\n"
            self.send_response(200); self.send_header('Content-Type', 'audio/x-mpegurl')
            self.send_header('Content-Length', str(len(body))); self.end_headers()
            self.wfile.write(body)
        else:
            self.send_response(200); self.send_header('Content-Type', 'video/mp2t')
            self.end_headers(); self.wfile.write(b'\x47' * 2048)
    def log_message(self, *a): pass
socketserver.TCPServer.allow_reuse_address = True
socketserver.TCPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
PY

cat > "$W/config.yaml" <<EOF
server:
  host: "127.0.0.1"
  port: $PORT
  gateway_socket: "$W/app.sock"
  prefix: "/app/litv"
database:
  path: "$W/litv.db"
auth:
  secret: "ua-secret"
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

setsid nohup python3 "$W/www/ua.py" "$UP" >/dev/null 2>&1 < /dev/null &
setsid nohup "$BIN" -config "$W/config.yaml" > "$W/run.log" 2>&1 < /dev/null &
sleep 2
curl -sS -o /dev/null "http://127.0.0.1:$PORT/api/v1/health" || { echo "服务端没起来"; tail -5 "$W/run.log"; exit 1; }

echo "== 1. 建带 UA 的源 =="
SID=$($G -X POST -H "Content-Type: application/json" \
  -d "{\"name\":\"UA源\",\"type\":\"m3u\",\"url\":\"http://127.0.0.1:$UP/a.m3u\",\"user_agent\":\"$UA\"}" \
  "$GW/sources" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
echo "  源 id=$SID"
R=$($G "$GW/sources")
chk "源保存了 UA" "$UA" "$R"

echo
echo "== 2. 刷新源（抓取时应带 UA）=="
$G -X POST "$GW/sources/$SID/refresh" >/dev/null
sleep 0.5
chk "上游收到了我们配的 UA" "$UA" "$(cat "$W/seen_ua.txt" 2>/dev/null)"

echo
echo "== 3. UA 落进线路 headers_json =="
HJ=$(python3 -c "
import sqlite3
c=sqlite3.connect('$W/litv.db')
r=c.execute('SELECT headers_json FROM streams LIMIT 1').fetchone()
print(r[0] if r else '')
")
chk "headers_json 含 User-Agent" 'User-Agent' "$HJ"
chk "headers_json 里就是那个 UA" 'MyIPTV' "$HJ"

echo
echo "== 4. 建终端并批准 =="
DK="dev-ua-test"
REG=$($G -X POST -H "Content-Type: application/json" \
  -d "{\"device_key\":\"$DK\",\"name\":\"UA测试机\",\"platform\":\"android\"}" "$GW/device/register")
# 注意：register 会**立刻返回 token**，但设备状态是 pending，
# 拿这个 token 调接口仍然会被拒（2003）。必须先在管理台批准。
TOKEN=$(printf '%s' "$REG" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
DID=$(printf '%s' "$REG" | sed -n 's/.*"device_id":\([0-9]*\).*/\1/p')
[ -n "$DID" ] && $G -X POST "$GW/devices/$DID/approve" >/dev/null
CID=$(printf '%s' "$($G "$GW/channels")" | sed -n 's/.*"id":\([0-9]*\).*/\1/p' | head -1)
echo "  频道 id=$CID 设备 id=$DID token=${TOKEN:0:12}..."

echo
echo "== 5. 中转模式（默认）=="
REL=$(curl -sS -m 8 -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:$PORT/api/v1/tv/play/$CID")
chk "mode=relay" '"mode":"relay"' "$REL"
chk "地址是站内路径" '/app/litv/live/' "$REL"
chk "带回 UA" '"ua":"MyIPTV' "$REL"

echo
echo "== 6. 直连模式 =="
DIR=$(curl -sS -m 8 -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:$PORT/api/v1/tv/play/$CID?direct=1")
chk "mode=direct" '"mode":"direct"' "$DIR"
# 直连给的是**线路本身**的上游地址（m3u 里那条 = /live.ts），不是播放列表地址
chk "给的是上游地址" "127.0.0.1:$UP/" "$DIR"
if printf '%s' "$DIR" | grep -q '"/app/litv/live/'; then
  printf '\033[31m  FAIL\033[0m 直连不该返回站内路径\n'; fail=$((fail+1))
else
  printf '\033[32m  PASS\033[0m 直连没返回站内路径\n'; pass=$((pass+1))
fi
chk "直连也带回 UA" '"ua":"MyIPTV' "$DIR"

echo
echo "== 7. 关掉直连开关后应回落到中转 =="
pkill -x litvd 2>/dev/null; sleep 0.5
sed -i 's/direct_enabled: true/direct_enabled: false/' "$W/config.yaml"
setsid nohup "$BIN" -config "$W/config.yaml" > "$W/run2.log" 2>&1 < /dev/null &
sleep 2
DIR2=$(curl -sS -m 8 -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:$PORT/api/v1/tv/play/$CID?direct=1")
chk "服务端禁止直连时回落 relay" '"mode":"relay"' "$DIR2"

echo
echo "=============================================="
printf '结果: \033[32m%d 通过\033[0m, \033[31m%d 失败\033[0m\n' "$pass" "$fail"
pkill -x litvd 2>/dev/null
pkill -f "http[.]server" 2>/dev/null
pkill -f "ua[.]py" 2>/dev/null
[ "$fail" -eq 0 ] || exit 1
