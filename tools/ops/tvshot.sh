#!/usr/bin/env bash
# 对已连接的设备/模拟器截图 + 按键操作
# 用法：LITV_DEV=192.168.1.10:5555 bash tools/ops/tvshot.sh
#
# ★ 截图必须走「设备内 screencap 落盘 + adb pull」。
#   adb exec-out 经过 WSL 的 Windows 互操作会把二进制截断，拿到的 PNG 是坏的。
#
# ★★ 但在小米 EA55 2022（Amlogic T5 / Android 9）上，`screencap` **抓不到视频层**：
#    视频走独立的硬件显示管线，不经 SurfaceFlinger 合成，截出来是黑屏或最后一帧叠加。
#      · 想判断「画面是不是卡住了」——screencap 会骗你（两张一模一样的黑图/冻图）
#      · 那种机型上要用硬件回采：ybd156/xiaomi-ea55-2022-tv-screen-stream（:8899/snapshot）
#    设了 LITV_STREAM=<ip:8899> 时，本脚本优先用硬件回采；并附 --stall 判断卡顿。
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

# 可选：硬件回采服务的地址（ybd156/xiaomi-ea55-2022-tv-screen-stream）
STREAM="${LITV_STREAM:-}"
STREAM_URL=""
[ -n "$STREAM" ] && STREAM_URL="http://${STREAM}"

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
    # 有硬件回采就用它：screencap 在 Amlogic 机型上拿不到视频层
    if [ -n "$STREAM_URL" ]; then
        curl -s --max-time 10 "$STREAM_URL/snapshot" -o "$OUT/$name.jpg" 2>/dev/null
        local szj
        szj=$(stat -c %s "$OUT/$name.jpg" 2>/dev/null || echo 0)
        if [ "$szj" -gt 1000 ]; then
            echo "$OUT/$name.jpg  (${szj} 字节，硬件回采)"
            return
        fi
        echo "（硬件回采失败，回落 screencap —— 注意视频层可能是黑的）" >&2
    fi
    a shell screencap -p /sdcard/_litv_shot.png >/dev/null
    "$ADB" -s "$DEV" pull /sdcard/_litv_shot.png "$OUT/$name.png" >/dev/null 2>&1
    a shell rm -f /sdcard/_litv_shot.png >/dev/null
    local sz
    sz=$(stat -c %s "$OUT/$name.png" 2>/dev/null || echo 0)
    echo "$OUT/$name.png  (${sz} 字节)"
}

# 判断「画面有没有在动」：间隔 N 秒抓两帧硬件回采，比字节数和 sha256。
# screencap 在 Amlogic 上做不到这件事（永远给你同一张黑图）。
stall_check() {
    local gap="${1:-3}"
    [ -n "$STREAM_URL" ] || {
        echo "需要 LITV_STREAM=<ip:8899>：screencap 分不出「冻结」和「抓不到视频层」" >&2
        return 1
    }
    local f1="$OUT/.stall_a.jpg" f2="$OUT/.stall_b.jpg"
    curl -s --max-time 10 "$STREAM_URL/snapshot" -o "$f1" || return 1
    sleep "$gap"
    curl -s --max-time 10 "$STREAM_URL/snapshot" -o "$f2" || return 1
    local s1 s2 h1 h2
    s1=$(sha256sum "$f1" | cut -d' ' -f1); s2=$(sha256sum "$f2" | cut -d' ' -f1)
    echo "帧 1: $(stat -c %s "$f1") 字节"
    echo "帧 2: $(stat -c %s "$f2") 字节"
    if [ "$s1" = "$s2" ]; then
        echo "→ 两帧完全相同：画面**冻结**（真卡住）"
        return 1
    fi
    echo "→ 两帧不同：画面在动（没卡）"
    rm -f "$f1" "$f2"
    return 0
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
    --stall)
        # 判断画面有没有在动。必须走硬件回采（见文件头说明）。
        stall_check "${2:-3}"
        ;;
    --stats)
        [ -n "$STREAM_URL" ] || { echo "需要 LITV_STREAM=<ip:8899>" >&2; exit 1; }
        curl -s --max-time 8 "$STREAM_URL/stats"
        ;;
    *)
        shot "${1:-now}"
        ;;
esac
