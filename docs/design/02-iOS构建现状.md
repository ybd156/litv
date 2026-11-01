# iOS 端构建：现状与卡点（2026-11-02 更新）

> 目标：在 iOS 上用 **libmpv** 做播放内核，产出**未签名** `.ipa`。
> 现状：**ipa 已经能出、App 已经能用**（AVPlayer 内核）；**libmpv 的交叉编译卡在依赖链第 4 层**。

---

## 一、已经交付的

| 项 | 状态 |
|---|---|
| SwiftUI 客户端 | ✅ 7 个 Swift 文件，绑定/首页/播放/EPG/设置 |
| 未签名 ipa | ✅ `LITV-unsigned.ipa`（Mach-O arm64，无 `_CodeSignature`） |
| 构建通路 | ✅ GitHub Actions `macos-latest` + xcodegen + xcodebuild |
| 播放内核 | ✅ AVPlayer（HLS / H.264 / HEVC / AAC） |
| 服务端配合 | ✅ 裸 TS 自动转封装成 HLS，iOS 能播任何源 |

仓库：<https://github.com/ybd156/litv>

## 二、libmpv 的依赖链（这是难点所在）

mpv 不是一个能独立编的库，它要求一整条链：

```
mpv 0.35.1
 ├── libass          （字幕，**无条件必需**，0.35 之后没有关闭开关）
 │    ├── fribidi    （双向文本，必需）
 │    └── freetype   （字体解析，必需）
 ├── libplacebo      （0.36+ 变成无条件必需；0.35.1 可关）
 └── FFmpeg
      └── libuavs3d  （AVS3 解码，可选）
```

## 三、逐个攻克的进度

| 库 | 状态 | 踩过的坑 |
|---|---|---|
| **libuavs3d** | ✅ 编出来了 | ① CMake 4 拒绝 `cmake_minimum_required(<3.5)` → `-DCMAKE_POLICY_VERSION_MINIMUM=3.5`<br>② 默认还会编命令行程序 `uavs3dec`，iOS 下链接失败 → `--target uavs3d`<br>③ 不生成 `.pc` 文件，ffmpeg 找不到 → 手写 `uavs3d.pc`<br>④ `CMAKE_SYSTEM_PROCESSOR` 没设成 arm64，SIMD 汇编被跳过，留下未定义符号 → 显式指定 |
| **FFmpeg** | ✅ 编出来了 | ffmpeg.org 在 CI 上常超时 → 换 GitHub 归档 |
| **mpv** | ⏳ 卡在依赖 | 0.39 的 libplacebo 是硬依赖 → 降到 0.35.1；0.35.1 的 libass 又是硬依赖 |
| **fribidi** | ❌ **当前卡点** | meson 交叉编译时把代码生成器 `gen-unicode-version` 当目标平台产物编，iOS 上失败；而它**没有 CMakeLists**（只有 meson + autotools） |
| freetype | ⏳ 未开始 | — |
| libass | ⏳ 未开始 | — |

## 四、当前卡点的具体形态

```
FAILED: gen.tab/gen-unicode-version.p/gen-unicode-version.c.o
ninja: build stopped: subcommand failed.
```

`gen-unicode-version` 是个**构建期工具**（在宿主上跑，生成 Unicode 表），
meson 交叉编译时把它当成了 iOS 目标产物，于是链接失败。

### 三条出路

**A. 给 fribidi 打补丁**：在 CI 里 `sed` 它的 `meson.build`，给那个 executable 加 `native: true`。
- 成本：一次 sed，但要知道确切写法
- 风险：低

**B. 用 autotools 编 fribidi**：`./autogen.sh && ./configure --host=arm64-apple-darwin`
- 成本：需要 brew 装 autoconf/automake/libtool，CI 时间 +2 分钟
- 风险：中（autotools 交叉编译要传一堆变量）

**C. 换内核：用 MobileVLCKit 替代 libmpv**
- CocoaPods 上有预编译 xcframework，直接链接，**完全跳过整条依赖链**
- VLC 支持 AC3/EAC3/DTS/TS/AVS3(部分)，能力覆盖甚至比 mpv 更全
- License：LGPL-2.1+（比 mpv 的 GPL 更宽松）
- 代价：不是 mpv（你最初指定的），但**达成的目的完全一样**：补 AVPlayer 解不了的格式

## 五、我的判断

**推荐 C**。理由：

1. 依赖链还有 3 层（fribidi → freetype → libass），每层都有各自的交叉编译坑，
   已经花了 8 次 CI 迭代才推进到第 4 层
2. 这条链的产物**只为了补 AC3/AVS3 这类少数格式**——正常 HLS 直播 AVPlayer 已经能播
3. VLCKit 是预编译 xcframework，一次链接搞定，且能力不弱于 mpv
4. 维护成本差一个数量级：自编 mpv 意味着每次 ffmpeg/mpv 安全更新都要重跑整条链

如果坚持要 mpv，走 A（打补丁），我再试 2-3 轮应该能通。

## 六、无论哪条路，这些已经沉淀下来了

- `ios/native/build-xcframework-macos.sh` 里 uavs3d + FFmpeg 的 iOS 交叉编译是**可用的**，
  将来要做任何 iOS 媒体相关的东西都能复用
- `.github/workflows/ios-xcframework.yml` 的 CI 骨架可用
- 四个"反常识"的坑都写在脚本注释里了
