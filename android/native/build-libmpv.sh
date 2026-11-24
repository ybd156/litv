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

# ffmpeg 版本。
#
# 为什么是 n8.0 而不是 n7.1：
#   仓库里现有的 libmpv.so 是用 ffmpeg **git 快照 N-126627** 编的，
#   它引用了 av_dynamic_hdr_smpte2094_app5_alloc / _from_t35 两个符号，
#   而 n7.1 正式版还没有这两个（是 7.1 之后才加进 libavutil 的）。
#   用 n7.1 重编会让 libmpv 在 dlopen 时找不到符号，整个内核不可用。
#
#   实测 n7.1 和 n8.0 都没有这两个符号（8.0 的 libavutil 只导出 600 个，
#   旧快照 630 个），说明它们是 8.0 之后才进主线的。所以只能用 master。
#
#   是否真的兼容**不靠猜**——CI 里有 tools/checks/check-av-abi.sh
#   逐符号比对，不通过就不发布。
# 固定到仓库里现有 libmpv.so 所对应的那个 ffmpeg commit。
# 从旧 libavutil.so 的版本串里读出来的：N-126627-g094a2f8a2a。
# 只有用同一个 commit 重编，libmpv 引用的符号才一定都在。
#
# ★ 2026-11-24 改：默认换成 **n6.1**，与脚本里钉的 mpv v0.36.0 配对。
#
# 为什么不能继续用 094a2f8a2a（n8 那一代）：mpv 0.36 用的是已被 ffmpeg 删除的 API，
# 真机日志（Native Build 45191092177）：
#   ad_spdif.c: use of undeclared identifier 'FF_PROFILE_UNKNOWN'
#   ad_spdif.c: 'FF_PROFILE_DTS_HD_HRA'
#   af_lavcac3enc.c: call to undeclared function 'avcodec_close'
#   af_lavcac3enc.c: no member named 'ch_layouts' / 'sample_fmts' in 'struct AVCodec'
# FF_PROFILE_* 在 7.0 改名成 AV_PROFILE_*，avcodec_close / AVCodec.ch_layouts 也在 7.x 被移除。
#
# 上面"必须同一个 commit"的约束**只在复用仓库里预编译的 libmpv.so 时成立**
# （SKIP_MPV=1 那条路）。现在 SKIP_MPV=0，mpv 和 ffmpeg 一起从源码编，
# 只要两者配对即可 —— n6.1 ↔ mpv 0.36 是同一代，API 对得上。
# 真要换回 n8，就得同时把 mpv 升到 0.41+（并处理 0.37+ 的 libplacebo 硬依赖）。
FFMPEG_TAG="${FFMPEG_TAG:-n6.1}"

