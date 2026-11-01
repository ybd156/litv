# iOS 端：libmpv 依赖链验证记录（2026-11-02）

> 目标：在 iOS 上用 libmpv，产出未签名 ipa。
> 结论：**ipa 已出、App 已可用（AVPlayer）**；libmpv 卡在依赖链最后一层 libass。

---

## 一、本地验证过的（真编过，不是推测）

在 WSL 里用本机 gcc + meson 1.12 + cmake 3.31 把每个依赖单独编了一遍，
把所有**与平台无关**的问题一次性挖干净，避免在 CI 上盲试。

| 依赖 | 验证动作 | 结果 | 踩到的坑 |
|---|---|---|---|
| **fribidi 1.0.15** | 打补丁后本地 meson 编译 + 安装 | ✅ 通过 | 代码生成器 `gen.tab/*.c` 没 include `<stdlib.h>`/`<string.h>` 就用了 exit/strcpy/strstr/strrchr；clang 16+ 把隐式函数声明当**错误**。`CFLAGS` 对 meson 的 `native:true` 目标不生效，必须直接给源码补 include |
| **freetype 2.13.2** | cmake 配置 + 编译 + 安装 | ✅ 通过 | `FT_DISABLE_PNG/BZIP2/HARFBUZZ/BROTLI` 这些选项名有效 |
| **mpv 0.35.1** | meson 参数校验 | ✅ 全部有效 | 40 多个 `-D` 开关逐个验证，没有 Unknown option；只因本地缺 ffmpeg 停在 libavcodec |
| **uavs3d** | CI 上编出 | ✅ 通过 | 见下节四个坑 |
| **FFmpeg 7.1** | CI 上编出 | ✅ 通过 | ffmpeg.org 在 CI 上常超时 → 换 GitHub 归档 |

## 二、CI 上已攻克的坑（10 次迭代换来的）

1. `runs-on: macos-14` → job 3 秒内被取消（连 step 都不跑）→ 换 `macos-latest`
2. CMake 4 拒绝 `cmake_minimum_required(<3.5)` → `-DCMAKE_POLICY_VERSION_MINIMUM=3.5`
3. uavs3d 默认会编命令行程序 `uavs3dec`，iOS 下链接失败 → `--target uavs3d`
4. uavs3d 不生成 `.pc`，ffmpeg 的 `--enable-libuavs3d` 靠 pkg-config 找库 → 手写 `uavs3d.pc`
5. `CMAKE_SYSTEM_PROCESSOR` 没设成 arm64 → uavs3d 的 SIMD 汇编被跳过，留下未定义符号 `uavs3d_funs_init_arm64` → 显式指定
6. mpv 0.39 的 libplacebo 是硬依赖 → 降到 0.35.1
7. mpv 0.35.1 的 libass 仍是硬依赖 → 只能把 libass 编出来

## 三、唯一剩下的卡点

**libass 0.17.1 只有 autotools**（`configure.ac` + `Makefile.am`），
没有 meson.build，也没有 CMakeLists。

需要：
```bash
brew install autoconf automake libtool
./autogen.sh
./configure --host=arm64-apple-darwin \
  --prefix="$WORK" --disable-shared --enable-static \
  --disable-fontconfig --disable-harfbuzz --disable-libunibreak \
  CC="$(xcrun --sdk iphoneos -f clang)" \
  CFLAGS="-arch arm64 -isysroot $(xcrun --sdk iphoneos --show-sdk-path) -miphoneos-version-min=14.0"
make && make install
```

这是标准做法，但**我没在本地验证过**（WSL 装 autotools 时卡在 perl 模块路径）。
所以它是否一次过，仍是未知数。

## 四、三条路与我的建议

| 方案 | 成本 | 风险 | 结果 |
|---|---|---|---|
| **A. 补 autotools 编 libass** | CI 里加 `brew install autoconf automake libtool` + configure 段 | 中（autotools 交叉编译要传对一堆变量） | 通了就有真正的 libmpv |
| **B. 换 MobileVLCKit 预编译** | 加一个 CocoaPods 依赖 | 低 | 能力覆盖甚至比 mpv 更全（AC3/EAC3/DTS/TS），LGPL 比 GPL 宽松 |
| **C. 接受 AVPlayer 现状** | 0 | 0 | 少 AC3/AVS3；服务端可转 AAC 兜底 |

**建议 B**：libass 这层过了之后还有 mpv 本身的 iOS 适配（渲染后端 ios-gl、
音频 audiotune、依赖 FFmpeg 静态链接），每一层都可能有新问题。
而 VLCKit 是预编译 xcframework，一次链接搞定，达成同样的目的。

**如果坚持 mpv**：走 A，我按上面那段写进脚本再试。

## 五、已沉淀的资产（无论选哪条都算数）

- `ios/native/build-xcframework-macos.sh`：uavs3d + FFmpeg 的 iOS 交叉编译**可用**
- `.github/workflows/ios-xcframework.yml`：CI 骨架可用
- 上面 7 个坑全部写在脚本注释里
- iOS App 本体（SwiftUI + AVPlayer）已完整可用，ipa 已产出
