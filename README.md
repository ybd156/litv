# LITV · IPTV 多终端管理平台

> 私有化部署在飞牛 NAS（fnOS）上的「直播源 + 频道编排 + 多终端分发」中台。
> 服务端是一个 Go 单二进制，打成 fnOS 原生 `.fpk`；管理台走飞牛统一网关**点开即登录**；
> 终端（Android TV / 手机 / iOS）用绑定码接入，播放内核 **Media3 主 + libmpv / VLC 兜底**。

> [!IMPORTANT]
> **合规声明**：本项目是**中立的源管理与播放外壳**，不提供、不预装、不托管、不分发任何直播源、EPG 或音视频内容。
> 所有源均由用户自行导入并对其合法性负责。请勿用于播放侵权内容。

---

## 当前状态

| 模块 | 状态 | 说明 |
|---|---|---|
| 服务端 `litvd` | ✅ 可运行 | 双入口、源解析、频道编排、EPG、设备绑定、流扇出、源探测、遥控中转 |
| 管理台 Web | ✅ 可构建 | Vue 3 + Vite + Naive UI，深色主题，部署在 `/app/litv/` 子路径，8 个页面含直播预览 |
| fnOS 打包 | ✅ 已出包 | `litv_{x86,arm}.fpk` 0.0.2，生命周期冒烟 39/39 |
| Android TV | ✅ 已出包 | 四内核、自适应渲染档、完整遥控器交互、EPG 面板、线路/分组切换 |
| Android 手机 | ✅ 同一 APK | **竖屏适配**（16:9 置顶播放器 + 下方信息区）、触摸手势、分组 chip、收藏、遥控器 |
| iOS | ✅ 已出包 | SwiftUI 客户端，播放内核 **MobileVLCKit**（AC3/DTS/裸 TS 都能解），产出**未签名** `LITV-unsigned.ipa`（16MB，arm64） |

### 已验证的测试

```bash
bash build/test/e2e.sh            # 终端链路端到端（28 项）
bash build/test/remote-test.sh    # 遥控通道（WS 中转）
bash build/test/timeshift-test.sh # 时移录制与回看（4 项）
bash build/test/remux-test.sh     # 裸 TS → HLS 转封装（7 项）
bash build/test/record-test.sh    # 录制中心含调度器（9 项）
bash build/test/alert-test.sh     # 源失效告警含 webhook（8 项）
bash build/test/web-test.sh       # Web 端播放经网关取流（7 项）
bash build/test/dlna-test.sh      # DLNA 发现/浏览/播放（14 项）
bash build/test/smoke.sh          # 服务端 API 冒烟
packaging/fnos/tools/smoke-test.sh dist/litv_x86_0.0.2.fpk   # fpk 生命周期（39 项）
```

全部通过（77 项断言 + 遥控/冒烟 + 打包 39 项）。

### 已实现的功能梯队

**第一梯队（已完成）**
- ✅ 多源自动择优（`internal/probe` 定期探测，`BestStream` 按健康度+延迟自动选）
- ✅ 终端远程配置（管理台改参数 → 电视下次刷新生效）
- ✅ 手机当遥控（WebSocket 中转，跨网段可用，电视不开端口）
- ✅ 回看 / 时移（`internal/timeshift` 边看边录，默认保留约 1 小时）

**第二梯队（已完成）**
- ✅ EPG 智能匹配（归一化 + 数字编号匹配）
- ✅ 录制中心（`internal/record`，立即录制与预约录制，`-c copy` 不转码）
- ✅ 跨端续播（`watch_progress`，客厅看一半去卧室接着看）
- ✅ 播放质量统计（起播耗时/P90/卡顿率/失败率，客户端上报）

**第三梯队（已完成）**
- ✅ 多语言（fnOS i18n 中英双语）
- ✅ 裸 TS → HLS 转封装（`internal/remux`，顺带解决 iOS 播不了裸 TS）
- ✅ 源失效告警（`internal/alert`，Telegram / 自定义 webhook，30 分钟内去重）
- ✅ Web 端直接看（管理台「直播预览」页，hls.js）
- ✅ DLNA 投屏（`internal/dlna`，SSDP 发现 + ContentDirectory 浏览，电视自带播放器可直接放）

---

## 目录结构