# 只重编 ffmpeg（复用仓库里已有的 libmpv.so）。
#
# 为什么需要这个开关：mpv 0.39 强制依赖 libplacebo，而编 libplacebo 又要
# vulkan/glslang/shaderc 一大串，CI 里成本很高。
# 但 TLS 完全在 ffmpeg 里（openssl 编进 libavformat.so），
# mpv 本身不需要改——只要 ffmpeg 版本一致（都是 n7.1），
# 旧的 libmpv.so 直接链新的 libavformat.so 就行。
SKIP_MPV="${SKIP_MPV:-0}"

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
  # openssl 装在独立目录：ffmpeg 换版本时要能单独删掉重建，
  # 不能连累 openssl（否则 configure 会报 openssl not found）。
  prefix="$OUT/$abi-deps"
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


  say "编译 ffmpeg [$abi]"
  if [ ! -d "$SRC/ffmpeg" ]; then
    # 用 GitHub 的 tag 归档而不是 ffmpeg.org：后者在国内/代理下经常
    # TLS handshake failure，GitHub 反而稳。
    local url
    case "$FFMPEG_TAG" in
      master)
        url="https://github.com/FFmpeg/FFmpeg/archive/refs/heads/master.tar.gz" ;;
      # 纯十六进制 = commit，走 archive/<sha>
      [0-9a-f]*)
        url="https://github.com/FFmpeg/FFmpeg/archive/${FFMPEG_TAG}.tar.gz" ;;
      *)
        url="https://github.com/FFmpeg/FFmpeg/archive/refs/tags/${FFMPEG_TAG}.tar.gz" ;;
    esac
    fetch_tar "$url" "$ROOT/dl/ffmpeg-$FFMPEG_TAG.tar.gz" || return 1
    mkdir -p "$SRC"
    tar xzf "$ROOT/dl/ffmpeg-$FFMPEG_TAG.tar.gz" -C "$SRC" \
      || { err "解包 ffmpeg 失败（压缩包不完整？）"; return 1; }
    # ★ 目录名不能按 "$FFMPEG_TAG" 硬拼。
    # 用**短 sha** 请求归档时，GitHub 解出来的顶层目录是**解析后的完整 sha**：
    #   请求 archive/094a2f8a2a.tar.gz  →  目录 FFmpeg-094a2f8a2a5e7fa64736e067de224ce28fdf5979
    # 2026-11-23 就是这一条让三个 ABI 全灭（日志：mv: cannot stat '.../FFmpeg-094a2f8a2a'，
    # 紧接着 "[err] ffmpeg configure 失败"）。改成「解出来的那个 FFmpeg-* 目录」，
    # 与 URL 形式（分支/tag/短 sha/长 sha）彻底解耦。
    local extracted
    extracted="$(ls -d "$SRC"/FFmpeg-* 2>/dev/null | head -1)"
    [ -n "$extracted" ] || { err "解包后找不到 FFmpeg-* 目录"; return 1; }
    rm -rf "$SRC/ffmpeg"
    mv "$extracted" "$SRC/ffmpeg" || { err "重命名 ffmpeg 源码目录失败"; return 1; }
  fi

  # 版本校验：CI 缓存会按前缀还原旧的构建产物，光看"文件存在"就跳过
  # 会导致换版本后仍然用旧库（踩过：改 n8.0 后日志还是"已编译"，
  # 结果发布出去的还是 n7.1 的库）。
  local marker="$prefix/.ffmpeg-tag"
  if [ -f "$prefix/lib/libavcodec.so" ]; then
    if [ "$(cat "$marker" 2>/dev/null)" = "$FFMPEG_TAG" ]; then
      ok "ffmpeg[$abi] 已编译（$FFMPEG_TAG）"; return 0
    fi
    say "缓存的 ffmpeg 版本是 $(cat "$marker" 2>/dev/null || echo 未知)，要的是 $FFMPEG_TAG，重建"
    rm -rf "$prefix"
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

  # 32 位 x86 的汇编不能进共享库，而且不止 nasm 那一层：
  #   1) ld.lld: relocation R_386_32 cannot be used against local symbol（--disable-x86asm 能挡 nasm）
  #   2) libswscale/x86/rgb2rgb_template.c: inline assembly requires more registers than available
  #      —— 这是 C 里的内联汇编，--disable-x86asm 管不到，必须 --disable-asm 才一起挡掉。
  # 模拟器上的 x86 只做「功能与稳定性对比」，不需要 SIMD 性能。
  local asm_flags=()
  if [ "$abi" = "x86" ]; then
    asm_flags=(--disable-asm)
  fi

  # openssl 装到 $prefix，必须让 pkg-config 找到它的 .pc，
  # 否则 ffmpeg 的 check_pkg_config 找不到 openssl 会直接 die。
  export PKG_CONFIG_PATH="$OUT/$abi-deps/lib/pkgconfig:$prefix/lib/pkgconfig:${PKG_CONFIG_PATH:-}"

  # ★ 参数里不要出现 --disable-postproc：libpostproc 已被 FFmpeg 从 master 移除。
  #   当初钉 FFMPEG_TAG=094a2f8a2a（n8 那一代）时，configure 直接
  #   "Unknown option \"--disable-postproc\"" 退出（2026-11-23 三个 ABI 全灭的真因）。
  #   现在虽然钉回 n6.1（那边还有 postproc），也不加——不需要就交给默认值，
  #   免得以后换版本又撞同一个坑。
  #   （注释只能写在这里——写进下面的续行命令里会把命令截断，preflight 的
  #    check-continuation.py 专门抓这个。）
  # ★ --disable-vulkan：IPTV 用不到；而且 armeabi-v7a 上它的 hwcontext_vulkan.c
  #   在新 clang 的 -Werror=incompatible-function-pointer-types 下直接编不过
  #   （2026-11-24 的失败点：PFN_vkDebugUtilsMessengerCallbackEXT 函数指针类型不兼容）。
  #   extra-cflags 里的 -Wno-incompatible-function-pointer-types 是同一类问题的兜底
  #   （mpv 侧也有同样的 const/函数指针代差）。
  "$SRC/ffmpeg/configure" \
    --prefix="$prefix" \
    --target-os=android --arch="${abi%%-*}" \
    --enable-cross-compile \
    --cc="$cc" --cxx="$cxx" \
    --sysroot="$TOOLCHAIN/sysroot" \
    --enable-shared --disable-static \
    --disable-programs --disable-doc \
    --disable-avdevice \
    --disable-vulkan \
    "${avs3_flags[@]}" \
    "${asm_flags[@]}" \
    --extra-cflags="-I$OUT/$abi-deps/include -fPIC -O2 -Wno-incompatible-function-pointer-types" \
    --extra-ldflags="-L$OUT/$abi-deps/lib" \
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
  echo "$FFMPEG_TAG" > "$prefix/.ffmpeg-tag"
  ok "ffmpeg[$abi] 完成（$FFMPEG_TAG）"
}

