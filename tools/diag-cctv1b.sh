#!/bin/bash
# 查明 CCTV1 清单拉取失败的真实原因
S=/vol1/@appcenter/litv/app.sock
g() { curl -sS --unix-socket "$S" -H "X-Trim-Userid:1" -H "X-Trim-Username:admin" -H "X-Trim-Isadmin:true" "$@"; }

U=$(cat /tmp/url.txt)
echo "=== 完整错误 ==="
g "http://localhost$U" | python3 -c 'import json,sys;print(json.load(sys.stdin)["msg"])'
echo

echo "=== 频道 5 的线路 URL + UA（从数据库读）==="
DB=$(find /vol1/@appdata/litv -name "*.db" 2>/dev/null | head -1)
echo "  数据库: $DB"
python3 - "$DB" <<'PY'
import sqlite3, sys
db = sys.argv[1]
c = sqlite3.connect(db)
row = c.execute("SELECT url, headers_json FROM streams WHERE channel_id=5 LIMIT 1").fetchone()
if row:
    print("  url:", row[0][:110])
    print("  headers:", (row[1] or "")[:120])
else:
    print("  没有线路")
PY
echo

echo "=== 服务器直接 curl 该 URL（带 UA / 不带 UA）==="
URL=$(python3 - "$DB" <<'PY'
import sqlite3, sys
c = sqlite3.connect(sys.argv[1])
r = c.execute("SELECT url FROM streams WHERE channel_id=5 LIMIT 1").fetchone()
print(r[0] if r else "")
PY
)
UA=$(python3 - "$DB" <<'PY'
import sqlite3, sys, json
c = sqlite3.connect(sys.argv[1])
r = c.execute("SELECT headers_json FROM streams WHERE channel_id=5 LIMIT 1").fetchone()
try:
    print(json.loads(r[0]).get("User-Agent","") if r and r[0] else "")
except Exception:
    print("")
PY
)
echo "  UA=$UA"
echo "  -- 带 UA --"
curl -sS -m 20 -A "$UA" "$URL" -o /tmp/up1.txt -w "    http=%{http_code} size=%{size_download} time=%{time_total}s\n" 2>&1 | tail -2
head -c 150 /tmp/up1.txt 2>/dev/null; echo
echo "  -- 不带 UA --"
curl -sS -m 20 "$URL" -o /tmp/up2.txt -w "    http=%{http_code} size=%{size_download} time=%{time_total}s\n" 2>&1 | tail -2
head -c 150 /tmp/up2.txt 2>/dev/null; echo
