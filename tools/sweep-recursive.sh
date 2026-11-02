#!/bin/bash
# 递归扫描：主清单 → （可能是子清单）→ 真正的分片
# 之前的扫描把子清单当分片量，所以嵌套的台全被误判为失败。
S=/vol1/@appcenter/litv/app.sock
g() { curl -sS --unix-socket "$S" -H "X-Trim-Userid:1" -H "X-Trim-Username:admin" -H "X-Trim-Isadmin:true" "$@"; }
B=http://localhost/app/litv/api/v1

python3 - <<'PY'
import sqlite3
c = sqlite3.connect('/vol1/@appdata/litv/data/litv.db')
rows = c.execute("SELECT id,name FROM channels ORDER BY id LIMIT 30").fetchall()
open('/tmp/sw2.tsv','w').write('\n'.join(f"{r[0]}\t{r[1]}" for r in rows))
PY

ok=0; bad=0; n=0
while IFS=$'\t' read -r CID NAME; do
  n=$((n+1))
  U=$(g "$B/web/play/$CID" 2>/dev/null | python3 -c "import json,sys
try: print(json.load(sys.stdin)['data']['url'])
except Exception: print('')")
  [ -z "$U" ] && { bad=$((bad+1)); printf "  %-3s %-20s 无地址\n" "$CID" "$NAME"; continue; }

  # 第一层清单
  L1=$(g "http://localhost$U" 2>/dev/null)
  if [ -z "$L1" ]; then bad=$((bad+1)); printf "  %-3s %-20s 清单失败\n" "$CID" "$NAME"; continue; fi
  SEG=$(echo "$L1" | grep -v '^#' | grep -v '^$' | head -1)

  # 取一次，看是分片还是子清单
  SZ=$(g "http://localhost$SEG" -o /tmp/lvl.bin -w "%{size_download}" 2>/dev/null)
  if head -c 7 /tmp/lvl.bin 2>/dev/null | grep -q "#EXTM3U"; then
    # 是子清单，再往下一层
    SEG2=$(grep -v '^#' /tmp/lvl.bin | grep -v '^$' | head -1)
    if [ -n "$SEG2" ]; then
      SZ=$(g "http://localhost$SEG2" -o /tmp/lvl2.bin -w "%{size_download}" 2>/dev/null)
    fi
  fi

  if [ "${SZ:-0}" -gt 20000 ]; then
    ok=$((ok+1)); printf "  %-3s %-20s OK %s 字节\n" "$CID" "$NAME" "$SZ"
  else
    bad=$((bad+1)); printf "  %-3s %-20s 失败 %s 字节\n" "$CID" "$NAME" "${SZ:-0}"
  fi
done < /tmp/sw2.tsv

echo
echo "=============================================="
echo "  可播 $ok / $n ，失败 $bad"
