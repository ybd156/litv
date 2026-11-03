#!/usr/bin/env bash
# 对已连接的设备/模拟器截图 + 按键操作
# 用法：LITV_DEV=192.168.1.10:5555 bash tools/ops/tvshot.sh
#
# ★ 截图必须走「设备内 screencap 落盘 + adb pull」。
#   adb exec-out 经过 WSL 的 Windows 互操作会把二进制截断，拿到的 PNG 是坏的。
set -uo pipefail

# adb 从 PATH 或 $ANDROID_HOME 找，不写死本机路径
ADB="${ADB:-$(command -v adb || true)}"
if [ -z "$ADB" ] && [ -n "${ANDROID_HOME:-}" ]; then
  ADB="$ANDROID_HOME/platform-tools/adb"
fi
[ -x "$ADB" ] || { echo "找不到 adb：装 platform-tools 或设置 ANDROID_HOME" >&2; exit 1; }

# 设备地址必须显式给：LITV_DEV=192.168.1.10:5555
DEV="${LITV_DEV:-}"
[ -n "$DEV" ] || { echo "用法：LITV_DEV=<ip:port> bash tools/ops/tvshot.sh" >&2; exit 1; }

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="$ROOT/build/shots"
mkdir -p "$OUT"

a() { "$ADB" -s "$DEV" "$@" 2>/dev/null | tr -d '\r'; }

wake() {
    a shell input keyevent KEYCODE_WAKEUP >/dev/null
    a shell wm dismiss-keyguard >/dev/null
    sleep 1
}

shot() {
    local name="${1:-shot}"
    a shell screencap -p /sdcard/_litv_shot.png >/dev/null
    "$ADB" -s "$DEV" pull /sdcard/_litv_shot.png "$OUT/$name.png" >/dev/null 2>&1
    a shell rm -f /sdcard/_litv_shot.png >/dev/null
    local sz
    sz=$(stat -c %s "$OUT/$name.png" 2>/dev/null || echo 0)
    echo "$OUT/$name.png  (${sz} 字节)"
}

case "${1:-}" in
    --launch)
        wake
        a shell am force-stop com.litv.tv >/dev/null
        a shell am start -n com.litv.tv/com.litv.tv.ui.LauncherActivity >/dev/null
        sleep 4
        shot "${2:-launch}"
        ;;
    --key)
        a shell input keyevent "$2" >/dev/null
        sleep "${3:-1}"
        shot "${4:-key$2}"
        ;;
    --tap)
        a shell input tap "$2" "$3" >/dev/null
        sleep "${4:-1}"
        shot "${5:-tap}"
        ;;
    --install)
        "$ADB" -s "$DEV" install -r -d "$2" 2>&1 | tail -2
        ;;
    --log)
        a logcat -d -t "${2:-300}" | grep -iE "LITV|AndroidRuntime|FATAL" | tail -40
        ;;
    --activity)
        a shell dumpsys activity activities | grep -m2 -E "mResumedActivity|topResumedActivity"
        ;;
    *)
        shot "${1:-now}"
        ;;
esac
