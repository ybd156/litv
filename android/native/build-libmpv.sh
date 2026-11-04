#!/usr/bin/env bash
# =============================================================================
# 交叉编译 libmpv for Android（WSL，免 root）
#
# 为什么必须自己编：
#   mpv-android 官方 README 原文 —— "not a library/module (AAR) you can import
#   into your app"。市面上所谓的 mpv aar 都是个人发布、ABI/编解码器集合不受控，
#   不能作为产品依赖。
#
# 产出：libmpv.so（arm64-v8a / armeabi-v7a），放进
#       android/app/src/main/jniLibs/<abi>/
#
# 关键取舍（v1 最小可用）：
#   - 先不编 libass（字幕）：IPTV 直播基本不用字幕，编它要拉 freetype/fribidi/
#     harfbuzz 三条依赖链，收益低、风险高。需要时再开 --with-libass。
#   - ffmpeg 开 --enable-libuavs3d：这是 AVS3 能播的唯一路径（ffmpeg 无原生 avs3 解码器）。
#   - 不开 --enable-gpl：先避开 GPL 传染；需要 x264/x265 等编码器时再开。
#
# 耗时：首次约 40~90 分钟（ffmpeg 全量解码器编译是大头）。
#
# ---------------------------------------------------------------------------
# TLS 必须编进去（2026-11 补）
#
# 之前的 configure 只写了 --enable-protocol=https,tls，但**没有 TLS 后端**，
# 结果 tls 协议是空壳：https 源连不上，libmpv 对我们的源（全是 https）完全不可用。
# 这个问题一直没暴露，因为默认内核链里 Exo 排前面，mpv 从来没真正上过场。
#
# 现在编 openssl（静态，no-shared），ffmpeg 用 --enable-openssl 链进去，
# 不用额外往 APK 里塞 libssl.so / libcrypto.so。
# =============================================================================
set -uo pipefail

# 构建根目录可用 BUILD_ROOT 覆盖；默认放在工程内的 native/.build，
# 不写死任何本机绝对路径。
PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT="${BUILD_ROOT:-$PROJ/native/.build}"
SRC="$ROOT/src"
OUT="$ROOT/out"

# 默认只编 arm64-v8a：现在主流电视盒子都是 64 位，先跑通一条链路，
# 成功后再 ABIS="armeabi-v7a" 补 32 位（老盒子）。
ABIS="${ABIS:-arm64-v8a}"
API=21
JOBS="$(nproc)"

# AVS3 开关。
# 默认关：uavs3d 的 CMakeLists 与 NDK 27 有兼容 bug
#   （NDK 的 flags.cmake 里 if("hwaddress" IN_LIST ANDROID_SANITIZE) 在
#    ANDROID_SANITIZE 未定义时直接报 Unknown arguments specified），
# 而 AVS3 只有极少数运营商频道用得到，不该拖住整个 libmpv 的交付。
# 需要时：ENABLE_AVS3=1 ./build-libmpv.sh（并换 NDK r26 或给 uavs3d 打补丁）
ENABLE_AVS3="${ENABLE_AVS3:-0}"

say()  { printf '\n\033[36m==> %s\033[0m\n' "$*"; }
ok()   { printf '\033[32m[ok]\033[0m %s\n' "$*"; }
err()  { printf '\033[31m[err]\033[0m %s\n' "$*"; }

[ -f "$ROOT/env.sh" ] && source "$ROOT/env.sh"
NDK="${ANDROID_NDK_HOME:-$ROOT/sdk/ndk/27.0.12077973}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
[ -d "$TOOLCHAIN" ] || { err "找不到 NDK toolchain: $TOOLCHAIN"; exit 1; }

# 注意 PATH 顺序：pip 装的 cmake 必须在 SDK 自带 cmake 之前。
# SDK 的 cmake 3.22 太旧，NDK 27 的 flags.cmake 会报
# 'if given arguments: "hwaddress" IN_LIST ... Unknown arguments specified'。
export PATH="$HOME/.local/bin:$ROOT/toolpkg/usr/bin:$PATH"
command -v cmake  >/dev/null || err "缺少 cmake（pip3 install --user --break-system-packages 'cmake==3.31.6'）"
command -v ninja  >/dev/null || err "缺少 ninja（pip3 install --user --break-system-packages ninja）"
command -v meson  >/dev/null || err "缺少 meson（pip3 install --user --break-system-packages meson）"
command -v make   >/dev/null || err "缺少 make（apt-get download make && dpkg-deb -x make_*.deb \$ROOT/toolpkg）"
say "cmake=$(cmake --version | head -1 | awk '{print $3}') ninja=$(ninja --version) meson=$(meson --version)"

