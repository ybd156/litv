# 2026-11 安全与稳定性加固

本轮改动来自一次完整代码评审，范围是「认证 / 删除 / 网络 / 存储 / 播放 / 打包」。
下面按威胁写清楚**防住了什么、还剩什么局限**，以及**哪些验证过、哪些没验证**。

## 1. 播放地址里的设备标识（`dk`）

### 威胁

播放/分片地址长期带 `dk=<device_key>`。`device_key` 不是普通展示字段：
服务端遇到「已 bound 且平台一致」的同 key 重复注册，会**直接换发可用 token**。
所以它等价于设备凭据。地址一旦进入浏览器历史、反向代理日志或 Referer，
拿到它的人就能把这台设备顶掉。

### 改动

- 新增 `auth.Signer.DeriveDeviceKey`：`base64url(HMAC-SHA256(secret, "dk|"+device_key))` 取前 22 字符。
- 所有对外签发地址（直播、时移、投屏、分片）改用派生值；签名与校验都用派生值。
- 旧地址里的明文 `dk` 仍然能校验通过（HMAC 输入就是地址里的那个字符串），
  已发出的 12 小时地址不会因为升级立刻失效。

### 剩余局限

- 派生值仍然是**稳定的设备级标识**：同一个设备的所有签名地址共享同一个 `dk`。
  它能防「反推 device_key」，但不能防「拿到地址的人重放该地址」——重放由
  `exp` + 签名控制，和解绑/吊销无关。
- 反代/访问日志里仍会出现 `dk`（派生值）。需要更强隔离时应关闭访问日志中的查询串。

## 2. 非管理员读取敏感管理接口

### 威胁

原 `requireAdmin` 的规则是「非管理员允许 GET 一切」。同一台 NAS 上的普通 fnOS 用户
可以直接读到：源地址（通常带账号密码）、系统日志与配置、设备列表、录制文件，
以及 `/api/v1/web/play/{id}` 签发的播放地址。

### 改动

改为显式白名单：非管理员只允许 `GET /api/v1/system/me`，其余一律 403。
前端据此提示「需要管理员」。

### 剩余局限

- 网关信任模型未变：`X-Trim-*` 只在 `viaGateway=true` 时可信。
- `auth.devmode` 仍然会把所有请求当成管理员。它只应在本地开发时开启。

## 3. 管理台注销与应急会话

### 威胁

`/api/v1/auth/logout` 以前没有挂到路由上，前端点退出只清本地 token，
服务端会话还能用满整个 TTL（默认 7 天）。旧 token 泄露等于长期有效。

### 改动

- 注册 `POST /api/v1/auth/logout`：只吊销当前 bearer 对应的 `admin_sessions` 行。
- 注册 `POST /api/v1/auth/logout-all`（管理员组内）：吊销全部应急会话。
- 两者都只影响 `admin_sessions`，不会误删设备/网关身份。
- 管理台顶栏新增「退出登录」：先让服务端吊销会话，再清本地 token 并跳回
  应急登录页。只有存在 `litv_admin_token` 时才显示——从飞牛桌面进来的身份
  由网关注入，本应用既拿不到也注销不了它。
- 服务端不可达时仍然退出本地，但如实提示「本地已退出；服务端会话可能仍然有效」，
  不会假装已经撤销。

### 剩余局限

- `logout-all` 目前只有 API，没有管理台入口；需要时用 curl 带管理员 bearer 调用。
- 已签发但未使用过的旧 token 在 `logout-all` 后立即失效；设备 token 不受影响。

## 4. 出站抓取（SSRF）

### 威胁

恶意订阅源可以写一条指向 `127.0.0.1`、`169.254.169.254`（云元数据）或
`localhost` 的线路，让服务端替它访问本机管理面或读取实例凭据。

### 改动

- `fetch.ValidateURL`：只允许 http/https；拒绝 `localhost`、`*.localhost`、
  回环、未指定、组播、链路本地地址。
- `fetch.newTransport` 的 `DialContext` 按**解析结果**再校验每个 IP，
  防 DNS rebinding（域名先解析到公网、建连时换成内网）。
