#!/bin/bash
# 看那些 6410 字节的响应到底是什么
S=/vol1/@appcenter/litv/app.sock
g() { curl -sS --unix-socket "$S" -H "X-Trim-Userid:1" -H "X-Trim-Username:admin" -H "X-Trim-Isadmin:true" "$@"; }
B=http://localhost/app/litv/api/v1

for CID in 41 43 48; do
  echo "=== 频道 $CID ==="
  U=$(g "$B/web/play/$CID" | python3 -c 'import json,sys;print(json.load(sys.stdin)["data"]["url"])')
  SEG=$(g "http://localhost$U" | grep -v '^#' | grep -v '^$' | head -1)
  g "http://localhost$SEG" -o /tmp/x.bin -w "  分片 http=%{http_code} size=%{size_download} type=%{content_type}\n"
  echo "  前 120 字节:"
  head -c 120 /tmp/x.bin | od -c | head -4
  echo
  echo "  --- 上游线路地址 ---"
  python3 - "$CID" <<'PY'
import sqlite3, sys
c = sqlite3.connect('/vol1/@appdata/litv/data/litv.db')
r = c.execute("SELECT url FROM streams WHERE channel_id=? LIMIT 1", (sys.argv[1],)).fetchone()
print("   ", (r[0][:110] if r else "无"))
PY
  echo
done
