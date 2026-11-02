#!/bin/bash
# 看重复频道的两条线路，路径是否相同
python3 - <<'PY'
import sqlite3
c = sqlite3.connect('/vol1/@appdata/litv/data/litv.db')
rows = c.execute("""
  SELECT channel_id, COUNT(*) n FROM streams GROUP BY channel_id HAVING n>1 LIMIT 3
""").fetchall()
for cid, n in rows:
    print(f"=== 频道 {cid}（{n} 条）===")
    for sid, u in c.execute("SELECT id,url FROM streams WHERE channel_id=?", (cid,)):
        path = u.split('?')[0]
        print(f"  id={sid}  path={path}")
    print()
# 统计：路径相同 vs 不同
same = 0; diff = 0
for cid, in c.execute("SELECT DISTINCT channel_id FROM streams GROUP BY channel_id HAVING COUNT(*)>1"):
    paths = [u.split('?')[0] for (u,) in c.execute("SELECT url FROM streams WHERE channel_id=?", (cid,))]
    if len(set(paths)) < len(paths):
        same += 1
    else:
        diff += 1
print(f"汇总：同路径重复 {same} 个频道，不同路径（真多线路）{diff} 个频道")
PY
