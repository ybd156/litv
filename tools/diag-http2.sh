#!/bin/bash
# 对比：Go 客户端的错误 vs NAS 上 curl 直连
S=/vol1/@appcenter/litv/app.sock
g() { curl -sS --unix-socket "$S" -H "X-Trim-Userid:1" -H "X-Trim-Username:admin" -H "X-Trim-Isadmin:true" "$@"; }
DB=/vol1/@appdata/litv/data/litv.db

echo "=== Go 的完整报错 ==="
U=$(g "http://localhost/app/litv/api/v1/web/play/132" | python3 -c 'import json,sys;print(json.load(sys.stdin)["data"]["url"])')
g "http://localhost$U" | python3 -c 'import json,sys;print("  "+json.load(sys.stdin)["msg"])'
echo

echo "=== cctv1-MCP 的线路地址 ==="
python3 - <<'PY'
import sqlite3, json
c = sqlite3.connect('/vol1/@appdata/litv/data/litv.db')
r = c.execute("""SELECT s.url, s.headers_json FROM streams s
                 JOIN channels ch ON ch.id=s.channel_id
                 WHERE ch.name LIKE '%cctv1-MCP%' LIMIT 1""").fetchone()
url, hj = r
ua = json.loads(hj or '{}').get('User-Agent','')
open('/tmp/url5.txt','w').write(url)
open('/tmp/ua5.txt','w').write(ua)
print("  url:", url[:110])
print("  UA:", ua)
PY

URL=$(cat /tmp/url5.txt)
UA=$(cat /tmp/ua5.txt)

echo
echo "=== NAS 上 curl（带 UA，HTTP/2）==="
curl -sS -m 30 -A "$UA" "$URL" -o /tmp/c1 -w "  http=%{http_code} size=%{size_download} time=%{time_total}s\n" 2>&1 | tail -2
head -c 130 /tmp/c1 2>/dev/null; echo

echo
echo "=== NAS 上 curl（强制 HTTP/1.1）==="
curl -sS -m 30 --http1.1 -A "$UA" "$URL" -o /tmp/c2 -w "  http=%{http_code} size=%{size_download} time=%{time_total}s\n" 2>&1 | tail -2
head -c 130 /tmp/c2 2>/dev/null; echo

echo
echo "=== 域名解析 ==="
getent hosts live.ottiptv.cc | head -2