# ---------------------------------------------------------------- 2.5 mpv 的依赖
#
# mpv 从 0.30 起就把 libass 当硬依赖（meson.build 里 dependency('libass')
# 没有 required: 参数，等于 required: true），所以想编 mpv 必须先有 libass。
# libass 又要 freetype 和 fribidi。
#
# 这条链子之前一直没编，是因为仓库里那套 .so 来自别处（版本串能对上但
# 符号面多 30 个，明显带本地补丁）。现在要自己产出一整套一致的库，
# 就得把这条链补齐。
#
# 全部装到 $OUT/<abi>-deps，和 openssl 共用，重建 ffmpeg 不会连累它们。

DEPS_PREFIX() { echo "$OUT/$1-deps"; }

build_fribidi() {
  local abi="$1" triple prefix
  triple="$(triple_for "$abi")"; prefix="$(DEPS_PREFIX "$abi")"
  [ -f "$prefix/lib/libfribidi.a" ] && { ok "fribidi[$abi] 已编译"; return 0; }
  say "编译 fribidi [$abi]"

  if [ ! -d "$SRC/fribidi" ]; then
    fetch_tar "https://github.com/fribidi/fribidi/archive/refs/tags/v1.0.15.tar.gz" "$ROOT/dl/fribidi.tar.gz" || return 1
    mkdir -p "$SRC" && tar xzf "$ROOT/dl/fribidi.tar.gz" -C "$SRC" && mv "$SRC/fribidi-1.0.15" "$SRC/fribidi"
  fi

  local bdir="$SRC/fribidi-build-$abi"; rm -rf "$bdir"; mkdir -p "$bdir"
  cat > "$bdir/cross.txt" <<CROSS
[binaries]
c = '$TOOLCHAIN/bin/$( [ "$abi" = "armeabi-v7a" ] && echo armv7a-linux-androideabi || echo "$triple" )$API-clang'
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

  ( cd "$bdir" && meson setup build "$SRC/fribidi" --cross-file cross.txt       --prefix="$prefix" --buildtype=release --default-library=static -Db_staticpic=true       -Ddocs=false -Dbin=false -Dtests=false >meson.log 2>&1 &&     ninja -C build >>meson.log 2>&1 && ninja -C build install >>meson.log 2>&1 )     || { err "fribidi 编译失败"; tail -15 "$bdir/meson.log"; return 1; }
  ok "fribidi[$abi] 完成"
}

