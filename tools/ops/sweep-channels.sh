#!/bin/bash
# 抽查 N 个频道，统计实际可播比例
# 连接参数全部走环境变量，不写死任何本机路径：
#   LITV_SOCK  litvd 的 unix socket（必填）
#   LITV_DB    SQLite 数据库路径（需要查库的脚本必填）
#   LITV_BASE  HTTP 基地址，默认 http://localhost/app/litv/api/v1
S="${LITV_SOCK:?请设置 LITV_SOCK（litvd 的 unix socket 路径）}"
B="${LITV_BASE:-http://localhost/app/litv/api/v1}"
g() { curl -sS --unix-socket "$S" -H "X-Trim-Userid:1" -H "X-Trim-Username:admin" -H "X-Trim-Isadmin:true" "$@"; }


python3 - <<'PY'
import os, sqlite3
c = sqlite3.connect(os.environ['LITV_DB'])
rows = c.execute("SELECT id, name FROM channels ORDER BY id LIMIT 60").fetchall()
open('/tmp/sweep.tsv','w').write('\n'.join(f"{r[0]}\t{r[1]}" for r in rows))
print(f"  抽查 {len(rows)} 个频道")
PY

ok=0; bad=0; n=0
while IFS=$'\t' read -r CID NAME; do
  n=$((n+1))
  U=$(g "$B/web/play/$CID" 2>/dev/null | python3 -c 'import json,sys
try:
    print(json.load(sys.stdin)["data"]["url"])
except Exception:
    print("")' 2>/dev/null)
  if [ -z "$U" ]; then bad=$((bad+1)); printf "  %-3s %-22s 无线路\n" "$CID" "$NAME"; continue; fi
  SEG=$(g "http://localhost$U" 2>/dev/null | grep -v '^#' | grep -v '^$' | head -1)
  if [ -z "$SEG" ]; then bad=$((bad+1)); printf "  %-3s %-22s 清单失败\n" "$CID" "$NAME"; continue; fi
  SZ=$(g "http://localhost$SEG" -o /dev/null -w "%{size_download}" 2>/dev/null)
  if [ "${SZ:-0}" -gt 50000 ]; then
    ok=$((ok+1)); printf "  %-3s %-22s OK %s 字节\n" "$CID" "$NAME" "$SZ"
  else
    bad=$((bad+1)); printf "  %-3s %-22s 分片只有 %s 字节\n" "$CID" "$NAME" "${SZ:-0}"
  fi
done < /tmp/sweep.tsv

echo
echo "=============================================="
echo "  可播 $ok / $n ，失败 $bad"
