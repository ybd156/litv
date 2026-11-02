#!/bin/bash
python3 - <<'PY'
import sqlite3
c = sqlite3.connect('/vol1/@appdata/litv/data/litv.db')
tables = [r[0] for r in c.execute("SELECT name FROM sqlite_master WHERE type='table'")]
print("表:", tables)
for t in tables:
    if 'prog' in t or 'epg' in t:
        try:
            n = c.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0]
            print(f"  {t}: {n} 行")
            cols = [d[1] for d in c.execute(f"PRAGMA table_info({t})")]
            print(f"    列: {cols}")
        except Exception as e:
            print("  ", t, e)
# 频道 tvg_id 情况
try:
    n = c.execute("SELECT COUNT(*) FROM channels WHERE tvg_id<>''").fetchone()[0]
    print(f"  有 tvg_id 的频道: {n}/223")
    for r in c.execute("SELECT name, tvg_id, epg_id FROM channels WHERE tvg_id<>'' LIMIT 5"):
        print("   ", r)
except Exception as e:
    print("  频道查询失败:", e)
PY