build_freetype() {
  local abi="$1" prefix
  prefix="$(DEPS_PREFIX "$abi")"
  [ -f "$prefix/lib/libfreetype.a" ] && { ok "freetype[$abi] 已编译"; return 0; }
  say "编译 freetype [$abi]"

  if [ ! -d "$SRC/freetype" ]; then
    fetch_tar "https://github.com/freetype/freetype/archive/refs/tags/VER-2-13-3.tar.gz" "$ROOT/dl/freetype.tar.gz" || return 1
    mkdir -p "$SRC" && tar xzf "$ROOT/dl/freetype.tar.gz" -C "$SRC" && mv "$SRC/freetype-VER-2-13-3" "$SRC/freetype"
  fi

  # 用 CMake 而不是 autotools：NDK 自带 android.toolchain.cmake，
  # 交叉编译一次就对，不用纠结 --host/--build 和 libtool。
  local bdir="$SRC/freetype-build-$abi"; rm -rf "$bdir"; mkdir -p "$bdir"
  ( cd "$bdir" && cmake "$SRC/freetype" -G Ninja       -DCMAKE_MAKE_PROGRAM="$(command -v ninja)"       -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"       -DANDROID_ABI="$abi" -DANDROID_PLATFORM="android-$API"       -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$prefix"       -DBUILD_SHARED_LIBS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON       -DFT_DISABLE_ZLIB=TRUE -DFT_DISABLE_PNG=TRUE -DFT_DISABLE_BZIP2=TRUE       -DFT_DISABLE_HARFBUZZ=TRUE -DFT_DISABLE_BROTLI=TRUE       >cmake.log 2>&1 && ninja -C . >>cmake.log 2>&1 && ninja -C . install >>cmake.log 2>&1 )     || { err "freetype 编译失败"; grep -iE "error|not found" "$bdir/cmake.log" | tail -10; return 1; }
  ok "freetype[$abi] 完成"
}

build_zlib() {
  local abi="$1" prefix
  prefix="$(DEPS_PREFIX "$abi")"
  [ -f "$prefix/lib/libz.a" ] && { ok "zlib[$abi] 已编译"; return 0; }
  say "编译 zlib [$abi]"

  # 为什么需要：mpv 的 meson 会用宿主 pkg-config 找 zlib，
  # 结果链到 /usr/lib/x86_64-linux-gnu/libz.so，报
  #   "incompatible with aarch64linux"
  # 给 Android 编一个放进 deps，pkg-config 就会优先找到它。
  if [ ! -d "$SRC/zlib" ]; then
    fetch_tar "https://github.com/madler/zlib/archive/refs/tags/v1.3.1.tar.gz" "$ROOT/dl/zlib.tar.gz" || return 1
    mkdir -p "$SRC" && tar xzf "$ROOT/dl/zlib.tar.gz" -C "$SRC" && mv "$SRC/zlib-1.3.1" "$SRC/zlib"
  fi

  local bdir="$SRC/zlib-build-$abi"; rm -rf "$bdir"; mkdir -p "$bdir"
  ( cd "$bdir" && cmake "$SRC/zlib" -G Ninja       -DCMAKE_MAKE_PROGRAM="$(command -v ninja)"       -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"       -DANDROID_ABI="$abi" -DANDROID_PLATFORM="android-$API"       -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$prefix"       -DBUILD_SHARED_LIBS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON       >cmake.log 2>&1 && ninja -C . >>cmake.log 2>&1 && ninja -C . install >>cmake.log 2>&1 )     || { err "zlib 编译失败"; grep -iE "error|not found" "$bdir/cmake.log" | tail -8; return 1; }

  # zlib 的 CMake 把 zlib.pc 装到 share/pkgconfig，
  # 而我们的 PKG_CONFIG_PATH 只含 lib/pkgconfig，导致 meson 找不到它、
  # 回退去用宿主的 zlib（报 incompatible with aarch64linux）。
  # 复制一份到 lib/pkgconfig，统一位置。
  if [ -f "$prefix/share/pkgconfig/zlib.pc" ] && [ ! -f "$prefix/lib/pkgconfig/zlib.pc" ]; then
    mkdir -p "$prefix/lib/pkgconfig"
    cp -f "$prefix/share/pkgconfig/zlib.pc" "$prefix/lib/pkgconfig/zlib.pc"
  fi
  ok "zlib[$abi] 完成"
}

