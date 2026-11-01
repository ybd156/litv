#!/usr/bin/env bash
# =============================================================================
# LITV Android 开发环境（WSL，免 root）—— 快速版
#
# 为什么这么装：
#   - JDK：Adoptium 官方源在国内只有 20~50KB/s，200MB 要下两小时。
#     改用 `apt-get download` 从系统源拿 deb（走本地镜像，通常几 MB/s），
#     再 dpkg-deb -x 解包到用户目录 —— 不需要 root，也不用编译。
#   - Android SDK：dl.google.com 同样慢，走腾讯云镜像。
#   - 全程不碰 /usr，不需要 sudo。
#
# 幂等，可反复执行。
# =============================================================================
set -uo pipefail

ROOT="$HOME/android-dev"
DL="$ROOT/dl"
SDK="$ROOT/sdk"
JDKPKG="$ROOT/jdkpkg"
LOG="$ROOT/install.log"

mkdir -p "$DL" "$SDK"
exec > >(tee -a "$LOG") 2>&1

say()  { printf '\n\033[36m==> %s\033[0m\n' "$*"; }
ok()   { printf '\033[32m[ok]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[warn]\033[0m %s\n' "$*"; }
err()  { printf '\033[31m[err]\033[0m %s\n' "$*"; }

echo "开始时间: $(date '+%F %T')"

# ---------------------------------------------------------------- 1. JDK 21
say "1/4 安装 JDK 21（apt-get download + dpkg-deb -x，免 root）"
if [ -x "$JDKPKG/usr/lib/jvm/java-21-openjdk-amd64/bin/java" ]; then
  ok "JDK 已解包"
else
  cd "$DL" || exit 1
  say "从系统源下载 openjdk-21 deb（仅下载，不安装）"
  apt-get download openjdk-21-jdk-headless openjdk-21-jre-headless 2>&1 | tail -5
  ls -lh openjdk-21*.deb 2>/dev/null || warn "deb 下载不完整"

  mkdir -p "$JDKPKG"
  for d in openjdk-21-jre-headless*.deb openjdk-21-jdk-headless*.deb; do
    [ -f "$d" ] || continue
    say "解包 $d"
    dpkg-deb -x "$d" "$JDKPKG"
  done
  cd - >/dev/null || true
fi

JAVA_HOME_REAL="$JDKPKG/usr/lib/jvm/java-21-openjdk-amd64"

# 关键坑：deb 包里 conf/security 等目录是绝对路径符号链接，
# 直接解包到非系统路径会指向不存在的 /etc/java-21-openjdk，
# java 启动时直接报 "Error loading java.security file"。
# 处理办法：把指向包外的符号链接替换成实体文件。
if [ -d "$JAVA_HOME_REAL" ]; then
  say "修复 JDK 内部绝对路径符号链接"
  find "$JAVA_HOME_REAL" -type l 2>/dev/null | while read -r lnk; do
    tgt="$(readlink "$lnk")"
    case "$tgt" in
      /*)
        # 绝对链接：目标在包内就改成相对，包外就尽量在包内找同名文件
        base="$(basename "$tgt")"
        found="$(find "$JDKPKG" -name "$base" -type f 2>/dev/null | head -n1)"
        if [ -n "$found" ]; then
          rm -f "$lnk" && cp -f "$found" "$lnk"
        fi
        ;;
    esac
  done
  chmod +x "$JAVA_HOME_REAL/bin/"* 2>/dev/null || true
fi

export JAVA_HOME="$JAVA_HOME_REAL"
export PATH="$JAVA_HOME/bin:$PATH"
if [ -x "$JAVA_HOME/bin/java" ]; then
  ok "JDK: $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"
else
  err "JDK 未就绪，后续步骤会失败"
fi

# ---------------------------------------------------------------- 2. cmdline-tools
say "2/4 安装 Android cmdline-tools"
CLT="$SDK/cmdline-tools/latest/bin/sdkmanager"
if [ -x "$CLT" ]; then
  ok "cmdline-tools 已安装"
else
  # 腾讯云镜像，国内速度好很多
  for u in \
    "https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-linux-11076708_latest.zip" \
    "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" ; do
    say "下载 $u"
    if curl -fL --connect-timeout 15 -m 900 --progress-bar \
         -o "$DL/cmdline-tools.zip" "$u"; then
      break
    fi
    rm -f "$DL/cmdline-tools.zip"
  done
  if [ -s "$DL/cmdline-tools.zip" ]; then
    mkdir -p "$SDK/cmdline-tools"
    rm -rf "$SDK/cmdline-tools/latest" "$SDK/cmdline-tools/cmdline-tools"
    unzip -q "$DL/cmdline-tools.zip" -d "$SDK/cmdline-tools" && \
      mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest" && \
      ok "cmdline-tools 就绪"
  fi
fi

export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"

# ---------------------------------------------------------------- 3. 许可
say "3/4 接受 SDK 许可"
[ -x "$CLT" ] && { yes | "$CLT" --sdk_root="$SDK" --licenses >/dev/null 2>&1; ok "许可处理完成"; }

# ---------------------------------------------------------------- 4. 组件
say "4/4 安装 SDK 组件"
if [ -x "$CLT" ]; then
  for pkg in \
      "platform-tools" \
      "platforms;android-35" \
      "build-tools;35.0.0" \
      "ndk;27.0.12077973" \
      "cmake;3.22.1" ; do
    say "安装 $pkg"
    "$CLT" --sdk_root="$SDK" --install "$pkg" 2>&1 | tail -2
  done
fi

# ---------------------------------------------------------------- 汇总
say "汇总"
"$JAVA_HOME/bin/java" -version 2>&1 | head -1 | sed 's/^/  java: /'
[ -d "$SDK/platforms" ] && ls "$SDK/platforms" | sed 's/^/  platform: /'
[ -d "$SDK/build-tools" ] && ls "$SDK/build-tools" | sed 's/^/  build-tools: /'
[ -d "$SDK/ndk" ] && ls "$SDK/ndk" | sed 's/^/  ndk: /'
[ -d "$SDK/cmake" ] && ls "$SDK/cmake" | sed 's/^/  cmake: /'

cat > "$ROOT/env.sh" <<ENVEOF
export JAVA_HOME="$JAVA_HOME_REAL"
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export ANDROID_NDK_HOME="$SDK/ndk/27.0.12077973"
export PATH="\$JAVA_HOME/bin:\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/cmdline-tools/latest/bin:\$PATH"
export GRADLE_USER_HOME="$ROOT/gradle"
ENVEOF
ok "环境变量: source $ROOT/env.sh"

echo "结束时间: $(date '+%F %T')"
echo "INSTALL_DONE"
