# 02 · IPTV 多终端系统：现成轮子调研与技术选型

> 目标：**少造轮子，但核心代码自己写**。
> 本文只做"哪些现成、能不能用、License 允不允许、要自己补哪块"的判断，不含产品设计。
> 调研时间：2026-11。所有结论附 URL；查不到的直接标 **未证实**，不做推测性填充。

---

## 0. 口径与验证方法

| 项 | 说明 |
|---|---|
| 语言 / License 数据来源 | GitHub 仓库 README 原文、LICENSE 徽章（`img.shields.io/github/license/<repo>`）、Maven Central / CocoaPods 元数据 |
| "fpk" 口径 | 按 **飞牛 fnOS 应用包（.fpk）** 理解，详见同目录 [01-fnos-fpk.md](./01-fnos-fpk.md)。三条对本篇有约束力的事实：① **没有 systemd**，应用要自己实现 `cmd/main start\|stop\|status` 守护；② 默认**非 root** 运行，用核显需 `join-groups: ["video","render"]`；③ Go 静态单文件是官方最顺的形态 |
| 标注约定 | 「已验证」= 有 URL 直接支撑；「判断」= 基于已验证事实的工程推论；「未证实」= 查不到可靠来源，需实测或补证 |
| 不写什么 | 不写未经验证的版本号、性能数字、API 名。参数类建议均注明"以 `ffmpeg -h encoder=xxx` 实测为准" |

---

## 1. TL;DR 选型结论表

| 维度 | 推荐 | 备选 | 理由 | 风险 |
|---|---|---|---|---|
| **服务端主体** | **Go 自研**（单静态二进制，易进 fpk） | 参考 Threadfin（Go/MIT）源码结构；不直接 fork | 现有 IPTV 服务端没有一个同时满足「Go + 宽松 License + 直播转发 + 多终端鉴权」；Threadfin 是最接近的 Go 参考实现，但定位是 Plex/Emby 的 M3U 代理 | 自研工作量集中在「源管理 + 探测 + 转发调度」，这是本项目真正的差异化，值得写 |
| **源聚合/测速** | 借鉴 **Guovin/iptv-api** 的算法与配置模型，**不引入其代码** | 自己写简化版（并发探测 + 速率/分辨率过滤） | 它的频道别名库、测速维度、广告源识别规则是行业里最完整的公开实现 | **AGPL-3.0**：以网络服务方式运行修改版必须开放全部源码 → 商用/闭源场景不可链接其代码，只能"读思路重写" |
| **流媒体转发** | **MediaMTX**（Go/MIT）作为可选外部进程；HLS 切片与鉴权自己写 | nginx + nginx-rtmp / ffmpeg 直出 | MediaMTX 单二进制、MIT、支持 RTSP/RTMP/SRT/HLS/WebRTC 互转、内置 JWT/HTTP 鉴权与 hooks | 引入外部进程会让 fpk 打包变复杂；HLS 的"单源多终端 + 防盗链"仍要自己写一层 |
| **EPG** | 自研聚合器 + 外部 XMLTV 源（51zmt 等） | Threadfin 的 XMLTV 合并逻辑作参考 | XMLTV 是稳定标准（DTD 公开），聚合与频道名匹配逻辑必须自己做（业务核心） | 第三方 EPG 源无 SLA、随时失效；商用需注意节目单数据版权 |
| **Android 播放内核** | **Media3(ExoPlayer) 为主 + 可选 libmpv 兜底** | 纯 libmpv | Media3 是 Google 官方、Apache-2.0、HLS 模块成熟、硬解直通、起播快；libmpv 覆盖"怪格式/软解/字幕" | Media3 对 AC3/E-AC3/部分 TS 封装要靠 FFmpeg 扩展，而可用扩展是 **GPL-3.0**（传染到 APK） |
| **Android 播放内核（备选路线）** | **libmpv 自编**（mpv-android 的 `buildscripts/`） | 第三方 Maven `org.jellycine.mpv:libmpv` | libmpv 一次接入解决 TS/HLS/软硬解/字幕/AVS3（若 ffmpeg 带 libuavs3d） | mpv-android **官方明确不提供 AAR**，必须自己编（NDK + 交叉编译 ffmpeg），工作量大且要长期维护 |
| **TV 端 UI** | **Compose for TV（androidx.tv）** | Leanback（存量生态） / 自绘 | Google 官方 TV 方向已转向 Compose for TV；焦点/D-pad 有官方 `TvLazyRow`/`FocusRequester` 支持 | 起播体验（预览小窗 + 快速切台）需要大量自研；Compose 在低端盒子上的首帧表现需实测 |
| **iOS 构建** | **xtool**（MIT）做"无 Mac 出 IPA" | 租云 Mac / GitHub Actions macOS runner | 唯一活跃的跨平台 Xcode 替代方案，能 build+sign+install | 生态很年轻；SwiftUI/第三方 xcframework 支持 **未证实**；签名仍受 Apple 免费证书 7 天限制 |
| **iOS 播放内核** | **AVPlayer** 为主（HLS 原生最优） | MobileVLCKit（格式兜底） | AVPlayer 对 HLS 是系统级实现，功耗/起播最好 | AVPlayer **不支持 AC3/E-AC3（无授权 Dolby）**、对裸 MPEG-TS 直播支持有限 → 服务端必须转 HLS 封装 |
| **签名/分发** | 自签（Sideloadly/SideStore）+ 引导用户侧载 | TrollStore（受限机型/版本） | 无 Mac、无企业证书下唯一合规路径 | 免费证书 7 天过期；TrollStore 仅覆盖 iOS 14.0–17.0 部分版本，**17.0.1+ 永不支持** |

---

## 2. A. 服务端

### A.1 现成开源项目逐项评估