build_harfbuzz() {
  local abi="$1" triple prefix
  triple="$(triple_for "$abi")"; prefix="$(DEPS_PREFIX "$abi")"
  [ -f "$prefix/lib/libharfbuzz.a" ] && { ok "harfbuzz[$abi] 已编译"; return 0; }
  say "编译 harfbuzz [$abi]"

  # libass 0.16/0.17 都要求 harfbuzz >= 1.2.3，绕不过去，只能编。
  # harfbuzz 是 meson 项目，glib/icu/cairo 都能关掉，编出来很小。
  if [ ! -d "$SRC/harfbuzz" ]; then
    fetch_tar "https://github.com/harfbuzz/harfbuzz/releases/download/8.3.0/harfbuzz-8.3.0.tar.xz" "$ROOT/dl/harfbuzz.tar.xz" || return 1
    mkdir -p "$SRC" && tar xf "$ROOT/dl/harfbuzz.tar.xz" -C "$SRC" && mv "$SRC/harfbuzz-8.3.0" "$SRC/harfbuzz"
  fi

  local bdir="$SRC/harfbuzz-build-$abi"; rm -rf "$bdir"; mkdir -p "$bdir"
  cat > "$bdir/cross.txt" <<CROSS
[binaries]
c = '$TOOLCHAIN/bin/$( [ "$abi" = "armeabi-v7a" ] && echo armv7a-linux-androideabi || echo "$triple" )$API-clang'
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

  ( cd "$bdir" && PKG_CONFIG_PATH="$prefix/lib/pkgconfig"       meson setup build "$SRC/harfbuzz" --cross-file cross.txt       --prefix="$prefix" --buildtype=release --default-library=static -Db_staticpic=true       -Dglib=disabled -Dgobject=disabled -Dicu=disabled -Dcairo=disabled       -Dchafa=disabled -Ddocs=disabled -Dtests=disabled -Dintrospection=disabled       -Dfreetype=enabled       >meson.log 2>&1 && ninja -C build >>meson.log 2>&1 && ninja -C build install >>meson.log 2>&1 )     || { err "harfbuzz 编译失败"; grep -iE "error|not found" "$bdir/meson.log" | tail -10; return 1; }
  ok "harfbuzz[$abi] 完成"
}

build_libass() {
  local abi="$1" triple prefix cc cxx
  triple="$(triple_for "$abi")"; prefix="$(DEPS_PREFIX "$abi")"
  [ -f "$prefix/lib/libass.a" ] && { ok "libass[$abi] 已编译"; return 0; }
  say "编译 libass [$abi]"

  # 用 0.16.0 而不是最新的 0.17.x：
  # 0.17 把 harfbuzz 变成了硬依赖（configure 里直接 error），
  # 而 harfbuzz 又要 icu/glib 一串。IPTV 直播根本不需要复杂文本整形，
  # 0.16 的 --disable-harfbuzz 仍然有效，mpv 0.36 也只要求 libass >= 0.12.2。
  if [ ! -d "$SRC/libass" ]; then
    fetch_tar "https://github.com/libass/libass/releases/download/0.16.0/libass-0.16.0.tar.gz" "$ROOT/dl/libass.tar.gz" || return 1
    mkdir -p "$SRC" && tar xzf "$ROOT/dl/libass.tar.gz" -C "$SRC" && mv "$SRC/libass-0.16.0" "$SRC/libass"
  fi

  if [ "$abi" = "armeabi-v7a" ]; then cc="$TOOLCHAIN/bin/armv7a-linux-androideabi$API-clang"; else cc="$TOOLCHAIN/bin/${triple}$API-clang"; fi
  cxx="${cc}++"
  local bdir="$SRC/libass-build-$abi"; rm -rf "$bdir"; mkdir -p "$bdir"
  ( cd "$bdir" &&     PKG_CONFIG_PATH="$prefix/lib/pkgconfig"     CC="$cc" CXX="$cxx" AR="$TOOLCHAIN/bin/llvm-ar" RANLIB="$TOOLCHAIN/bin/llvm-ranlib"     "$SRC/libass/configure" --host="$triple" --build="$(gcc -dumpmachine)" --prefix="$prefix"       --enable-static --disable-shared --with-pic --disable-fontconfig       --disable-require-system-font-provider       >configure.log 2>&1 && make -j"$JOBS" >>build.log 2>&1 && make install >>build.log 2>&1 )     || {
      err "libass 编译失败"
      [ -f "$bdir/configure.log" ] && { echo "--- configure.log 尾部 ---"; tail -25 "$bdir/configure.log"; }
      [ -f "$bdir/build.log" ] && { echo "--- build.log 尾部 ---"; tail -25 "$bdir/build.log"; }
      echo "--- deps 里的 pkgconfig ---"; ls "$prefix/lib/pkgconfig" 2>/dev/null
      return 1
    }
  ok "libass[$abi] 完成"
}

