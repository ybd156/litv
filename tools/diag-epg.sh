#!/bin/bash
# 查服务器上的 EPG 数据情况
S=/vol1/@appcenter/litv/app.sock
H1="X-Trim-Userid:1"; H2="X-Trim-Username:admin"; H3="X-Trim-Isadmin:true"
g() { curl -sS --unix-socket "$S" -H "$H1" -H "$H2" -H "$H3" "$@"; }
B=http://localhost/app/litv/api/v1

echo "=== EPG 源 ==="
g "$B/epg/sources" | head -c 300
echo
echo
echo "=== 数据库里的节目数 ==="
python3 - <<'PY'
import sqlite3
c = sqlite3.connect('/vol1/@appdata/litv/data/litv.db')
try:
    n = c.execute("SELECT COUNT(*) FROM programs").fetchone()[0]
    print("  programs 行数:", n)
except Exception as e:
    print("  查不到 programs:", e)
try:
    m = c.execute("SELECT COUNT(*) FROM channels WHERE epg_id IS NOT NULL AND epg_id<>''").fetchone()[0]
    t = c.execute("SELECT COUNT(*) FROM channels").fetchone()[0]
    print(f"  有 epg_id 的频道: {m}/{t}")
except Exception as e:
    print("  查频道失败:", e)
try:
    rows = c.execute("SELECT xmltv_id, COUNT(*) FROM programs GROUP BY xmltv_id LIMIT 5").fetchall()
    for r in rows:
        print("   ", r[0], r[1])
except Exception as e:
    pass
PY
