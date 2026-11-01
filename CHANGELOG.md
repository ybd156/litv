# 更新日志

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)。
格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

> 版本号从 `0.0.1` 起。自用项目，`0.x` 期间不保证配置格式向后兼容——
> 升级前请备份 `config.yaml` 和数据库。

---

## [0.0.3] - 2026-11-02

源级 UA、直连播放、地址分框，外加修一个「电视根本播不了」的真 bug。

### 新增

- **源级 User-Agent**
  很多源站校验 UA：不带就 403，或者返回空列表（表现为「导入 0 个频道」）。
  现在添加/编辑源时可以单独填 UA（管理台给了 6 个常用预设）。
  这个 UA 会贯穿三处：抓取源、服务端代理、客户端直连播放。
  导入时写进每条线路的 `headers_json`，之后不用回头再查源。

- **直连播放**（`direct=1`）
  服务端把**上游原始地址**直接交给客户端，客户端自己连源站。
  省掉 NAS 的带宽和 CPU，起播也更快；服务端挂了不影响已开播的客户端。
  - 服务端不允许、或这条线路要走转封装时，自动回落到中转
  - 配置开关 `stream.direct_enabled`（默认开）
  - Android 加了 `directMode` 设置：auto / direct / relay

- **绑定页地址分框**
  服务器地址拆成 4 段 IP + 端口，输满自动跳格。
  遥控器上输 `http://192.168.1.10:9527` 要反复切数字/符号键，分框后只输数字。

- **iOS App 图标**
  之前工程里根本没有 `Assets.xcassets`，ipa 里没有 `Assets.car`，装上是白图标。

- **界面模式手动开关**
  自动判定在部分国产手机/平板上会失灵（它们会同时上报 LEANBACK，
  甚至 `UiModeManager` 报 TELEVISION），结果手机进了横屏网格 UI。
  现在可以在设置里强制 auto / tv / phone。

### 修复

- **电视/手机根本播不了**（致命）
  服务端返回的是站内相对路径（`/app/litv/live/1/stream.ts`），
  客户端直接丢给 ExoPlayer / VLC —— 没有 scheme，播不了。
  现在客户端会拼上服务器地址变成绝对地址；直连模式的上游绝对地址原样透传。

- **iOS 的 `url()` 会把绝对地址也拼上 base**
  直连时拼成 `http://nas:9527/http://源站/xxx.m3u8`，必然失败。

### 变更

- 删除 APK 逆向报告及其全部引用
- GitHub Actions 只保留 `release.yml`（其余三个是历史遗留），
  并清掉已删除 workflow 的历史运行记录

---

## [0.0.2] - 2026-11-02

修一个**装不上**的致命问题，外加手机端竖屏适配。

### 修复

- **端口被占用时服务直接退出**（致命）
  装到飞牛上启动失败，日志是 `bind: address already in use  0.0.0.0:9527`。
  NAS 上 9527 被别的服务占了很常见，但原来的行为是直接 `os.Exit(1)`，
  用户只看到"服务启动失败"，完全不知道换个端口就好。
  现在会**自动往后试最多 20 个端口**，并明确打出「配置端口 vs 实际端口」。
  只有 `EADDRINUSE` 才顺延；权限不足之类的错误立即返回，不掩盖真问题。

- **ffmpeg 找不到**（影响 iOS / 网页）
  原来只查 `PATH`，而 NAS 上 ffmpeg 经常不在 `PATH` 里。
  查找顺序扩到：随包目录 → `PATH` → `/usr/bin` → `/usr/local/bin`
  → `/opt/ffmpeg/bin` → `/usr/trim/bin` → `/volume*/@appstore`。
  失败时的警告也重写了，写清楚**影响范围**（只影响 iOS/网页，TV/手机不受影响）
  和**怎么解决**，而不是只丢一句"ffmpeg 不可用"。

- **`ffmpeg` 配置位置反直觉**
  之前 `ffmpeg` 配在 `transcode` 段，实际却是 `remux`（转封装）在用，
  报错还叫「转封装已配置但 ffmpeg 不可用」——用户根本不知道该去哪改。
  现在新增 `remux.ffmpeg`，留空则回落到 `transcode.ffmpeg`。

### 新增