# ---------------------------------------------------------------- 3. mpv
build_mpv() {
  local abi="$1" prefix
  prefix="$OUT/$abi"
  [ -f "$prefix/lib/libmpv.so" ] && { ok "mpv[$abi] 已编译"; return 0; }

  say "编译 mpv [$abi]"
  if [ ! -d "$SRC/mpv" ]; then
    fetch_tar "https://github.com/mpv-player/mpv/archive/refs/tags/v0.36.0.tar.gz" "$ROOT/dl/mpv.tar.gz" || return 1
    mkdir -p "$SRC" && tar xzf "$ROOT/dl/mpv.tar.gz" -C "$SRC" && mv "$SRC/mpv-0.36.0" "$SRC/mpv"
  fi

  # mpv 0.36 和 ffmpeg 7+/8+ 有一处已知不兼容：
  #   ad_spdif.c 的 write_packet 参数是 uint8_t*，
  #   而新版 ffmpeg 的 avio_alloc_context 要求 const uint8_t*。
  #   新 clang 把 -Wincompatible-function-pointer-types 当错误，直接编不过。
  #   mpv 0.37+ 才修，但 0.37 强制 libplacebo，所以这里给 0.36 打补丁。
  local spdif="$SRC/mpv/audio/decode/ad_spdif.c"
  if [ -f "$spdif" ] && ! grep -q "const uint8_t \*buf, int buf_size" "$spdif"; then
    # 只在 write_packet 那一行加 const，不动别的
    sed -i '/^static int write_packet(/ s/uint8_t \*buf/const uint8_t *buf/' "$spdif"
    say "已给 ad_spdif.c 打 const 补丁"
  fi

  # ffmpeg 7.1 起把 AV_OPT_TYPE_CHANNEL_LAYOUT 改名成 AV_OPT_TYPE_CHLAYOUT，
  # mpv 0.36 还在用旧名，编译直接报 undeclared identifier。
  # 只改这一处符号引用，语义完全一致。
  local lavfi="$SRC/mpv/filters/f_lavfi.c"
  if [ -f "$lavfi" ] && grep -q "AV_OPT_TYPE_CHANNEL_LAYOUT" "$lavfi"; then
    sed -i 's/AV_OPT_TYPE_CHANNEL_LAYOUT/AV_OPT_TYPE_CHLAYOUT/g' "$lavfi"
    say "已给 f_lavfi.c 打 AV_OPT_TYPE_CHLAYOUT 改名补丁"
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
  # 0.37+ 把 libplacebo 变成硬依赖，0.36 还能关。
  # 编 libplacebo 要 vulkan/glslang/shaderc 一整串，对 IPTV 没用（我们只用 vo=gpu）。
  # 兜底：mpv 0.36 有些地方还在用非 const 的函数指针，新版 ffmpeg 改了签名。
  # 这些差异是纯 const 正确性、行为等价，降级成警告即可，
  # 免得每碰到一处就要打一个补丁、多跑一轮 CI。
  PKG_CONFIG_PATH="$(DEPS_PREFIX "$abi")/lib/pkgconfig:$(DEPS_PREFIX "$abi")/share/pkgconfig:$prefix/lib/pkgconfig" meson setup build "$SRC/mpv" \
    --cross-file "$bdir/cross.txt" \
    --prefix="$prefix" \
    --buildtype=release \
    -Dlibmpv=true \
    -Dc_args=-Wno-incompatible-function-pointer-types \
    -Dlibplacebo=disabled \
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
    >"$bdir/meson.log" 2>&1 || {
    err "mpv meson 配置失败"
    echo "===MESONLOG_START==="
    cat "$bdir/meson.log" 2>&1 | tail -40
    echo "===MESONLOG_END==="
    # 同时用 ::error:: 注解发一遍——GitHub 会截断超长日志，
    # 但注解会单独保留，这是唯一可靠拿到错误内容的方式。
    tail -12 "$bdir/meson.log" 2>/dev/null | while IFS= read -r l; do echo "::error::MPV $l"; done
    echo "pwd=$(pwd)  bdir=$bdir"
    ls -la "$bdir" 2>&1 | head -5
    return 1
  }

  ninja -C build -j"$JOBS" >>"$bdir/meson.log" 2>&1 || { err "mpv 编译失败，见 $bdir/meson.log"; return 1; }
  ninja -C build install >>"$bdir/meson.log" 2>&1
  ok "mpv[$abi] 完成"
}

