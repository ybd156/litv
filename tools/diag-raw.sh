#!/bin/bash
S=/vol1/@appcenter/litv/app.sock
H1="X-Trim-Userid:1"; H2="X-Trim-Username:admin"; H3="X-Trim-Isadmin:true"

P=$(curl -sS --unix-socket "$S" -H "$H1" -H "$H2" -H "$H3" http://localhost/app/litv/api/v1/web/play/132)
echo "--- web/play 原始响应（前 200 字）---"
echo "$P" | head -c 200
echo
U=$(echo "$P" | python3 -c "import json,sys
try:
    print(json.load(sys.stdin)['data']['url'])
except Exception as e:
    print('')")
if [ -z "$U" ]; then echo "拿不到播放地址，退出"; exit 1; fi
echo "--- 清单原始响应 ---"
curl -sS --unix-socket "$S" -H "$H1" -H "$H2" -H "$H3" "http://localhost$U" -w "\n[http=%{http_code}]\n" | tail -c 500
