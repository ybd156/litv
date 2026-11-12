#!/usr/bin/env bash
# 源失效告警验证：起一个本地 webhook 接收器 → 造一个整台挂掉的频道
# → 手动探测 → 校验 webhook 真的收到了告警
set -uo pipefail

PORT=19591
HOOKPORT=18096
W=/tmp/litv-alert
BASE="http://127.0.0.1:$PORT"
GWURL="http://localhost/app/litv/api/v1"
BIN=${BIN:-/tmp/litvd}
G="curl -sS --unix-socket $W/app.sock -H X-Trim-Userid:1000 -H X-Trim-Username:admin -H X-Trim-Isadmin:true"

pass=0; fail=0
chk() { if printf '%s' "$3" | grep -q "$2"; then printf '\033[32m  PASS\033[0m %s\n' "$1"; pass=$((pass+1));
        else printf '\033[31m  FAIL\033[0m %s\n        期望: %s\n        实际: %s\n' "$1" "$2" "${3:0:200}"; fail=$((fail+1)); fi; }

pkill -x litvd 2>/dev/null
# 清掉上一轮残留的静态服务，否则端口被占会静默失败（表现为上游 404）
pkill -f "http[.]server" 2>/dev/null
sleep 0.5
rm -rf "$W"; mkdir -p "$W/cache" "$W/hook"

# ---- 本地 webhook 接收器 ----
cat > "$W/hook/srv.py" <<'PY'
import http.server, json, sys
class H(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        n = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(n).decode('utf-8', 'replace')
        with open('/tmp/litv-alert/hook/received.jsonl', 'a') as f:
            f.write(body + "\n")
        self.send_response(200); self.end_headers(); self.wfile.write(b'{"ok":true}')
    def log_message(self, *a): pass
http.server.HTTPServer(('127.0.0.1', 18096), H).serve_forever()
PY
setsid nohup python3 "$W/hook/srv.py" >/dev/null 2>&1 < /dev/null &
sleep 1
curl -sS -o /dev/null -X POST -H "Content-Type: application/json" -d '{"ping":1}' \
  "http://127.0.0.1:$HOOKPORT/hook" && echo "webhook 接收器就绪"

cat > "$W/config.yaml" <<EOF
server:
  host: "127.0.0.1"
  port: $PORT
  gateway_socket: "$W/app.sock"
  prefix: "/app/litv"
database:
  path: "$W/litv.db"
auth:
  secret: "alert-secret"
stream:
  cache_dir: "$W/cache"
probe:
  enabled: false
timeshift:
  enabled: false
remux:
  enabled: false
record:
  enabled: false
alert:
  enabled: true
  webhook_url: "http://127.0.0.1:$HOOKPORT/hook"
  min_interval_minutes: 1
EOF

setsid nohup "$BIN" -config "$W/config.yaml" > "$W/run.log" 2>&1 < /dev/null &
sleep 2
curl -sS -o /dev/null "$BASE/api/v1/health" || { echo "服务端没起来"; tail -5 "$W/run.log"; exit 1; }

echo
echo "== 造一个整台挂掉的频道（指向不存在的端口）=="
CID=$($G -X POST -H "Content-Type: application/json" \
   -d '{"name":"坏台","group":"告警测试","enabled":true}' "$GWURL/channels" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
$G -X POST -H "Content-Type: application/json" \
   -d '{"url":"http://127.0.0.1:19999/dead.ts","quality":"死链","priority":0}' \
   "$GWURL/channels/$CID/streams" >/dev/null
echo "频道 id=$CID"

echo
echo "== 探测前：dead-channels 应为空 =="
R=$($G "$GWURL/alerts/dead-channels")
chk "探测前没有死台" '"count":0' "$R"
chk "告警已启用" '"alert_enabled":true' "$R"

echo
echo "== 手动探测（应触发告警）=="
R=$($G -X POST "$GWURL/system/probe")
chk "探测执行" '"checked"' "$R"

sleep 2
echo
echo "== 探测后：dead-channels 应列出坏台 =="
R=$($G "$GWURL/alerts/dead-channels")
chk "列出死台" '"count":1' "$R"
chk "死台带频道名" '"name":"坏台"' "$R"

echo
echo "== webhook 是否收到 =="
if [ -f "$W/hook/received.jsonl" ]; then
  echo "  收到 $(wc -l < "$W/hook/received.jsonl") 条"
  BODY=$(grep -v '"ping"' "$W/hook/received.jsonl" | tail -1)
  chk "webhook 内容含 source=litv" '"source":"litv"' "$BODY"
  chk "webhook 内容含坏台名" '坏台' "$BODY"
  echo "  告警正文: $(printf '%s' "$BODY" | head -c 160)"
else
  printf '\033[31m  FAIL\033[0m webhook 没收到任何请求\n'; fail=$((fail+1))
fi

echo
echo "== 去重：立刻再探测一次，不应重复发 =="
BEFORE=$(grep -vc '"ping"' "$W/hook/received.jsonl" 2>/dev/null || echo 0)
$G -X POST "$GWURL/system/probe" >/dev/null
sleep 2
AFTER=$(grep -vc '"ping"' "$W/hook/received.jsonl" 2>/dev/null || echo 0)
if [ "$BEFORE" = "$AFTER" ]; then
  printf '\033[32m  PASS\033[0m 1 分钟内未重复告警（去重生效）\n'; pass=$((pass+1))
else
  printf '\033[31m  FAIL\033[0m 重复告警了（%s -> %s）\n' "$BEFORE" "$AFTER"; fail=$((fail+1))
fi

echo
echo "=============================================="
printf '结果: \033[32m%d 通过\033[0m, \033[31m%d 失败\033[0m\n' "$pass" "$fail"
pkill -x litvd 2>/dev/null
[ "$fail" -eq 0 ] || exit 1