# ---------------------------------------------------------------- 3.5 libplayer（JNI 桥）
#
# 为什么必须自己编：`libplayer.so` 是 is.xyz.mpv 的 JNI 桥（Kotlin 侧 MPVLib.kt 调它），
# 它和 ffmpeg 是**强耦合**的 —— 2026-11-24 真机证据：
#   旧 libplayer.so 引用 av_jni_set_android_app_ctx（ffmpeg 7/8 才有），
#   而我们为配 mpv 0.36 用的是 n6.1 → dlopen 直接失败
#   `cannot locate symbol "av_jni_set_java_vm"` → 「内核 mpv 不可用，跳过」。
# 所以 JNI 桥必须和 ffmpeg 同一代一起编，源码 vendored 在 native/libplayer-jni/
# （来自 mpv-android，GPL，见该目录 LICENSE.mpv-android）。
build_player_jni() {
  local abi="$1" prefix
  prefix="$OUT/$abi"
  local jni="$PROJ/native/libplayer-jni"
  [ -f "$jni/Android.mk" ] || { err "缺少 libplayer JNI 源码（native/libplayer-jni/Android.mk）"; return 1; }
  [ -f "$prefix/lib/libmpv.so" ] || { err "libplayer[$abi] 需要先有 libmpv.so"; return 1; }

  say "编译 libplayer（JNI 桥）[$abi]"
  local nb="$NDK/ndk-build"
  [ -x "$nb" ] || nb="$NDK/build/ndk-build"
  [ -x "$nb" ] || { err "找不到 ndk-build: $NDK"; return 1; }

  local bdir="$SRC/libplayer-build-$abi"
  rm -rf "$bdir" && mkdir -p "$bdir" || return 1

  # Android.mk 从 PREFIX*/lib 取预编译库、从 PREFIX*/include 取头文件，
  # 我们每个 ABI 只有一个前缀，所以四个变量都指向同一个目录。
  (
    cd "$jni" || exit 1
    "$nb" \
      NDK_PROJECT_PATH="$bdir" \
      APP_BUILD_SCRIPT="$jni/Android.mk" \
      NDK_APPLICATION_MK="$jni/Application.mk" \
      APP_ABI="$abi" \
      APP_PLATFORM=android-21 \
      APP_CFLAGS="-Wno-error" \
      PREFIX32="$prefix" PREFIX64="$prefix" PREFIX_X86="$prefix" PREFIX_X64="$prefix" \
      NDK_OUT="$bdir/obj" NDK_LIBS_OUT="$bdir/libs" \
      -j"$JOBS" >>"$bdir/build.log" 2>&1
  ) || {
    err "libplayer[$abi] 编译失败，日志尾部："
    tail -25 "$bdir/build.log" 2>/dev/null
    return 1
  }

  local so="$bdir/libs/$abi/libplayer.so"
  [ -f "$so" ] || { err "libplayer[$abi] 没产出 .so"; return 1; }
  cp -f "$so" "$prefix/lib/libplayer.so" || return 1
  ok "libplayer[$abi] 完成"
}

