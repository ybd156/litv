# LITV · IPTV 多终端管理平台

自建的 IPTV 源管理 + 播放平台，跑在飞牛 fnOS 上，一套服务端带三端客户端
（Android TV / Android 手机 / iOS）。

> **本仓库不提供、不内置、不托管任何直播源。** 所有源由使用者自行导入，
> 源的合法性由使用者自负。

<!-- AUTO:VERSION:START -->
当前版本：**0.1.19** · [下载](https://github.com/ybd156/litv/releases/latest)
<!-- AUTO:VERSION:END -->

---

## 它解决什么问题

手上有一堆 IPTV 源（M3U），想在家里电视、手机、iPad 上都能看，还要：

- 多个源自动择优，某个台卡了自动换线路
- 有节目单（EPG），知道现在播什么、下一档是什么
- 能回看、能预约录制
- 手机能当遥控器，也能投到电视上
- 管理台能改配置、看设备、看日志

自己搭一套要写不少东西，这个项目就是把这些拼起来。

---

## 功能

### 直播

- 多源自动择优：定期探测每条线路的健康度与延迟，按最优选选路
- 流扇出：3 台设备看同一个台只占 1 份上行带宽
- 起播快：新终端直接从环形缓冲的最近 GOP 起播
- 裸 TS → HLS 转封装（只转封装不转码，几乎不吃 CPU）

### 节目单（EPG）

完整链路，从拉到用：

- **源管理**：定时自动刷新（cron）、单源刷新、编辑、优先级、失败重试、
  自定义 UA/Referer、导入本地 XMLTV、体积与条数上限
- **频道映射**：自动匹配（带置信度）、批量映射、按台号匹配、
  未匹配诊断（给最佳候选）、冲突提示
- **运维**：总览统计、一键清理过期节目、刷新审计、连续失败告警、节目搜索
- **客户端**：按天切换、正在播进度、节目详情、分类筛选、
  开播提醒、从节目单预约录制

### 投屏（DLNA）

- **发现由服务端做**：NAS 发 M-SEARCH 找局域网里的 MediaRenderer，
  手机只调接口拿列表
- 为什么不让手机自己发现：iOS 14+ 对本地网络多播有限制，SSDP 需要
  Apple 单独批准的 `multicast` 权限（要审核，自签包拿不到）。
  NAS 和电视在同一局域网，由它发现没有限制，安卓和 iOS 还能共用一套逻辑
- 投给电视的是**服务端中转地址**（带签名、绑定线路），不是源站地址——
  电视不一定能直连源站，而 NAS 一定能

### 其他

- **回看 / 时移**：边看边录，默认保留约 1 小时；回看起点对齐关键帧
- **录制中心**：立即录制与预约录制，`-c copy` 不转码
- **手机遥控**：WebSocket 中转，跨网段可用，电视不需要开端口
- **跨端续播**：客厅看一半，卧室接着看
- **源失效告警**：Telegram / 自定义 webhook，30 分钟内去重
- **管理台**：Vue 3 + Naive UI，深色主题，含直播预览页

---

## 下载

<!-- AUTO:DOWNLOAD:START 本段由 .github/workflows/readme-downloads.yml 自动生成，不要手改 -->
当前发布版 **[v0.1.19](https://github.com/ybd156/litv/releases/tag/v0.1.19)**，点文件名直接下载：

| 产物 | 大小 | 用途 |
|---|---|---|
| [`LITV-0.1.19-tv-armv7.apk`](https://github.com/ybd156/litv/releases/download/v0.1.19/LITV-0.1.19-tv-armv7.apk) | 65.8 MB | **电视 / 盒子，32 位**（Android 9 及以下装这个） |
| [`LITV-0.1.19-tv-arm64.apk`](https://github.com/ybd156/litv/releases/download/v0.1.19/LITV-0.1.19-tv-arm64.apk) | 73.0 MB | 电视 / 盒子，64 位 |
| [`LITV-0.1.19-phone-armv7.apk`](https://github.com/ybd156/litv/releases/download/v0.1.19/LITV-0.1.19-phone-armv7.apk) | 65.8 MB | 手机，32 位（2018 年前的老机型） |
| [`LITV-0.1.19-phone-arm64.apk`](https://github.com/ybd156/litv/releases/download/v0.1.19/LITV-0.1.19-phone-arm64.apk) | 73.0 MB | 手机，64 位（绝大多数手机装这个） |
| [`LITV-0.1.19-unsigned.ipa`](https://github.com/ybd156/litv/releases/download/v0.1.19/LITV-0.1.19-unsigned.ipa) | 16.5 MB | iOS（需自签） |
| [`litv_x86_0.1.19.fpk`](https://github.com/ybd156/litv/releases/download/v0.1.19/litv_x86_0.1.19.fpk) | 6.2 MB | 飞牛 NAS（x86_64） |
| [`litv_arm_0.1.19.fpk`](https://github.com/ybd156/litv/releases/download/v0.1.19/litv_arm_0.1.19.fpk) | 5.8 MB | 飞牛 NAS（ARM） |
| [`SHA256SUMS.txt`](https://github.com/ybd156/litv/releases/download/v0.1.19/SHA256SUMS.txt) | — | 校验和 |

**不知道装哪个**：在设备上跑 `adb shell getprop ro.product.cpu.abi`
（或看系统信息），`armeabi-v7a` 装 armv7，`arm64-v8a` 装 arm64。

旧版本在 [Releases 列表](https://github.com/ybd156/litv/releases)。
<!-- AUTO:DOWNLOAD:END -->

**不知道装哪个**：在设备上跑 `adb shell getprop ro.product.cpu.abi`
（或看系统信息），`armeabi-v7a` 装 armv7，`arm64-v8a` 装 arm64。

旧版本在 [Releases 列表](https://github.com/ybd156/litv/releases)。

### 电视版和手机版的区别

代码是同一份，差别只有默认界面形态和包名：

| | 包名 | 默认界面 |
|---|---|---|
| 电视版 | `com.litv.tv` | 全屏播放 + 浮层频道列表 |
| 手机版 | `com.litv.tv.phone` | 竖屏列表 + 底部 Tab |

**包名不同，所以两个可以同时装在一台设备上。**

界面形态优先级：设置里手动指定 > 安装的是哪个版本 > 设备特征自动判断。
所以手机版装到电视上不会跑成竖屏小窗。

## 安装

### 飞牛 NAS

1. 从 Release 下载对应架构的 `.fpk`
2. 飞牛「应用中心 → 手动安装」选择该文件
3. 安装向导里填端口（**默认 28027**）、时区、是否允许局域网访问
4. 装完在应用中心打开「LITV」

服务端首次启动会自动生成 `config.yaml` 与数据库，位于应用数据目录。

### 客户端

装好后打开，输入服务器地址（`http://<NAS-IP>:28027`），
拿到一个**绑定码**，到管理台「设备」页批准即可。

同一台设备重装 / 清数据**不需要重新批准**——客户端用系统稳定的设备标识
（Android 上是 `ANDROID_ID` 派生）重新认回原设备。

---

## 开发

### 编译一律走 GitHub Actions

**不要在本地搭工具链。** 改完代码推上去让 CI 编。

| 工作流 | 触发 | 做什么 |
|---|---|---|
| `Build Check` | push 到 main、PR、手动 | Go（gofmt+build+vet+test -race）、管理台（typecheck+build）、Android 两个 flavor |
| `iOS Build Check` | 只在 `ios/**` 变化时 | 编译 iOS（不发版） |
| `Release` | `v*` tag 或手动 | 出三端产物 |

原因：

- 本地要装 Go 1.23 + JDK + Android SDK 35 + NDK + Gradle 8.14.6 + Node 24 一整套，
  换台机器就得重搭
- 版本稍有差异就会出现「我这里能编过」
- **iOS 本地根本编不了**（需要 Apple SDK）

仓库是 public，标准 runner 全部免费（含 macOS）。详见 [AGENTS.md](AGENTS.md) §8。

### 本地能做什么

不用编译就能做的事：

```bash
# 看 Go 代码格式对不对（CI 会卡这个）
gofmt -l server

# 一致性检查
python3 tools/checks/check-api-contract.py
python3 tools/checks/check-swift-balance.py
```

要跑完整的构建/测试，推到分支上看 CI 结果。

---

## 测试

### 单元测试

`Build Check` 里跑 `go test -race ./...`，覆盖 `api` / `config` / `epg` /
`store` / `stream` / `timeshift` 六个包。

约定：**修 bug 先写一条在旧代码上会失败的测试**。这个仓库里有过两次
「测试是绿的但根本没测到东西」的经历，所以新增测试必须证明它在修复前会红。

### 集成脚本

`build/test/` 下是版本管理的集成脚本，需要一台跑着服务的机器：

```bash
bash build/test/e2e.sh            # 终端链路端到端
bash build/test/remote-test.sh    # 遥控通道（WS 中转）
bash build/test/timeshift-test.sh # 时移录制与回看
bash build/test/remux-test.sh     # 裸 TS → HLS 转封装
bash build/test/record-test.sh    # 录制中心含调度器
bash build/test/alert-test.sh     # 源失效告警含 webhook
bash build/test/web-test.sh       # Web 端经网关取流
bash build/test/dlna-test.sh      # DLNA 发现/浏览/播放
bash build/test/smoke.sh          # API 冒烟
bash build/test/wizard-yaml-test.sh        # 安装向导的 YAML 转义（含恶意密码）
bash build/test/verify-frontend-embed.sh   # 前端是否真的嵌进了二进制
```

---

## 架构

### 双入口

| 入口 | 谁用 | 鉴权 | 传输 |
|---|---|---|---|
| `/app/litv/*` | 管理台（浏览器） | 飞牛网关注入 `X-Trim-*` | Unix Socket |
| `:28027/api/*` | 三端 App | 设备 token（绑定码换取） | TCP |
| `/live/*` | 播放器 | 短期签名 URL（HMAC + 过期） | HTTP 流 |

**为什么不能只走网关**：网关只覆盖浏览器会话，原生 App 不带飞牛 Cookie。

**为什么不能只走端口**：那就放弃了「接入飞牛账号」。

**安全边界**：`X-Trim-*` 头**只在经网关（Unix Socket）时被信任**，
TCP 入口一律不认——否则任何人伪造一个头就是管理员。

### 流出口

```
一个频道 = 一路上游连接 + 一个环形缓冲 + N 个订阅者
新终端加入 → 从缓冲区最近的 GOP 起播
```

- 3 台设备看同一频道只占 1 份上行带宽
- 缓冲按 188 字节（TS 包长）对齐丢弃
- 下游太慢就丢块，绝不阻塞上游
- **没有观众时不再写时移盘**（否则空闲频道会持续占用磁盘 IO）

### 目录

```
server/                  Go 服务端（chi + SQLite，无 cgo）
  cmd/litvd/             入口
  internal/
    api/                 HTTP 路由与 handler
    auth/                会话、token、流签名
    config/              config.yaml 加载与默认值
    store/               SQLite 数据访问
    stream/              上游扇出、环形缓冲
    epg/                 XMLTV 解析、定时刷新
    timeshift/           时移环形盘
    record/              录制调度
    remux/               裸 TS → HLS 转封装
    probe/               线路健康度探测
    parse/               M3U / TXT 源解析
    fetch/               出站抓取（含 SSRF 限制）
    logo/                台标抓取与缓存
    remote/              遥控 WebSocket 中转
    alert/               Telegram / webhook 告警
    dlna/                DLNA 服务端 + 投屏控制端
    web/                 go:embed 管理台静态资源
admin/                   Vue 3 + TS + Vite 管理台
android/                 Android TV + 手机（同一份代码，两个 flavor）
ios/                     SwiftUI（MobileVLCKit 主，AVPlayer 兜底）
packaging/fnos/          fpk 清单、生命周期脚本、安装向导
build/test/              集成测试脚本
tools/                   开发辅助脚本（checks / inspect / ops）
edge/                    Cloudflare 边缘降级部署（可选，代码开源、实例私有）
docs/                    设计文档、调研、审查记录
.github/workflows/       编译与发布
```

---

## 播放内核

三端都不是单一内核，按能力依次降级：

| 平台 | 主 | 兜底 |
|---|---|---|
| Android | Media3/ExoPlayer | VLC → mpv |
| iOS | MobileVLCKit | AVPlayer（仅 HLS） |
| Web | hls.js | — |

**为什么保留三个内核**：Exo 最快最省电，但遇到裸 TS / AC3 / 某些封装会失手；
VLC 兼容性最好但体积大；mpv 是最后的兜底。代价是包体——
`libvlc.so` 单个就 37MB，三个内核的原生库占了 APK 的 95%。

**iOS 为什么是 VLCKit 而不是 libmpv**：VLCKit 是唯一既能解裸 TS、
又能解 AC3/DTS、且能合法分发的选择。详细对比见
[总体方案](docs/design/00-总体方案.md)。

### AV3A 音频

AV3A 是 AVS3-P3（Audio Vivid）音频，中国国家标准。

**没有任何开源解码器**：FFmpeg 主线没有，所以 VLC / mpv 也不支持；
ExoPlayer 完全不认这个编码。

本项目的做法：自己补了一套 media3 扩展，把这条链路接通——

```
Av3aReader + Av3aTsPayloadReaderFactory   解复用：认出 AV3A 的 stream_type
Av3aDecoder (SimpleDecoder)               解码：调原生库
Av3aAudioRenderer (DecoderAudioRenderer)  输出：交给 AudioSink
```

**解码库不随仓库分发**（没有合法来源）。需要你自己把 `.so` 放到
App 的外部目录 `Android/data/<包名>/files/av3a/`，然后在设置里打开
「AV3A 音频解码」。没有库时这条链路完全不参与，其它音频照常走系统解码。

状态：**代码已接通，真机未验证出声**。所以遇到 AV3A 源如果还是没声音，
优先换同频道的非 AV3A 线路。

---

## 关键参数

**ExoPlayer 直播预设**（起播快是 IPTV 的命）：

```
setBufferDurationsMs(1000, 60000, 500, 1000)
setBackBuffer(0, true)
```

**mpv 直播预设**：

```
hwdec=mediacodec,mediacodec-copy,auto
video-latency-hacks=yes
profile=fast
demuxer-lavf-o=probesize=1500000,analyzeduration=1500000
cache-pause=yes
```

**交互常量**：数字跳台缓冲 2500ms、长按判定 800ms、双击退出 2000ms。

**电视端键位**：左 = 频道页、右 = 设置菜单、OK = OSD、长按 OK = 线路选择。

**电视端频道浮层**：三列（分组 / 频道 / 节目单）+ 底部信息条
（台标 · 台号名称 · 正在播 · 下一档 · 线路 x/y · 延迟 · 进度 · 时间）。

---

## 故障排查

### 装完打不开 / 提示「服务启动失败」

1. 看应用数据目录下的日志
2. 确认端口（默认 28027）没被占用
3. 确认 `config.yaml` 格式没坏（安装向导会做 YAML 转义）

### 电视转圈之后黑屏

先看 OSD 上显示的**内核**（Exo / VLC / mpv）。

- 如果一直是黑的，在设置里换内核试
- 画面比例选项里 `auto` 是完整显示，`zoom` 是裁切铺满——
  `zoom` 在视频尺寸未知时可能算出 0 尺寸导致黑屏，排查时先用 `auto`

### 投屏搜不到设备

1. 确认电视/盒子和 NAS 在**同一个局域网**（不同网段搜不到）
2. 电视要支持 DLNA（大多数国产电视和盒子都支持，名字里常带「投屏」）
3. 到管理台看 NAS 的网络配置，多网卡时 M-SEARCH 可能从错误的网卡发出

### 节目单是空的

1. 管理台「EPG 源」加一个源，点刷新，看**状态列**的错误原文
2. 到「频道 ↔ EPG 映射」看未匹配列表，用自动匹配或手动映射
3. 注意同一份 M3U 里一个台常有多条线路（`cctv1-MCP` / `cctv1-AV3A`），
   它们会共用同一份节目单，这是正常的

### 某条线路音频是 AV3A

见上文「AV3A 音频」。

---

## 文档

| 文档 | 内容 |
|---|---|
| [总体方案](docs/design/00-总体方案.md) | 架构、选型对比、里程碑 |
| [API 契约](docs/design/01-API.md) | 接口、字段、错误码 |
| [iOS 构建现状](docs/design/02-iOS构建现状.md) | 为什么只能 CI 编、签名与分发 |
| [fpk 规范调研](docs/research/01-fnos-fpk.md) | manifest 字段、生命周期、网关注册 |
| [技术选型调研](docs/research/02-stack-and-oss.md) | 现成轮子、License 雷区、播放内核 |
| [卸载回调安全](docs/security/uninstall-callback.md) | 卸载链路的威胁模型 |
| [AI 开发指南](AGENTS.md) | 给 AI / 新人的工程约定 |
| [变更记录](CHANGELOG.md) | 每一版改了什么 |
| [工具脚本](tools/README.md) | 辅助脚本怎么用 |

---

## License

待定（建议 AGPL-3.0 或自定义「非商业」）。

⚠️ **GPL 义务提醒**：本项目分发 `libmpv`（GPL-2.0+），
分发含 GPL 库的 APK 意味着整个 App 需按 GPL 兼容条款开源。
`libvlc` 是 LGPL-2.1+，动态链接只需提供源码链接。
定 License 前先把这一条理清。