```
LITV2/
├── server/                  # Go 服务端（单二进制）
│   ├── cmd/litvd/           # 主程序
│   └── internal/
│       ├── config/          # 配置加载（fnOS 环境变量感知）
│       ├── store/           # SQLite 存储层（纯 Go 驱动，免 cgo）
│       ├── parse/           # M3U / TXT / TVBox / JSON 解析器
│       ├── fetch/           # 订阅抓取（GBK 转码、超时、限流）
│       ├── epg/             # XMLTV 解析 + 频道名模糊匹配
│       ├── auth/            # 网关头身份 + 设备 token + 流签名
│       ├── stream/          # ★ 一路源分发 N 终端（环形缓冲 fanout）
│       ├── api/             # HTTP 接口层
│       └── web/             # 管理台静态资源（go:embed）
├── admin/                   # 管理台前端（Vue 3 + Vite）
├── packaging/fnos/          # 飞牛 .fpk 打包工程
│   ├── manifest
│   ├── cmd/                 # 生命周期脚本（start/stop/status…）
│   ├── wizard/              # 安装向导
│   ├── config/              # privilege（QSV 硬解用）/ resource
│   └── build.sh             # 一键出包
├── docs/
│   ├── design/              # 总体方案、API 契约
│   └── research/            # 两份调研报告（fpk 规范 / 技术选型）
└── build/test/              # 冒烟测试脚本与样例源
```

---

## 下载安装

**直接去 Releases 拿包**：<https://github.com/ybd156/litv/releases/latest>

> 每个版本改了什么，见 **[CHANGELOG.md](CHANGELOG.md)**。
> Release 页面上的「本版变化」就是从它自动摘出来的。

| 设备 | 文件 |
|---|---|
| **安卓电视 / 盒子（Android 9、32 位）** | `LITV-0.0.2-tv-armv7.apk` |
| 64 位设备（新电视 / 手机） | `LITV-0.0.2-arm64.apk` |
| 飞牛 NAS（N100 等 x86） | `litv_x86_0.0.2.fpk` |
| 飞牛 NAS（ARM 机型） | `litv_arm_0.0.2.fpk` |
| iPhone / iPad | `LITV-0.0.2-unsigned.ipa` |

发版：Actions 里手动跑 `Release` 工作流，或推 `v*` tag，会自动构建三端并发布。

**APK 签名**：v1（JAR）+ v2 + v3 三种方案全开 —— v1 给老盒子、v2 是 Android 7+ 主力、
v3 是 Android 9+。keystore 随仓库分发（CI 要用），口令在 `build.gradle.kts` 的默认值里，
**它只保证"能装、能覆盖升级"，不构成安全边界**。

## 快速开始（WSL）

环境：Ubuntu 26.04 / Go 1.23+ / Node 22+。**不需要 root**。

### iOS 出包（走 GitHub Actions）

iOS 不在 WSL 里编——需要 Apple 的 iOS SDK，Linux 上做不了。
跑一次 Actions 里的 `Release` 工作流，三端包会一起构建并发布到 Releases。

产出的 `.ipa` 是**未签名**的，用 Sideloadly / SideStore 自签安装即可。

> 踩坑记录：`runs-on: macos-14` 在这个账号下会让 job 3 秒内被取消（连 step 都不跑），
> 换成 `macos-latest` 正常。

### iOS 播放内核：为什么最终是 VLCKit 而不是 libmpv

最初的目标是 libmpv。实际推进时发现 mpv 在 iOS 上不是"一个库"，而是一整条链：

```
mpv 0.35.1
 ├── libass（**无条件必需**，0.35 之后没有关闭开关）
 │    ├── fribidi
 │    └── freetype
 ├── libplacebo（0.36+ 硬依赖；0.35.1 可关）
 └── FFmpeg
      └── libuavs3d（AVS3 解码）
```

已经在 WSL 本地真编通并验证过的：**libuavs3d / FFmpeg / fribidi / freetype**，
mpv 的 40+ 个 meson 开关也逐个校验过。**卡在 libass**——它只有 autotools
（无 meson / 无 CMakeLists），需要 autoconf + automake + libtool 交叉编译。

考虑到这条链的产出只是补 AC3/AVS3，而 `MobileVLCKit` 是预编译 xcframework、
一次链接搞定、能力覆盖更全（AC3/EAC3/DTS/MPEG-TS）、License 还更宽松（LGPL vs GPL），
最终改用 VLCKit。

完整记录见 [docs/design/02-iOS构建现状.md](docs/design/02-iOS构建现状.md)，
`ios/native/build-xcframework-macos.sh` 保留着（uavs3d + FFmpeg 的 iOS 交叉编译可用）。

**双内核策略**：VLCKit 优先，起不来时自动退回 AVPlayer；`#if canImport(MobileVLCKit)`
包住整个文件，Pod 没装上也不影响 App 编译。

### 本地构建

```bash
# 1. 构建服务端
cd server
go build -ldflags "-s -w -X main.version=0.0.2" -o litvd ./cmd/litvd

# 2. 构建管理台
cd ../admin
npm install && npm run build

# 3. 把前端产物塞进二进制（go:embed）
rm -rf ../server/internal/web/www && cp -r dist ../server/internal/web/www

# 4. 重新编译（静态资源已内嵌）
cd ../server && go build -ldflags "-s -w -X main.version=1.0.0" -o litvd ./cmd/litvd

# 5. 本地跑起来（开发模式）
mkdir -p /tmp/litv && ./litvd -config /tmp/litv/config.yaml
```