# ---------------------------------------------------------------- 4. 汇总到工程
collect() {
  local abi="$1" dest="$PROJ/app/src/main/jniLibs/$abi"
  mkdir -p "$dest"

  # strip 掉调试符号。
  # 前面 make install 传了 STRIP=true（宿主的 strip 不认 Android 的 .so，
  # 不跳过会报 Unable to recognise the architecture 导致 install 半途而废），
  # 结果产物带着完整调试信息：libavcodec.so 从 12MB 涨到 59MB，APK 直接翻倍。
  # 这里用 NDK 自带的 llvm-strip 补上。
  local strip_bin="$TOOLCHAIN/bin/llvm-strip"
  if [ -x "$strip_bin" ]; then
    for so in "$OUT/$abi/lib/"*.so; do
      [ -f "$so" ] && "$strip_bin" --strip-unneeded "$so" 2>/dev/null
    done
    say "已 strip [$abi]"
  else
    err "找不到 llvm-strip，产物会很大"
  fi
  for so in libmpv.so libplayer.so libavcodec.so libavformat.so libavutil.so libswscale.so libswresample.so; do
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
  # mpv 的依赖链：libass 是硬依赖，libass 又要 freetype + fribidi。
  # 只在真要编 mpv 时才走这条，避免浪费 CI 时间。
  if [ "$SKIP_MPV" != "1" ]; then
    build_fribidi  "$abi" || { err "fribidi[$abi] 失败，跳过该 ABI"; continue; }
    build_freetype "$abi" || { err "freetype[$abi] 失败，跳过该 ABI"; continue; }
    build_zlib     "$abi" || { err "zlib[$abi] 失败，跳过该 ABI"; continue; }
    build_harfbuzz "$abi" || { err "harfbuzz[$abi] 失败，跳过该 ABI"; continue; }
    build_libass   "$abi" || { err "libass[$abi] 失败，跳过该 ABI"; continue; }
  fi

  if [ "$SKIP_MPV" = "1" ]; then
    say "跳过 mpv（SKIP_MPV=1，复用仓库里已有的 libmpv.so）"
  else
    build_mpv   "$abi" || { err "mpv[$abi] 失败，跳过该 ABI"; continue; }
    # JNI 桥必须和上面这套 ffmpeg/libmpv 同一代编，否则 dlopen 会缺符号
    build_player_jni "$abi" || { err "libplayer[$abi] 失败，跳过该 ABI"; continue; }
  fi
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
  # libmpv 的校验分两种情况：
  #   SKIP_MPV=0：必须本次真编出来。仓库里那份是旧 ffmpeg 的产物，
  #               和新的 ffmpeg 库混在一起会 dlopen 失败。
  #   SKIP_MPV=1：允许复用仓库里的旧 libmpv（这时 ABI 门禁负责把关）。
  if [ "$SKIP_MPV" = "1" ]; then
    if [ ! -f "$OUT/$abi/lib/libmpv.so" ] && [ ! -f "$PROJ/app/src/main/jniLibs/$abi/libmpv.so" ]; then
      err "$abi 既没编出 libmpv.so，仓库里也没有"; missing=1
    fi
  else
    [ -f "$OUT/$abi/lib/libmpv.so" ] || { err "$abi 本次没编出 libmpv.so（SKIP_MPV=0 要求必须编）"; missing=1; }
  fi
  [ -f "$OUT/$abi/lib/libavformat.so" ] || { err "$abi 没有编出 libavformat.so"; missing=1; }
done
[ "$missing" = "0" ] || { err "有 ABI 没编出产物，构建失败"; exit 1; }

echo "LIBMPV_DONE"
