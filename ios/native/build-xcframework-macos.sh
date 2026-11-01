#!/usr/bin/env bash
# =============================================================================
# 在 macOS 上构建 iOS 版 libmpv / FFmpeg 的 xcframework
#
# 为什么这个脚本必须在 macOS 上跑：
#   iOS 的 libmpv 依赖链（ffmpeg / libass / libplacebo / MoltenVK）需要
#   Apple 的 iOS SDK 与 clang，官方构建路径就是 Xcode。Linux/WSL 上
#   编不出来——这不是工具链配置问题，是 SDK 授权与框架缺失。
#
# 所以我们的做法是：
#   GitHub Actions 的 macos runner 负责"编出 .xcframework"
#   WSL 负责"用 xtool 链接打包成 .ipa"
#   开发全程留在 WSL，只有二进制在云端生成。
#
# 产出：build/LITVKit.xcframework（含 ios-arm64 切片）
# =============================================================================
set -euo pipefail

IOS_MIN="${IOS_MIN:-14.0}"
WORK="${WORK:-$PWD/build-ios}"
ARCH="${ARCH:-arm64}"

say() { printf '\n\033[36m==> %s\033[0m\n' "$*"; }

mkdir -p "$WORK"
cd "$WORK"

# ---------------------------------------------------------------- 1. uavs3d
if [ ! -f "$WORK/lib/libuavs3d.a" ]; then
  say "构建 uavs3d（AVS3 解码器）"
  [ -d uavs3d ] || git clone --depth 1 https://github.com/uavs3/uavs3d.git
  cmake -S uavs3d -B uavs3d/build-ios \
    -DCMAKE_SYSTEM_NAME=iOS \
    -DCMAKE_OSX_ARCHITECTURES="$ARCH" \
    -DCMAKE_OSX_DEPLOYMENT_TARGET="$IOS_MIN" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$WORK" \
    -DCOMPILE_10BIT=0
  cmake --build uavs3d/build-ios -j"$(sysctl -n hw.ncpu)"
  cmake --install uavs3d/build-ios
fi

# ---------------------------------------------------------------- 2. FFmpeg
if [ ! -f "$WORK/lib/libavcodec.a" ]; then
  say "构建 FFmpeg（静态库，开 libuavs3d）"
  [ -d ffmpeg ] || {
    curl -fL -o ffmpeg.tar.xz https://ffmpeg.org/releases/ffmpeg-7.1.tar.xz
    tar xJf ffmpeg.tar.xz && mv ffmpeg-7.1 ffmpeg
  }
  cd ffmpeg
  ./configure \
    --prefix="$WORK" \
    --target-os=darwin --arch="$ARCH" \
    --enable-cross-compile \
    --sysroot="$(xcrun --sdk iphoneos --show-sdk-path)" \
    --cc="$(xcrun --sdk iphoneos -f clang)" \
    --extra-cflags="-arch $ARCH -miphoneos-version-min=$IOS_MIN -fembed-bitcode" \
    --extra-ldflags="-arch $ARCH -miphoneos-version-min=$IOS_MIN" \
    --enable-static --disable-shared \
    --disable-programs --disable-doc --disable-avdevice --disable-postproc \
    --enable-libuavs3d \
    --enable-videotoolbox \
    --enable-decoder=h264,hevc,mpeg2video,avs2,avs3,mp3,aac,ac3,eac3,dca,flac,opus \
    --enable-parser=h264,hevc,mpegvideo,avs3,aac,ac3 \
    --enable-demuxer=mpegts,hls,mov,flv,matroska,h264,hevc,aac,mp3 \
    --enable-protocol=http,https,tcp,udp,rtp,rtsp,file,tls
  make -j"$(sysctl -n hw.ncpu)"
  make install
  cd ..
fi

# ---------------------------------------------------------------- 3. mpv
if [ ! -f "$WORK/lib/libmpv.a" ]; then
  say "构建 mpv（libmpv 静态库）"
  [ -d mpv ] || {
    curl -fL -o mpv.tar.gz https://github.com/mpv-player/mpv/archive/refs/tags/v0.39.0.tar.gz
    tar xzf mpv.tar.gz && mv mpv-0.39.0 mpv
  }
  brew install meson ninja pkg-config || true
  cat > "$WORK/cross-ios.txt" <<CROSS
[binaries]
c = '$(xcrun --sdk iphoneos -f clang)'
cpp = '$(xcrun --sdk iphoneos -f clang++)'
ar = '$(xcrun --sdk iphoneos -f ar)'
strip = '$(xcrun --sdk iphoneos -f strip)'
pkg-config = 'pkg-config'

[host_machine]
system = 'darwin'
cpu_family = 'aarch64'
cpu = 'arm64'
endian = 'little'
CROSS

  PKG_CONFIG_PATH="$WORK/lib/pkgconfig" meson setup mpv/build mpv \
    --cross-file "$WORK/cross-ios.txt" \
    --prefix="$WORK" --buildtype=release \
    -Dlibmpv=true -Dcplayer=false -Dbuild-date=false \
    -Dlibass=disabled -Dlibplacebo=disabled \
    -Dvideotoolbox=enabled -Dmacos-10-15-feature=disabled
  ninja -C mpv/build
  ninja -C mpv/build install
fi

# ---------------------------------------------------------------- 4. 打 xcframework
say "打包 xcframework"
LIBS="libmpv.a libavcodec.a libavformat.a libavutil.a libswscale.a libswresample.a libuavs3d.a"
mkdir -p "$WORK/headers" "$WORK/xcf"
cp -r "$WORK"/include/* "$WORK/headers/" 2>/dev/null || true

# 先把所有静态库合成一个 fat .a，方便 xtool 链接
libtool -static -o "$WORK/xcf/libLITVKit.a" $(for l in $LIBS; do [ -f "$WORK/lib/$l" ] && echo "$WORK/lib/$l"; done)

xcodebuild -create-xcframework \
  -library "$WORK/xcf/libLITVKit.a" \
  -headers "$WORK/headers" \
  -output "$WORK/xcf/LITVKit.xcframework"

say "完成"
ls -R "$WORK/xcf/LITVKit.xcframework" | head -20
echo "IOS_XCFRAMEWORK_DONE"
