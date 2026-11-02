#!/bin/bash
# 刷新源（拿新签名）后重测 CCTV1
S=/vol1/@appcenter/litv/app.sock
g() { curl -sS --unix-socket "$S" -H "X-Trim-Userid:1" -H "X-Trim-Username:admin" -H "X-Trim-Isadmin:true" "$@"; }
B=http://localhost/app/litv/api/v1

echo "=== 刷新源 1 ==="
g -X POST "$B/sources/1/refresh" | head -c 200; echo
echo

echo "=== 刷新后 CCTV1 的线路 ==="
python3 - <<'PY'
import sqlite3, json
c = sqlite3.connect('/vol1/@appdata/litv/data/litv.db')
rows = c.execute("""
  SELECT s.id, s.url, s.health, ch.name
  FROM streams s JOIN channels ch ON ch.id=s.channel_id
  WHERE ch.name LIKE '%CCTV1%'
""").fetchall()
for r in rows:
    print(f"  stream={r[0]} health={r[2]} {r[3]}")
    print(f"    {r[1][:120]}")
PY
echo

echo "=== 重测 web/play → 清单 → 分片 ==="
CID=$(python3 -c "
import sqlite3
c=sqlite3.connect('/vol1/@appdata/litv/data/litv.db')
r=c.execute(\"SELECT id FROM channels WHERE name LIKE '%CCTV1%' LIMIT 1\").fetchone()
print(r[0] if r else '')
")
echo "  频道 id=$CID"
U=$(g "$B/web/play/$CID" | python3 -c 'import json,sys;print(json.load(sys.stdin)["data"]["url"])')
g "http://localhost$U" -o /tmp/m2.m3u8 -w "  清单 http=%{http_code} size=%{size_download}\n"
head -c 160 /tmp/m2.m3u8; echo
SEG=$(grep -v '^#' /tmp/m2.m3u8 2>/dev/null | grep -v '^$' | head -1)
if [ -n "$SEG" ]; then
  g "http://localhost$SEG" -o /tmp/s2.ts -w "  分片 http=%{http_code} size=%{size_download}\n"
else
  echo "  没有分片"
fi
