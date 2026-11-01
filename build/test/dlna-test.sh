#!/usr/bin/env bash
# DLNA 验证：设备描述 → SSDP 发现 → 浏览分组 → 浏览频道 → 拿到可播地址
#
# 模拟的是一个 DLNA 客户端（电视自带播放器 / Kodi）：
#   1. 发 M-SEARCH 组播找设备
#   2. 拉 desc.xml 看服务地址
#   3. SOAP Browse ObjectID=0 列分组
#   4. Browse 某分组 列频道，拿到 res URL
#   5. 拉那个 URL，确认能出 HLS
set -uo pipefail

PORT=19611
W=/tmp/litv-dlna
BASE="http://127.0.0.1:$PORT"
GWURL="http://localhost/app/litv/api/v1"
BIN=${BIN:-/tmp/litvd}
FF=${FF:-$HOME/tools/ffmpeg/ffmpeg}
G="curl -sS --unix-socket $W/app.sock -H X-Trim-Userid:1000 -H X-Trim-Username:admin -H X-Trim-Isadmin:true"

pass=0; fail=0
chk() { if printf '%s' "$3" | grep -q "$2"; then printf '\033[32m  PASS\033[0m %s\n' "$1"; pass=$((pass+1));
        else printf '\033[31m  FAIL\033[0m %s\n        期望: %s\n        实际: %s\n' "$1" "$2" "${3:0:220}"; fail=$((fail+1)); fi; }

[ -x "$FF" ] || { echo "缺少 ffmpeg"; exit 2; }

pkill -x litvd 2>/dev/null
pkill -f "http[.]server" 2>/dev/null
sleep 0.5
rm -rf "$W"; mkdir -p "$W/cache" "$W/www"

echo "== 生成测试流 =="
"$FF" -hide_banner -loglevel error \
  -f lavfi -i "testsrc2=size=320x180:rate=15" -f lavfi -i "sine=frequency=440" \
  -t 60 -c:v libx264 -preset ultrafast -g 30 -c:a aac -b:a 48k \
  -f mpegts "$W/www/live.ts" 2>&1 | tail -2

cat > "$W/config.yaml" <<EOF
server:
  host: "0.0.0.0"
  port: $PORT
  gateway_socket: "$W/app.sock"
  prefix: "/app/litv"
database:
  path: "$W/litv.db"
auth:
  secret: "dlna-secret"
stream:
  cache_dir: "$W/cache"
probe:
  enabled: false
timeshift:
  enabled: false
remux:
  enabled: true
  segment_seconds: 2
  playlist_size: 5
  idle_timeout_seconds: 30
record:
  enabled: false
dlna:
  enabled: true
  friendly_name: "LITV 测试"
  advertise_ip: "127.0.0.1"
transcode:
  enabled: false
  ffmpeg: "$FF"
EOF

( cd "$W/www" && setsid nohup python3 -m http.server 18093 >/dev/null 2>&1 & )
setsid nohup "$BIN" -config "$W/config.yaml" > "$W/run.log" 2>&1 < /dev/null &
sleep 2
curl -sS -o /dev/null "$BASE/api/v1/health" || { echo "服务端没起来"; tail -5 "$W/run.log"; exit 1; }

echo
echo "== 建两个分组的频道 =="
for g in 央视频道 卫视频道; do
  CID=$($G -X POST -H "Content-Type: application/json" \
     -d "{\"name\":\"$g-测试台\",\"group\":\"$g\",\"enabled\":true}" "$GWURL/channels" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
  $G -X POST -H "Content-Type: application/json" \
     -d "{\"url\":\"http://127.0.0.1:18093/live.ts\",\"quality\":\"本地\",\"priority\":0}" \
     "$GWURL/channels/$CID/streams" >/dev/null
  echo "  $g -> 频道 $CID"
done

echo
echo "== 1. 设备描述 =="
DESC=$(curl -sS -m 10 "$BASE/dlna/desc.xml")
chk "是 MediaServer" 'urn:schemas-upnp-org:device:MediaServer:1' "$DESC"
chk "有友好名称" 'LITV 测试' "$DESC"
chk "声明 ContentDirectory" 'ContentDirectory:1' "$DESC"
chk "描述地址可推导" '/dlna/cds.xml' "$DESC"

echo
echo "== 2. SSDP 发现（M-SEARCH）=="
python3 - "$PORT" <<'PY' > "$W/ssdp.txt" 2>&1
import socket, sys, time
port = sys.argv[1]
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2)
s.settimeout(3)
msg = ("M-SEARCH * HTTP/1.1\r\n"
       "HOST: 239.255.255.250:1900\r\n"
       'MAN: "ssdp:discover"\r\n'
       "MX: 2\r\n"
       "ST: urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n")
