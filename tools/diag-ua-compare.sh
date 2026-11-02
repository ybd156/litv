#!/bin/bash
# 对比两类频道：/other/ (userid=8773389203) vs /mg/ (userid=1407772004)
# 判断是「UA 没生效」还是「这批线路本身是死的」
S=/vol1/@appcenter/litv/app.sock
DB=/vol1/@appdata/litv/data/litv.db
g() { curl -sS --unix-socket "$S" -H "X-Trim-Userid:1" -H "X-Trim-Username:admin" -H "X-Trim-Isadmin:true" "$@"; }

python3 - "$DB" <<'PY'
import sqlite3, sys, json
c = sqlite3.connect(sys.argv[1])
rows = c.execute("""
  SELECT s.channel_id, ch.name, s.url, s.headers_json
  FROM streams s JOIN channels ch ON ch.id = s.channel_id
""").fetchall()
other = [r for r in rows if '/other/' in r[2]][:2]
mg    = [r for r in rows if '/mg/' in r[2]][:2]
out = []
for tag, group in (('OTHER', other), ('MG', mg)):
    for cid, name, url, hj in group:
        ua = ''
        try:
            ua = (json.loads(hj or '{}') or {}).get('User-Agent', '')
        except Exception:
            pass
        out.append(f"{tag}\t{cid}\t{name}\t{ua}\t{url}")
open('/tmp/cases.tsv', 'w').write('\n'.join(out))
print(f"  样本 {len(out)} 条")
PY

echo
while IFS=$'\t' read -r TAG CID NAME UA URL; do
  echo "=== [$TAG] id=$CID $NAME (UA=$UA) ==="
  echo "  -- 带 UA --"
  R=$(curl -sS -m 25 -A "$UA" "$URL" -o /tmp/o1 -w "%{http_code}|%{size_download}|%{time_total}|%{redirect_url}" 2>&1)
  echo "    $R"
  head -c 110 /tmp/o1 2>/dev/null; echo
  echo "  -- 不带 UA --"
  R2=$(curl -sS -m 25 "$URL" -o /tmp/o2 -w "%{http_code}|%{size_download}|%{time_total}|%{redirect_url}" 2>&1)
  echo "    $R2"
  head -c 110 /tmp/o2 2>/dev/null; echo
  echo
done < /tmp/cases.tsv
