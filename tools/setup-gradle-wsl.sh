#!/usr/bin/env bash
# =============================================================================
# 下载 Gradle 并生成 wrapper（WSL，免 root）
#
# 为什么不用系统包：apt 里的 gradle 版本太老（4.x），AGP 8.5 要 8.7+。
# 为什么先下发行包再生成 wrapper：wrapper 的 jar 得由 gradle 自己产出，
# 没有 gradle 就没法生成 wrapper —— 鸡生蛋问题，所以先拿到 bin 包。
# =============================================================================
set -uo pipefail

ROOT="$HOME/android-dev"
VER="8.7"
DEST="$ROOT/gradle-dist"
ZIP="$ROOT/dl/gradle-$VER-bin.zip"

mkdir -p "$DEST" "$ROOT/dl"

say() { printf '\n\033[36m==> %s\033[0m\n' "$*"; }
ok()  { printf '\033[32m[ok]\033[0m %s\n' "$*"; }

if [ -x "$DEST/gradle-$VER/bin/gradle" ]; then
  ok "Gradle $VER 已就绪"
else
  if [ ! -s "$ZIP" ]; then
    for u in \
      "https://mirrors.cloud.tencent.com/gradle/gradle-$VER-bin.zip" \
      "https://services.gradle.org/distributions/gradle-$VER-bin.zip" ; do
      say "下载 $u"
      if curl -fL --connect-timeout 15 -m 900 --progress-bar -o "$ZIP" "$u"; then break; fi
      rm -f "$ZIP"
    done
  fi
  [ -s "$ZIP" ] || { echo "Gradle 下载失败"; exit 1; }
  say "解压到 $DEST"
  unzip -q "$ZIP" -d "$DEST"
  ok "Gradle 就绪: $("$DEST/gradle-$VER/bin/gradle" --version 2>&1 | grep -E '^Gradle' | head -1)"
fi

# 生成 wrapper，让仓库自包含（别人 clone 下来不用装 gradle）
PROJ="/mnt/d/Documents/deepseek-harness/default-workspace/LITV2/android"
if [ -d "$PROJ" ] && [ ! -f "$PROJ/gradle/wrapper/gradle-wrapper.jar" ]; then
  say "为工程生成 Gradle wrapper"
  ( cd "$PROJ" && "$DEST/gradle-$VER/bin/gradle" wrapper --gradle-version "$VER" --distribution-type bin 2>&1 | tail -5 )
  ok "wrapper 生成完成"
fi

echo "GRADLE_READY"
