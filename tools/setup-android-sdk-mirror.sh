#!/usr/bin/env bash
# =============================================================================
# 直接从腾讯云镜像装 Android SDK 组件（WSL，免 root）
#
# 为什么不用 sdkmanager：
#   它要先去 dl.google.com 拉 repository XML，这条连接在国内经常卡在
#   "Fetch remote repository..." 几分钟然后失败，且不支持换源。
#   直接下官方 zip 再解包，路径可预测、可重试、可断点，稳得多。
#
# 布局约定（sdkmanager 认的目录结构）：
#   sdk/platforms/android-35
#   sdk/build-tools/35.0.0
#   sdk/platform-tools
#   sdk/ndk/27.0.12077973
#   sdk/cmake/3.22.1
# =============================================================================
set -uo pipefail

ROOT="$HOME/android-dev"
SDK="$ROOT/sdk"
DL="$ROOT/dl"
BASE="https://mirrors.cloud.tencent.com/AndroidSDK"
LOG="$ROOT/sdk-install.log"

mkdir -p "$SDK" "$DL"
exec > >(tee -a "$LOG") 2>&1

say() { printf '\n\033[36m==> %s\033[0m\n' "$*"; }
ok()  { printf '\033[32m[ok]\033[0m %s\n' "$*"; }
err() { printf '\033[31m[err]\033[0m %s\n' "$*"; }

# 下载 + 解包 + 归位
# install_zip <zip文件名> <解包后顶层目录名> <目标相对路径>
install_zip() {
  local file="$1" want="$2" dest="$3"
  local zip="$DL/$file"

  if [ -d "$SDK/$dest" ] && [ -n "$(ls -A "$SDK/$dest" 2>/dev/null)" ]; then
    ok "$dest 已存在，跳过"
    return 0
  fi

  if [ ! -s "$zip" ]; then
    say "下载 $file"
    curl -fL --connect-timeout 15 -m 2400 --progress-bar -o "$zip" "$BASE/$file" || {
      err "下载 $file 失败"; return 1; }
  fi

  say "解包 $file → $dest"
  local tmp="$DL/unpack-$file"
  rm -rf "$tmp" && mkdir -p "$tmp"
  unzip -q "$zip" -d "$tmp" || { err "解包失败 $file"; return 1; }

  # zip 里通常只有一层顶层目录，也可能直接是内容
  local top
  top="$(find "$tmp" -mindepth 1 -maxdepth 1 -type d | head -n1)"
  mkdir -p "$SDK/$(dirname "$dest")"
  rm -rf "$SDK/$dest"

  if [ -n "$top" ] && [ "$(find "$tmp" -mindepth 1 -maxdepth 1 | wc -l)" -eq 1 ]; then
    mv "$top" "$SDK/$dest"
  else
    mv "$tmp" "$SDK/$dest"
  fi
  rm -rf "$tmp"
  ok "$dest 就位（$(du -sh "$SDK/$dest" 2>/dev/null | cut -f1)）"
}

say "安装 Android SDK 组件（腾讯云镜像）"

install_zip "platform-tools_r35.0.2-linux.zip"  "platform-tools"  "platform-tools"
install_zip "platform-35_r01.zip"               "android-35"      "platforms/android-35"
install_zip "build-tools_r35_linux.zip"         "android-15.0.0"  "build-tools/35.0.0"
install_zip "cmake-3.22.1-linux.zip"            "cmake"           "cmake/3.22.1"
install_zip "android-ndk-r27-linux.zip"         "android-ndk-r27" "ndk/27.0.12077973"

# build-tools 的解包目录名不稳定，统一兜底：如果 35.0.0 里没有 aapt2，就在同级找一个搬进去
BT="$SDK/build-tools/35.0.0"
if [ -d "$BT" ] && [ ! -f "$BT/aapt2" ]; then
  say "修正 build-tools 目录结构"
  found="$(find "$SDK/build-tools" -maxdepth 3 -name aapt2 -type f 2>/dev/null | head -n1)"
  if [ -n "$found" ]; then
    mv "$(dirname "$found")"/* "$BT"/ 2>/dev/null || true
  fi
fi
chmod +x "$SDK/platform-tools/"* "$SDK/build-tools/35.0.0/"* 2>/dev/null || true

# licenses：AGP 会检查，缺了直接构建失败
mkdir -p "$SDK/licenses"
for l in android-sdk-license android-sdk-preview-license android-googletv-license \
         android-sdk-arm-dbt-license mips-android-sysimage-license; do
  printf '\n24333f8a63b6825ea9c5514f83c2829b004d1fee\n8933bad161af4178b1185d1a37fbf41ea5269c55\n' > "$SDK/licenses/$l"
done
ok "licenses 已写入"

say "结果"
for p in platforms/android-35 platform-tools build-tools/35.0.0 ndk/27.0.12077973 cmake/3.22.1; do
  if [ -d "$SDK/$p" ]; then
    printf '  %-28s %s\n' "$p" "$(du -sh "$SDK/$p" 2>/dev/null | cut -f1)"
  else
    printf '  %-28s %s\n' "$p" "缺失"
  fi
done
[ -f "$SDK/platforms/android-35/android.jar" ] && echo "  android.jar OK" || echo "  android.jar 缺失"

cat > "$ROOT/env.sh" <<ENVEOF
export JAVA_HOME="$ROOT/jdkpkg/usr/lib/jvm/java-21-openjdk-amd64"
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export ANDROID_NDK_HOME="$SDK/ndk/27.0.12077973"
export PATH="\$JAVA_HOME/bin:\$ANDROID_HOME/platform-tools:\$PATH"
export GRADLE_USER_HOME="$ROOT/gradle"
ENVEOF
ok "环境变量: source $ROOT/env.sh"
echo "SDK_DONE"