s.sendto(msg.encode(), ("239.255.255.250", 1900))
try:
    data, addr = s.recvfrom(4096)
    print("RESPONSE_FROM", addr)
    print(data.decode("utf-8", "replace"))
except socket.timeout:
    print("TIMEOUT")
PY
if grep -q "RESPONSE_FROM" "$W/ssdp.txt"; then
  printf '\033[32m  PASS\033[0m SSDP 收到应答\n'; pass=$((pass+1))
  grep -q "LOCATION" "$W/ssdp.txt" && { printf '\033[32m  PASS\033[0m 应答含 LOCATION\n'; pass=$((pass+1)); } || { printf '\033[31m  FAIL\033[0m 应答无 LOCATION\n'; fail=$((fail+1)); }
else
  printf '\033[33m  SKIP\033[0m SSDP 组播在 WSL 里可能不通（%s）\n' "$(head -1 "$W/ssdp.txt")"
fi

echo
echo "== 3. Browse 根目录（应列出分组）=="
BROWSE='<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1"><ObjectID>0</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter><StartingIndex>0</StartingIndex><RequestedCount>100</RequestedCount><SortCriteria></SortCriteria></u:Browse></s:Body></s:Envelope>'
R=$(curl -sS -m 10 -X POST -H 'Content-Type: text/xml; charset="utf-8"' \
  -H 'SOAPACTION: "urn:schemas-upnp-org:service:ContentDirectory:1#Browse"' \
  -d "$BROWSE" "$BASE/dlna/cds/control")
chk "返回 BrowseResponse" 'BrowseResponse' "$R"
chk "含央视频道分组" '央视频道' "$R"
chk "含卫视频道分组" '卫视频道' "$R"

echo
echo "== 4. Browse 分组（应列出频道与播放地址）=="
BROWSE2='<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1"><ObjectID>group:央视频道</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter><StartingIndex>0</StartingIndex><RequestedCount>100</RequestedCount><SortCriteria></SortCriteria></u:Browse></s:Body></s:Envelope>'
R=$(curl -sS -m 10 -X POST -H 'Content-Type: text/xml; charset="utf-8"' \
  -H 'SOAPACTION: "urn:schemas-upnp-org:service:ContentDirectory:1#Browse"' \
  -d "$BROWSE2" "$BASE/dlna/cds/control")
chk "含频道名" '央视频道-测试台' "$R"
chk "含 videoItem 类型" 'object.item.videoItem' "$R"
chk "含 HLS 协议声明" 'application/vnd.apple.mpegurl' "$R"

# 从 SOAP 结果里抠出 res 地址。
# 注意：DIDL 先被转义一次塞进 <Result>，外层 SOAP 又转义一次，
# 所以这里的 & 是 &amp;amp;，要反转义两遍才能拿到真 URL（真实客户端由 XML 解析器处理）。
RES=$(printf '%s' "$R" | grep -oE 'http://127\.0\.0\.1:'"$PORT"'/live/[^<]*' | head -1 \
  | sed 's/&amp;/\&/g; s/&amp;/\&/g')
echo "  播放地址: ${RES:0:95}"

echo
echo "== 5. 拉 DLNA 给出的播放地址 =="
if [ -n "$RES" ]; then
  PL=$(curl -sS -m 25 "$RES")
  chk "DLNA 地址能出 m3u8" '#EXTM3U' "$PL"
else
  printf '\033[31m  FAIL\033[0m 没解析出播放地址\n'; fail=$((fail+1))
fi

echo
echo "== 6. ConnectionManager GetProtocolInfo =="
CMS='<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:GetProtocolInfo xmlns:u="urn:schemas-upnp-org:service:ConnectionManager:1"></u:GetProtocolInfo></s:Body></s:Envelope>'
R=$(curl -sS -m 10 -X POST -H 'Content-Type: text/xml; charset="utf-8"' \
  -H 'SOAPACTION: "urn:schemas-upnp-org:service:ConnectionManager:1#GetProtocolInfo"' \
  -d "$CMS" "$BASE/dlna/cms/control")
chk "返回协议信息" 'GetProtocolInfoResponse' "$R"

echo
echo "=============================================="
printf '结果: \033[32m%d 通过\033[0m, \033[31m%d 失败\033[0m\n' "$pass" "$fail"
pkill -x litvd 2>/dev/null
pkill -f "http[.]server" 2>/dev/null
[ "$fail" -eq 0 ] || exit 1
