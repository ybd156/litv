#!/bin/bash
# 判断 Go 超时是「签名失效」还是「客户端问题」
S=/vol1/@appcenter/litv/app.sock
g() { curl -sS --unix-socket "$S" -H "X-Trim-Userid:1" -H "X-Trim-Username:admin" -H "X-Trim-Isadmin:true" "$@"; }

U=$(g "http://localhost/app/litv/api/v1/web/play/132" | python3 -c 'import json,sys;print(json.load(sys.stdin)["data"]["url"])')
g "http://localhost$U" > /tmp/err.json

echo "=== 从 Go 报错里抽出上游地址 ==="
python3 - <<'PY'
import json, re
d = json.load(open('/tmp/err.json'))
msg = d.get('msg','')
m = re.search(r'Get "(https://[^"]+)"', msg)
if m:
    open('/tmp/errurl.txt','w').write(m.group(1))
    print("  抽到:", m.group(1)[:120])
else:
    print("  没抽到地址:", msg[:120])
PY

EURL=$(cat /tmp/errurl.txt 2>/dev/null)
UA=okHttp/Mod-1.5.0.0

if [ -n "$EURL" ]; then
  echo
  echo "=== curl Go 报错里那个地址（带 UA）==="
  curl -sS -m 30 -A "$UA" "$EURL" -o /tmp/e1 -w "  http=%{http_code} size=%{size_download} time=%{time_total}s\n" 2>&1 | tail -2
  head -c 120 /tmp/e1 2>/dev/null; echo
fi

echo
echo "=== 当前 BestStream 会选哪条（health 排序）==="
python3 - <<'PY'
import sqlite3
c = sqlite3.connect('/vol1/@appdata/litv/data/litv.db')
rows = c.execute("""
  SELECT s.id, s.health, s.priority, s.latency_ms, s.url
  FROM streams s JOIN channels ch ON ch.id=s.channel_id
  WHERE ch.name LIKE '%cctv1-MCP%'
  ORDER BY CASE s.health WHEN 1 THEN 0 WHEN -1 THEN 1 ELSE 2 END, s.priority, s.latency_ms, s.id
""").fetchall()
for r in rows:
    print(f"  stream={r[0]} health={r[1]} prio={r[2]} lat={r[3]}")
    print(f"    {r[4][:100]}")
PY
