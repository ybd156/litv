#!/usr/bin/env bash
# =============================================================================
# 安装静态 ffmpeg（WSL，免 root）
#
# 为什么用静态版而不是 apt：
#   apt 的 ffmpeg 拖几十个 .so 依赖，解包后还得拼 LD_LIBRARY_PATH；
#   johnvansickle 的静态构建是单文件，扔进 tools/ 就能用，
#   将来打进 fpk 也最省事（一个可执行文件，无依赖）。
#
# 用途：裸 TS → HLS 转封装（iOS 的 AVPlayer 只认 HLS）。
# =============================================================================
set -uo pipefail

DEST="$HOME/tools/ffmpeg"
ZIP="$HOME/tools/ffmpeg.tar.xz"
URL="https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz"

say() { printf '\n\033[36m==> %s\033[0m\n' "$*"; }
ok()  { printf '\033[32m[ok]\033[0m %s\n' "$*"; }
err() { printf '\033[31m[err]\033[0m %s\n' "$*"; }

if [ -x "$DEST/ffmpeg" ]; then
  ok "ffmpeg 已就绪: $("$DEST/ffmpeg" -version 2>&1 | head -1)"
  exit 0
fi

mkdir -p "$DEST" "$HOME/tools"
say "下载静态 ffmpeg（约 30MB）"
# 国内直连慢，走代理（WSL2 要用宿主网关 IP，不是 127.0.0.1）
if [ -z "${https_proxy:-}" ]; then
  HOSTIP="$(ip route show default | awk '{print $3}' | head -1)"
  export https_proxy="http://$HOSTIP:10808"
  export http_proxy="$https_proxy"
fi
curl -fL --connect-timeout 30 -m 1800 --progress-bar -o "$ZIP" "$URL" || {
  err "下载失败（可以手动下载后放到 $ZIP）"; exit 1; }

say "解压"
cd "$HOME/tools" || exit 1
tar xJf "$ZIP" || { err "解压失败"; exit 1; }
SRC="$(find "$HOME/tools" -maxdepth 1 -type d -name 'ffmpeg-*-static' | head -1)"
[ -n "$SRC" ] || { err "没找到解压目录"; exit 1; }
cp -f "$SRC/ffmpeg" "$SRC/ffprobe" "$DEST/" 2>/dev/null
chmod +x "$DEST"/*
ok "ffmpeg: $("$DEST/ffmpeg" -version 2>&1 | head -1)"
echo "路径: $DEST/ffmpeg"
echo "在 config.yaml 里配置： transcode.ffmpeg: \"$DEST/ffmpeg\""
