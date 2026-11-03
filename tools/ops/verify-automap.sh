#!/bin/bash
# 触发 EPG 同步（会自动匹配频道），然后看匹配率
S=/vol1/@appcenter/litv/app.sock
H1="X-Trim-Userid:1"; H2="X-Trim-Username:admin"; H3="X-Trim-Isadmin:true"
g() { curl -sS --unix-socket "$S" -H "$H1" -H "$H2" -H "$H3" "$@"; }

echo "=== 触发 EPG 同步 ==="
g -X POST "http://localhost/app/litv/api/v1/epg/refresh" | head -c 200
echo
echo

echo "=== 匹配结果 ==="
python3 - <<'PY'
import sqlite3
c = sqlite3.connect('/vol1/@appdata/litv/data/litv.db')
m = c.execute("SELECT COUNT(*) FROM channels WHERE epg_id<>''").fetchone()[0]
t = c.execute("SELECT COUNT(*) FROM channels").fetchone()[0]
print(f"  已映射频道: {m}/{t}")
for r in c.execute("SELECT name, epg_id FROM channels WHERE epg_id<>'' LIMIT 5"):
    print("   ", r)
PY