- 抓取客户端统一带上限与超时；分片代理使用同一个受保护 client。
- 覆盖到**所有出站路径**，不只 `fetch.Get`：
  - `stream.Hub.readOnce`（直播扇出的上游连接）先 `ValidateURL`，再用
    `fetch.NewTransport()` 建连，保留直播需要的「不设总超时」。
  - `remux.ensure` 在交给 ffmpeg 之前校验；ffmpeg 自己不会挡本机地址。
  - `record.start` 同样在拉起 ffmpeg 前校验，失败按「录制失败」记，不重试。
- 测试用 `fetch.SetAllowLoopback(true)` 放行 `httptest`；生产默认关闭。

### 有意放行的范围

- **RFC1918 私网与 ULA 默认放行**。很多用户的自建源就在 NAS 同网段，
  一刀切会让最常见的部署直接不可用。
- 若配置了 `HTTP_PROXY`/`HTTPS_PROXY`，实际建连对象是代理，
  SSRF 校验作用在代理地址上，目标地址不受 IP 级校验。需要严格隔离时不要给出站代理。

## 5. edge 口令与限速

### 威胁

- 设备绑定码只有 6 位数字（100 万种可能），原来可以无限尝试。
- `/playlist.m3u?t=` 完全没限速，猜中就能拿到全部上游地址。
- 注销接口是空壳，服务端会话不删。
- 管理台口令与 6 位绑定码共用同一个值。

### 改动

- `bumpAttempt()` 改为单条 UPSERT + `RETURNING fails`（解释器不支持时兜底 SELECT），
  并发请求不会一起读到同一个旧计数。
- 设备注册、管理台登录按 IP 限速；`/playlist.m3u` 用**独立的桶**限速，
  避免猜 playlist 把设备绑定一起锁死。playlist 的窗口单独设为 1 分钟
  （20 次 / 1 分钟，绑定码仍是 10 次 / 10 分钟）：旧客户端拿着作废的
  `BIND_CODE` 反复重试时，只影响同一出口 IP 一分钟，而不是十分钟。
- `adminLogout` 真正删除 `admin_sessions` 行；无 token 时幂等返回成功。
- 新增可选 `ADMIN_PASSWORD`：配了以后 `BIND_CODE` 只用于设备绑定。
  未配置时回落到 `BIND_CODE`（兼容老部署，但属于弱口令）。
- `platform` 口径与 Go 端对齐：trim + 小写 + 截断，只拒绝保留值 `admin`，
  不再做字符集白名单（否则 `Android TV` 在 NAS 上能注册、在边缘版被拒）。
  冲突比较忽略大小写，历史混合大小写的行会在下次注册时就地迁移。
- 新增 `edge/scripts/workertest.mjs`：直接 import Worker，D1 用 `node:sqlite`
  实现（SQL 真执行），覆盖限速阈值与窗口、成功清零、平台冲突、
  `ADMIN_PASSWORD` 优先级、注销即失效、playlist 鉴权与限速。
- 管理台（`admin/`）按部署形态区分文案：edge 上没有飞牛网关，登录页不再
  提示「回飞牛桌面」，也不显示无意义的「用户名」；错误原因一律以服务端
  `msg` 为准（`2001` 不再被套成「未认证」）。形态用编译期常量
  `import.meta.env.BASE_URL` 判断（edge 构建 `--base=/`），不额外发探针请求。

### 剩余局限

- 计数存在 D1，按 IP 计数；换 IP 可以绕过。免费额度下不引入更重的方案。
- playlist 超限期间同一出口 IP 的正确请求也会 404（最长 1 分钟）；
  这是「先占名额再校验」换来的防并发绕过，属于有意的取舍。
- 设备 token 仍然没有有效期，换 `BIND_CODE` 只影响新注册。

## 6. Android 签名密钥

### 威胁

仓库内的 `android/keystore/litv-release.jks` 和口令是公开的。
它能保证「覆盖升级」，但**不能证明包是谁发的**。

### 改动

- 保留 legacy keystore（删掉会让老用户无法覆盖安装）。
- `build.gradle.kts` 的签名配置优先级改为：
  环境变量 → `local.properties` → legacy keystore。
  文件路径和口令**同时兼容旧键** `LITV_STORE_FILE` / `LITV_STORE_PASSWORD`：
  新名字优先，旧名字仍然认，按旧文档配过的本地发布机不会突然回落成
  legacy 密钥。
