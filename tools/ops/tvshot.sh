#!/usr/bin/env bash
# 用 D:\Android 上的 TV 模拟器（Android 9 / armeabi-v7a）截图 + 操作
#
# ★ 截图必须走「设备内 screencap 落盘 + adb pull」。
#   adb exec-out 经过 WSL 的 Windows 互操作会把二进制截断，拿到的 PNG 是坏的。
set -uo pipefail

ADB=/mnt/d/Android/sdk/platform-tools/adb.exe
DEV=${LITV_DEV:-192.168.250.11:5555}
OUT=/mnt/d/Documents/deepseek-harness/default-workspace/LITV2/build/shots
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
