# LITV2 项目检查报告：给维护 AI 的执行说明

> 审查日期：2026-11-02，Asia/Hong_Kong。基线提交：`c3b9162b68ef0c3cf17bff3e43d8e3c0dcdcebe7`。
> 审查对象是**当前工作区**，不是仅该提交。审查期间 Android/iOS 文件继续发生外部修改；实施前必须重新读源码和 Git diff，不能盲信旧行号。
> 本轮只检查并新增审查材料，没有修改业务源码。下列任务均**尚未修复**，不是“已经验证完成”的功能清单。

## 一、结论

项目已具备完整的多端原型，Go 包边界、统一 API 外壳、网关/TCP 身份分离、设备 token 哈希存储和流签名的方向值得保留。**目前最需要的不是继续堆功能，而是补齐权限边界、跨端契约、并发生命周期和发布验证。**

以下整理了 **18 个主任务：12 个 P1、6 个 P2**，另列 7 个后续建议。未发现可以不带条件地定为 P0 的问题；没有把“需要先审批的提权”写成匿名直接管理员，也没有把缺少真机验证写成已实测崩溃。

- `P1`：安全边界、主链路或发布正确性问题，建议修复或明确限制后再发稳定版。
- `P2`：特定模式下失效、配置/会话未闭环、工程可靠性问题。
- “静态确认”表示源码证据充分，**不等于本次跑过 Go 服务、APK、iOS 真机或 NAS**。

机器可读任务与依赖见 [任务 JSON](<docs/review/AI_REVIEW_TASKS_2026-11-02.json>)。其中 `path` 与 `write_scopes` 均相对于仓库根目录。

## 二、本次实际验证与边界