# 构建期统一走代理（境外源码站），国内镜像直连由 NO_PROXY 兜底。
# CI（GitHub Actions）直连就够快，不需要也不该走代理。
if [ -z "${CI:-}" ] && [ -z "${https_proxy:-}" ]; then
  HOSTIP="$(ip route show default | awk '{print $3}' | head -1)"
  export http_proxy="http://$HOSTIP:10808"
  export https_proxy="http://$HOSTIP:10808"
fi

mkdir -p "$SRC" "$OUT" "$ROOT/dl"

# ---------------------------------------------------------------- 工具函数
# abi 三元组映射
triple_for() {
  case "$1" in
    arm64-v8a)   echo "aarch64-linux-android" ;;
    armeabi-v7a) echo "armv7a-linux-androideabi" ;;
    x86)         echo "i686-linux-android" ;;
    x86_64)      echo "x86_64-linux-android" ;;
    *) echo "" ;;
  esac
}

fetch_tar() { # fetch_tar <url> <outfile>
  [ -s "$2" ] && { ok "已存在 $(basename "$2")"; return 0; }
  say "下载 $1"
  # connect-timeout 给足：走代理时首次建连偶尔要 20~30 秒
  curl -fL --connect-timeout 40 -m 2400 --retry 3 --retry-delay 3 \
       --progress-bar -o "$2" "$1" || { err "下载失败"; rm -f "$2"; return 1; }
}

# ---------------------------------------------------------------- 1. uavs3d（AVS3 解码器）
build_uavs3d() {
  local abi="$1" triple prefix
  triple="$(triple_for "$abi")"
  prefix="$OUT/$abi"
  [ -f "$prefix/lib/libuavs3d.a" ] && { ok "uavs3d[$abi] 已编译"; return 0; }

  say "编译 uavs3d [$abi]"
  if [ ! -d "$SRC/uavs3d" ]; then
    fetch_tar "https://github.com/uavs3/uavs3d/archive/refs/heads/master.tar.gz" "$ROOT/dl/uavs3d.tar.gz" || return 1
    mkdir -p "$SRC" && tar xzf "$ROOT/dl/uavs3d.tar.gz" -C "$SRC" && mv "$SRC/uavs3d-master" "$SRC/uavs3d"
  fi

  local bdir="$SRC/uavs3d/build-android-$abi"
  rm -rf "$bdir" && mkdir -p "$bdir" && cd "$bdir" || return 1

  # 用 Ninja 而不是 Unix Makefiles：容器/精简系统里 make 常常缺失，
  # 而 ninja 是单二进制、由 meson 依赖自动带上，更省事。
  cmake "$SRC/uavs3d" -G Ninja \
    -DCMAKE_MAKE_PROGRAM="$(command -v ninja)" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" \
    -DANDROID_PLATFORM="android-$API" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$prefix" \
    -DCOMPILE_10BIT=0 \
    >"$bdir/cmake.log" 2>&1 || { err "uavs3d cmake 失败，见 $bdir/cmake.log"; tail -15 "$bdir/cmake.log"; return 1; }

  ninja -C . >>"$bdir/cmake.log" 2>&1 || { err "uavs3d 编译失败"; return 1; }
  ninja -C . install >>"$bdir/cmake.log" 2>&1
  ok "uavs3d[$abi] 完成"
}