- `release.yml` 新增可选 `ANDROID_KEYSTORE_BASE64`：解码到 `$RUNNER_TEMP`，
  并把路径写进 `LITV_KEYSTORE_FILE`；同时从 secrets 注入口令/别名。
  没配 secret 时回落到 legacy 并打印告警。
- `.gitignore` 为 legacy keystore 加显式例外，避免「注释说保留、规则却忽略」的矛盾。

### 剩余局限

- 使用 legacy 密钥签出的包，任何拿到该公开密钥的人都能伪造出「可覆盖安装」的同名包。
  正式分发必须配置自己的密钥。
- 只配 `ANDROID_KEYSTORE_BASE64` 而不配口令/别名时，构建会因空口令失败；
  四个值要一起配。

## 7. 播放与存储的稳定性修复

### 清单缓存（`api/manifest_cache.go`）

- 只缓存**上游原文**（`body` + 最终 URL + 选中线路），TTL 2 秒；
  改写按请求者 `dk` 进行。缓存改写结果会把 A 的签名地址发给 B。
- 同一频道用 slot 的互斥锁做 single-flight，多台设备同时看只抓一次上游。
- 分片请求用「最近一次成功清单选中的线路」取请求头，避免清单能取、分片 403。

### 流扇出（`stream/ring.go`）

- 从「单块 `[]byte` + 反复 append/整块拷贝」改为分块环形缓冲，只做尾插头删。
- 快照优先从 PAT（PID 0 且 payload unit start）开始，退而求其次对齐到 TS 188 包边界。
- 注意：**没有实现 H.264 IDR 解析**，只按 TS 包/PAT 对齐，跨 GOP 起播仍可能黑一小段。

### 转封装（`remux`）

- `ensure` 全程持锁，「检查 → 建目录 → 清残留 → 启动 → 登记」原子化，
  消除并发双 ffmpeg 写同一目录。
- `PlaylistContext` 支持客户端断开后立刻放弃等待（最多 10 秒）。
- 切片改用 `http.ServeContent`，支持 Range/Last-Modified，不再每次 seek 重传整片。

### 时移（`timeshift`）

- 空闲 30 分钟回收 Ring 并删除文件，避免几百个频道各占一个打开的文件句柄。
- 启动时清理上次运行遗留的 `chN/chN-<nano>.ts`，只删匹配命名的文件。

### 数据库写入

- `GetDeviceByToken` 只在剩余有效期不足 TTL 一半时续期。
- `TouchDevice` / `TouchDeviceWithInfo` 60 秒节流；注册补写 IP/版本不节流。
- 新增 30 天 `play_events`、90 天 `audit_logs` 清理。

### 电视端首页（`tvBootstrap`）

- 频道列表改用 `ListAllEnabledChannels`，不再复用管理台分页接口的 `Limit=500`——
  频道超过 500 的部署以前在电视端只能看到前 500 个台。
- 当前节目用一次 `ProgramsForEpgIDs` 批量查询；内部按 500 个 id 分批，
  避开 SQLite 的 999 变量上限（否则会 `too many SQL variables`，
  而这里又忽略了错误，表现就是首页「当前节目」整片空白）。

### 录制与 ffmpeg 请求头

- 录制与转封装共用 `internal/ffmpeg` 的参数拼接：`-user_agent`（线路优先）
  + `-headers`（`-i` 之前）。
- 含 CRLF 或空值的头直接丢弃，防止往 ffmpeg 请求里注入额外头。

## 8. 验证状态

### 已在本机验证（go1.23.12 / Windows，临时工具链，不作为标准路径）

- `python tools/checks/check-versions.py`：五处版本号一致（0.1.13）。
- `edge/`：`npm ci`、`npm run typecheck`、`npm test`（selftest 57 项 + 新增
  workertest 35 项，全部通过）。workertest 直接执行 Worker 本体，D1 由
  `node:sqlite` 承担，因此限速用的 UPSERT/RETURNING 也是在真 SQLite 上跑过的。
