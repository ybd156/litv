# litv-edge

LITV2 的边缘降级部署：NAS 挂了之后，用 Cloudflare 免费额度顶住。

> **代码开源，实例私有。**
> 这个目录只放代码和文档，不含任何源地址——它们全在本仓库的 Actions Secrets 里。
> 部署工作流在仓库根的 `.github/workflows/edge-deploy.yml`，只改 `edge/` 才会触发。
>
> **安全边界**：源地址、Cloudflare token 都在 Secrets 里，不进代码、不进日志、
> 不进产物。生成的 `playlist.m3u` 被 `.gitignore` 挡着，不会入库。
> 但请注意：本仓库是 public，能拿到 Secrets 的只有仓库所有者——
> 如果以后加了有写权限的协作者，他们就能读走你的源地址和 Cloudflare 凭据。

做法不是"再造一个服务端"，而是**在边缘实现最小的一层 LITV 协议**，
让现有的 Android TV / 手机 / iOS 客户端以为自己在连 NAS——**客户端零改动**。

## 为什么能不改 app

客户端本来就留了两条路，正好够用：

| 机制 | 代码位置 |
|---|---|
| `/api/v1/tv/play/{id}` 返回 `mode="direct"` + 上游绝对地址 + `headers` 时，客户端自己直连源站 | `android/.../data/Api.kt` 的 `absolute()`、`Models.kt` 的 `PlayInfo.playHeaders()` |
| 绑定页本来就是「服务器地址 + 6 位数字绑定码」两个输入框 | `android/.../ui/BindActivity.kt`、`ios/.../BindView.swift` |

所以这里**只实现协议，不碰任何媒体字节**：没有中转、没有 ffmpeg、没有磁盘。
播放时服务端把上游地址原样给客户端，流量完全不经过 Cloudflare。

```
客户端 ──HTTP──> Workers（频道表 / 线路表 / 设备 token / 管理台 API）
   └──────────── 直连 ────────────> 上游源站
```

## 组成

| 部分 | 位置 | 说明 |
|---|---|---|
| 抓取与生成 | `scripts/build.mjs` | 拉订阅 m3u → 去重合并 → 产出 `playlist.m3u` 和 `sql/seed.sql` |
| 边缘 API | `src/index.ts` | 最小 litvd 协议 + 管理台后端 |
| bootstrap 响应体 | `src/bootstrap.sql.js` | 在 SQLite 里直接拼 JSON，Worker 只吐字节（见「频道数」一节） |
| 管理台 | `public/admin.html` | 单文件、无构建、无外部依赖 |
| 存储 | D1 | 频道 / 线路 / 设备 / 人工覆盖 |
| 自测 | `scripts/selftest.mjs` | m3u 链路 + 内存 SQLite 端到端，共 30 条断言 |

## 支持情况

| 功能 | 边缘模式 |
|---|---|
| 绑定 / 设备 token | ✅ 绑定码即口令 |
| 频道列表、分组、搜索 | ✅ |
| 多线路、手动切线路 | ✅ |
| 直连播放（含 UA / Referer） | ✅ |
| 管理台（改频道、加删线路、探活、吊销设备） | ✅ |
| 收藏、续播上报 | ⚠️ 收下但不下发（没有存储） |
| EPG 节目单 | ❌ 返回空 |
| 时移 / 录制 / DLNA 投屏 / 遥控中转 | ❌ |

## 源格式：填什么它认什么

`SOURCE_URLS` 里填的地址，脚本会**自己嗅探格式**，不用你指定：

| 拿到的东西 | 处理 |
|---|---|
| `#EXTM3U` / `#EXTINF` 开头 | 按 M3U 解析（支持 `tvg-id`、`group-title`、`#EXTVLCOPT` 的 UA/Referer） |
| `分组,#genre#` + `频道名,地址` | 按 TXT 解析。**自动识别 GBK**，国内这类源大多不是 UTF-8 |
| 网页（HTML） | 跳过并提示：你填的是面板首页，需要"订阅/播放列表"地址 |
| JSON | 跳过并提示：这是面板 API，不是播放列表 |
| 认不出来 | 跳过，并打印开头一小段（地址已脱敏）帮你定位 |

判据和 [server/internal/parse](../server/internal/parse/parse.go) 里的 `Detect()` 保持一致，
不另立一套规则。名字里带逗号的情况按"从右往左找第一个紧跟协议头的逗号"拆，
`rtp://`、`udp://` 这些内网协议也认。