# ---------------------------------------------------------------- 1.5 openssl
#
# ffmpeg 的 tls 协议必须有后端才能用，否则 https 是空壳。
# 编成**静态库**（no-shared），ffmpeg 直接链进去，
# APK 里就不用额外带 libssl.so / libcrypto.so。
build_openssl() {
  local abi="$1" prefix target
  prefix="$OUT/$abi"
  [ -f "$prefix/lib/libssl.a" ] && { ok "openssl[$abi] 已编译"; return 0; }

  say "编译 openssl [$abi]"
  if [ ! -d "$SRC/openssl" ]; then
    # 用 GitHub release 归档：openssl.org 在国内/代理下经常 TLS handshake failure
    fetch_tar "https://github.com/openssl/openssl/releases/download/openssl-3.0.15/openssl-3.0.15.tar.gz"       "$ROOT/dl/openssl.tar.gz" || return 1
    mkdir -p "$SRC" && tar xzf "$ROOT/dl/openssl.tar.gz" -C "$SRC" && mv "$SRC/openssl-3.0.15" "$SRC/openssl"
  fi

  case "$abi" in
    arm64-v8a)   target=android-arm64 ;;
    armeabi-v7a) target=android-arm ;;
    x86)         target=android-x86 ;;
    x86_64)      target=android-x86_64 ;;
    *) err "openssl 不认识的 ABI: $abi"; return 1 ;;
  esac

  local log="$ROOT/dl/openssl-$abi.log"
  cd "$SRC/openssl" || return 1

  # OpenSSL 的 Configure 靠 ANDROID_NDK_ROOT 找工具链
  ANDROID_NDK_ROOT="$NDK" PATH="$TOOLCHAIN/bin:$PATH"   ./Configure "$target"     -D__ANDROID_API__="$API"     --prefix="$prefix"     --openssldir="$prefix/ssl"     no-shared no-tests no-asm no-ui-console     >"$log" 2>&1 || { err "openssl Configure 失败，见 $log"; tail -20 "$log"; return 1; }

  ANDROID_NDK_ROOT="$NDK" PATH="$TOOLCHAIN/bin:$PATH"     make -j"$JOBS" >>"$log" 2>&1 || { err "openssl make 失败，见 $log"; tail -20 "$log"; return 1; }
  ANDROID_NDK_ROOT="$NDK" PATH="$TOOLCHAIN/bin:$PATH"     make install_sw >>"$log" 2>&1 || { err "openssl install 失败，见 $log"; tail -20 "$log"; return 1; }
  make clean >>"$log" 2>&1

  [ -f "$prefix/lib/libssl.a" ] || { err "openssl[$abi] 没产出 libssl.a"; return 1; }
  ok "openssl[$abi] 完成"
}

# ---------------------------------------------------------------- 2. ffmpeg
build_ffmpeg() {
  local abi="$1" triple prefix
  triple="$(triple_for "$abi")"
  prefix="$OUT/$abi"
  [ -f "$prefix/lib/libavcodec.so" ] && { ok "ffmpeg[$abi] 已编译"; return 0; }

  say "编译 ffmpeg [$abi]"
  if [ ! -d "$SRC/ffmpeg" ]; then
    # 用 GitHub 的 tag 归档而不是 ffmpeg.org：后者在国内/代理下经常
    # TLS handshake failure，GitHub 反而稳。
    fetch_tar "https://github.com/FFmpeg/FFmpeg/archive/refs/tags/n7.1.tar.gz" "$ROOT/dl/ffmpeg.tar.gz" || return 1
    mkdir -p "$SRC" && tar xzf "$ROOT/dl/ffmpeg.tar.gz" -C "$SRC" && mv "$SRC/FFmpeg-n7.1" "$SRC/ffmpeg"
  fi

  local bdir="$SRC/ffmpeg-build-$abi"
  rm -rf "$bdir" && mkdir -p "$bdir" && cd "$bdir" || return 1

  local cc cxx
  if [ "$abi" = "armeabi-v7a" ]; then
    cc="$TOOLCHAIN/bin/armv7a-linux-androideabi$API-clang"
  else
    cc="$TOOLCHAIN/bin/${triple}$API-clang"
  fi
  cxx="${cc}++"

  # 按 ENABLE_AVS3 决定要不要带 libuavs3d
  local avs3_flags=()
  if [ "$ENABLE_AVS3" = "1" ]; then
    avs3_flags=(--enable-libuavs3d)
  fi

  # openssl 装到 $prefix，必须让 pkg-config 找到它的 .pc，
  # 否则 ffmpeg 的 check_pkg_config 找不到 openssl 会直接 die。
  export PKG_CONFIG_PATH="$prefix/lib/pkgconfig:${PKG_CONFIG_PATH:-}"

  "$SRC/ffmpeg/configure" \
    --prefix="$prefix" \
    --target-os=android --arch="${abi%%-*}" \
    --enable-cross-compile \
    --cc="$cc" --cxx="$cxx" \
    --sysroot="$TOOLCHAIN/sysroot" \
    --enable-shared --disable-static \
    --disable-programs --disable-doc \
    --disable-avdevice --disable-postproc \
    "${avs3_flags[@]}" \
    --extra-cflags="-I$prefix/include -fPIC -O2" \
    --extra-ldflags="-L$prefix/lib" \
    --enable-jni --enable-mediacodec \
    --enable-decoder=h264,hevc,mpeg2video,avs2,avs3,mp3,aac,ac3,eac3,dca,flac,opus,vorbis \
    --enable-parser=h264,hevc,mpegvideo,avs3,aac,ac3 \
    --enable-demuxer=mpegts,hls,mov,flv,matroska,rtsp,h264,hevc,aac,mp3 \
    --enable-protocol=http,https,tcp,udp,rtp,rtsp,file,tls \
    --enable-openssl \
    >"$bdir/configure.log" 2>&1 || {
    err "ffmpeg configure 失败"
    # ffmpeg 的 configure 最后总是打一段"如果这是未预期的错误请报告"的废话，
    # 真正的原因在上面。把 ERROR 行挑出来才有用。
    grep -iE "error|not found|requires|unknown option" "$bdir/configure.log" | tail -12
    return 1
  }

  # 确认 TLS 真的编进去了。不做这个检查的话，configure 找不到库时
  # 只打印警告然后继续，最后又产出一个 https 连不上的 .so——这个坑踩过一次。
  # ffmpeg 7.x 把开关写在 ffbuild/config.mak（CONFIG_OPENSSL=yes），
  # config.h 里是 #define CONFIG_OPENSSL 1。两处都查，别只查一处。
  if grep -q "^CONFIG_OPENSSL=yes" "$bdir/ffbuild/config.mak" 2>/dev/null      || grep -q "define CONFIG_OPENSSL 1" "$bdir/config.h" 2>/dev/null; then
    ok "ffmpeg[$abi] TLS 已启用（openssl）"
  else
    err "ffmpeg[$abi] 没启用 openssl，https 会连不上"
    grep -i openssl "$bdir/configure.log" | tail -5
    return 1
  fi

  make -j"$JOBS" >>"$bdir/build.log" 2>&1 || { err "ffmpeg make 失败，见 $bdir/build.log"; return 1; }
  # 关键：make install 默认会用宿主 strip，而宿主 strip 不认识 Android ARM 的 .so，
  # 报 "Unable to recognise the architecture"，导致 install 半途而废（.so 没装全、.pc 没生成）。
  # 传 STRIP=true 跳过 stripping。
  make install STRIP=true >>"$bdir/build.log" 2>&1 || { err "ffmpeg install 失败，见 $bdir/build.log"; return 1; }
  [ -f "$prefix/lib/pkgconfig/libavcodec.pc" ] || { err "ffmpeg 未安装完整（缺 libavcodec.pc）"; return 1; }
  ok "ffmpeg[$abi] 完成"
}

