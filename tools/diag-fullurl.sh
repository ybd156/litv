#!/bin/bash
S=/vol1/@appcenter/litv/app.sock
g() { curl -sS --unix-socket "$S" -H "X-Trim-Userid:1" -H "X-Trim-Username:admin" -H "X-Trim-Isadmin:true" "$@"; }

echo "=== 库里完整 URL ==="
python3 - <<'PY'
import sqlite3
c = sqlite3.connect('/vol1/@appdata/litv/data/litv.db')
r = c.execute("""SELECT s.url FROM streams s JOIN channels ch ON ch.id=s.channel_id
                 WHERE ch.name LIKE '%cctv1-MCP%' LIMIT 1""").fetchone()
u = r[0]
open('/tmp/fullurl.txt','w').write(u)
print(u)
print()
print("  长度:", len(u))
print("  含空格:", ' ' in u)
print("  含 +:", '+' in u)
print("  含 %:", '%' in u)
import urllib.parse as p
print("  解析出的 query 键:", sorted(p.parse_qs(p.urlparse(u).query).keys()))
PY

echo
echo "=== Go 的完整报错 ==="
U=$(g "http://localhost/app/litv/api/v1/web/play/132" | python3 -c 'import json,sys;print(json.load(sys.stdin)["data"]["url"])')
g "http://localhost$U" | python3 -c 'import json,sys;d=json.load(sys.stdin);print(d.get("msg",""))'
