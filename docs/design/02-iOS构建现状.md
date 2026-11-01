# iOS 端：已完成（2026-11-02）

> 目标：iOS 原生客户端 + 未签名 ipa。
> 结果：**已交付**，播放内核用 **MobileVLCKit**（不是最初设想的 libmpv，原因见下）。

---

## 一、交付物

```
LITV-unsigned.ipa   17 MB
├── Payload/LITV.app/LITV                              890 KB   Mach-O arm64
├── Payload/LITV.app/Frameworks/MobileVLCKit.framework  38.6 MB  VLC 引擎
└── Payload/LITV.app/Info.plist                        com.litv.app / iOS 15.0+
```

- **未签名**（无 `_CodeSignature`、无 `embedded.mobileprovision`），用户用 Sideloadly / SideStore 自签安装
- 仓库：<https://github.com/ybd156/litv>
- 产物：Actions → `Build iOS unsigned IPA` → Artifacts → `LITV-unsigned-ipa`

## 二、两处与最初设想的偏差（都是拍板过的）

| 原计划 | 实际 | 为什么 |
|---|---|---|
| **xtool**（Linux 上编 iOS） | **GitHub Actions macOS runner** | xtool 在 WSL 里卡在 Swift 6.4.1 的产物落盘 bug + 系统 C 库头文件；macOS runner 自带 Xcode，这些问题一个都不存在 |
| **libmpv** | **MobileVLCKit** | libmpv 在 iOS 上要编六层依赖（见下），而 VLCKit 是预编译 xcframework，一次链接搞定，能力覆盖更全、License 更宽松 |

## 三、为什么放弃 libmpv（完整记录）

mpv 在 iOS 上的依赖链：

```
mpv 0.35.1
 ├── libass（**无条件必需**，0.35 之后没有关闭开关）
 │    ├── fribidi
 │    └── freetype
 ├── libplacebo（0.36+ 硬依赖；0.35.1 可关）
 └── FFmpeg
      └── libuavs3d（AVS3 解码）
```

**已在本地真编通的**（WSL 里逐个验证，不是猜）：

| 依赖 | 验证 | 结果 |
|---|---|---|
| libuavs3d | CI + 本地 | ✅ 四个坑全过 |
| FFmpeg 7.1 | CI | ✅ |
| fribidi 1.0.15 | 本地编译安装 | ✅ 补 include 后通过 |
| freetype 2.13.2 | 本地编译安装 | ✅ |
| mpv 0.35.1 | meson 参数逐个校验 | ✅ 40+ 开关全部有效 |

**卡在最后一层**：libass 只有 autotools（无 meson / 无 CMakeLists），
需要 autoconf + automake + libtool 交叉编译，本地验证时卡在 perl 模块路径上。

过了 libass 之后还有 mpv 本体的 iOS 适配（ios-gl 渲染后端、audiotune、FFmpeg 静态链接）。
考虑到这条链的产出**只是补 AC3/AVS3**（正常 HLS 直播 AVPlayer 已经能播），
收益与成本不成比例，改用 VLCKit。

## 四、VLCKit 方案的技术细节

**依赖**：`ios/Podfile` → `pod 'MobileVLCKit', '~> 3.5'`，`use_frameworks! :linkage => :static`

**封装**：`VLCPlayerView.swift`（UIViewRepresentable 包 VLCMediaPlayer）
- 直播低延迟参数：`--no-drop-late-frames --no-skip-frames --network-caching=1500 --live-caching=1500 --avcodec-hw=any`
- 用 `#if canImport(MobileVLCKit)` 包住：万一 Pod 没装上，整个文件退化成空实现，
  App 仍能用 AVPlayer 编译通过——**不让一个可选内核拖垮整个构建**

**双内核策略**：VLCKit 优先（能解 AC3/DTS/裸 TS），VLC 起不来时自动退回 AVPlayer。

**服务端配合**：iOS 请求播放地址时带 `fmt=hls`，裸 TS 源由服务端转封装成 HLS。

## 五、这一路踩过的坑（全部写进脚本注释）

1. `runs-on: macos-14` → job 3 秒内被取消（连 step 都不跑）→ 换 `macos-latest`
2. CMake 4 拒绝 `cmake_minimum_required(<3.5)` → `-DCMAKE_POLICY_VERSION_MINIMUM=3.5`
3. uavs3d 默认会编命令行程序 `uavs3dec`，iOS 下链接失败 → `--target uavs3d`
4. uavs3d 不生成 `.pc` → 手写 `uavs3d.pc`
5. `CMAKE_SYSTEM_PROCESSOR` 没设成 arm64 → SIMD 汇编被跳过，留下未定义符号
6. ffmpeg.org 在 CI 上常超时 → 换 GitHub 归档
7. mpv 0.39 的 libplacebo 是硬依赖 → 降到 0.35.1
8. fribidi 代码生成器缺 include，clang 16+ 把隐式函数声明当错误 →
   `CFLAGS` 对 meson 的 `native:true` 目标不生效，必须直接给源码补 include
9. VLCKit 3.5 的 `addOptions` 收 NSDictionary，传数组编译不过 → 改 `addOption(_:)`
10. **CI 编译步骤漏了 `set -o pipefail`** → `xcodebuild ... | tail` 把失败吞掉，
    表现成「构建成功但 ipa 只有 1.7KB、没有主二进制」。
    **这种假成功比真失败更危险**，已加注释防回归。

## 六、遗留

`ios/native/build-xcframework-macos.sh` 保留着（uavs3d + FFmpeg 的 iOS 交叉编译是**可用**的），
将来若要继续做 libmpv，从 libass 那一层接着做即可。