# ---------------------------------------------------------------- 3. mpv
build_mpv() {
  local abi="$1" prefix
  prefix="$OUT/$abi"
  [ -f "$prefix/lib/libmpv.so" ] && { ok "mpv[$abi] 已编译"; return 0; }

  say "编译 mpv [$abi]"
  if [ ! -d "$SRC/mpv" ]; then
    fetch_tar "https://github.com/mpv-player/mpv/archive/refs/tags/v0.39.0.tar.gz" "$ROOT/dl/mpv.tar.gz" || return 1
    mkdir -p "$SRC" && tar xzf "$ROOT/dl/mpv.tar.gz" -C "$SRC" && mv "$SRC/mpv-0.39.0" "$SRC/mpv"
  fi

  # mpv 用 waf/meson 构建；v0.39 已切到 meson
  local bdir="$SRC/mpv-build-$abi"
  rm -rf "$bdir" && mkdir -p "$bdir" && cd "$bdir" || return 1

  cat > "$bdir/cross.txt" <<CROSS
[binaries]
c = '$TOOLCHAIN/bin/$( [ "$abi" = "armeabi-v7a" ] && echo armv7a-linux-androideabi || echo $(triple_for "$abi") )$API-clang'
cpp = c
ar = '$TOOLCHAIN/bin/llvm-ar'
strip = '$TOOLCHAIN/bin/llvm-strip'
pkg-config = 'pkg-config'

[host_machine]
system = 'android'
cpu_family = '$( case "$abi" in arm64-v8a) echo aarch64 ;; x86) echo x86 ;; x86_64) echo x86_64 ;; *) echo arm ;; esac )'
cpu = '$( case "$abi" in arm64-v8a) echo aarch64 ;; x86) echo i686 ;; x86_64) echo x86_64 ;; *) echo armv7a ;; esac )'
endian = 'little'
CROSS

  # mpv 0.39 的 meson 选项与 0.37 之前差别很大：
  #   - 没有 libass / libplacebo 开关（改为 pkg-config 自动探测，没装就是没有）
  #   - 没有 android 开关；Android 支持由 egl-android + android-media-ndk 组成
  #   - 没有 macos-10-15-feature（改叫 macos-10-15-4-features）
  # 给错选项 meson 会直接 "Unknown option" 退出，所以这里只列真实存在的。
  PKG_CONFIG_PATH="$prefix/lib/pkgconfig" \
  meson setup build "$SRC/mpv" \
    --cross-file "$bdir/cross.txt" \
    --prefix="$prefix" \
    --buildtype=release \
    -Dlibmpv=true \
    -Dcplayer=false \
    -Dbuild-date=false \
    -Dgpl=false \
    -Degl-android=enabled \
    -Dandroid-media-ndk=enabled \
    -Dalsa=disabled -Djack=disabled -Dpulse=disabled -Dpipewire=disabled \
    -Dopenal=disabled -Dsdl2=disabled -Dsdl2-audio=disabled \
    -Dvaapi=disabled -Dvdpau=disabled -Dcuda-hwaccel=disabled \
    -Ddrm=disabled -Dgbm=disabled -Dwayland=disabled -Dx11=disabled -Dvulkan=disabled \
    -Dcaca=disabled -Djpeg=disabled -Dlcms2=disabled -Dlibarchive=disabled \
    -Dlibbluray=disabled -Dlua=disabled -Duchardet=disabled -Dzimg=disabled \
    -Ddvbin=disabled -Ddvdnav=disabled -Dcdda=disabled \
    >"$bdir/meson.log" 2>&1 || { err "mpv meson 配置失败，见 $bdir/meson.log"; tail -20 "$bdir/meson.log"; return 1; }

  ninja -C build -j"$JOBS" >>"$bdir/meson.log" 2>&1 || { err "mpv 编译失败，见 $bdir/meson.log"; return 1; }
  ninja -C build install >>"$bdir/meson.log" 2>&1
  ok "mpv[$abi] 完成"
}