| 项目 | 语言 | License | 定位 | 可复用程度 | 能否进 fpk |
|---|---|---|---|---|---|
| [Guovin/iptv-api](https://github.com/Guovin/iptv-api) | Python 97.4% | **AGPL-3.0** | 直播源采集→校验→测速→生成 M3U/TXT/API | 只能"读思路重写"，**不可链接代码** | 技术可打包（要带 Python 运行时），但 AGPL 使商用不可接受；且 fnOS 无 systemd，还要自己写 `cmd/main` 守护 |
| [Threadfin](https://github.com/Threadfin/Threadfin) | Go 65.8% | **MIT** | xTeVe 的活跃分支：M3U/XMLTV 代理 + 频道映射 + 缓冲转流 | **高**：Go + MIT，架构可整体借鉴，关键文件可参考实现 | ✅ **最理想**：`go build` 出单文件，直接套 `cmd/main` |
| [xTeVe](https://github.com/xteve-project/xTeVe) | Go 71.7% | 无 LICENSE 文件（未识别） | Threadfin 的前身 | 低：**最后提交 2021-03，已停止** | 不建议 |
| [ErsatzTV](https://github.com/ErsatzTV/ErsatzTV) | C# 88.5% | **Zlib** | 媒体库→自建"电视频道"+ IPTV/EPG 输出 | 中：频道编排/调度模型值得抄，但 .NET 运行时不适合小设备 | 勉强（要带 .NET 运行时 + 自己写守护） |
| [MediaMTX](https://github.com/bluenviron/mediamtx) | Go 94.0% | **MIT** | 零依赖媒体路由：RTSP/RTMP/SRT/HLS/WebRTC/MoQ 互转 | **高**：可直接作为"转发面"外部进程 | ✅ 单可执行文件无依赖；但要作为**子进程**由我们的 `cmd/main` 拉起与回收 |
| [Owncast](https://github.com/owncast/owncast) | Go 46.1% | **MIT** | 单频道自托管直播（面向主播） | 低：单流模型，不是多频道 IPTV | ✅ 但不适配场景 |
| [streamlink](https://github.com/streamlink/streamlink) | Python 99.9% | **BSD-2-Clause** | 从网页提取流地址并交给播放器 | 中：作为"源解析器"子进程可选 | 一般（Python 依赖重） |
| [restreamer](https://github.com/datarhei/restreamer) | HTML 47.2%（后端语言未证实） | **Apache-2.0** | 单路 RTMP/RTSP → HLS 的"重启流"盒子 | 低：单路思路，多频道要起 N 个实例 | 一般 |
| [AlistGo/alist](https://github.com/AlistGo/alist) | Go 99.6% | **AGPL-3.0** | 网盘挂载 + WebDAV + 文件索引 | 与直播无关（xiaoya 生态的直播是外挂 m3u/txt 源） | ✅ 但 AGPL |
| [FongMi/TV](https://github.com/FongMi/TV) | Java 81.3% | **GPL-3.0** | 客户端（含 TVBox 兼容配置） | **不是服务端**；其 `lives` 配置格式是事实标准之一 | 不适用（客户端） |

#### 逐条结论

**1) Guovin/iptv-api —— 算法金矿，代码禁区**
README 明确列出：多源聚合、频道别名库（收录 2,769 频道 / 7,254 条别名，含 48 条正则）、测速（延迟/速率/分辨率/帧率）、广告与占位源过滤（识别含 `#EXT-X-ENDLIST` 的短循环列表、分片地址含广告关键字）、EPG 抓取、RTMP 推流（依赖 FFmpeg + 带 RTMP 模块的 nginx）、输出 `/m3u` `/txt` `/content` `/log/*` 等接口。
License 段落写得很直白：

> 本项目采用 AGPL-3.0。若将修改后的程序作为网络服务、公开容器镜像或以其他对外服务方式运行，须向使用者提供包括修改在内的完整源代码。

→ **判断**：把它的代码放进我们的服务端（哪怕只是一个模块）等于整个服务端要开源。**只能借鉴思路**：别名归一化策略、`$` 后缀语义、按分辨率映射最低码率（`resolution_speed_map`，默认 `1280x720:0.2,1920x1080:0.5,3840x2160:1.0`）、性能模式分级。这些是"参数经验"，重写成本远低于合规成本。

**2) Threadfin —— 最值得读的 Go 参考实现**
MIT + Go，功能覆盖我们要做的大部分"源管理"：合并外部 M3U/XMLTV、自动更新、频道过滤/映射/排序/台标/分类、可调 tuner 数量、HLS/M3U8 缓冲转流、与 Plex/Emby/Jellyfin 的 EPG 兼容。
依赖很少（`go-ssdp`、`gorilla/websocket`、`osext`、`avfs`），`go build threadfin.go` 即出单文件 —— 这正是 fpk 想要的形态。
→ **判断**：把它当"设计参考 + 代码片段来源"（MIT 允许），不要 fork（它的 UI/更新机制与 Plex 强绑定）。

**3) xTeVe —— 已死，只读文档**
最后提交 2021-03。Threadfin 的 README 直接指向 xTeVe 的旧文档作为自己的文档。→ 只当历史资料。

**4) ErsatzTV —— 思路参考（频道编排），技术栈不匹配**
C# 88.5% / **Zlib**（非常宽松）。强项是"把媒体库变成带 EPG 的直播频道"+ 硬件转码（NVENC / QSV / VAAPI / AMF / VideoToolbox）。
→ **判断**：我们要做的是"接别人的直播源"，不是"编排自己的媒体库"，场景不同；但它的"频道 → 节目单 → 转码档位"数据模型可以照抄。

**5) MediaMTX —— 直接当转发面用（MIT）**
零依赖单可执行文件；协议互转（RTSP/RTMP/SRT/HLS/WebRTC/MPEG-TS/RTP）；内置 **internal / HTTP / JWT 三种鉴权**；支持 hooks（客户端连接/断开/读取/发布时触发外部命令）；Control API + Prometheus 指标；支持"发布者离线时也保持流可用"（always-available）。
→ **判断**：最适合承担"一路上游 → N 路下游"的扇出，以及"协议归一化"。我们的服务端只负责"决策"（谁可以看、看哪路、何时重连），MediaMTX 负责"搬运"。
→ **风险**：fpk 里多一个进程 + 多一套配置面；Windows/macOS/Linux 都支持，但交叉编译与升级要自己管。

**6) Owncast / restreamer / streamlink —— 场景不对**
Owncast 是"一个人直播给很多人看"（单频道、带聊天室），不是多频道 IPTV 网关。restreamer 是单路"重启流"。streamlink 是"从网页抽流地址"的 Python 工具 —— 如果要做"某些源必须靠解析网页才能拿到真实地址"，它可以作为可选子进程，但会把 Python 塞进包。
→ **判断**：三个都不进主链路。

**7) xiaoya / alist 生态的直播**
`AlistGo/alist` 是 Go + **AGPL-3.0** 的网盘聚合。所谓"xiaoya 直播"不是独立项目，而是基于 Alist 的第三方整合发行，直播部分靠外部 m3u/txt 源，**没有独立的直播服务端能力**（判断，非官方表述）。
→ **判断**：对我们的价值 = 0（既没有转流能力，License 又最严）。

**8) TVBox 接口站（配置 JSON）—— 格式要兼容，代码不要**
TVBox 的配置 JSON 是整个中文 TV 生态的"事实标准"。权威字段定义可直接读 [takagen99/Box 的 `ApiConfig.java`](https://github.com/takagen99/Box/blob/main/app/src/main/java/com/github/tvbox/osc/api/ApiConfig.java)（见 A.2.3）。
→ **判断**：我们的服务端应该**能输出** TVBox 兼容 JSON（这样存量 TVBox/影视仓/FongMi 客户端可直接订阅），但不要把 TVBox 的 spider/jar 机制搬进服务端 —— 那是客户端侧的"网页解析器"体系。

---

### A.2 直播源格式规范（含脏数据）

#### A.2.1 M3U / M3U8 扩展属性

基础结构（事实标准，非 RFC）：

```
#EXTM3U x-tvg-url="http://epg.example.com/e.xml" url-tvg="http://epg.example.com/e.xml"
#EXTINF:-1 tvg-id="cctv1" tvg-name="CCTV-1" tvg-logo="http://logo/cctv1.png" group-title="央视频道",CCTV-1 综合
http://host/live/cctv1.m3u8
#EXTINF:-1 tvg-id="" tvg-logo="" group-title="卫视频道",湖南卫视
http://host/live/hunan.m3u8
```

- 首行 `#EXTM3U` 为必需；`x-tvg-url` / `url-tvg` 是 EPG 地址的两种写法 —— Guovin/iptv-api 的 `open_subscribe_epg` 就是从这两个头部字段自动提取 EPG 地址（[README 配置表](https://github.com/Guovin/iptv-api)）。
- `#EXTINF:<时长>,<显示名>`：直播恒为 `-1`；**逗号后的显示名是唯一必需的可读字段**。
- 常用属性：`tvg-id`（对应 XMLTV 的 `<channel id>`）、`tvg-name`、`tvg-logo`、`group-title`。
- 非标准但生态里常见的扩展：`#EXTGRP:<分组>`（独立行指定分组）、`tvg-chno`（频道号）、`radio="true"`、`catchup`/`catchup-source`/`catchup-days`（回看）。
- 播放器兼容性陷阱：`$` 后缀（如 `http://x$!`）是**非标准私有约定**，Guovin 用它做白名单标记与接口说明；写进 M3U 会让部分播放器（README 点名 PotPlayer）无法播放，所以它有 `open_url_info` 开关来关闭。

**解析要点**
1. 先按 `#EXTINF` 切块，每块的"下一行非 `#` 开头"即 URL；允许 URL 与 `#EXTINF` 之间夹 `#EXTVLCOPT`、`#KODIPROP`、`#EXTGRP`。
2. 属性值可能含逗号与引号 → 不能用"按逗号 split"。必须做**带引号感知的 key="value" 扫描**。
3. 显示名可能含逗号（`CCTV-1 综合,高清`）→ 只取**第一个引号外的逗号**之后全部内容。
4. `group-title` 可能缺失 → 用"上一个 `#EXTGRP`"或"文件名/来源名"兜底。

#### A.2.2 TXT 格式（`分组,#genre#`）

Guovin/iptv-api 的模板规范（[`config/demo.txt`](https://github.com/Guovin/iptv-api/blob/master/config/demo.txt) + [`docs/tutorial.md` 第 135 行](https://github.com/Guovin/iptv-api/blob/master/docs/tutorial.md)）：

```
📺央视频道,#genre#
CCTV-1,http://host/live/cctv1.m3u8
CCTV-2,http://host/live/cctv2.m3u8
📡卫视频道,#genre#
湖南卫视,http://host/live/hunan.m3u8$!白名单
```

规则（原文）："模板文件需要按照（频道分类,#genre#），（频道名称,频道接口）进行编写，注意是**英文逗号**"；地址后可加 `$!` 表示白名单（不测速、排最前），`$` 后可附说明信息。

**解析要点**
- `#genre#` 行 = 分组声明，格式 `分组名,#genre#`；也支持"只有分组名、没有 URL"的模板写法（只声明想要哪些频道，源由聚合器去填）。
- `名称,url` 行：**第一个英文逗号**前是名称，之后是 URL（URL 本身含逗号的情况极少但存在，用"只切第一个逗号"最稳）。
- `$` 之后是元信息，不是 URL 的一部分 → 入库前必须剥离，否则探测必失败。

#### A.2.3 TVBox `lives` 配置 JSON（精确字段）

字段定义以 [`ApiConfig.java`](https://github.com/takagen99/Box/blob/main/app/src/main/java/com/github/tvbox/osc/api/ApiConfig.java) 的 `parseJson()` 为准，已核对源码：

**顶层**
| 字段 | 类型 | 说明 |
|---|---|---|
| `spider` | string | 爬虫 jar 地址，可带 `;md5;<hash>` 校验；`img+` 前缀表示 jar 藏在图片里 |
| `sites` | array | 点播源；也允许写成 `video.sites` |
| `parses` | array | 解析线路 |
| `flags` | string[] | 需要 VIP 解析的 flag |
| `lives` | array | **直播配置（本节重点）** |
| `rules` | array | 嗅探/广告过滤规则（`host`+`rule`/`filter`，或 `hosts`+`regex`/`script`） |
| `ads` | string[] | 广告域名黑名单 |
| `ijk` | array | IJK 解码参数组（`group` + `options[{name,category,value}]`） |
| `wallpaper` / `jarCache` / `livePlayHeaders` | — | 壁纸 / jar 缓存开关 / 直播请求头 |

**`sites[]` 元素字段**：`key`(必填)、`name`、`type`(int,必填)、`api`(必填)、`searchable`(默认1)、`quickSearch`(默认1)、`filterable`(默认1)、`hide`(默认0)、`playUrl`、`ext`、`jar`、`playerType`(默认-1)、`categories`、`click`、`style`。

**`parses[]` 元素字段**：`name`、`url`、`ext`(JSON 对象)、`type`(默认0)。

**`lives[]` 有两种互不兼容的形态**（源码里用 `lives.contains("type")` 分流）：

形态一（FongMi 风格，推荐）：
```json
{"lives":[{"type":"0","url":"http://host/live.txt","epg":"http://host/epg.xml","playerType":-1}]}
```
`type:"0"` 表示 url 指向一个 txt/m3u 源文件。

形态二（takagen99 内联风格）：
```json
{"lives":[{"group":"央视频道","channels":[
  {"name":"CCTV-1","urls":["http://a/cctv1.m3u8$源1","http://b/cctv1.m3u8$源2"]}
]}]}
```
`group` 支持 `分组名_密码` 形式（下划线后为分组密码）；`urls[]` 里 `$` 后是"源名称"。

**判断**：我们要兼容的是**形态一 + 外部 txt/m3u**，因为它和 A.2.2 的 TXT 格式天然衔接；形态二（内联）可作为导出选项。`spider`/`jar`/`parses` 与我们无关，可留空。

#### A.2.4 XMLTV 格式字段

权威定义：[`xmltv.dtd`（Debian sources 镜像）](https://sources.debian.org/src/xmltv/1.0.0-1/xmltv.dtd)、[XMLTV Wiki: XMLTVFormat](https://wiki.xmltv.org/index.php/XMLTVFormat)。

DTD 骨架（已核对）：
```
<!ELEMENT tv (channel*, programme*)>
<!ATTLIST tv date CDATA #IMPLIED ...>
```
- `<tv>`：根节点，`date` 为生成时间。**所有频道在前，所有节目在后**（DTD 顺序约束，解析器可能不宽容）。
- `<channel id="...">`：`id` 是**唯一标识**，必须与 M3U 的 `tvg-id` 完全一致才能对上；内含 `<display-name>`（可多个，可带 `lang`）、`<icon src="...">`。
- `<programme start="20261101200000 +0800" stop="..." channel="...">`：时间格式为 `YYYYMMDDhhmmss +ZZZZ`（"loosely based on ISO 8601"，DTD 原文），**必须带时区偏移**，否则跨时区会错位。
- `<programme>` 子元素常用：`<title>`、`<sub-title>`、`<desc>`、`<category>`、`<episode-num system="xmltv_ns|onscreen">`、`<icon>`、`<rating>`、`<new/>`。
- 编码通常 UTF-8；实际抓到的源常见 GBK/GB18030（见下）。

#### A.2.5 常见脏数据与处理清单

| 脏数据 | 现象 | 处理 |
|---|---|---|
| **BOM** | 文件头 `EF BB BF`，导致首行 `#EXTM3U` 匹配失败、第一组分组名带不可见字符 | 读入即 strip `\uFEFF`；解析器第一个字符判断用 `codePointAt(0)` 而非 `charAt(0)` |
| **GBK/GB18030 编码** | 中文频道名乱码（"锟斤拷"） | 先按 UTF-8 严格解码，失败则回退 GB18030；不要"先转再猜" |
| **CRLF / 混用换行** | 行尾带 `\r`，URL 末尾多一个不可见字符 → HTTP 404 | 统一 `split(/\r?\n/)` 并 trim |
| **重复频道** | 同一频道在多个分组、同一分组多行 | 归一化后按 `(归一化名, URL host)` 去重；保留"多源"而不是丢源（一个频道多 URL 是我们的核心价值） |
| **空行 / 注释行** | 空行、`#` 开头的说明 | 跳过空行；`#` 行除已知指令外一律当注释 |
| **URL 带 `$` 后缀** | 探测失败 | 入库前剥离 `$` 之后内容（Guovin 的 `$!` 白名单、`$说明`） |
| **相对 URL / `clan://`** | TVBox 生态常见 `clan://localhost/file/...` | 按配置源地址做 base 解析；不认识的 scheme 直接标记不可用 |
| **`tvg-id` 缺失或全空** | EPG 完全对不上 | 走 A.3 的名称模糊匹配 |
| **分组名带 emoji / 空格** | 排序、去重失效 | 归一化：去 emoji、trim、全角转半角、大小写折叠 |
| **`#EXT-X-ENDLIST` 的短循环列表** | 是"无信号/广告"占位源 | 抓取 playlist 判断是否含 `ENDLIST` 且分片数少 → 判为广告源（Guovin 的做法） |
| **超大文件（万级频道）** | 内存与解析耗时 | 流式逐行解析，不要一次 `readAll` |

---

### A.3 EPG 源与频道名 → EPG id 匹配

**常见 XMLTV 提供方（可查到的）**

| 源 | 地址 | 说明 |
|---|---|---|
| 老张 EPG（51zmt） | `http://epg.51zmt.top:8000/e.xml` | 国内最常被引用的"节目总表"，另有测试用节目表；见 [dogwalkerg/tv README](https://github.com/dogwalkerg/tv) 与 [iMoke Wiki 整理](https://wiki.imoke.org/iptv/IPTV%20EPG%E7%94%B5%E5%AD%90%E8%8A%82%E7%9B%AE%E5%8D%95%E6%94%B6%E9%9B%86.html) |
| 112114 | 见社区整理 | 与 51zmt 并列为国内两大常用源；具体域名请自行核实（**未证实**确切地址，社区帖提到"经常连不上 112114、51zmt"） |
| epg.pw | — | **未证实**：本次检索未取得可验证的官方接口说明，不要写进产品默认配置 |
| 自建 | — | 长期方案：自己聚合 + 缓存 + 本地快照，对外只暴露我们自己的 `/epg.xml` |

**判断**：第三方 EPG 只能当"可选增强源"，不能当依赖。产品必须支持"用户自带 XMLTV URL"，并在服务端做**多源合并 + 本地缓存 + 失败降级**。

**频道名 → EPG id 的匹配策略（tvg-id 缺失时）**

分层、可解释、可人工纠正（这是必须自己写的部分）：

1. **精确匹配**：`tvg-id` 直接命中 `<channel id>`。
2. **规范化匹配**：把两侧都做归一化后比较 —— 去空格/全角/emoji、大小写折叠、去"高清/HD/1080P/超清/4K/备用"等画质后缀、去 `-`/`·`/`_`。
3. **别名表**：维护 `别名 → 标准名`（Guovin 的 2,769 频道 / 7,254 别名 / 48 正则就是这类资产，可参考其数据形态自行整理）。
4. **模糊匹配**：编辑距离 / token 包含关系打分，取最高分且**高于阈值**；候选分差过小则判为"不确定"，**宁可留空也不要错配**（错配的 EPG 比没有 EPG 更伤体验）。
5. **人工兜底**：管理后台提供"未匹配频道"列表 + 一键绑定（Guovin 的 `unmatch.log` 就是这个思路）。
6. **回写缓存**：把人工确认的结果持久化为 `频道指纹 → epg_id`，下次直接命中。

**关键工程点**
- 匹配结果必须**版本化**：EPG 源更新后 `<channel id>` 可能变，要能重算。
- 匹配是 O(频道数 × EPG 频道数)，万级时要用倒排索引（按归一化名前缀/首字分桶），不要暴力双重循环。

---

### A.4 流媒体转发 / 转码

#### A.4.1 HLS 直播切片与"单源多终端"

架构判断（推荐）：

```
上游源 (m3u8 / 裸 TS / RTSP / RTMP)
        │  ① 单次拉流（每路源只拉一次）
        ▼
   转发进程（ffmpeg 或 MediaMTX）
        │  ② 切片 / 转封装
        ▼
   本地 HLS 目录或内存分片（.m3u8 + .ts/.m4s）
        │  ③ 鉴权后的 HTTP 分发
        ▼
   N 个客户端（各自独立 token、独立会话）
```

**核心原则：一个上游流只拉一次。** 否则 10 个终端 = 10 倍上游带宽 + 10 倍被封风险。

三种可选实现（按复杂度排序）：

| 方案 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| **A. ffmpeg 直出 HLS** | `ffmpeg -i <src> -c copy -f hls -hls_time 2 -hls_list_size 6 -hls_flags delete_segments+append_list <dir>/ch.m3u8` | 简单、`-c copy` 几乎零 CPU | 每个频道一个 ffmpeg 进程；无人观看时仍在跑（需自己做空闲回收） |
| **B. MediaMTX 扇出** | 上游作为 path 的 source，客户端从 MediaMTX 的 HLS/WebRTC 端点读 | 天然多协议、多客户端扇出、hooks 可做计费/鉴权 | 多一个进程；HLS 切片参数受其配置约束 |
| **C. 自研切片器** | 自己解 TS/取分片、维护 playlist 窗口 | 完全可控（可做"边切片边注入广告标记/自定义 tag"） | 工作量最大，不建议第一版做 |

→ **建议**：第一版用 **A（ffmpeg `-c copy`）+ 自研调度层**（谁在看、何时起、何时回收），把"切片"这个脏活交给 ffmpeg；等真的需要 WebRTC/低延迟再引入 MediaMTX（B）。

**多终端一致性要点**
- `hls_list_size` 与 `hls_time` 决定"可回溯窗口"：`hls_time 2` + `list_size 6` ≈ 12s 窗口，切台/重连不会立刻 404。
- `delete_segments` 必须开，否则磁盘会被撑爆；但要保留 `list_size + 2` 个分片给慢客户端。
- 客户端并发数要有上限（每路源一个"扇出组"），超出后排队或拒绝 —— 这是 N100 这类设备的真实瓶颈，不是带宽。
- **无人观看回收**：最后一路客户端断开后延时 N 秒停 ffmpeg（Guovin 的 `rtmp_idle_timeout` 默认 300s 就是这个思路），并用"起播时的首帧超时"兜底。

#### A.4.2 N100（Intel UHD / QSV）的 ffmpeg 参数建议

**事实部分（已验证）**
- FFmpeg 的 QSV 编码器为 `h264_qsv` / `hevc_qsv`（`ffmpeg -encoders | grep qsv` 可列，选项见 [ffmpeg-codecs 文档](https://ffmpeg.org/ffmpeg-codecs.html)）。
- N100 属 Intel Alder Lake-N，带 UHD Graphics（24EU），QSV 可用；解码/编码能力与具体驱动版本强相关（Intel media-driver / oneVPL），**具体能编几路 1080p 未证实**，必须实测。
- `-tune zerolatency`、`-preset veryfast`、`-fflags nobuffer` 是 **libx264/x265 与通用 ffmpeg 的选项**；QSV 编码器**不一定支持 `-tune`**。给 QSV 传 `-tune zerolatency` 很可能直接报 "Option not found"。

**推荐起步参数（转码档，非 copy）**
```bash
# 硬解 + 硬编 H.264，低延迟直播档
ffmpeg -hide_banner -loglevel warning \
  -hwaccel qsv -hwaccel_output_format qsv -i "$SRC" \
  -vf "scale_qsv=w=1280:h=720" \
  -c:v h264_qsv -preset veryfast -profile:v high \
  -g 50 -keyint_min 50 -sc_threshold 0 -bf 0 \
  -b:v 2500k -maxrate 3000k -bufsize 3000k \
  -c:a aac -b:a 128k -ar 48000 -ac 2 \
  -f hls -hls_time 2 -hls_list_size 6 \
  -hls_flags delete_segments+append_list+omit_endlist \
  "$OUT/ch.m3u8"
```

要点说明（每条都可自查）：
- `-hwaccel qsv -hwaccel_output_format qsv`：解码后帧留在显存，避免 `qsv→system→qsv` 的两次拷贝（这是 QSV 性能的关键）。
- `-bf 0` + `-g 50` + `-sc_threshold 0`：固定 GOP、无 B 帧 → **切台/起播快**，代价是同码率画质略降。
- `-hls_flags omit_endlist`：直播流不要写 `#EXT-X-ENDLIST`（写了播放器会认为流结束）。
- 低延迟还可加：`-flags low_delay`、`-avioflags direct`、输入端 `-fflags nobuffer -probesize 32 -analyzeduration 0`（后两个会牺牲首帧稳定性，**建议只在"已确认源稳定"时用**）。
- 需要 HEVC 时换 `-c:v hevc_qsv -profile:v main`；注意 **iOS/Android 对 HEVC over HLS 的支持是有条件的**（见 C.2）。
- 一切选项以 `ffmpeg -h encoder=h264_qsv` 的实际输出为准（不同 ffmpeg 版本选项名有增删，**不要照抄网上参数**）。

**在 fnOS/fpk 里的两个前提（务必先解决，否则 QSV 直接不可用）**
1. **权限**：fnOS 第三方应用默认非 root，要用 `/dev/dri` 必须 `join-groups: ["video","render"]`（见 [01-fnos-fpk.md](./01-fnos-fpk.md)）。**不要为了硬解开 root。**
2. **驱动/运行库**：QSV 依赖 Intel media-driver / oneVPL 运行库；fpk 里要么随包带上，要么在启动时检测并明确报错（不要静默 fallback 到软编，否则用户会看到"CPU 100% 但能播"的假象）。

**什么时候根本不用转码**：如果上游就是标准 H.264+AAC 的 HLS/TS，`-c copy` 转封装（甚至只做 playlist 重写）即可，CPU 接近 0。**先判断"能不能 copy"，再谈硬编** —— 这是 N100 上能扛几十路和只能扛 3 路的分水岭。

---

### A.5 播放地址鉴权常见方案

| 方案 | 做法 | 强度 | 适用 |
|---|---|---|---|
| **短期签名 URL（推荐主方案）** | `?e=<过期unix>&s=<HMAC-SHA256(secret, path+e+uid)>`，服务端校验并比对过期时间 | 中高（可绑用户、可限时、可撤销） | 所有 m3u8 与 ts 分片请求 |
| **nginx `secure_link`** | `secure_link $arg_s,$arg_e; secure_link_md5 "$secure_link_expires$uri$remote_addr secret";` | 中（成熟、零代码） | 若用 nginx 做分发层；见 [ngx_http_secure_link_module](https://nginx.org/en/docs/http/ngx_http_secure_link_module.html) |
| **UA / Referer 透传校验** | 校验 `User-Agent`/`Referer` 白名单 | 低（可伪造） | 仅作"提高盗链成本"，不能当唯一防线 |
| **IP 白名单 / 网段限制** | 仅允许内网或已知网段 | 中（但移动端切换网络就断） | 局域网场景；或作为"同账号并发设备数"的辅助 |
| **Cookie/Session** | 首次登录下发 HttpOnly Cookie，分片请求带 Cookie | 中 | HLS 原生播放器（AVPlayer）对自定义 header 支持差，Cookie 反而更省事 |

**必须注意的坑（这是"看起来能用、上线就漏"的地方）**
1. **m3u8 与 ts 必须同一套鉴权**。只签 m3u8 不签分片 = 分片 URL 一旦泄露就永久可用。签名要覆盖 **path + 过期时间**，分片路径同样校验。
2. **过期时间不能太长**：直播分片寿命只有几秒，token 有效期 30–120s 足够；长有效期等于没有。
3. **播放器差异**：AVPlayer / ExoPlayer 对"重定向后丢 query"很敏感 → 鉴权参数放在**原始 URL 的 query**里，不要依赖 302 传递。
4. **HMAC 用常量时间比较**，别用 `==`。
5. **密钥轮换**：支持 `kid` 或"新旧双密钥并行校验"，否则轮换瞬间全体掉线。
6. **并发控制**：同一 token 限制并发 IP 数，超限拒绝（比 IP 白名单更实用）。

---

## 3. B. Android 客户端（Kotlin）

### B.1 播放内核选型对比

| 内核 | License | HLS/TS 直播 | 软/硬解切换 | 字幕 | HDR | 起播速度 | 结论 |
|---|---|---|---|---|---|---|---|
| **AndroidX Media3 (ExoPlayer)** | **Apache-2.0** | HLS 有官方模块 `media3-exoplayer-hls`；裸 TS 走 `media3-exoplayer-ts`（需自行确认模块可用性） | 硬解默认走 MediaCodec；软解需外挂 FFmpeg 扩展（见 B.3） | 支持 SRT/SSA/WebVTT（部分靠扩展） | 支持 HDR→SDR tone mapping（依赖设备与配置） | 快（硬解直通、可预缓冲） | **主力** |
| **libmpv (mpv-android)** | **MIT**（应用层）/ libmpv 本体 GPLv2+（需以 mpv 仓库为准） | 极强（ffmpeg 全格式） | `hwdec=auto-safe` 运行时切换，业界最好 | libass，效果最好 | 好 | 中（进程内初始化 + 解码器选择有开销） | **兜底/高级档** |
| **ijkplayer** | **GPL-2.0** | 曾经的事实标准 | 有 | 一般 | 弱 | 中 | **淘汰**：bilibili 已停止维护（仓库无近期提交） |
| **VLC (libVLC)** | LGPL-2.1+（Android 版） | 强 | 有 | 好 | 一般 | 慢（起播偏慢） | 备选，体积大 |

**判断**：
- **主链路选 Media3**：官方维护、Apache-2.0、硬解功耗与起播最优、与 Android TV/Compose 生态一致。Jellyfin 官方 Android 客户端当前就是 Media3 1.11.1 + `org.jellyfin.media3:media3-ffmpeg-decoder` 1.9.0+1（见其 [libs.versions.toml](https://github.com/jellyfin/jellyfin-android/blob/master/gradle/libs.versions.toml)）—— 这是"成熟产品怎么选"的最强证据。
- **libmpv 作为"格式兜底 + 软解"第二内核**：当 Media3 解不了（异常 TS 封装、AC3、AVS3）时切换。**双内核切换的复杂度是真实成本**，要评估是否值得。
- **ijkplayer 不要碰**：GPL-2.0 且无维护。

### B.2 libmpv 在 Android 的接入方式

**重要事实（直接推翻常见误解）**：mpv-android 官方 README 明确写：

> mpv-android is **not** a library/module (AAR) you can import into your app.
> If you'd like to use libmpv in your app you can use our code as inspiration. The important parts are `MPVLib`, `BaseMPVView` and the native code. Native code is built by [these scripts](buildscripts/).

（[mpv-android README](https://github.com/mpv-android/mpv-android)）

所以：
- ❌ 不存在官方 `io.github.jellyfin.mpv` 这类"官方坐标"（**未证实**该坐标存在，不要写进依赖）。
- ✅ 官方路径 = **自己编**：`mpv-android/buildscripts/` 交叉编译 libmpv + ffmpeg → 产出 `.so` → 把 `MPVLib.kt`（JNI 封装）与 `BaseMPVView.kt`（Surface 渲染）搬进自己的工程。
- ⚠️ 第三方 Maven 上确实有 libmpv 的 aar（例如 `org.jellycine.mpv:libmpv` 1.0.4，标注 MIT），但**来源为个人发布，ABI 覆盖/ffmpeg 编解码器集合/更新节奏均不受控**，只能作为"验证可行性"的临时手段，不能作为产品依赖。

**自编 libmpv 的工程量（判断）**
1. 装 NDK + meson/ninja + 交叉编译脚本（mpv-android 的 buildscripts 就是干这个）。
2. 决定 ffmpeg 编解码器集合 —— 这里决定了"能不能解 AVS3/AC3"，也决定了 .so 体积（全量 ffmpeg 会让 APK 涨到几十 MB，**必须按 ABI 拆分 + 裁剪**）。
3. 维护成本：mpv/ffmpeg 每次安全更新都要重编。**这是长期负担，不是一次性投入。**

### B.3 Media3 自定义 Renderer / FFmpeg 扩展 / AVS3

**官方 FFmpeg 扩展的真相**（[androidx/media `libraries/decoder_ffmpeg/README.md`](https://github.com/androidx/media/blob/release/libraries/decoder_ffmpeg/README.md)）：
- 模块提供的是 **`FfmpegAudioRenderer`——只做音频**，不是视频解码器。
- **不在 Google Maven 上发布**（README 引用 ExoPlayer issue 2781），必须 clone 仓库本地依赖。
- 构建：NDK（README 称在 r26b 上测过）+ CMake + Ninja，推荐 FFmpeg **release/6.0** 分支，用 `build_ffmpeg.sh` 编 `armeabi-v7a/arm64-v8a/x86/x86_64`。
- 启用方式：`DefaultRenderersFactory(context).setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON|PREFER)`。
- 视频软解要用**第三方**：`io.github.anilbeesetti:nextlib-media3ext`（**GPL-3.0**），支持视频 H.264/HEVC/VP8/VP9 + AV1(dav1d)、音频 AC3/EAC3/DCA/MLP/TrueHD，并提供 `DecoderManager` 做**运行时硬/软解切换**（`selectVideoDecoder(HARDWARE|...)`）；其构建固定 FFmpeg 9.0.1 + dav1d 1.5.4。
- 另一条路：`org.jellyfin.media3:media3-ffmpeg-decoder`（**GPL-3.0**，Maven Central 有）。

⚠️ **License 提醒**：GPL-3.0 的扩展一旦静态链接进 APK，整个 APK 的 GPL 义务就要评估（尤其"通过应用商店分发"场景）。**这是选型的硬约束，不是技术细节。**

**AVS3（AVS3-P2 / IEEE1857.10）在 Android 上的现状**

已验证事实：
1. **FFmpeg 没有原生 avs3 解码器**。FFmpeg 的 Changelog 里关于 AVS3 只有两条记录，都在 **4.4** 版本块内：`AVS3 demuxer` 与 `AVS3 video decoder via libuavs3d`；此后各版本（含 8.0.x）**未再出现** AVS3 相关条目 → 意味着至今仍**只能通过外部库 libuavs3d 解码**，没有内置实现。（[FFmpeg 8.0 分支 Changelog](https://github.com/FFmpeg/FFmpeg/blob/release/8.0/Changelog)）
2. 官方文档有专门小节：`libuavs3d` — "AVS3-P2/IEEE1857.10 video decoder… You need to explicitly configure the build with `--enable-libuavs3d`"，并有一个 `frame_threads` 选项。（[ffmpeg-codecs 文档](https://ffmpeg.org/ffmpeg-codecs.html)）
3. 解码库本体：[uavs3/uavs3d](https://github.com/uavs3/uavs3d)，"AVS3 decoder which supports AVS3-P2 **baseline profile**"，**BSD-3-Clause**（README 原文），最后提交 2025-08（仍在小幅维护）。
4. 编码器：[uavs3/uavs3e](https://github.com/uavs3/uavs3e)，最后提交 2022-10（基本停滞）。
5. 常被提到的 `davs3` 项目：**未证实**。`github.com/xatabhk/davs3` 返回 404；本次检索未找到可验证的 "davs3" 独立仓库。相关可查的是 `xatabhk/FFmpeg-avs2-avs3`（FFmpeg fork）与 `xatabhk/vlc-avs2-avs3`（VLC fork），属于"给 ffmpeg/vlc 打 AVS3 补丁"的路线，而非独立解码库。

**结论（判断）**
- **Android 上解 AVS3 只有一条现实路径**：自编 ffmpeg（`--enable-libuavs3d`）→ 通过 libmpv 或 nextlib 式软解 renderer 使用。**没有系统级/硬件 AVS3 解码器**（主流 SoC 不支持），所以**必然是软解**。
- **性能**：1080p 软解可行性取决于设备 CPU（ARM 大核 + NEON 优化），**4K 软解在电视盒子上基本不可行**（未实测，但按 uavs3d 单线程/多线程吞吐与盒子 CPU 推断）。**必须在目标机型上实测**再决定是否承诺 4K AVS3。
- **`baseline profile` 限制**：uavs3d 只保证 baseline，遇到非 baseline 流可能解不了 —— 这是产品承诺前必须验证的边界。
- **建议**：把 AVS3 定位为"可选能力"（单独的可选解码包/可选内核），不要放进主链路，否则 APK 体积与稳定性都会被拖累。

### B.4 电视端 UI 框架与交互

**框架对比**

| 方案 | 状态 | 焦点/D-pad | 结论 |
|---|---|---|---|
| **Leanback** | 老牌、存量多，但 Google 新文档已把重心放到 Compose for TV | `BrowseSupportFragment` 自带焦点管理，改起来很痛 | 只适合"标准海报墙"；我们要的"列表+预览小窗+数字键"会被它绑死 |
| **Compose for TV (`androidx.tv`)** | 官方当前方向；`androidx.tv.material3` 组件（`TvLazyRow`、`TvSurface` 等） | 需自己写 `FocusRequester`/`onKeyEvent`，但**完全可控** | **推荐** |
| **自绘（View/Canvas）** | — | 全手写 | 只在"预览小窗 + 秒切台"性能不达标时考虑 |

参考文档：[在 Android TV 上使用 Jetpack Compose](https://developer.android.com/training/tv/playback/compose)、Google 官方迁移指南 [leanback-to-compose-tv-migration](https://github.com/android/skills/blob/main/tv/leanback-to-compose-tv-migration/SKILL.md)。

**要抄的交互（行业共识，来源为各 App 的实际行为，非文档）**

| 交互 | 行为 | 实现要点 |
|---|---|---|
| **频道列表 + 预览小窗** | 列表滚动时右侧/背景播放当前高亮频道，确认键才"进全屏" | 双 Surface / 双 Player 实例，或单 Player 换输出 Surface；预览流用低码率档 |
| **上下键换台** | 全屏下 ↑/↓ 直接切上/下一个频道 | 按键去抖（长按连发要节流），切台期间显示"正在切换"占位，**不要黑屏** |
| **数字键直接跳台** | 输入 `1` `0` `5` → 3 秒内无输入则跳到 105 | 需要"频道号"概念（TVBox 配置里 `channelNum` 是自增序号；要支持用户自定义频道号） |
| **长按呼出 EPG** | 长按 OK/菜单 → 弹出当前频道节目单 + 其他频道正在播 | 需要预取 EPG，且**首屏就要有数据**（不能等用户按了才请求） |
| **返回键层级** | 全屏 → 列表 → 退出，逐级返回 | 明确的状态机，避免"按返回直接退出" |

**必须自研的部分（判断）**：焦点系统（Compose 的默认焦点走位在复杂列表里经常不听话）、切台状态机、预览小窗与全屏的 Player 复用。这三块没有任何现成库能直接用。

**开源参考**（License 已核）：
- [FongMi/TV](https://github.com/FongMi/TV)：**GPL-3.0**，Java。TVBox 兼容配置 + 直播/点播一体，遥控器交互最完整。→ **只能看交互，不能抄代码**（GPL 传染）。
- [lizongying/my-tv](https://github.com/lizongying/my-tv)：C 80.1%，**仓库未标注 License**（"not specified"）→ **默认保留所有权利，不可复用**，只能看界面思路。
- [jellyfin/jellyfin-androidtv](https://github.com/jellyfin/jellyfin-androidtv)：**GPL-2.0**，Kotlin 69.6%。TV 端焦点/播放器架构参考价值高，同样只能看。
- takagen99/Box、q215613905/TVBoxOS：TVBox 系分支，License 需逐个确认（本次未核）。

> **重要提醒**：TV 播放器开源生态几乎全是 **GPL/AGPL**。想"抄代码"，可选空间极小；想"抄交互"，完全没问题。

### B.5 局域网发现 / 配对

| 方案 | 原理 | 适用 | 风险 |
|---|---|---|---|
| **mDNS/DNS-SD** | 组播 224.0.0.251:5353 广播 `_http._tcp` 等服务（[RFC 6762](https://www.rfc-editor.org/rfc/rfc6762)） | 同局域网自动发现服务端 | 部分路由器/AP 隔离（AP isolation）会阻断组播；Android 上 `NsdManager` 可用 |
| **SSDP / DLNA** | UDP 1900 组播 `M-SEARCH`（UPnP 设备发现基础） | 与电视/盒子生态天然兼容；Threadfin 依赖 `go-ssdp` 就是干这个 | DLNA 协议族庞大且实现参差，**只做发现，不做投屏**，成本可控 |
| **二维码配对** | 服务端生成含一次性 token 的二维码，App 扫码换长期凭据 | 最省事、跨网段也能用（只要 App 能访问服务端） | 需摄像头权限；TV 端无摄像头 → 只能反向（TV 显示二维码，手机扫） |
| **设备激活码** | TV 显示 6–8 位码 → 用户在 Web/手机端输入 → 绑定 | **最适合 TV**（无输入法、无摄像头） | 码要短时有效 + 限次尝试，否则会被爆破 |

**推荐组合（判断）**：**激活码绑定为主 + mDNS 发现为辅**。
- TV 首次启动 → 显示"设备码 + 局域网地址" → 用户在手机/网页登录后输入码 → 服务端下发设备凭据（长期 token）。
- mDNS 只用于"同一局域网内 App 自动填服务端地址"，减少用户手输 IP 的痛苦；失败就退回手输。
- DLNA 只在"要接第三方播放器"时才做，别把它当配对方案。

---

## 4. C. iOS 客户端

### C.1 xtool（Linux/WSL 上构建 iOS）

**事实（已验证）**
- 仓库：[xtool-org/xtool](https://github.com/xtool-org/xtool)，**MIT**，Swift 99.0%。
- 定位（README 原文）："Cross-platform Xcode replacement. Build and deploy iOS apps with **SwiftPM** on Linux, Windows, and macOS"，用开放标准复刻 Xcode 功能。
- 子命令（README 原文）：`setup`（初始化 iOS 开发环境）、`auth`（管理 Apple Developer Services 认证）、`sdk`（管理 **Darwin Swift SDK**）、`new` / `dev`（创建/构建运行 SwiftPM 项目）、`ds`（与 Apple Developer Services 交互）、`devices` / `install` / `uninstall` / `launch`。
- 可当 Swift Package 依赖：`XKit`（Apple Developer Services、iOS 设备交互）、`XToolSupport`。README 注明 Swift 接口**不保证 semver**。
- 起源：作者 kabiroberai 先做了 "Swift SDK for Darwin"（在 Linux 上构建 iOS Swift Package），后开源为 xtool（[Swift Forums 发布帖](https://forums.swift.org/t/xtool-cross-platform-xcode-replacement-build-ios-apps-on-linux-and-more/79803)）。

**关键问题逐条回答**

| 问题 | 答案 | 依据 |
|---|---|---|
| 原理 | 用 **Darwin Swift SDK**（含 iOS 平台 SDK 与 Swift 标准库）替代 Xcode 的 toolchain + SDK；用 SwiftPM 做构建系统；用 Apple Developer Services API 做签名/描述文件/设备注册 | README 的 `sdk` / `auth` / `ds` 子命令 |
| 需要完整 Xcode 吗 | **不需要**；需要的是 Darwin Swift SDK（由 `xtool sdk` 管理） | README "replicates Xcode functionality with open standards" + `sdk` 子命令 |
| 支持 SwiftUI 吗 | **未证实**。SwiftUI 属于 iOS SDK 内的框架，理论上随 Darwin SDK 提供；但 xtool 官方文档站（xtool.sh）为 JS 渲染，本次未能取得正文，**必须实测一个 SwiftUI 工程** | — |
| 能链接第三方 .a / .xcframework 吗 | **未证实**。SwiftPM 支持 binaryTarget（xcframework），但 xtool 是否有额外限制、静态库是否需要重签，需实测 | — |
| 能签名装真机吗 | **能**（`xtool auth` + `ds` + `install`）。但签名凭据仍来自 Apple：免费 Apple ID 的个人团队证书 **7 天有效**、需设备 UDID 注册；付费开发者账号则 1 年 | README 子命令 + Apple 免费 provisioning 的通行规则 |

**判断**：xtool 是目前"无 Mac 出 IPA"唯一活跃方案，**值得做 PoC**，但要把"三个未证实项（SwiftUI / xcframework / 真机稳定性）"作为 PoC 的验收条件，任一失败就退回"云 Mac"方案。

### C.2 iOS 播放内核：AVPlayer vs VLCKit

**AVPlayer / AVFoundation 的支持范围（判断 + 公开事实）**

| 项 | 支持情况 |
|---|---|
| **HLS（.m3u8）** | ✅ 系统级最优实现，硬件解码、低功耗、AirPlay 原生 |
| **H.264 / AVC** | ✅ |
| **H.265 / HEVC** | ✅（iOS 11+；HLS 里的 HEVC 需按 Apple HLS Authoring Spec 封装） |
| **AAC / MP3 音频** | ✅ |
| **AC3 / E-AC3（Dolby Digital）** | ❌ **不支持**（Apple 未授权第三方 App 直接解码；需 Dolby 授权或系统级支持） |
| **裸 MPEG-TS（非 HLS 封装）** | ⚠️ 支持很差/不支持 → **服务端必须输出 HLS** |
| **AVS3** | ❌ 完全不支持（系统无解码器） |

→ **结论**：**iOS 端能不能用，取决于服务端能不能把流"洗"成标准 HLS（H.264/HEVC + AAC）**。这不是客户端问题，是服务端转码策略问题。
→ 若源是 AC3 音轨，**必须在服务端转成 AAC**（或提供"仅 iOS 的 AAC 档"），否则 iOS 用户"有画面没声音"。

**MobileVLCKit**
- CocoaPods 上最新为 **4.0.0a2，最后发布 2023-03**，License 标注 "Custom"（以 [VLCKit 仓库](https://code.videolan.org/videolan/VLCKit) 为准，通常为 LGPL-2.1+，含可选 GPL 组件）。（[CocoaPods 页面](https://cocoapods.org/pods/MobileVLCKit)）
- → **判断**：能用，但**版本明显滞后**；要用新版得自己编 xcframework（需要 macOS + Xcode，与我们"无 Mac"目标冲突）。且 LGPL 在 iOS 上的静态链接合规问题需要法务确认。
- → 定位：**只在"必须播 AC3/异常封装"时作为可选内核**，不作为默认。

### C.3 无 Mac 情况下的真机安装路径

| 方案 | 需要什么 | 有效期 | 限制 |
|---|---|---|---|
| **Sideloadly** | Windows/Mac + Apple ID（免费或付费） | 免费 7 天 / 付费 1 年 | 需定期重签；免费账号同时最多 3 个 App（Apple 限制，**未在本次检索中逐条验证，属通行规则**）。[sideloadly.io](https://sideloadly.io/) |
| **SideStore / AltStore** | 首次需电脑装一次，之后**设备自签**（on-device 刷新） | 同上 | SideStore 是 AltStore 分支，支持机上签名、AppID 合并。[sidestore.io](https://sidestore.io/)、[SideStore/SideStore](https://github.com/SideStore/SideStore) |
| **TrollStore（巨魔）** | 依赖特定漏洞，**免签名、免证书、安装后不过期** | 永久 | **版本窗口极窄**：iOS 14.0–17.0 的部分版本；**iOS 17.0.1 及更高版本永不支持**（[TrollStore2 说明](https://github.com/XLsn0w/TrollStore2)）。不可作为产品分发方案，只能当"用户自选" |
| **企业签名** | 企业开发者账号（$299/年）+ 合规用途 | 1 年（但常被封） | 违规分发会被 Apple 吊销，**有法律与账号风险**，不建议 |
| **App Store** | 开发者账号 + 审核 | — | IPTV 类 App 审核政策风险极高（内容合规） |

**xtool 产出的 .ipa 能否直接侧载？**
→ **能**。xtool 的 `install` 子命令本身就是"签名 + 装到已连接设备"；它产出的是标准 .ipa，理论上也可以交给 Sideloadly/SideStore 重签后安装。
→ 但注意：**用 xtool 自己签的 ipa，一旦换机器/换账号就要重签**；且免费证书 7 天过期是 Apple 侧限制，任何工具都绕不过。

**判断（关键结论）**：iOS 侧真正的瓶颈**不是构建工具，而是分发**。没有企业证书/上架能力时，只能走"用户自签"，这决定了 iOS 端的定位应是"技术验证 + 极客用户"，不能作为首发主平台。

---

## 5. 「必须自己写」vs「可直接复用」清单

### 5.1 必须自己写（这是产品价值所在）

| 模块 | 为什么必须自研 | 可借鉴对象 |
|---|---|---|
| 源管理数据模型（源/频道/多 URL/分组/别名） | 业务核心，没有现成模型能匹配"多终端 + 多源" | Threadfin 的数据结构（MIT，可参考） |
| 源探测与健康度评分（延迟/速率/分辨率/广告源识别） | 决定体验的关键算法；且成熟实现是 AGPL | Guovin/iptv-api 的**思路与参数**（不可用代码） |
| EPG 聚合 + 频道名模糊匹配 + 人工绑定 | 强业务属性，公开实现都不完整 | Guovin 的 unmatch 机制 |
| 鉴权与授权（token 签发/校验/并发控制/设备绑定） | 安全边界，不能依赖第三方黑盒 | nginx secure_link 思路 |
| 转流调度（何时起流、扇出、空闲回收、失败重连） | 决定"能扛多少路"的核心 | Guovin 的 `rtmp_idle_timeout` / MediaMTX hooks |
| TV 端交互（焦点、预览小窗、数字键、长按 EPG） | 没有任何库能直接用；GPL 生态也不能抄 | FongMi/TV 的**交互**（GPL，仅看） |
| 多端一致的播放策略（何时 copy、何时转码、何时切内核） | 跨端体验一致性的核心 | — |

### 5.2 可直接复用 / 直接依赖

| 组件 | License | 用法 | 商用/闭源安全 |
|---|---|---|---|
| **MediaMTX** | **MIT** | 外部进程，做协议互转/扇出 | ✅ 安全 |
| **FFmpeg** | LGPL-2.1+（默认构建）/ GPL（若启用 GPL 组件如 x264） | 转码/切片子进程 | ⚠️ **注意构建配置**：`--enable-gpl` 会传染；用 `-c copy`/QSV 编码器不需要 GPL 组件 |
| **Media3 / ExoPlayer** | **Apache-2.0** | Android 播放内核 | ✅ 安全 |
| **Compose for TV (androidx.tv)** | Apache-2.0 | TV UI | ✅ 安全 |
| **xtool** | **MIT** | iOS 构建/签名 | ✅ 安全（生成物另论） |
| **uavs3d** | **BSD-3-Clause** | AVS3 软解（需自编 ffmpeg） | ✅ 安全 |
| **Threadfin** | **MIT** | **只读参考**（架构/片段） | ✅ 可复制片段（需保留版权声明） |
| **ErsatzTV** | **Zlib** | 参考频道/EPG 数据模型 | ✅ 安全 |
| **streamlink** | BSD-2-Clause | 可选子进程 | ✅ 安全 |
| **restreamer** | Apache-2.0 | 参考 | ✅ 安全 |

### 5.3 ⛔ 不能进产品（License 阻断）

| 组件 | License | 阻断原因 |
|---|---|---|
| **Guovin/iptv-api** | **AGPL-3.0** | 网络服务即触发源码开放义务 → 服务端必须开源 |
| **AlistGo/alist**（及 xiaoya 生态） | **AGPL-3.0** | 同上 |
| **FongMi/TV** | **GPL-3.0** | 链接进 APK → APK 需 GPL 开源 |
| **nextlib (`nextlib-media3ext`)** | **GPL-3.0** | 同上（这是"Media3 视频软解"最现实的方案，但 License 有毒） |
| **`org.jellyfin.media3:media3-ffmpeg-decoder`** | **GPL-3.0** | 同上 |
| **ijkplayer** | GPL-2.0 + 无维护 | 双重阻断 |
| **lizongying/my-tv** | **未标注 License** | 默认保留所有权利，不可复用 |
| **jellyfin-android / jellyfin-androidtv** | GPL-2.0 | 只能读，不能抄 |
| **libmpv 本体** | GPLv2+（mpv 项目） | 动态链接的合规边界需法务确认；**这是"用 libmpv 做商业闭源 App"的核心法律问题，务必先解决再投入** |

> ⚠️ **最容易踩的坑**：Android 想软解/播 AC3，社区给的答案几乎全是 GPL-3.0 的扩展。**如果产品要闭源分发，这条路走不通** —— 要么接受 GPL，要么自研（自己编 ffmpeg 的 LGPL 构建 + 自己写 Media3 Renderer，工作量陡增），要么改用 libmpv 并解决 GPL 边界。**这个决策要在写第一行播放代码之前定下来。**

---

## 6. 参考链接

**服务端**
- https://github.com/Guovin/iptv-api （Python / AGPL-3.0 / 功能与配置全表）
- https://github.com/Guovin/iptv-api/blob/master/config/demo.txt （TXT `#genre#` 模板实例）
- https://github.com/Guovin/iptv-api/blob/master/docs/tutorial.md （TXT 格式规范，第 135 行）
- https://github.com/Threadfin/Threadfin （Go / MIT / xTeVe 分支）
- https://github.com/xteve-project/xTeVe （已停止，最后提交 2021-03）
- https://github.com/ErsatzTV/ErsatzTV （C# / Zlib）
- https://github.com/bluenviron/mediamtx （Go / MIT / 协议互转 + 鉴权 + hooks）
- https://github.com/owncast/owncast （Go / MIT / 单频道）
- https://github.com/datarhei/restreamer （Apache-2.0）
- https://github.com/streamlink/streamlink （Python / BSD-2-Clause）
- https://github.com/AlistGo/alist （Go / AGPL-3.0）

**格式规范**
- https://github.com/takagen99/Box/blob/main/app/src/main/java/com/github/tvbox/osc/api/ApiConfig.java （TVBox 配置 JSON 权威字段定义）
- https://sources.debian.org/src/xmltv/1.0.0-1/xmltv.dtd （XMLTV DTD 原文）
- https://wiki.xmltv.org/index.php/XMLTVFormat （XMLTV 格式说明）
- https://github.com/dogwalkerg/tv （51zmt EPG 地址实例）
- https://wiki.imoke.org/iptv/IPTV%20EPG%E7%94%B5%E5%AD%90%E8%8A%82%E7%9B%AE%E5%8D%95%E6%94%B6%E9%9B%86.html （EPG 源收集）

**转码 / 鉴权**
- https://ffmpeg.org/ffmpeg-codecs.html （编码器选项；libuavs3d 在 4.7 节）
- https://github.com/FFmpeg/FFmpeg/blob/release/8.0/Changelog （AVS3 仅在 4.4 引入 demuxer + libuavs3d 解码）
- https://nginx.org/en/docs/http/ngx_http_secure_link_module.html （secure_link 鉴权）
- https://gist.github.com/mrbar42/09c149059f72da2f09e652d4c5079919 （nginx + rtmp + secure_link 的 HLS 安全示例）

**Android**
- https://github.com/androidx/media/blob/release/libraries/decoder_ffmpeg/README.md （官方 FFmpeg 扩展：仅音频、需自编、推荐 FFmpeg 6.0）
- https://github.com/jellyfin/jellyfin-android/blob/master/gradle/libs.versions.toml （成熟产品依赖：media3 1.11.1 + jellyfin ffmpeg decoder）
- https://github.com/anilbeesetti/nextlib （Media3 的 FFmpeg 扩展：视频+音频+DecoderManager，GPL-3.0）
- https://github.com/mpv-android/mpv-android （MIT；**明确不提供 AAR**，用 `MPVLib` + `BaseMPVView` + `buildscripts/`）
- https://github.com/uavs3/uavs3d （AVS3-P2 baseline 解码，BSD-3-Clause）
- https://github.com/uavs3/uavs3e （AVS3 编码，2022-10 后基本停滞）
- https://developer.android.com/training/tv/playback/compose （Compose for TV 官方文档）
- https://github.com/android/skills/blob/main/tv/leanback-to-compose-tv-migration/SKILL.md （Leanback → Compose TV 迁移指南）
- https://developer.android.com/jetpack/androidx/releases/media3 （Media3 版本记录）
- https://www.rfc-editor.org/rfc/rfc6762 （mDNS）

**iOS**
- https://github.com/xtool-org/xtool （MIT；SwiftPM → iOS App，含 sign/install）
- https://forums.swift.org/t/xtool-cross-platform-xcode-replacement-build-ios-apps-on-linux-and-more/79803 （作者原始发布说明）
- https://cocoapods.org/pods/MobileVLCKit （4.0.0a2，2023-03）
- https://code.videolan.org/videolan/VLCKit （VLCKit 源码与 License）
- https://sidestore.io/ / https://github.com/SideStore/SideStore
- https://sideloadly.io/
- https://github.com/XLsn0w/TrollStore2 （TrollStore 版本窗口说明）

---

## 7. 风险与未解问题

### 7.1 高优先级风险

| # | 风险 | 影响 | 建议动作 |
|---|---|---|---|
| R1 | **播放器扩展全是 GPL-3.0** | 闭源分发直接卡死；改架构成本极高 | **立即定调**：闭源 → 只能 libmpv（解决 GPL 边界）或自研 LGPL 构建 + 自写 Renderer；开源 → 无所谓。这是**第一个要做的决策** |
| R2 | **libmpv 本体的 GPL 边界** | 商业闭源 App 用 libmpv 的法律风险 | 法务确认"动态链接是否构成衍生作品"；或考虑商业授权/替代方案 |
| R3 | **AVS3 只能软解且只有 baseline** | 4K 不可行、非 baseline 流不可解 | 定位为"可选能力"；先在目标盒子实测 1080p 单路/双路 CPU 占用再定承诺 |
| R4 | **EPG 第三方源无 SLA** | 节目单随时空 | 自建聚合 + 缓存 + 多源降级；产品文案不承诺"完整节目单" |
| R5 | **iOS 分发（免费证书 7 天）** | 用户体验极差，无法规模化 | iOS 定位为 PoC/极客版；或评估上架合规性 |
| R6 | **xtool 三个未证实项** | PoC 可能直接失败 | PoC 验收：① SwiftUI 工程能跑 ② 能链一个 xcframework ③ 真机装+跑 24h 不崩 |
| R7 | **N100 并发能力未实测** | 容量规划全靠猜 | 先做压测：copy 模式能扛 N 路、转码模式能扛 M 路，得出"每路成本"再定产品档位 |
| R8 | **HLS 鉴权只签 m3u8 漏签 ts** | 分片泄露 = 无限盗用 | 鉴权中间件覆盖所有媒体路径（含分片），写进代码 review checklist |

### 7.2 未解问题（需要补调研或实测）

1. **`media3-exoplayer-ts` 对裸 MPEG-TS 直播的实际表现**（起播时间、抗丢包、能否 seek）—— 需实测。
2. **Compose for TV 在低端盒子（2GB RAM / 老 SoC）的首帧与滚动表现** —— 需实测，决定是否退回 Leanback。
3. **QSV 在 N100 上同时"硬解 + 硬编"的真实吞吐**（几路 1080p30）—— 需实测；本文参数仅为起点。
4. **libmpv 自编的 APK 体积与 ABI 拆分策略** —— 需实测（全量 ffmpeg 可能 >40MB/ABI）。
5. **AVPlayer 对"非标准 HLS"（自定义 tag、超短分片、无 EXT-X-DISCONTINUITY 的码率切换）的容忍度** —— 需用真实源实测。
6. **xtool 是否支持 SwiftUI 与 binaryTarget** —— 文档站为 JS 渲染，未取到正文；需实测。
7. **epg.pw 的真实接口与可用性** —— 本次未证实，不要写进默认配置。
8. **`davs3` 项目是否存在** —— 未证实（`xatabhk/davs3` 为 404）。
9. **xiaoya 生态与 Alist 的确切关系** —— 本文为"判断"非"验证"，如要写进文档需另找官方说明。
10. **TVBox 系各分支（takagen99/Box、q215613905/TVBoxOS、FongMi/TV）的 License 差异** —— 仅核了 FongMi（GPL-3.0），其余需逐个确认。

### 7.3 一句话结论

> **服务端**：Go 自研主干 + MIT 的 MediaMTX 做转发面 + 读 Guovin 的思路（不碰它的代码）+ 兼容 TVBox/XMLTV 格式。
> **Android**：Media3 打底，libmpv 兜底 —— 但**先解决 GPL 决策**，否则后面全是返工。
> **TV UI**：Compose for TV 自研交互，别指望开源库（生态几乎全 GPL）。
> **iOS**：服务端把流洗成标准 HLS（H.264/HEVC + AAC）是前提；xtool 先做 PoC，分发问题比构建问题更难。