服务端启动后：
- 管理台：`http://<IP>:9527/`（TCP 直连模式，需要 `auth.dev_mode: true` 才能进）
- 健康检查：`curl http://127.0.0.1:9527/api/v1/health`

### 冒烟测试

```bash
bash build/test/smoke.sh
```
覆盖：健康检查 → 网关身份 → 建源 → 预览 → 刷新 → 分组 → 频道 → 线路 → 设备注册 → 审批 → 绑定码 → 运行时状态。

---

## 故障排查

### 装完打不开 / 提示"服务启动失败"

**最常见原因：端口被占用。** 0.0.2 起会自动顺延端口并写日志：

```
WARN 配置的端口被占用，已自动改用下一个可用端口 配置端口=9527 实际端口=9528
```

实际端口记在配置文件旁的 `litv.port`，管理台「概览」页也能看到
（`actual_port` / `port_changed`）。**TV / 手机填服务器地址时用实际端口。**

想固定端口：应用设置 → 换一个没被占用的（比如 18027）。

### iOS / 网页播不了，TV 正常

**说明服务端没找到 ffmpeg。** 转封装只影响 iOS 和网页（它们只认 HLS）；
TV / 手机 App 直接吃裸 TS，不受影响。

服务端会按这个顺序找 ffmpeg：
随包目录 → `PATH` → `/usr/bin` → `/usr/local/bin` → `/opt/ffmpeg/bin` → `/usr/trim/bin`

都没有就装一个，或在「应用设置」里把 `remux.ffmpeg` 填成绝对路径。

> 为什么不把 ffmpeg 打进 fpk：静态构建单个 130–150MB，包体积会从 6MB 涨到 150MB+，
> 为了一个只影响 iOS/网页的能力不值当。

## 打包并安装到飞牛

```bash
cd packaging/fnos
./build.sh              # 默认 x86；ARM 用 ./build.sh --arch arm
```

产物：`dist/litv_x86_<版本>.fpk` + `.sha256`

安装：
1. 飞牛桌面 → **应用中心 → 手动安装** → 选择 `.fpk`
2. 向导里填：服务端口、管理员密码、数据目录
3. 装完从桌面图标打开 → 走统一网关，**自动是登录状态**

调试：
```bash
# 在 NAS 上
/var/apps/litv/target/cmd/main status
tail -f /var/apps/litv/var/litv.log
```

---

## 架构要点

### 双入口（这是核心设计）

| 入口 | 谁用 | 鉴权 | 传输 |
|---|---|---|---|
| `/app/litv/*` | 管理台（浏览器） | 飞牛网关注入 `X-Trim-Userid/Username/Isadmin` | Unix Socket |
| `:9527/api/*` | 三端 App | 设备 token（绑定码换取） | TCP |
| `/live/*` | 播放器 | 短期签名 URL（HMAC + 过期） | HTTP 流 |

**为什么不能只走网关**：网关只覆盖浏览器会话，原生 App 不会带飞牛 Cookie。
**为什么不能只走端口**：那就放弃了「接入飞牛账号」。
**安全边界**：`X-Trim-*` 头**只在经网关（Unix Socket）时被信任**；TCP 入口一律不认，否则任何人伪造一个头就是管理员。

### 流出口（性能关键）

```
一个频道 = 一路上游连接 + 一个环形缓冲 + N 个订阅者
新终端加入 → 直接从缓冲区最近的 GOP 起播 → 切台 < 300ms
```

- 3 台设备看同一频道只占 1 份上行带宽
- 缓冲区按 188 字节（MPEG-TS 包长）对齐丢弃，减少起播花屏
- 下游太慢就丢块，绝不阻塞上游（一个卡死的盒子不能拖垮全家）

---

## 关键参数（来自 5 个生产级 APK 的逆向实测）

**ExoPlayer 直播预设**（起播快是 IPTV 的命）：
```
setBufferDurationsMs(1000, 60000, 500, 1000)
setBackBuffer(0, true)   // 直播不回退缓冲
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


---

## 文档

| 文档 | 内容 |
|---|---|
| [总体方案](docs/design/00-总体方案.md) | 架构、选型对比、里程碑、可扩展功能清单 |
| [API 契约](docs/design/01-API.md) | 全部接口、字段、错误码 |
| [fpk 规范调研](docs/research/01-fnos-fpk.md) | 官方 manifest 字段、生命周期、网关注册、账号接入三方案 |
| [技术选型调研](docs/research/02-stack-and-oss.md) | 现成轮子评估、License 雷区、播放内核、xtool |

---

## License

待定（建议 AGPL-3.0 或自定义「非商业」）。
⚠️ 注意：若引入 GPL-3.0 的 Media3 视频软解扩展或自编 libmpv，整个客户端 APK 的 GPL 义务需一并评估。