**单个源失败不会拖垮整体**：其它源解析成功就照常生成；只有**全部**源都没解析出条目时
才 exit 1（宁可让部署失败，也不拿空列表覆盖线上的好数据）。

暂不支持 TVBox 的 `{"lives":[...]}` JSON 格式。

## 部署

和 LITV2 同一个仓库，工作流已经在 `.github/workflows/edge-deploy.yml`，
不用另建仓库、不用改路径。

### 1. D1 数据库

本仓库已经建好并写进 `wrangler.toml`（`database_id` 是标识不是凭据，
提交进仓库没有风险）。要换成你自己的库：

控制台 → Workers & Pages → **D1** → Create database，名字 `litv-edge`，
把返回的 UUID 填进 `wrangler.toml` 的 `database_id`。

（也可以用 `npx wrangler d1 create litv-edge`，但没必要为这一步在本机装环境。）

### 2. 配 Secrets

本仓库 Settings → Secrets and variables → Actions：

| Secret | 必需 | 说明 |
|---|---|---|
| `BIND_CODE` | ✅ | **必须是 6 位数字**（客户端输入框限死了）。设备绑定码 + 管理台登录口令 |
| `CLOUDFLARE_API_TOKEN` | ✅ | Cloudflare → API Tokens。模板用 "Edit Cloudflare Workers"，**再手动加 Account → D1 → Edit** |
| `CLOUDFLARE_ACCOUNT_ID` | ✅ | 控制台右侧栏的 Account ID |
| `GITHUB_DISPATCH_TOKEN` | 可选 | 配了管理台才有「立即重建」按钮。fine-grained PAT，只给本仓库的 **Actions: write** |
| `SOURCE_URLS` | 回退 | 一行一个地址。**推荐在管理台的「源」页里配**，这里只是没有 UI 时的兜底 |
| `SOURCE_UA` | 回退 | 全局默认拉取 UA。每个源自己的 UA 在「源」页里配 |

> **源不放这里。** 订阅源是日常要改的东西，放在管理台的「源」页（存在 D1），
> 每个源可以带自己的 UA / Referer。`SOURCE_URLS` 只在 D1 里一个源都没有时才用。

### 3. 跑一次

Actions → `Edge Deploy` → Run workflow。它会先跑 `check`（类型检查 + 自测 + 打包检查），
全绿才部署。

只改 `edge/` 或 `admin/` 的提交会自动触发。

## 客户端怎么连

| 字段 | 填什么 |
|---|---|
| 服务器地址 | 切到"用域名访问"，填 `https://litv-edge.<你的子域>.workers.dev` |
| 绑定码 | 那 6 位数字 |

绑完直接进频道列表。**iOS 同样流程**，协议完全一致。

第三方播放器（可选）：

```
https://litv-edge.<你的子域>.workers.dev/playlist.m3u?t=<BIND_CODE>
```

## 管理台

**用的是 LITV2 自带的管理台**（`admin/` 的 Vue 应用），不是另写一套。
CI 会把它构建出来（`--base=/`）塞进静态资源，访问根路径就是：

```
https://litv-edge.<你的子域>.workers.dev/
```

登录页和 NAS 上那个一模一样：品牌方块 + 主机名 + 口令。口令就是 `BIND_CODE`。

**能用的**：源（增删改、每源自己的 UA/Referer、测试连通）、频道（搜索/分组/改名/
启用停用/批量/拖拽排序/线路增删）、设备（查看、吊销）、概览、立即重建。

**明确不支持的**（点了会提示"边缘模式不支持"，不会假装成功）：
EPG 导入与映射、台标抓取/上传、设备审批与下发配置、系统配置、系统日志。
这些在边缘架构里没有对应物——不是没做，是本来就不存在。

### 源和「立即重建」

- 源存在 D1 里，在「源」页改完就保存
- 抓取 + 解析要跑 CI（免费版 Worker 单次只有 10ms CPU，解析不了播放列表）
- 配了 `GITHUB_DISPATCH_TOKEN` 就能点「立即重建」，否则等每天 05:17

### 简易版管理台（备用）

`/admin.html` 还留着一个单文件版本，功能更少但没有构建依赖。
自带管理台出问题时可以用它兜底。

### 手工改动不会被每日重建冲掉