# ---------------------------------------------------------------- 4. 汇总到工程
collect() {
  local abi="$1" dest="$PROJ/app/src/main/jniLibs/$abi"
  mkdir -p "$dest"
  for so in libmpv.so libavcodec.so libavformat.so libavutil.so libswscale.so libswresample.so; do
    [ -f "$OUT/$abi/lib/$so" ] && cp -f "$OUT/$abi/lib/$so" "$dest/" && echo "  → $abi/$so"
  done
  # 清理带版本号的符号链接副本
  find "$dest" -name "*.so.*" -delete 2>/dev/null
}

# ---------------------------------------------------------------- 主流程
for abi in $ABIS; do
  say "=========== 构建 $abi ==========="
  if [ "$ENABLE_AVS3" = "1" ]; then
    build_uavs3d "$abi" || err "uavs3d[$abi] 失败，跳过该 ABI"
  else
    say "跳过 uavs3d（ENABLE_AVS3=0，AVS3 解码暂不编入）"
  fi
  build_openssl "$abi" || { err "openssl[$abi] 失败，跳过该 ABI"; continue; }
  build_ffmpeg  "$abi" || { err "ffmpeg[$abi] 失败，跳过该 ABI"; continue; }
  build_mpv     "$abi" || { err "mpv[$abi] 失败，跳过该 ABI"; continue; }
  collect       "$abi"
done

say "结果"
for abi in $ABIS; do
  echo "--- $abi ---"
  ls -lh "$PROJ/app/src/main/jniLibs/$abi" 2>/dev/null || echo "  （无产物）"
done
# 产物校验：脚本没有 set -e，某个 ABI 失败只会 continue，
# 不加这一步的话 CI 会显示"成功"但其实是空的（已经骗过一次）。
missing=0
for abi in $ABIS; do
  # 检查**构建输出**目录，不是 jniLibs——
  # jniLibs 里本来就有提交进仓库的旧 .so，查那里会误判成"编成功了"。
  [ -f "$OUT/$abi/lib/libmpv.so" ] || { err "$abi 没有编出 libmpv.so"; missing=1; }
  [ -f "$OUT/$abi/lib/libavformat.so" ] || { err "$abi 没有编出 libavformat.so"; missing=1; }
done
[ "$missing" = "0" ] || { err "有 ABI 没编出产物，构建失败"; exit 1; }

echo "LIBMPV_DONE"
