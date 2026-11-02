#!/bin/bash
# 在 fnOS 服务器上跑：测 CCTV1 的完整播放链路
S=/vol1/@appcenter/litv/app.sock
H1="X-Trim-Userid:1"
H2="X-Trim-Username:admin"
H3="X-Trim-Isadmin:true"
B=http://localhost/app/litv/api/v1

g() { curl -sS --unix-socket "$S" -H "$H1" -H "$H2" -H "$H3" "$@"; }

echo "=== 1. 找 CCTV1 ==="
g "$B/channels?limit=300" > /tmp/ch.json
python3 - <<'PY'
import json
d = json.load(open('/tmp/ch.json'))['data']['items']
hit = None
for c in d:
    if 'CCTV1' in c['name'] or 'CCTV-1' in c['name']:
        hit = c
        break
print("  频道数", len(d))
if hit:
    print("  选中", hit['id'], hit['name'], hit['group'])
    open('/tmp/cid.txt', 'w').write(str(hit['id']))
else:
    print("  没找到 CCTV1")
PY

CID=$(cat /tmp/cid.txt 2>/dev/null)
[ -z "$CID" ] && { echo "无 CCTV1，退出"; exit 1; }

echo
echo "=== 2. web/play ==="
g "$B/web/play/$CID" > /tmp/play.json
python3 - <<'PY'
import json
d = json.load(open('/tmp/play.json'))
print("  kind:", d['data'].get('kind'))
print("  url:", d['data'].get('url', '')[:100])
open('/tmp/url.txt', 'w').write(d['data']['url'])
PY

U=$(cat /tmp/url.txt)

echo
echo "=== 3. 拉清单 ==="
g "http://localhost$U" -o /tmp/m.m3u8 -w "  http=%{http_code} size=%{size_download} type=%{content_type}\n"
head -c 200 /tmp/m.m3u8
echo

echo
echo "=== 4. 拉第一个分片 ==="
SEG=$(grep -v '^#' /tmp/m.m3u8 | grep -v '^$' | head -1)
if [ -z "$SEG" ]; then echo "  清单里没有分片"; exit 1; fi
g "http://localhost$SEG" -o /tmp/seg.ts -w "  http=%{http_code} size=%{size_download} type=%{content_type}\n"
echo "  分片前 4 字节:"; od -An -tx1 -N4 /tmp/seg.ts

echo
echo "=== 5. 分片里有没有 TS 同步字节(47) ==="
python3 - <<'PY'
d = open('/tmp/seg.ts','rb').read()
n = d.count(b'\x47')
print(f"  大小 {len(d)}，0x47 出现 {n} 次（TS 包每 188 字节一个同步字节）")
print("  看起来像 TS" if len(d) > 1000 and n > len(d)//400 else "  ⚠ 不像 TS")
PY
