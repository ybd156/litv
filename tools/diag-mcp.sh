#!/bin/bash
# 测 cctv1-MCP（userid=8773389203，好线路）能不能播
S=/vol1/@appcenter/litv/app.sock
g() { curl -sS --unix-socket "$S" -H "X-Trim-Userid:1" -H "X-Trim-Username:admin" -H "X-Trim-Isadmin:true" "$@"; }
B=http://localhost/app/litv/api/v1

python3 - <<'PY'
import sqlite3
c = sqlite3.connect('/vol1/@appdata/litv/data/litv.db')
r = c.execute("SELECT id, name FROM channels WHERE name LIKE '%cctv1-MCP%' LIMIT 1").fetchone()
print(f"  频道 {r[0]} {r[1]}" if r else "  没有 cctv1-MCP")
open('/tmp/cid2.txt','w').write(str(r[0]) if r else '')
PY
CID=$(cat /tmp/cid2.txt)
[ -z "$CID" ] && exit 1

echo "=== web/play ==="
g "$B/web/play/$CID" > /tmp/p2.json
python3 -c 'import json;d=json.load(open("/tmp/p2.json"));print("  kind:",d["data"].get("kind"));open("/tmp/u2.txt","w").write(d["data"]["url"])'
U=$(cat /tmp/u2.txt)

echo "=== 清单 ==="
g "http://localhost$U" -o /tmp/m3.m3u8 -w "  http=%{http_code} size=%{size_download}\n"
head -c 180 /tmp/m3.m3u8; echo

echo "=== 分片 ==="
SEG=$(grep -v '^#' /tmp/m3.m3u8 2>/dev/null | grep -v '^$' | head -1)
if [ -n "$SEG" ]; then
  g "http://localhost$SEG" -o /tmp/s3.ts -w "  http=%{http_code} size=%{size_download} type=%{content_type}\n"
  python3 -c "
d=open('/tmp/s3.ts','rb').read()
n=d.count(b'\x47')
print(f'  大小 {len(d)}，0x47 出现 {n} 次 -> ' + ('TS 数据' if len(d)>10000 and n>len(d)//400 else '不像 TS'))
"
else
  echo "  没有分片"
fi
