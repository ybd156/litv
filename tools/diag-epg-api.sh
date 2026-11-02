#!/bin/bash
S=/vol1/@appcenter/litv/app.sock
H1="X-Trim-Userid:1"; H2="X-Trim-Username:admin"; H3="X-Trim-Isadmin:true"
g() { curl -sS --unix-socket "$S" -H "$H1" -H "$H2" -H "$H3" "$@"; }

echo "=== 频道 5（CCTV1综合）的 epg_id ==="
python3 - <<'PY'
import sqlite3
c = sqlite3.connect('/vol1/@appdata/litv/data/litv.db')
r = c.execute("SELECT id,name,epg_id FROM channels WHERE id=5").fetchone()
print("  ", r)
if r and r[2]:
    n = c.execute("""SELECT COUNT(*) FROM epg_programs p
                     JOIN epg_channels ec ON ec.id=p.epg_channel_id
                     WHERE ec.xmltv_id=?""", (r[2],)).fetchone()[0]
    print("   该频道节目数:", n)
    for row in c.execute("""SELECT p.start_at,p.end_at,p.title FROM epg_programs p
                            JOIN epg_channels ec ON ec.id=p.epg_channel_id
                            WHERE ec.xmltv_id=? ORDER BY p.start_at LIMIT 3""", (r[2],)):
        print("    ", row)
PY

echo
echo "=== /tv/epg/5 接口返回 ==="
g "http://localhost/app/litv/api/v1/tv/epg/5?day=0" | head -c 400