| 检查 | 实际结果 | 不能据此声称什么 |
|---|---|---|
| 当前管理台 `vue-tsc --noEmit -p tsconfig.json`，通过直接 Node 入口执行 | 退出码 0 | 不能证明 API 的 JSON 契约一致 |
| 现有 [路由检查器](<tools/check-api-contract.py>)，显式 UTF-8 输出 | 64 条服务端路由、35 个被正则捕获的管理台调用，路径全部匹配 | 不检查请求体、响应体、页面中的直接请求和原生客户端；不是全量契约测试 |
| 设置请求字段对账 | 确认多发 `allow_lan`、`epg_refresh_hour`、`log_level`、`port` 四个字段 | 没有实际向运行中的 Go 服务发 PUT |
| 绑定流程的 SQLite 约束复现 | 用源码中的 schema/SQL 在内存库复现重复 key、新 id 与码所属 id 不匹配、通用码 id=0 三种问题 | 不是 Go HTTP 端到端测试 |
| Android XML | 最近一次复核 24 个资源 XML + manifest，共 25 个文件解析成功 | 不等于 Android 编译或布局真机正确 |
| fnOS 生命周期、打包、现有测试脚本、Android 启动 shim | `bash -n` 语法检查通过 | 没有执行安装、卸载、录制、DLNA 或媒体冒烟 |
| 管理台生产构建 | 原依赖有 Windows shim/原生 Rollup 包缺失；隔离复制后 `npm ci --ignore-scripts` 成功，但 Vite 启动 esbuild 时被沙箱 `spawn EPERM` 阻止 | **没有得到通过的生产构建**；这次失败是环境限制，不是已证实的 TS 编译错误 |
| Go/Android/iOS/fnOS 的运行验证 | 原生 Go/JDK 未出现在可用命令中，WSL 返回“拒绝访问”；未执行真机或 NAS 测试 | 不采用 [README](<README.md#L24-L39>) 中“全部通过”作为本轮测试证据 |
| 仓库原生单元测试文件 | Git 跟踪文件中未找到 `*_test.go`、`.test.ts`、`.spec.ts`、`*Test.kt` | 仓库不是完全没有测试；现有测试主要是 shell 冒烟 |

验证辅助脚本是 [review_checks.py](<.review-artifacts/review_checks.py>)，只做契约、SQL、XML和报告一致性检查，不启动播放器或服务。它用于复现**审查快照中的缺陷**，不是修复后的验收套件；实施 AI 应新增真正的回归测试。构建隔离目录、依赖缓存均在 `.review-artifacts` 下，没有删除或更新原项目的锁文件和依赖。

## 三、主任务索引

| ID | 优先级 | 问题 | 主要范围 |
|---|---|---|---|
| R01 | P1 | 客户端可控 `platform` 被当作管理员授权依据 | 认证、注册、存储 |
| R02 | P1 | 缺失配置绕过校验；默认/后备签名密钥不安全 | 配置、启动、签名 |
| R03 | P1 | 流扇出向已关闭 channel 发送的竞态 | stream Hub |
| R04 | P1 | HLS 分片目标 `u` 未绑定签名，扩大成任意 HTTP 代理 | HLS 代理、签名 |
| R05 | P1 | 设置页提交契约错误，且保存不持久化 | 管理台、系统配置 |
| R06 | P1 | 绑定码、重复注册和本地解绑重绑走不通 | 全端绑定 |
| R07 | P1 | Swift 默认属性不等于缺省 JSON 解码，iOS DTO 不兼容 | iOS 模型、API |
| R08 | P1 | iOS 选 VLC 后仍提前拒绝裸 TS | iOS 播放能力选择 |
| R09 | P1 | 源请求头在 direct/relay/remux 中未完整传递 | 所有取流路径 |
| R10 | P1 | release 未保留真正的 JNI/Gson 入口 | Android R8 |
| R11 | P1 | 刚构建的前端不进入 Go embed，fpk 可能带旧管理台 | 打包、静态资源 |
| R12 | P1 | tag/输入版本未成为三端统一版本，还会删除错误 Release/tag | 发布 CI |
| R13 | P2 | relay 模式的手动线路选择只改变响应，实际拉流又选 BestStream | 播放地址、Hub |
| R14 | P2 | HLS 子清单未经递归改写，相对分片失效 | HLS 代理 |
| R15 | P2 | `token_hours` 没有设备会话到期实现 | 会话、迁移、客户端 |
| R16 | P2 | 遥控 play 指令不能给已打开的 TV 播放页换台 | Android Intent |
| R17 | P2 | 设备配置版本、周期心跳和应用配置没有闭环 | 设备配置、在线状态 |
| R18 | P2 | 向导密码直接插入 YAML，特殊字符可改值或破坏配置 | fnOS 安装/配置 |

## 四、逐项证据与修复要求

### R01｜P1｜把客户端声明的平台与安全角色分开

- **证据**：[注册入口](<server/internal/api/handlers_client.go#L27-L52>) 接收并存储任意 `platform`；[设备鉴权](<server/internal/store/repo.go#L525-L537>) 只返回 bound 设备；[身份中间件](<server/internal/api/api.go#L294-L303>) 把 `Platform == "admin"` 自动转换为管理员身份。
- **触发**：客户端注册时声明 `platform=admin`，随后管理员将这台设备作为普通终端批准。**未审批时不能直接提权。**
- **影响**：本来只获得播放权限的设备获得管理接口写权限。
- **最小改法**：注册平台设枚举白名单；管理员授权改为服务端控制的角色/会话类型，不能由设备展示字段派生。密码登录创建专用管理会话。迁移时区分真实管理会话与来源不明的历史 admin 设备，必要时重新认证。
- **验收**：恶意平台值被拒绝或只能得到普通设备角色；即使普通终端获批，也不能 PUT 系统配置；网关管理员、密码登录管理员仍工作。

### R02｜P1｜所有配置加载路径都要安全校验

- **证据**：[默认配置与 Load](<server/internal/config/config.go#L172-L260>) 在找不到配置时提前返回默认值；默认 Secret 为空。[Validate/后备密钥](<server/internal/config/config.go#L303-L330>) 的后备生成依赖进程 PID，不是密码学随机；[启动](<server/cmd/litvd/main.go#L57-L65>) 信任 Load 返回结果；[Signer](<server/internal/auth/auth.go#L83-L108>) 直接使用给定密钥。
- **触发**：配置路径错了/缺失，或文件存在但没有配置 secret。正常 fnOS 安装生成随机 secret 的路径不受此结论直接指控。
- **影响**：缺失配置可能被 `-check` 宣称通过；使用空或可预测密钥的部署中，播放签名的安全性不成立。实际取流还需要有频道数据和网络访问条件。
- **最小改法**：所有返回路径先 normalize/Validate；明确指定而不存在的配置应报错。安全初始化使用 `crypto/rand`，将密钥安全持久化以免每次启动变更；拒绝空密钥及已知占位值，不回显密钥。
- **验收**：缺失配置/占位密钥有明确结果；合法初始化的密钥跨重启稳定且不可由 PID 推导；空密钥签名被拒绝；`-check` 不再假通过。

### R03｜P1｜流扇出 channel 的发送与关闭必须同步

- **证据**：[broadcast](<server/internal/stream/hub.go#L233-L248>) 拷贝订阅者后释放读锁，再发送；[unsubscribe/close](<server/internal/stream/hub.go#L190-L229>) 可以在这段间隙关闭同一个 `s.ch`。
- **触发**：上游正在推块时客户端断开、切台，或同频道上游被替换。
- **影响**：可能发生 `send on closed channel`。发送在上游后台 goroutine 中，HTTP Recoverer 不能保护该 goroutine；可能结束整个服务。本次没有执行 Go 竞态测试。
- **最小改法**：明确单一 close owner，让发送与关闭受同一同步机制约束；可选择持锁非阻塞广播或独立订阅者状态机，不能仅加 `recover` 掩盖。顺便修正未加对应锁的 `up.closed` 读取。
- **验收**：并发推流、取消、替换和 Hub.Close 压测，`go test -race -count=50` 无竞态/恐慌；慢客户端仍不能阻塞其他客户端；关闭后 goroutine 能退出。

### R04｜P1｜分片签名必须绑定实际目标和用途

- **证据**：[liveSegment](<server/internal/api/handlers_client.go#L781-L820>) 验证频道签名后直接请求查询参数 `u`，所谓 SSRF 防护仅限制 http/https；[segmentURL](<server/internal/api/handlers_client.go#L824-L832>) 的签名没有包含 `u`；[签名内容](<server/internal/auth/auth.go#L92-L108>) 只有频道、到期时间和设备 key。
- **触发**：持有有效播放签名的已授权设备修改 `u`，或把其他播放路径的同域签名用于 `/seg`。目标必须能被 NAS 访问。
- **影响**：授权范围从频道内容扩大到任意 HTTP/HTTPS 地址，包括 NAS 内部服务。不是“无 token、无签名即可访问”。
- **最小改法**：专用 segment 签名绑定规范化目标、频道/线路、用途和到期时间；只为实际清单中出现的目标签发。控制重定向、请求超时和必要的资源限额。**不要一刀切禁止所有私网源**，否则会破坏合法 LAN IPTV；由管理员源策略决定可用地址范围。
- **验收**：修改 `u`、用途、频道、线路或到期时间均 403；正常分片、密钥及合法跨域清单可取；旧的频道级签名不能当成任意目标的代理权限。

### R05｜P1｜修好设置页 DTO、持久化和生效语义

- **证据**：[设置页 save](<admin/src/views/SettingsView.vue#L67-L82>) 提交 7 个字段；[putConfig](<server/internal/api/handlers_admin.go#L117-L141>) 不接受其中 4 个；[decode](<server/internal/api/api.go#L362-L374>) 启用了 `DisallowUnknownFields`。保存函数只改内存，没有保存文件或通知实际运行组件。
- **触发**：正常点击“保存设置”，包括没有改动的表单。
- **影响**：收到 400。即使减少请求字段绕过，保存也会在重启后丢失；端口/监听地址不能仅改显示值就宣称已生效。
- **最小改法**：定义唯一配置 DTO，支持的字段真正验证、持久化、应用；不支持的控件移除或禁用。监听端口/地址采用明确的重启生效流程和提示。不要通过关闭未知字段校验“修复”契约错误。
- **验收**：按真实页面 payload 保存为 200；GET 回读一致；重启后保留；运行时实际端口、日志级别与界面状态一致，或明确标为待重启。

### R06｜P1｜绑定码必须在事务中正确关联设备

- **证据**：[管理台生成通用码](<admin/src/api/modules/devices.ts#L24-L26>) 不传设备 id；[生成码接口](<server/internal/api/handlers_admin.go#L725-L735>) 默认 id=0；[注册](<server/internal/api/handlers_client.go#L47-L65>) 先 INSERT 再消费，要求码中的 id 等于新自增 id；[消费](<server/internal/store/repo.go#L574-L591>) 已在匹配前标 used；[设备表](<server/internal/store/db.go#L108-L127>) 的 device_key UNIQUE。[Android 解绑](<android/app/src/main/java/com/litv/tv/data/Prefs.kt#L101-L110>) 与 [iOS 解绑](<ios/LITVApp/Sources/Session.swift#L131-L137>) 丢掉 token 却保留 key。
- **影响**：通用码不能直接绑定；给已有设备的码在重新注册前撞 409；错设备消费了码却仍 pending；本地解绑后可能无法取回凭据。
- **最小改法**：区分通用邀请与设备专有码；验证、创建设备/关联、消费、批准和 token 轮换放在事务内，条件更新只能成功一次。重复注册要有安全的所有权证明；**不能仅凭公开 device_key 返回 bound 设备的新 token**。过期/错误码明确报错。
- **验收**：首次通用码绑定成功；专有码只绑定目标；错误匹配不消耗码；两个并发兑换只有一个成功；两端“绑定→本地解绑→重绑”都成功，旧凭据按策略失效。

### R07｜P1｜iOS JSON 解码必须真正兼容缺省字段

- **证据**：[Swift DTO](<ios/LITVApp/Sources/Models.swift#L73-L120>) 使用非可选属性默认值，但没有 `init(from:)`/`decodeIfPresent`；[bootstrap](<server/internal/api/handlers_client.go#L117-L136>) 可返回 `device.config={}`；[direct play](<server/internal/api/handlers_client.go#L289-L305>) 未返回 `expires`。此外 [iOS epg](<ios/LITVApp/Sources/Api.swift#L144-L147>) 解码数组，而 [服务端 epg](<server/internal/api/handlers_client.go#L175-L184>) 返回含 `programs` 的对象。最新工作区 [PlayerView](<ios/LITVApp/Sources/PlayerView.swift#L237-L239>) 还读取 `info.mode`，但本次读到的 PlayInfo 未声明该成员，实施前先核对并发改动并重新编译。
- **影响**：当前快照还有模型成员缺失的编译风险；Swift 合成 Decodable 对非可选字段仍要求 key，属性初始值不是缺省解码规则。新设备 bootstrap、direct 播放或 EPG 可在进入播放逻辑前失败。本次没有在 Swift 编译器中执行编译或 JSONDecoder 测试。
- **最小改法**：为可缺省字段实现明确的 `decodeIfPresent` 默认；校准 EPG 外壳；用服务端实际响应生成跨端 fixture。不要只改 UI 的错误提示。
- **验收**：空设备配置、缺省 headers/expires、direct/relay play、disabled timeshift、正常 EPG 的真实 JSON 均能解码；新 iOS 设备审批后能进入首页。

### R08｜P1｜按实际内核能力决定 TS/HLS

- **证据**：[iOS Api.play](<ios/LITVApp/Sources/Api.swift#L122-L130>) 在 VLC 可用且 direct 时不带 `fmt=hls`；[播放器](<ios/LITVApp/Sources/PlayerView.swift#L218-L246>) 在选择 VLC 前无条件拒绝 `kind=ts`。
- **触发**：VLC 可用、允许直连、裸 TS 频道，且 DTO 已能正确解码。
- **影响**：具备 VLC 解码能力仍提前报“需要 HLS”。修好 R07 后也不会自动修好这一逻辑。
- **最小改法**：只有 AVPlayer 路径需要 HLS 限制；VLC 允许裸 TS。VLC 失败后降级 AVPlayer 要重新请求 relay + HLS 地址，不能复用裸 TS URL。
- **验收**：VLC/direct/TS 能出画面；无 VLC 构建正确要求 HLS；模拟 VLC 失败时确有第二次 HLS/relay 请求并成功降级。

### R09｜P1｜把源请求头当成贯穿链路的契约

- **证据**：[direct 响应](<server/internal/api/handlers_client.go#L289-L305>) 返回 `headers`，但 [Android PlayInfo](<android/app/src/main/java/com/litv/tv/data/Models.kt#L88-L103>)、[iOS PlayInfo](<ios/LITVApp/Sources/Models.swift#L106-L120>) 不解码它；[TV 调用](<android/app/src/main/java/com/litv/tv/ui/PlayerActivity.kt#L233-L241>) 只传 UA。[HLS 代理](<server/internal/api/handlers_client.go#L740-L748>)、[分片代理](<server/internal/api/handlers_client.go#L800-L806>) 用全局 UA；[remux 启动](<server/internal/remux/remux.go#L128-L158>) 未传线路 UA/Referer。
- **触发**：上游需要特定 UA、Referer 或其他请求头；客户端与 NAS 的可达性不同。
- **影响**：direct、HLS 或 remux 出现 403；只切播放内核无法修好缺少请求头。当前所谓 auto direct 也没有完整的请求失败转 relay。
- **最小改法**：统一归一化/合并源头信息；两端 DTO 解码 headers，各内核传递支持的头；NAS 的清单/分片/ffmpeg 使用同一线路头。内核不支持必要头或 auto 直连不可达时重新请求 relay。不要把设备 Bearer 或管理身份头转发给源站。
- **验收**：只在 UA=X 且 Referer=Y 时返回流的测试源，在 TV、两个手机播放入口、iOS、NAS relay/remux 全部收到正确头；auto 回 relay 与 forced-direct 的行为可区分。

### R10｜P1｜保留 release 的 JNI 与反射入口

- **证据**：[release 配置](<android/app/build.gradle.kts#L68-L74>) 开启混淆；[规则](<android/app/proguard-rules.pro#L5-L17>) 保留了不存在的旧桥接类，却未保护真实 [MPVLib 回调](<android/app/src/main/java/is/xyz/mpv/MPVLib.kt#L77-L112>)。Gson 的遥控目标 DTO 位于 [RemoteActivity](<android/app/src/main/java/com/litv/tv/ui/RemoteActivity.kt#L150-L157>)，不受 data 包 keep 保护。既有 [R8 usage](<android/app/build/outputs/mapping/release/usage.txt#L24970-L25024>) 列出删除项。
- **影响**：native 按名回调和 Gson 反射可能被裁剪，debug 正常而 release 不正常。**既有 usage 不是本次新构建；未实测 JNI 崩溃或真机遥控。**
- **最小改法**：精确保留真实 MPVLib 类名、回调原名/签名以及必要的反射模型；去掉过时规则。不得通过全局关闭 shrink 代替明确规则。
- **验收**：新 minified release 的 DEX 保留 event/eventProperty/logMessage；ARMv7、ARM64 mpv 首播切台通过；release 遥控目标 JSON 解码出的 id/name/online 正确。

### R11｜P1｜发布时嵌入本次构建的管理台

- **证据**：[web.Handler](<server/internal/web/web.go#L16-L35>) 只用 go:embed；[构建顺序](<packaging/fnos/build.sh#L169-L221>) 编 Go 使用仓库内已有 embed，再把 admin dist 复制到包中的外部 www；Handler 不会读取这个外部目录。[CI](<.github/workflows/release.yml#L107-L128>) 虽构建了管理台，却没有明确同步到 embed 路径。
- **触发**：前端源码/依赖改了，但未手动更新仓库中已提交的 embed 产物。不能据此断言当前 head 的静态资源一定与源码不同。
- **影响**：刚修好的前端可能没有进 fpk；复制成功、打包成功也会假绿。
- **最小改法**：选定单一发布机制：先构建前端并复制到构建用 embed 路径，再编 Go；或显式支持验证过的外部静态目录。移除未使用的复制路径和手工同步依赖。
- **验收**：干净 checkout 中增加唯一前端标记，构建服务/fpk 后 HTTP 返回该标记；新前端 hash 能追溯到该构建，不需手动提交 dist。

### R12｜P1｜统一发布版本并禁止误删其他 tag

- **证据**：[workflow](<.github/workflows/release.yml#L16-L31>) 允许任意 v* tag，但各 job 从输入/DEFAULT_VERSION 取 V；[发布步骤](<.github/workflows/release.yml#L226-L256>) 删除对应 Release 和 tag。[Android](<android/app/build.gradle.kts#L44-L55>)、[iOS](<ios/project.yml#L30-L35>)、[fpk manifest](<packaging/fnos/manifest#L1-L8>) 固定版本；CI [fpk 调用](<.github/workflows/release.yml#L120-L128>) 未向支持 `--version` 的构建脚本传版本。
- **触发**：推送版本不同于默认值的 tag，或手动填写新版本。
- **影响**：包名、包内部版本与 Release 不一致；推新 tag 可能删除并重建旧默认版本的 Release/tag。
- **最小改法**：增加一次性版本解析步骤，tag 从 ref 取，手动输入校验；将统一版本/构建号/提交 SHA 传给三端；禁止 `--cleanup-tag` 删除非本次目标；发布已有版本采用明确受控的重跑策略。
- **验收**：推 v0.0.9 时三端内部版本、文件名、Release 都为 0.0.9，指向触发 SHA；已有 v0.0.3 不受影响；不合法版本在构建前失败。

### R13｜P2｜实际取流必须尊重手动线路选择

- **证据**：[tvPlay](<server/internal/api/handlers_client.go#L230-L287>) 选择 `stream_id` 后，没有把它带入 relay 播放 URL；[liveTS](<server/internal/api/handlers_client.go#L422-L450>)、[remux](<server/internal/api/handlers_client.go#L692-L705>)、[manifest](<server/internal/api/handlers_client.go#L723-L748>) 又调用 BestStream。direct 响应使用已选 URL，不能把它也算成同一失败。
- **影响**：relay 下“已切线路”可能只改变返回字段，真正请求的还是默认线路；Hub/remux 按频道复用还需考虑不同线路。
- **最小改法**：将有效线路 id 纳入签名/播放上下文，验证它属于频道；各入口使用同一选择。明确不同线路的共享策略，不能无声踢掉其他订阅者。
- **验收**：两个内容可区分的上游，指定第二线路后实际字节来自第二源；TS/HLS/remux 全覆盖；跨频道伪造线路被拒绝。

### R14｜P2｜HLS 子清单也要递归改写

- **证据**：[顶层清单](<server/internal/api/handlers_client.go#L750-L779>) 改写 URI，但 [liveSegment](<server/internal/api/handlers_client.go#L781-L820>) 对“含子清单”的响应直接 io.Copy，没有递归处理。
- **触发**：master.m3u8 指向相对 variant.m3u8，variant 又含相对 TS/密钥/初始化段。
- **影响**：第二级相对地址相对于 NAS `/seg` 解析，取不到真实分片；简单单层测试源不会发现。
- **最小改法**：统一清单代理函数，以实际最终响应 URL 为基准，辨识子清单、改写所有 URI 属性与分片并使用 R04 的目标绑定签名；控制深度和大小。
- **验收**：master→variant→相对 TS、AES key、EXT-X-MAP、本身重定向的组合全部经过正确签名的代理并能播。

### R15｜P2｜明确设备 token 与流 URL 两种到期

- **证据**：[TokenHours](<server/internal/config/config.go#L51-L58>) 存在配置和显示，但 [设备表](<server/internal/store/db.go#L108-L120>)、[GetDeviceByToken](<server/internal/store/repo.go#L525-L537>) 没有会话到期字段/检查。流签名到期在 [Signer.Verify](<server/internal/auth/auth.go#L99-L108>) 中确实实现。
- **影响**：`token_hours` 对设备凭据和密码登录创建的 admin 凭据不生效。不能把流 URL 的约 12 小时 expires 当成设备 token 的 TTL。
- **最小改法**：决定长效设备凭据与短效管理会话的策略，分开建模、迁移、续期和撤销；实现展示出来的 TTL，或明确删除误导配置。定义吊销时既有流/WS 的行为。
- **验收**：使用可控时钟测试到期；新请求拒绝旧会话，安全续期与撤销生效；已连接流/WS 的处置符合文档，合法设备不因每次打开被迫无谓重绑。

### R16｜P2｜singleTask 播放页处理新 Intent

- **证据**：[RemoteKeys](<android/app/src/main/java/com/litv/tv/ui/RemoteKeys.kt#L20-L35>) 用 startActivity 发送新频道；[manifest](<android/app/src/main/AndroidManifest.xml#L68-L74>) 声明 singleTask；[PlayerActivity](<android/app/src/main/java/com/litv/tv/ui/PlayerActivity.kt#L96-L112>) 只在 onCreate 读频道，未实现 onNewIntent。
- **影响**：首页首次遥控进入可工作，已经播放时后台 sent=true 但电视不换台。
- **最小改法**：实现 onNewIntent、setIntent、校验 allChannels 后走现有换台函数；需要“执行成功”语义时增加 command ack，不把已转发当执行成功。
- **验收**：播 A 时遥控 B/C/A 均换台，画面/OSD一致且没有重复 Activity；不存在的 id 被安全拒绝。

### R17｜P2｜配置版本与设备保活形成闭环

- **证据**：[SetDeviceConfig](<server/internal/store/repo.go#L558-L561>) 只改 config_json；[heartbeat](<server/internal/api/handlers_client.go#L188-L201>) 的 config_version 来自 bound_at，改配置不会变。客户端启动/播放后没有基于版本的完整周期刷新和应用配置链路；例如 [TV 起播](<android/app/src/main/java/com/litv/tv/ui/PlayerActivity.kt#L233-L249>) 是单次 heartbeat。
- **影响**：管理台修改设备参数不保证进入实际播放器；持续播放的设备也可能仅因没有 API 活动而显示离线。展示在线不等于 WebSocket 执行状态。
- **最小改法**：独立、单调的 config_version 在事务中更新；生命周期感知的周期 heartbeat；版本变化时拉取、验证并应用支持字段。未实现的参数不应可保存为“已应用”。
- **验收**：持续播放中改配置，一个规定周期内生效；未改配置不反复重建播放器；长播在线状态稳定，后台/退出能停止定时器。

### R18｜P2｜配置向导使用 YAML 安全序列化

- **证据**：[install_callback](<packaging/fnos/cmd/install_callback#L136-L143>) 和 [config_callback](<packaging/fnos/cmd/config_callback#L73-L89>) 把密码直接塞入双引号标量。ENVIRON 避免 awk/sed 的解释，不等于 YAML 转义正确。
- **触发**：合法密码包含双引号、反斜杠等字符。
- **影响**：密码值被转义成另一内容，或配置解析失败、重启后服务起不来。本次没有执行真实安装脚本。
- **最小改法**：用 YAML 编码器或兼容的安全标量编码，原子写入并保留严格权限；先校验新配置再重启，失败保留旧配置，不回显密码。
- **验收**：引号、反斜杠、`&`、`$`、中文组合安装/修改后解析值完全一致；错误配置不替换旧配置；配置文件权限符合敏感数据策略。

## 五、第二阶段建议：不要只修表面

1. **B01｜EPG 刷新与时区**：配置了 RefreshCron，却缺少真正调度刷新；[日边界](<server/internal/api/handlers_client.go#L167-L180>) 使用 `Truncate(24h)`，不保证 NAS/频道本地午夜。用显式时区和 `time.Date`，同时测试跨日和 DST，不用固定秒数代替日历日。
2. **B02｜全局资源预算**：[Hub.Count](<server/internal/stream/hub.go#L152-L156>) 统计 map 包含已关闭条目；MaxConcurrent 主要是配置/显示，没有在创建上游处硬性、原子限制；[时移管理器](<server/internal/timeshift/manager.go#L9-L21>) 保留所有访问过的频道。单频道有界不代表全局有界。增加活跃流/ffmpeg/录制并发上限、时移总容量和磁盘低水位策略，不能直接删除用户录制。
3. **B03｜统计和续播的覆盖声明**：[TV](<android/app/src/main/java/com/litv/tv/ui/PlayerActivity.kt#L513-L550>) 仅部分上报起播/进度；手机和 iOS 的 stats/progress/resume 没有完整接入。统一播放器事件组件，采集 start/stall/error、补齐手机/iOS续播；缺少样本不是零失败率。直播进度为 0 可以是合理语义，不应当作时间轴 bug。
4. **B04｜VLC 的显式所有权**：[VlcEngine](<android/app/src/main/java/com/litv/tv/player/VlcEngine.kt#L111-L124>) 释放 MediaPlayer 但不显式释放 LibVLC，[Manager](<android/app/src/main/java/com/litv/tv/player/PlayerManager.kt#L76-L100>) 每次创建新 engine。这是及时释放的设计风险，未实测永久泄漏/OOM。最终 release 保证所有资源释放、内部 prepare 不误释放共享上下文，并用计数/压力测试证明。
5. **B05｜异步请求失序**：[Web play](<admin/src/views/LiveView.vue#L92-L139>) 连续点 A/B，A 的慢响应可以最后覆盖 B 的播放器；[频道列表](<admin/src/views/ChannelsView.vue#L92-L112>) 同样没有请求序列保护。用 generation id/AbortController，过期响应和回调不能更新当前画面；测试人为倒序返回。
6. **B06｜测试成为发布门禁**：[路由检查器](<tools/check-api-contract.py#L20-L55>) 只覆盖一部分调用，发现 bad 也没有非零退出。补全 OpenAPI/JSON fixture 契约、权限负例、竞态、R8 release 与 fpk 内嵌资源测试，接入 PR/push CI。现有 [e2e](<build/test/e2e.sh#L13-L28>) 的全局 pkill/固定临时目录会伤及其他服务，先改为动态端口、唯一临时目录和仅清理自己创建的 PID，再自动执行。
7. **B07｜分发信任策略**：公开 Android 签名是 [CI](<.github/workflows/release.yml#L60-L66>) 明确承认的设计，不当成“意外泄露”。但正式分发不能仅靠此公钥区分官方与第三方重签包。明确 checksum/发布来源与长期签名迁移方案；不要未经计划轮换已发布签名，破坏用户升级链。

## 六、建议的执行顺序

1. **安全与稳定底座**：R01、R02、R03、R04；先补能失败的回归测试，再做最小修复。
2. **主用户链路**：R05、R06、R07、R08、R10。
3. **统一取流契约**：R13 → R09 → R14，同时落地 R04 的作用域签名。
4. **可验证发布**：R11 → R12，生产构建和包内版本/前端一致性必须成为门禁。
5. **会话和长期运维**：R15、R16、R17、R18，再处理 B01–B07。

多个任务共享认证、播放 handler、Swift 模型和打包文件，不能直接让多个 AI 同时修改同一文件。按 JSON 的依赖和 write_scopes 分工，重叠范围顺序执行；Lead 最后审阅完整 diff。

## 七、可直接复制给实施 AI 的提示词

```text
你是 LITV2 的维护 AI。目标是修复审查任务，不是增加功能或重写框架。

1. 先读 docs/review/AI_PROJECT_REVIEW_2026-11-02.md 和对应任务 JSON。
2. 执行 git status --short / git diff，保留所有已有未提交修改。
   对每个任务重新读 evidence.path，核对 source_fingerprints；源码变化时更新判断与定位。
   禁止 git reset --hard、git clean、覆盖用户改动或删除数据库/录制数据。
3. 优先 P1，并遵守 depends_on；同文件修改顺序执行，独立范围才并行。
4. 每个任务先写真实回归测试，确认旧实现失败，再做最小修复。
   建议改善协议契约，不要关闭未知字段校验、仅改 UI 文案或用 recover 掩盖竞态。
5. 测试只使用自建临时数据、动态端口和受控本地上游；不执行全局 pkill。
   私网 IPTV 是合法支持场景；权限、签名目标和来源策略才是防护边界。
6. Go 用 httptest + race；前端使用锁文件在同一 OS npm ci 后 build；
   Android 必须测试 minified release 的 JNI/Gson；iOS 用真实服务端 JSON fixture；
   fpk 验证实际提供的静态资源和三端内部版本，不只检查产物文件名。
7. 本审查没有成功执行生产 build/Go/真机/NAS。不要引用 README“已通过”替代本次运行。
   被沙箱、工具链或真机条件阻止时，记录未验证原因，不改权限绕过、不声称通过。
8. 每项交付：任务 ID、修改摘要、修改范围、回归命令/退出码、兼容性影响、剩余风险。
   只有验收满足才标完成；无法验收的任务保持待验证。
```

**完成定义**：证据所指的缺陷被最小修复；相应回归测试真正运行通过；未破坏已有工作区改动；发布版本和协议兼容性说明准确；未验证部分明确保留，不用“理论上可以”充当通过。

## 八、工作区与临时构建文件交接

审查期间外部提交把 HEAD 从上述基线推进到了 `7cdf95460a7cba7b8fb30ecc0c9fd6e3bad55f7b`。本轮为验证环境创建的 `.review-artifacts/admin-clean` 与 `.review-artifacts/npm-cache` 也在审查期间被并发提交纳入 Git；我没有执行 git add/commit，也没有擅自删除这些已跟踪文件或回滚外部提交。

这些是隔离构建副本和依赖缓存，不是业务源码。负责人确认后，建议先给 [.gitignore](<.gitignore>) 增加**仅针对这两个目录**的忽略规则，再从索引移除派生产物；保留实际 Android/iOS/Go 的改动。不建议为了清理而 reset 历史或执行全仓库 clean。

```bash
# 仅供负责人确认后执行；本次未执行。
git rm -r --cached -- .review-artifacts/admin-clean .review-artifacts/npm-cache
```

报告的证据文件指纹表示读取时的源码版本；若后续提交继续改变文件，实施 AI 必须重新阅读并重新判断，不能把审查中的行号当成自动补丁。