这是整套东西里最容易踩的坑。数据分成两类：

| 列 | 含义 |
|---|---|
| `channels.stable_key` | 跨重建稳定的标识（`id:<tvg-id>` 或 `name:<归一化频道名>`） |
| `channels.origin` | `m3u` = 订阅生成；`manual` = 管理台手工加 |
| `streams.origin` | 同上 |
| `streams.channel_key` | 让手工线路在重建后还能挂回正确频道 |
| `channel_overrides` | 对订阅频道的改名/改分组/隐藏，按 `stable_key` 存 |

重建顺序：删 `m3u` 线路 → 删 `m3u` 频道 → 插新频道 → **手工线路按 `stable_key`
重新挂 id** → 插新线路。所以：

- 订阅频道的改动存进 `channel_overrides`，重建后照样生效
- 手工添加的频道和线路不受重建影响
- 订阅频道删不掉（下次还会回来），管理台的"删除"对它是**隐藏**
- 手工线路如果挂的频道从订阅里消失了，会被跳过而不是挂成空频道

## 硬限制（先看清楚再决定）

**1. 上游必须公网可达。** 内网源、UDP 组播源、绑运营商线路 IP 的源，边缘模式下
一律拉不到——没有中转就没有任何补救手段。这是这个方案最大的天花板。

**2. 频道数上限不在 Worker，在客户端。** 免费版每次请求只有 10ms CPU，
所以 `bootstrap` 的 JSON **不是在 JS 里拼的**——整个响应体由
`src/bootstrap.sql.js` 在 SQLite 里拼成字符串，Worker 只做"取一行、吐字节"，
CPU 开销与频道数无关。D1 的查询耗时算 wall time，不占那 10ms。

实测（本地 SQLite，仅代表规模量级）：

| 频道数 | 响应体 | SQL 耗时 |
|---|---|---|
| 500 | 0.10 MB | 1.6 ms |
| 2000 | 0.39 MB | 5.6 ms |
| 5000 | 0.99 MB | 12.4 ms |
| 10000 | 1.99 MB | 22.7 ms |

真正的天花板是**客户端**：几 MB 的 JSON 丢给电视盒子解析会明显拖慢启动，
D1 单行/响应体也有大小限制。**几百到两千个频道是舒适区**，
超过五千要开始考虑裁掉隐藏频道或按分组拆。

注意：`ORDER BY` 在聚合函数里的顺序依赖 SQLite 的实际行为（`selftest` 有断言），
不是文档承诺——万一哪天顺序乱了，只是频道排序不对，不会坏。

**3. 探活结果是边缘视角。** 管理台"测试"按钮是从 Cloudflare 边缘发的请求，
通了不代表你家能播。只能当参考。

**4. 没有扇出。** 每台设备各自连上游。上游限制并发时会互踢，这个无解。

**5. 绑定码是 6 位数字，空间只有 100 万。** 设备注册和管理台登录都按 IP 做了
失败限速（10 次 / 10 分钟），但仍然不要把 workers.dev 地址到处发。

**6. `*.workers.dev` 在国内不稳定。** 建议绑自己的域名，并在目标网络实测。

**7. 收藏 / 续播 / 播放统计的写入被丢弃。** 界面上不会有历史记录。

## 安全说明

- 设备 token 服务端只存 SHA-256 哈希，不存明文
- 管理台会话也是哈希存储，带 7 天过期
- 同一个 `device_key` 换平台注册会被拒绝（对齐真服务端的 `ErrDeviceKeyConflict`）
- 绑定码错误按 IP 限速，防暴力枚举
- `playlist.m3u` 里有上游地址，和接口同等级保护（`?t=` 校验），错了一律 404
- 管理台把频道名当不可信输入转义后再渲染（名字来自上游 m3u）
- **没做的**：设备 token 没有有效期。换 `BIND_CODE` 只影响新注册，
  已发出的 token 仍然可用；要吊销得在管理台逐台删设备

## 开发

**不要在本机搭环境。** 按 LITV2 的 AGENTS.md §8，依赖安装、类型检查、自测、
打包检查全部在 `Edge Deploy` 的 `check` 任务里跑，本地只看 Actions 结论。

`check` 里依次执行：

```
npm install --no-audit --no-fund
npm run typecheck                      # tsc --noEmit
npm test                               # scripts/selftest.mjs
wrangler deploy --dry-run --outdir=.wrangler/dry
```
