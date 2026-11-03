#!/bin/bash
# 批准所有待批准的设备（模拟器测试用）
S=/vol1/@appcenter/litv/app.sock
H1="X-Trim-Userid:1"; H2="X-Trim-Username:admin"; H3="X-Trim-Isadmin:true"
g() { curl -sS --unix-socket "$S" -H "$H1" -H "$H2" -H "$H3" "$@"; }
B=http://localhost/app/litv/api/v1

echo "=== 待批准设备 ==="
g "$B/devices?status=pending" > /tmp/pend.json
python3 - <<'PY'
import json
d = json.load(open('/tmp/pend.json'))['data']
items = d if isinstance(d, list) else d.get('items', [])
print(f"  共 {len(items)} 台")
for x in items:
    print(f"  id={x['id']} name={x.get('name')} model={x.get('model')} key={x.get('device_key','')[:12]}")
open('/tmp/ids.txt','w').write('\n'.join(str(x['id']) for x in items))
PY

echo "=== 逐个批准 ==="
while read -r id; do
  [ -z "$id" ] && continue
  g -X POST "$B/devices/$id/approve" | head -c 80; echo
done < /tmp/ids.txt
