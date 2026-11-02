#!/usr/bin/env bash
# 让模拟器里的 LITV 连上 WSL 里的服务端，跳过手动输入
#
# 做法：
#   1. WSL 起 litvd（监听 0.0.0.0:9527）
#   2. 通过网关接口注册一个设备并批准，拿到 token
#   3. 用 adb run-as（debug 包可写）直接写 SharedPreferences
#   4. 重启 App -> 直接进首页
set -uo pipefail

ADB=/mnt/d/Android/sdk/platform-tools/adb.exe
DEV=${LITV_DEV:-emulator-5554}
PKG=com.litv.tv.debug
W=/tmp/litv-emu
REPO=/mnt/d/Documents/deepseek-harness/default-workspace/LITV2
OUT=$REPO/build/shots
mkdir -p "$OUT"

a() { "$ADB" -s "$DEV" "$@" 2>/dev/null | tr -d '\r'; }

shot() {
    a shell screencap -p /sdcard/s.png >/dev/null
    "$ADB" -s "$DEV" pull /sdcard/s.png "$OUT/$1.png" >/dev/null 2>&1
    echo "$OUT/$1.png"
}

# ---------- 1. 服务端 ----------
pkill -x litvd 2>/dev/null; sleep 0.5
rm -rf "$W"; mkdir -p "$W/cache"

# 造一个本地测试源（两路 HLS，模拟真实直播）
mkdir -p "$W/www"
cat > "$W/www/a.m3u" <<'EOF'
#EXTM3U
#EXTINF:-1 group-title="央视",CCTV1 综合
http://127.0.0.1:18077/live1.m3u8
#EXTINF:-1 group-title="央视",CCTV2 财经
http://127.0.0.1:18077/live2.m3u8
#EXTINF:-1 group-title="卫视",湖南卫视
http://127.0.0.1:18077/live3.m3u8
#EXTINF:-1 group-title="卫视",浙江卫视
http://127.0.0.1:18077/live4.m3u8
EOF

cat > "$W/config.yaml" <<EOF
server:
  host: "0.0.0.0"
  port: 9527
  gateway_socket: "$W/app.sock"
  prefix: "/app/litv"
database:
  path: "$W/litv.db"
auth:
  secret: "emu-test"
stream:
  cache_dir: "$W/cache"
probe:
  enabled: false
timeshift:
  enabled: false
remux:
  enabled: false
record:
  enabled: false
EOF

( cd "$W/www" && setsid nohup python3 -m http.server 18077 >/dev/null 2>&1 & )
setsid nohup /tmp/litvd -config "$W/config.yaml" > "$W/run.log" 2>&1 < /dev/null &
sleep 2

GW="http://localhost/app/litv/api/v1"
G="curl -sS --unix-socket $W/app.sock -H X-Trim-Userid:1000 -H X-Trim-Username:admin -H X-Trim-Isadmin:true"

# ---------- 2. 导入频道 + 注册设备 ----------
SID=$($G -X POST -H "Content-Type: application/json" \
  -d "{\"name\":\"模拟器测试源\",\"type\":\"m3u\",\"url\":\"http://127.0.0.1:18077/a.m3u\"}" \
  "$GW/sources" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
$G -X POST "$GW/sources/$SID/refresh" >/dev/null
echo "源 id=$SID 已导入"

REG=$($G -X POST -H "Content-Type: application/json" \
  -d '{"device_key":"emu-tv","name":"TV 模拟器","platform":"androidtv","model":"MiTV"}' \
  "$GW/device/register")
TOKEN=$(printf '%s' "$REG" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
DID=$(printf '%s' "$REG" | sed -n 's/.*"device_id":\([0-9]*\).*/\1/p')
[ -n "$DID" ] && $G -X POST "$GW/devices/$DID/approve" >/dev/null
echo "设备 id=$DID 已批准"

# ---------- 3. 模拟器能访问的地址 ----------
WSLIP=$(ip -4 addr show eth0 2>/dev/null | grep -oP 'inet \K[\d.]+' | head -1)
echo "WSL IP=$WSLIP"

# ---------- 4. 写 SharedPreferences ----------
PREFS="/data/data/$PKG/shared_prefs/${PKG}_prefs.xml"
a shell am force-stop $PKG >/dev/null
a shell "run-as $PKG mkdir -p /data/data/$PKG/shared_prefs" >/dev/null
cat > /tmp/prefs.xml <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="server_base">http://$WSLIP:9527</string>
    <string name="token">$TOKEN</string>
    <long name="device_id" value="$DID" />
    <string name="device_name">TV 模拟器</string>
</map>
EOF
# run-as 无法直接接管道，先推到 /sdcard 再拷
"$ADB" -s "$DEV" push /tmp/prefs.xml /sdcard/prefs.xml >/dev/null 2>&1
a shell "run-as $PKG sh -c 'cat /sdcard/prefs.xml > $PREFS'" >/dev/null

echo "=== 校验写入 ==="
a shell "run-as $PKG cat $PREFS" | head -8

# ---------- 5. 重启 App ----------
a shell am start -n $PKG/com.litv.tv.ui.LauncherActivity >/dev/null
sleep 6
a shell dumpsys activity activities | grep -m1 mResumedActivity
shot 02-home
