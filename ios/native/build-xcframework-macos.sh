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
  [ -d "$WORK/uavs3d" ] || git clone --depth 1 https://github.com/uavs3/uavs3d.git "$WORK/uavs3d"
  # CMAKE_POLICY_VERSION_MINIMUM: runner 自带 CMake 4.x，会拒绝 uavs3d 里
  # cmake_minimum_required(<3.5)，加这个开关才放行。
  cmake -S "$WORK/uavs3d" -B "$WORK/uavs3d/build-ios" \
    -DCMAKE_SYSTEM_NAME=iOS \
    -DCMAKE_SYSTEM_PROCESSOR="$ARCH" \
    -DCMAKE_OSX_ARCHITECTURES="$ARCH" \
    -DCMAKE_OSX_DEPLOYMENT_TARGET="$IOS_MIN" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$WORK" \
    -DCOMPILE_10BIT=0 \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5
  # 只编静态库目标 uavs3d：默认还会编命令行程序 uavs3dec，
  # 那是个可执行文件，在 iOS SDK 下链接会失败（ld: symbol(s) not found）。
  cmake --build "$WORK/uavs3d/build-ios" --target uavs3d -j"$(sysctl -n hw.ncpu)"
  cmake --install "$WORK/uavs3d/build-ios"
fi

# uavs3d 的 CMake 不生成 pkg-config 文件，但 ffmpeg 的 --enable-libuavs3d
# 是用 pkg-config 找库的（require_pkg_config libuavs3d "uavs3d >= 1.1.41"）。
# 这里手写一个最小的 .pc，否则 configure 会直接报 uavs3d not found。
mkdir -p "$WORK/lib/pkgconfig"
cat > "$WORK/lib/pkgconfig/uavs3d.pc" <<PCEOF
prefix=$WORK
exec_prefix=\${prefix}
libdir=\${prefix}/lib
includedir=\${prefix}/include

Name: uavs3d
Description: AVS3-P2 video decoder
Version: 1.1.41
Libs: -L\${libdir} -luavs3d
Cflags: -I\${includedir}
PCEOF

# ---------------------------------------------------------------- 2. FFmpeg
if [ ! -f "$WORK/lib/libavcodec.a" ]; then
  say "构建 FFmpeg（静态库，开 libuavs3d）"
  [ -d ffmpeg ] || {
    # 用 GitHub 的 tag 归档而不是 ffmpeg.org：后者在 CI 上经常超时/断流
    curl -fL --retry 3 -o ffmpeg.tar.gz https://github.com/FFmpeg/FFmpeg/archive/refs/tags/n7.1.tar.gz
    tar xzf ffmpeg.tar.gz && mv FFmpeg-n7.1 ffmpeg
  }
  cd ffmpeg
  # 让 pkg-config 能找到刚装的 uavs3d
  export PKG_CONFIG_PATH="$WORK/lib/pkgconfig"
  export PKG_CONFIG_LIBDIR="$WORK/lib/pkgconfig"
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
    --disable-vaapi --disable-vdpau --disable-cuda --disable-cuvid \
    --enable-decoder=h264,hevc,mpeg2video,avs2,avs3,mp3,aac,ac3,eac3,dca,flac,opus \
    --enable-parser=h264,hevc,mpegvideo,avs3,aac,ac3 \
    --enable-demuxer=mpegts,hls,mov,flv,matroska,h264,hevc,aac,mp3 \
    --enable-protocol=http,https,tcp,udp,rtp,rtsp,file,tls
  make -j"$(sysctl -n hw.ncpu)"
  make install
  cd ..
fi

# ---------------------------------------------------------------- 3. libass 依赖
#
# mpv 0.35.1 的 meson.build 里 libass 是无条件 dependency()（没有 required: 开关），
# 所以躲不掉。libass 又依赖 fribidi + freetype。
# 三者都是小而成熟的库，交叉编译成本可控。
say "准备 meson 交叉编译文件"
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

export PKG_CONFIG_PATH="$WORK/lib/pkgconfig"
export PKG_CONFIG_LIBDIR="$WORK/lib/pkgconfig"

# ---- fribidi（双向文本，libass 必需）----
if [ ! -f "$WORK/lib/libfribidi.a" ]; then
  say "构建 fribidi"
  [ -d fribidi ] || {
    curl -fL --retry 3 -o fribidi.tar.gz https://github.com/fribidi/fribidi/archive/refs/tags/v1.0.15.tar.gz
    tar xzf fribidi.tar.gz && mv fribidi-1.0.15 fribidi
  }
  meson setup fribidi/build fribidi --cross-file "$WORK/cross-ios.txt" \
    --prefix="$WORK" --buildtype=release --default-library=static \
    -Ddocs=false -Dtests=false -Dbin=false
  ninja -C fribidi/build install
