# LITV · IPTV 多终端管理平台

> 私有化部署在飞牛 NAS（fnOS）上的「直播源 + 频道编排 + 多终端分发」中台。
> 服务端是一个 Go 单二进制，打成 fnOS 原生 `.fpk`；管理台走飞牛统一网关**点开即登录**；
> 终端（Android TV / 手机 / iOS）用绑定码接入，播放内核 **Media3 主 + libmpv 兜底**。

> [!IMPORTANT]
> **合规声明**：本项目是**中立的源管理与播放外壳**，不提供、不预装、不托管、不分发任何直播源、EPG 或音视频内容。
> 所有源均由用户自行导入并对其合法性负责。请勿用于播放侵权内容。

---

## 当前状态

| 模块 | 状态 | 说明 |
|---|---|---|
| 服务端 `litvd` | ✅ 可运行 | 双入口、源解析、频道编排、EPG、设备绑定、流扇出、源探测、遥控中转 |
| 管理台 Web | ✅ 可构建 | Vue 3 + Vite + Naive UI，深色主题，部署在 `/app/litv/` 子路径 |
| fnOS 打包 | ✅ 已出包 | `packaging/fnos/dist/litv_{x86,arm,all}_v1.0.0.fpk`，生命周期冒烟 39/39 |
| Android TV | ✅ 已出包 | 四内核、自适应渲染档、完整遥控器交互、EPG 面板、线路/分组切换 |
| Android 手机 | ✅ 同一 APK | 触摸手势播放、分组 chip、收藏、遥控器 |
| iOS | 🚧 代码完成 | SwiftUI 客户端已写完（7 个 Swift 文件）；构建走 GitHub Actions macOS runner 出未签名 ipa，见 [ios-ipa.yml](.github/workflows/ios-ipa.yml) |

### 已验证的测试

```bash
bash build/test/e2e.sh           # 终端链路端到端（20 项）
bash build/test/remote-test.sh   # 遥控通道（WS 中转）
bash build/test/timeshift-test.sh # 时移录制与回看
bash build/test/smoke.sh         # 服务端 API 冒烟
packaging/fnos/tools/smoke-test.sh dist/litv_x86_v1.0.0.fpk   # fpk 生命周期（39 项）
```

全部通过。`e2e.sh` 覆盖：注册 → 待批准 → 批准 → bootstrap → EPG → 播放地址 → 拉流 →
篡改签名被拒 → 收藏 → 心跳 → 线路列表 → 不泄露上游地址 → 吊销失效。

### 已实现的功能梯队

**第一梯队（已完成）**
- ✅ 多源自动择优（`internal/probe` 定期探测，`BestStream` 按健康度+延迟自动选）
- ✅ 终端远程配置（管理台改参数 → 电视下次刷新生效）
- ✅ 手机当遥控（WebSocket 中转，跨网段可用，电视不开端口）
- ✅ 回看 / 时移（`internal/timeshift` 边看边录，默认保留约 1 小时）

**第二梯队**：EPG 智能匹配 ✅、录制中心 ⏳、跨端续播 ⏳、播放统计 ⏳
**第三梯队**：多语言（fnOS i18n 已备）✅、Web 播放/DLNA/告警 ⏳

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
│   └── research/            # 三份调研报告（fpk 规范 / 技术选型 / APK 逆向）
└── build/test/              # 冒烟测试脚本与样例源
```

---

## 快速开始（WSL）

环境：Ubuntu 26.04 / Go 1.23+ / Node 22+。**不需要 root**。

```bash
# 1. 构建服务端
cd server
go build -ldflags "-s -w -X main.version=1.0.0" -o litvd ./cmd/litvd

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

## 打包并安装到飞牛

```bash
cd packaging/fnos
./build.sh              # 默认 x86；ARM 用 ./build.sh --arch arm
```

产物：`dist/litv_x86_v1.0.0.fpk` + `.sha256`

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

详见 [docs/research/03-apk-re.md](docs/research/03-apk-re.md)。

---

## 文档

| 文档 | 内容 |
|---|---|
| [总体方案](docs/design/00-总体方案.md) | 架构、选型对比、里程碑、可扩展功能清单 |
| [API 契约](docs/design/01-API.md) | 全部接口、字段、错误码 |
| [fpk 规范调研](docs/research/01-fnos-fpk.md) | 官方 manifest 字段、生命周期、网关注册、账号接入三方案 |
| [技术选型调研](docs/research/02-stack-and-oss.md) | 现成轮子评估、License 雷区、播放内核、xtool |
| [APK 逆向报告](docs/research/03-apk-re.md) | 5 个播放器的内核/参数/UI/交互，抄什么不抄什么 |

---

## License

待定（建议 AGPL-3.0 或自定义「非商业」）。
⚠️ 注意：若引入 GPL-3.0 的 Media3 视频软解扩展或自编 libmpv，整个客户端 APK 的 GPL 义务需一并评估。
