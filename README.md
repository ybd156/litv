# LITV · IPTV 多终端管理平台

自建的 IPTV 源管理 + 播放平台，跑在飞牛 fnOS 上，一套服务端带三端客户端
（Android TV / Android 手机 / iOS）。

> **本仓库不提供、不内置、不托管任何直播源。** 所有源由使用者自行导入，
> 源的合法性由使用者自负。

当前版本：**0.1.7** · [下载](https://github.com/ybd156/litv/releases/latest)

---

## 它解决什么问题

手上有一堆 IPTV 源（M3U），想在家里电视、手机、iPad 上都能看，还要：

- 多个源自动择优，某个台卡了自动换线路
- 有节目单（EPG），知道现在播什么、下一档是什么
- 能回看、能预约录制
- 手机能当遥控器
- 管理台能改配置、看设备、看日志

自己搭一套要写不少东西，这个项目就是把这些拼起来。

---

## 功能

### 直播

- 多源自动择优：定期探测每条线路的健康度与延迟，按最优选路
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

### 其他

- **回看 / 时移**：边看边录，默认保留约 1 小时；回看起点对齐关键帧
- **录制中心**：立即录制与预约录制，`-c copy` 不转码
- **手机遥控**：WebSocket 中转，跨网段可用，电视不需要开端口
- **跨端续播**：客厅看一半，卧室接着看
- **源失效告警**：Telegram / 自定义 webhook，30 分钟内去重
- **DLNA 投屏**：SSDP 发现 + ContentDirectory 浏览
- **管理台**：Vue 3 + Naive UI，深色主题，含直播预览页

---

## 下载

| 产物 | 用途 |
|---|---|
| `LITV-<ver>-tv-armv7.apk` | Android TV / 电视盒子（armv7） |
| `LITV-<ver>-arm64.apk` | Android 手机 / 平板（arm64） |
| `LITV-<ver>-unsigned.ipa` | iOS（需自签，未签名） |
| `litv_x86_<ver>.fpk` | 飞牛 NAS（x86_64） |
| `litv_arm_<ver>.fpk` | 飞牛 NAS（ARM） |
| `SHA256SUMS.txt` | 校验和 |

Android TV 和手机是**同一个代码库、同一套功能**，按屏幕形态自适应布局。

---

## 安装

### 飞牛 NAS

1. 从 Release 下载对应架构的 `.fpk`
2. 飞牛「应用中心 → 手动安装」选择该文件
3. 安装向导里填端口（默认 9527）、时区、是否允许局域网访问
4. 装完在应用中心打开「LITV」

服务端首次启动会自动生成 `config.yaml` 与数据库，位于应用数据目录。

### 客户端

装好后打开，输入服务器地址（`http://<NAS-IP>:9527`），
拿到一个**绑定码**，到管理台「设备」页批准即可。

同一台设备重装 / 清数据**不需要重新批准**——客户端用系统稳定的设备标识
（Android 上是 `ANDROID_ID` 派生）重新认回原设备。

---

## 本地开发

### 前置

**Windows 上没有任何工具链**，全部在 WSL（Ubuntu）里：

| 工具 | 版本 |
|---|---|
| Go | 1.23+ |
| Node | 22+ |
| JDK | 21 |
| Gradle | 8.7（用 `android/gradlew` 这个 shim，它会 source `~/android-dev/env.sh`） |
| Android SDK | platform 35、build-tools 34/35 |
| iOS | **本地编不了**，只能用 GitHub Actions（需要 Apple SDK） |

`android/gradlew` 不是标准 wrapper，是转发到本地 Gradle 发行版的脚本——
因为标准 wrapper 会去 `services.gradle.org` 拉包，这个网络下会失败。

### 构建

```bash
# 服务端
cd server && go build ./... && go test ./...

# 管理台
cd admin && npm ci && npm run typecheck && npm run build

# Android
cd android && ./gradlew assembleDebug

# 飞牛包（会自动把前端产物同步进 go:embed 目录再编译）
cd packaging/fnos && ./build.sh
```

### iOS

改完推上去，CI 会自动跑 `iOS Build Check`（只编译，不发版）。
**本地没有任何办法验证 Swift 能否编译**，所以 iOS 改动必须走 CI。

---

## 测试

### Go 单元测试

```bash
cd server && go test ./...
```

覆盖 `api` / `config` / `epg` / `store` / `stream` / `timeshift` 六个包。

约定：**修 bug 先写一条在旧代码上会失败的测试**。这个仓库里有过两次
「测试是绿的但根本没测到东西」的经历，所以新增测试必须证明它在修复前会红。

### 集成脚本

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
| `:9527/api/*` | 三端 App | 设备 token（绑定码换取） | TCP |
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
server/           Go 服务端（chi + SQLite，无 cgo）
  cmd/litvd/      入口
  internal/       api / store / stream / epg / timeshift / record / remux / probe / alert / dlna / web
admin/            Vue 3 + TS + Vite 管理台
android/          Android TV + 手机（Media3 主，VLC/mpv 兜底）
ios/              SwiftUI（MobileVLCKit 主，AVPlayer 兜底）
packaging/fnos/   fpk 清单、生命周期脚本、安装向导
build/test/       集成测试脚本
docs/             设计文档与调研
```

---

## 播放内核

三端都不是单一内核，按能力依次降级：

| 平台 | 主 | 兜底 |
|---|---|---|
| Android | Media3/ExoPlayer | VLC → mpv |
| iOS | MobileVLCKit | AVPlayer（仅 HLS） |
| Web | hls.js | — |

**iOS 为什么是 VLCKit 而不是 libmpv**：VLCKit 是唯一既能解裸 TS、
又能解 AC3/DTS、且能合法分发的选择。详细对比见
[总体方案](docs/design/00-总体方案.md)。

### 已知限制：AV3A 音频

AV3A 是 AVS3-P3（Audio Vivid）音频，中国国家标准。

- **FFmpeg 主线没有它的解码器**（社区是靠打补丁）
- VLC / mpv 都基于 FFmpeg → 同样不支持
- ExoPlayer 完全不支持 AVS3/AV3A

所以**集成再多解码器也播不了 AV3A 源**。可行方向：
换用同频道的非 AV3A 线路，或丢弃音频轨静音播放。

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

---

## 故障排查

### 装完打不开 / 提示「服务启动失败」

1. 看应用数据目录下的日志
2. 确认端口没被占用
3. 确认 `config.yaml` 格式没坏（安装向导会做 YAML 转义）

### 电视转圈之后黑屏

先看 OSD 上显示的**内核**（Exo / VLC / mpv）。

- 如果一直是黑的，在设置里换内核试
- 画面比例选项里 `auto` 是完整显示，`zoom` 是裁切铺满——
  `zoom` 在视频尺寸未知时可能算出 0 尺寸导致黑屏，排查时先用 `auto`

### 节目单是空的

1. 管理台「EPG 源」加一个源，点刷新，看**状态列**的错误原文
2. 到「频道 ↔ EPG 映射」看未匹配列表，用自动匹配或手动映射
3. 注意同一份 M3U 里一个台常有多条线路（`cctv1-MCP` / `cctv1-AV3A`），
   它们会共用同一份节目单，这是正常的

### 某条线路音频是 AV3A

见上文「已知限制」。

---

## 文档

| 文档 | 内容 |
|---|---|
| [总体方案](docs/design/00-总体方案.md) | 架构、选型对比、里程碑 |
| [API 契约](docs/design/01-API.md) | 接口、字段、错误码 |
| [fpk 规范调研](docs/research/01-fnos-fpk.md) | manifest 字段、生命周期、网关注册 |
| [技术选型调研](docs/research/02-stack-and-oss.md) | 现成轮子、License 雷区、播放内核 |
| [AI 开发指南](AGENTS.md) | 给 AI / 新人的工程约定 |

---

## License

待定（建议 AGPL-3.0 或自定义「非商业」）。

⚠️ 若引入 GPL-3.0 的 Media3 软解扩展或自编 libmpv，
整个客户端 APK 的 GPL 义务需一并评估。
