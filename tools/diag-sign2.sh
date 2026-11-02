#!/bin/bash
# 对比：数据库里刷新后的地址 vs 重新从 M3U 拉一次拿到的地址
DB=/vol1/@appdata/litv/data/litv.db
UA=okHttp/Mod-1.5.0.0

echo "=== 库里存的地址 ==="
URL=$(python3 - <<'PY'
import sqlite3
c = sqlite3.connect('/vol1/@appdata/litv/data/litv.db')
r = c.execute("""SELECT s.url FROM streams s JOIN channels ch ON ch.id=s.channel_id
                 WHERE ch.name LIKE '%cctv1-MCP%' LIMIT 1""").fetchone()
print(r[0] if r else '')
PY
)
echo "  ${URL:0:110}"
curl -sS -m 20 -A "$UA" "$URL" -o /tmp/a1 -w "  curl: http=%{http_code} size=%{size_download} time=%{time_total}s\n"
head -c 100 /tmp/a1; echo

echo
echo "=== 重新拉一次源 M3U，取 cctv1-MCP 的最新地址 ==="
SRC=$(python3 - <<'PY'
import sqlite3
c = sqlite3.connect('/vol1/@appdata/litv/data/litv.db')
r = c.execute("SELECT url FROM sources WHERE id=1").fetchone()
print(r[0] if r else '')
PY
)
curl -sS -m 25 -A "$UA" "$SRC" -o /tmp/src.m3u -w "  M3U: http=%{http_code} size=%{size_download}\n"
NEW=$(grep -A1 -i "cctv1-MCP" /tmp/src.m3u | grep -oE "https://[^ ]+" | head -1)
echo "  ${NEW:0:110}"
if [ -n "$NEW" ]; then
  curl -sS -m 20 -A "$UA" "$NEW" -o /tmp/a2 -w "  curl: http=%{http_code} size=%{size_download} time=%{time_total}s\n"
  head -c 100 /tmp/a2; echo
fi