- Go：`gofmt -l .` 无输出、`go build ./...`、`go vet ./...` 均通过。
- Go：`go test ./...`（**没跑 `-race`**，Windows 无 cgo）除两条基线用例外的包全部通过。
  失败的两条是 `TestConfigSaveIsAtomicAndPrivate`、`TestSaveKeepsSecretPermissionTight`，
  它们断言配置文件权限为 0600，而 Windows 上 `os.Stat` 一律返回 0666，属平台差异，
  Linux CI 上通过。
- edge：另外用 `wrangler dev --local` + 本地 D1 手工打了一遍接口（绑定限速、
  平台冲突、保留值、口令优先级、注销、playlist 正误各路径），结果与 workertest 一致。
- edge：`npx wrangler deploy --dry-run` 通过，产物 52.67 KiB / gzip 12.25 KiB，
  `DB` / `ASSETS` / `SERVER_NAME` 三个绑定都能解析（**只打包，没部署**）。

### 已在 CI 验证（分支最新提交）

`Build Check`（`https://github.com/ybd156/litv/actions/runs/42717227761`）全部通过：

- 一致性检查（五处版本号）
- Go 服务端：`gofmt` / `go build` / `go vet` / `go test -race`
- 管理台：`npm ci` / `typecheck` / `build`
- Android：`assembleTvDebug` + `assemblePhoneDebug` / `testTvDebugUnitTest`

`Edge Deploy`（`https://github.com/ybd156/litv/actions/runs/42717227431`）：
`check` 通过，里面跑的是 `npm run typecheck` + `npm test`，而 `npm test`
现在同时执行 `selftest.mjs`（57 项，抓取/生成）和 `workertest.mjs`
（35 项，直接执行 Worker 本体）。这说明 `node:sqlite` 顶替 D1 的方案在
干净环境（`npm ci` 之后）同样成立，不是只在本机能跑。

`deploy` 在这轮 PR 上是 skipped，原因是工作流里
`if: github.event_name != 'pull_request'`——**PR 绝不部署**，不是缺 Secrets。
合并到 `main` 后（`f773b6a`）的 `Edge Deploy #42718434315` 里 `deploy` 真的跑了
并 success，说明 `BIND_CODE` / `CLOUDFLARE_API_TOKEN` / `CLOUDFLARE_ACCOUNT_ID`
三个必需 Secrets 都在。

但 `ADMIN_PASSWORD` 这一条值得单独记，因为它演示了一个很容易踩的坑：
这个 Secret 是在**那次运行创建之后 23 秒**才加上的（`updated_at 23:12:05Z`，
而运行创建于 `23:11:42Z`）。GitHub 在 job 启动时就把
`secrets.ADMIN_PASSWORD` 求值成空串了，所以「写入管理台口令（可选）」
那一步仍然是 `skipped`——**配置 Secret 不会追溯修改已经跑完的部署**。
此时线上 Worker 没有 `ADMIN_PASSWORD`，`adminPasscode()` 回落到
`BIND_CODE`，管理台口令仍然等于那个 6 位绑定码。

手动跑一次 `Edge Deploy`（run `42720792394`）后，同一步骤变成 `success`，
`ADMIN_PASSWORD` 才真正写进 Worker，口令与绑定码从这时起分开。
仓库现有 4 个 Secret：`ADMIN_PASSWORD` / `BIND_CODE` /
`CLOUDFLARE_ACCOUNT_ID` / `CLOUDFLARE_API_TOKEN`，没有 Variables。

iOS 单独手动触发（`workflow_dispatch`，分支 `hardening-2026-11`）：
`https://github.com/ybd156/litv/actions/runs/42591460058` 的 `build-ios` 通过
（xcodegen + `pod install` + `xcodebuild`，不签名）。本轮没有改 `ios/**`，
这次只是把「这个提交的 iOS 也能编」补成实测结论。

### 未验证

- Android `assembleRelease` / R8：`build-check.yml` 只编 debug，Release 走 `release.yml`。
- edge 本分支代码的真实部署：PR 阶段只做打包检查，合并到 `main` 后才会由
  push（`paths: edge/**`）或下一个整点定时任务发出去。届时需要线上确认
  新加的管理台口令与限速逻辑（`ADMIN_PASSWORD` 没配时仍退回 `BIND_CODE`）。



