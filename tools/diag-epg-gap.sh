#!/bin/bash
python3 - <<'PY'
import sqlite3
c = sqlite3.connect('/vol1/@appdata/litv/data/litv.db')
un = [r[0] for r in c.execute("SELECT name FROM channels WHERE epg_id='' LIMIT 25")]
ep = [r[0] for r in c.execute("SELECT display_name FROM epg_channels LIMIT 25")]
print("未匹配的频道（前 25）：")
print("  ", " / ".join(un))
print()
print("EPG 里的频道名（前 25）：")
print("  ", " / ".join(ep))
PY
