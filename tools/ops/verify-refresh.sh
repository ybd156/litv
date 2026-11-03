#!/bin/bash
# 验证刷新是否原地更新线路（不再重复追加）
S=/vol1/@appcenter/litv/app.sock
DB=/vol1/@appdata/litv/data/litv.db
g() { curl -sS --unix-socket "$S" -H "X-Trim-Userid:1" -H "X-Trim-Username:admin" -H "X-Trim-Isadmin:true" "$@"; }

count() {
  python3 - "$DB" <<'PY'
import sqlite3, sys
c = sqlite3.connect(sys.argv[1])
n = c.execute("SELECT COUNT(*) FROM streams").fetchone()[0]
dup = c.execute("""
  SELECT COUNT(*) FROM (
    SELECT channel_id, COUNT(*) c FROM streams GROUP BY channel_id HAVING c > 1
  )
""").fetchone()[0]
print(f"  线路总数={n}  有重复线路的频道={dup}")
PY
}

echo "=== 刷新前 ==="
count

echo "=== 刷新源 1 ==="
g -X POST "http://localhost/app/litv/api/v1/sources/1/refresh" | head -c 120; echo

echo "=== 刷新后 ==="
count

echo "=== 再刷一次 ==="
g -X POST "http://localhost/app/litv/api/v1/sources/1/refresh" | head -c 120; echo
count
