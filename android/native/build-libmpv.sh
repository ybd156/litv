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
# =============================================================================
set -uo pipefail

ROOT="$HOME/android-dev"
SRC="$ROOT/src"
OUT="$ROOT/out"
PROJ="/mnt/d/Documents/deepseek-harness/default-workspace/LITV2/android"

ABIS="${ABIS:-arm64-v8a armeabi-v7a}"
API=21
JOBS="$(nproc)"

say()  { printf '\n\033[36m==> %s\033[0m\n' "$*"; }
ok()   { printf '\033[32m[ok]\033[0m %s\n' "$*"; }
err()  { printf '\033[31m[err]\033[0m %s\n' "$*"; }

[ -f "$ROOT/env.sh" ] && source "$ROOT/env.sh"
NDK="${ANDROID_NDK_HOME:-$ROOT/sdk/ndk/27.0.12077973}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
[ -d "$TOOLCHAIN" ] || { err "找不到 NDK toolchain: $TOOLCHAIN"; exit 1; }

mkdir -p "$SRC" "$OUT"

# ---------------------------------------------------------------- 工具函数
# abi 三元组映射
triple_for() {
  case "$1" in
    arm64-v8a)   echo "aarch64-linux-android" ;;
    armeabi-v7a) echo "armv7a-linux-androideabi" ;;
    x86_64)      echo "x86_64-linux-android" ;;
    *) echo "" ;;
  esac
}

fetch_tar() { # fetch_tar <url> <outfile>
  [ -s "$2" ] && { ok "已存在 $(basename "$2")"; return 0; }
  say "下载 $1"
  curl -fL --connect-timeout 15 -m 1800 --progress-bar -o "$2" "$1" || { err "下载失败"; return 1; }
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

  # uavs3d 用 cmake。Android 的 CMake toolchain 文件在 NDK 里。
  cmake "$SRC/uavs3d" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" \
    -DANDROID_PLATFORM="android-$API" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$prefix" \
    -DCOMPILE_10BIT=0 \
    >"$bdir/cmake.log" 2>&1 || { err "uavs3d cmake 失败，见 $bdir/cmake.log"; return 1; }

  make -j"$JOBS" >>"$bdir/cmake.log" 2>&1 || { err "uavs3d make 失败"; return 1; }
  make install >>"$bdir/cmake.log" 2>&1
  ok "uavs3d[$abi] 完成"
}

# ---------------------------------------------------------------- 2. ffmpeg
build_ffmpeg() {
  local abi="$1" triple prefix
  triple="$(triple_for "$abi")"
  prefix="$OUT/$abi"
  [ -f "$prefix/lib/libavcodec.so" ] && { ok "ffmpeg[$abi] 已编译"; return 0; }

  say "编译 ffmpeg [$abi]"
  if [ ! -d "$SRC/ffmpeg" ]; then
    fetch_tar "https://ffmpeg.org/releases/ffmpeg-7.1.tar.xz" "$ROOT/dl/ffmpeg.tar.xz" || return 1
    mkdir -p "$SRC" && tar xJf "$ROOT/dl/ffmpeg.tar.xz" -C "$SRC" && mv "$SRC/ffmpeg-7.1" "$SRC/ffmpeg"
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

  "$SRC/ffmpeg/configure" \
    --prefix="$prefix" \
    --target-os=android --arch="${abi%%-*}" \
    --enable-cross-compile \
    --cc="$cc" --cxx="$cxx" \
    --sysroot="$TOOLCHAIN/sysroot" \
    --enable-shared --disable-static \
    --disable-programs --disable-doc \
    --disable-avdevice --disable-postproc \
    --enable-libuavs3d \
    --extra-cflags="-I$prefix/include -fPIC -O2" \
    --extra-ldflags="-L$prefix/lib" \
    --enable-jni --enable-mediacodec \
    --enable-decoder=h264,hevc,mpeg2video,avs2,avs3,mp3,aac,ac3,eac3,dca,flac,opus,vorbis \
    --enable-parser=h264,hevc,mpegvideo,avs3,aac,ac3 \
    --enable-demuxer=mpegts,hls,mov,flv,matroska,rtsp,h264,hevc,aac,mp3 \
    --enable-protocol=http,https,tcp,udp,rtp,rtsp,file,tls \
    >"$bdir/configure.log" 2>&1 || { err "ffmpeg configure 失败，见 $bdir/configure.log"; tail -20 "$bdir/configure.log"; return 1; }

  make -j"$JOBS" >>"$bdir/build.log" 2>&1 || { err "ffmpeg make 失败，见 $bdir/build.log"; return 1; }
  make install >>"$bdir/build.log" 2>&1
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
cpu_family = '$( [ "$abi" = "arm64-v8a" ] && echo aarch64 || echo arm )'
cpu = '$( [ "$abi" = "arm64-v8a" ] && echo aarch64 || echo armv7a )'
endian = 'little'
CROSS

  PKG_CONFIG_PATH="$prefix/lib/pkgconfig" \
  meson setup build "$SRC/mpv" \
    --cross-file "$bdir/cross.txt" \
    --prefix="$prefix" \
    --buildtype=release \
    -Dlibmpv=true \
    -Dcplayer=false \
    -Dbuild-date=false \
    -Dalsa=disabled -Djack=disabled -Dpulse=disabled \
    -Dpipewire=disabled -Dsdl2=disabled \
    -Dlibass=disabled -Dlibplacebo=disabled \
    -Dvaapi=disabled -Dvdpau=disabled -Dcuda-hwaccel=disabled \
    -Ddrm=disabled -Dgbm=disabled -Dwayland=disabled -Dx11=disabled \
    -Dandroid=enabled -Dmediacodec=enabled \
    >"$bdir/meson.log" 2>&1 || { err "mpv meson 配置失败，见 $bdir/meson.log"; tail -25 "$bdir/meson.log"; return 1; }

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
  build_uavs3d "$abi" || err "uavs3d[$abi] 失败，跳过该 ABI"
  build_ffmpeg  "$abi" || { err "ffmpeg[$abi] 失败，跳过该 ABI"; continue; }
  build_mpv     "$abi" || { err "mpv[$abi] 失败，跳过该 ABI"; continue; }
  collect       "$abi"
done

say "结果"
for abi in $ABIS; do
  echo "--- $abi ---"
  ls -lh "$PROJ/app/src/main/jniLibs/$abi" 2>/dev/null || echo "  （无产物）"
done
echo "LIBMPV_DONE"