- **手机端竖屏适配**
  - 绑定页、设置页去掉了 `screenOrientation="landscape"` 的强制锁定
    （之前手机上打开是横的，还得转手机）
  - 播放页新增 `layout-port` 变体：视频按 16:9 固定比例置顶，
    下方是频道信息 + 操作栏。之前竖屏时 16:9 画面只占中间一条，
    上下全是黑边，手势区也被黑边吃掉
  - 绑定页新增 `layout-port` 变体：原布局写死 `640dp` 宽 + `60dp` 内边距，
    在 360dp 宽的手机上直接溢出
  - 手机首页加了系统栏避让（状态栏压标题、底部 Tab 被手势条盖住）
  - 屏幕边距抽成 `@dimen/screen_padding`：横屏 48dp / 竖屏 20dp

- **实际端口可查**
  实际监听端口写到配置文件旁的 `litv.port`，
  管理台 `systemInfo` 也返回 `actual_port` / `port_changed`。
  TV / 手机填服务器地址时不用再猜。

### 变更

- 版本号统一为 `0.0.2`（Android `versionCode=2` / fpk manifest / iOS / Release）

---

## [0.0.1] - 2026-11-02

首个可用版本，六端齐活。

### 新增

**服务端（Go 单二进制，7900+ 行）**
- 双入口：飞牛统一网关（Unix Socket，身份由网关注入）+ TCP 9527（设备 token）
- 流扇出：一路上游分发 N 终端，环形缓冲 188 字节对齐
- 源解析：M3U / TXT / TVBox / JSON，GBK 自动转码
- 频道编排、EPG（XMLTV + 频道名模糊匹配）、设备绑定与批准
- 多源自动择优（定期探测，按健康度 + 延迟选）
- 时移 / 回看（边看边录，默认约 1 小时）
- 录制中心（立即录制 / 预约录制，`-c copy` 不转码）
- 裸 TS → HLS 转封装（给 iOS 和网页用）
- 跨端续播、播放质量统计（起播耗时 / P90 / 卡顿率 / 失败率）
- 源失效告警（Telegram / 自定义 webhook，30 分钟内去重）
- DLNA 投屏（SSDP 发现 + ContentDirectory 浏览，电视自带播放器可直接放）
- 手机当遥控（WebSocket 中转，跨网段可用，电视不开端口）

**管理台（Vue 3 + Vite + Naive UI，6800+ 行）**
- 8 个页面：概览 / 源管理 / 频道管理 / EPG / 直播预览 / 终端设备 / 系统设置
- 「直播预览」页用 hls.js 在浏览器里直接看，不用装 App
- 走飞牛网关，**点开即登录**

**Android（Kotlin，3900+ 行，TV 和手机同一个 APK）**
- TV：网格布局、完整遥控器交互（方向键 / OK / 返回 / 数字键换台 / 长按 / 双击退出）、
  EPG 面板、线路与分组切换、自适应渲染档（LOW / MID / HIGH）
- 手机：单列列表、触摸手势（上下滑换台 / 左半屏音量 / 右半屏亮度 / 双击暂停）
- 四播放内核：**Media3(Exo) 主 + libmpv + libVLC + AVS3**，按能力和失败情况自动切换
- 绑定码接入 + 二维码扫码

**iOS（SwiftUI + MobileVLCKit，900+ 行）**
- 绑定 / 首页 / 播放 / EPG / 设置
- 双内核：VLCKit 优先，起不来退回 AVPlayer
- 产出**未签名** ipa，用 Sideloadly / SideStore 自签安装

**打包与发布**
- fnOS `.fpk`（x86 / arm），生命周期冒烟 39/39
- GitHub Actions 一键出三端包并发 Release
- APK 签名 v1 + v2 + v3 三种方案全开

### 已知限制

- iOS 的 libmpv 未落地，改用 MobileVLCKit（依赖链过深，详见
  [docs/design/02-iOS构建现状.md](docs/design/02-iOS构建现状.md)）
- ffmpeg 不随包分发，需 NAS 上自备（只影响 iOS / 网页播裸 TS）
- AVS3 软解，4K 不现实

---

## 版本对照

| 版本 | 日期 | 关键变化 |
|---|---|---|
| 0.0.3 | 2026-11-02 | 源级 UA、直连播放、地址分框；修「电视播不了」的相对路径 bug |
| 0.0.2 | 2026-11-02 | 修端口占用导致装不上；ffmpeg 查找范围扩大；手机竖屏适配 |
| 0.0.1 | 2026-11-02 | 首个可用版本：服务端 + 管理台 + Android TV/手机 + iOS |