fi

# ---- freetype（字体解析，libass 必需）----
if [ ! -f "$WORK/lib/libfreetype.a" ]; then
  say "构建 freetype"
  [ -d freetype ] || {
    curl -fL --retry 3 -o freetype.tar.gz https://github.com/freetype/freetype/archive/refs/tags/VER-2-13-2.tar.gz
    tar xzf freetype.tar.gz && mv freetype-VER-2-13-2 freetype
  }
  cmake -S freetype -B freetype/build-ios \
    -DCMAKE_SYSTEM_NAME=iOS \
    -DCMAKE_SYSTEM_PROCESSOR="$ARCH" \
    -DCMAKE_OSX_ARCHITECTURES="$ARCH" \
    -DCMAKE_OSX_DEPLOYMENT_TARGET="$IOS_MIN" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$WORK" \
    -DBUILD_SHARED_LIBS=OFF \
    -DFT_DISABLE_PNG=ON -DFT_DISABLE_BZIP2=ON -DFT_DISABLE_HARFBUZZ=ON \
    -DFT_DISABLE_BROTLI=ON -DFT_DISABLE_ZLIB=OFF \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5
  cmake --build freetype/build-ios -j"$(sysctl -n hw.ncpu)"
  cmake --install freetype/build-ios
fi

# ---- libass（字幕渲染，mpv 必需）----
if [ ! -f "$WORK/lib/libass.a" ]; then
  say "构建 libass"
  [ -d libass ] || {
    curl -fL --retry 3 -o libass.tar.gz https://github.com/libass/libass/archive/refs/tags/0.17.1.tar.gz
    tar xzf libass.tar.gz && mv libass-0.17.1 libass
  }
  meson setup libass/build libass --cross-file "$WORK/cross-ios.txt" \
    --prefix="$WORK" --buildtype=release --default-library=static \
    -Dfontconfig=disabled -Dharfbuzz=disabled -Dlibunibreak=disabled -Dtest=disabled
  ninja -C libass/build install
fi

# ---------------------------------------------------------------- 4. mpv
#
# 用 mpv 0.35.1 而不是 0.39：
#   0.36 之后 libplacebo 从"可选依赖"变成了无条件 dependency()，
#   而 iOS 上编 libplacebo 要拉 Vulkan/GLES + shaderc 整条链，成本极高。
#   0.35.1 仍然可以 -Dlibplacebo=disabled，且自带 ios-gl 后端。
if [ ! -f "$WORK/lib/libmpv.a" ]; then
  say "构建 mpv 0.35.1（libmpv 静态库）"
  [ -d mpv ] || {
    curl -fL --retry 3 -o mpv.tar.gz https://github.com/mpv-player/mpv/archive/refs/tags/v0.35.1.tar.gz
    tar xzf mpv.tar.gz && mv mpv-0.35.1 mpv
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
    -Dlibmpv=true -Dcplayer=false -Dbuild-date=false -Dgpl=false \
    -Dlibplacebo=disabled -Dgl=enabled -Dios-gl=enabled \
    -Dcocoa=disabled -Dgl-cocoa=disabled -Dmacos-cocoa-cb=disabled \
    -Dmacos-media-player=disabled -Dmacos-touchbar=disabled \
    -Dalsa=disabled -Djack=disabled -Dpulse=disabled -Dpipewire=disabled -Dopenal=disabled \
    -Dsdl2=disabled -Dsdl2-audio=disabled -Dsdl2-video=disabled \
    -Dvaapi=disabled -Dvdpau=disabled -Dcuda-hwaccel=disabled -Dcuda-interop=disabled \
    -Ddrm=disabled -Dgbm=disabled -Dwayland=disabled -Dx11=disabled -Dxv=disabled -Dvulkan=disabled \
    -Dcaca=disabled -Djpeg=disabled -Dlcms2=disabled -Dlibarchive=disabled -Dlibbluray=disabled \
    -Dlua=disabled -Duchardet=disabled -Dzimg=disabled -Ddvbin=disabled -Ddvdnav=disabled \
    -Dcdda=disabled -Drubberband=disabled -Djavascript=disabled
  ninja -C mpv/build
  ninja -C mpv/build install
fi

# ---------------------------------------------------------------- 4. 打 xcframework
say "打包 xcframework"
LIBS="libmpv.a libavcodec.a libavformat.a libavutil.a libswscale.a libswresample.a libuavs3d.a libass.a libfreetype.a libfribidi.a"
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
