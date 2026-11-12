# 02 · iOS 构建现状

> 本文件描述的是**当前工作区的真实状态**，不是目标状态。
> 所有"未验证"都是真的没验证过——本地没有 Apple SDK，iOS 只能由 CI 出结论。

## 一、工程结构

`ios/project.yml` 用 xcodegen 生成 `LITV.xcodeproj`，部署目标 **iOS 15.0**（[project.yml:24](../../ios/project.yml#L24)）。

12 个 Swift 源文件：

| 文件 | 职责 |
|---|---|
| `Session.swift` | `@MainActor` 的会话状态机：服务端地址、token、绑定态、频道数据、播放请求 |
| `Api.swift` | `ApiClient`（struct），负责信封解包与请求构造 |
| `KeychainStore.swift` | Keychain 存取 token 与 `device_key`，含旧明文迁移 |
| `Models.swift` | DTO |
| `LITVApp.swift` | 入口 |
| `RootTabs.swift` / `HomeView.swift` | 主界面 |
| `BindView.swift` | 绑定 / 换服务端 |
| `PlayerView.swift` | 播放页 |
| `RemoteView.swift` | 遥控 |
| `VLCPlayerView.swift` | VLC 播放器封装 |
| `DecodeFixtures.swift` | 解码样例 |

### VLCPlayerView 是双实现

`VLCPlayerView.swift` 用条件编译区分两种实现：

- [`#if canImport(MobileVLCKit)`](../../ios/LITVApp/Sources/VLCPlayerView.swift#L51) → 真实播放器（`UIViewRepresentable`）
- [`#else`](../../ios/LITVApp/Sources/VLCPlayerView.swift#L199) → 不可用时的降级实现（`View`）

这解释了为什么单元测试可以不带 Pod 编译：测试目标用的是降级分支的等价替身。
**代价是替身与真实实现可能漂移**——见第四节。

## 二、新增的会话测试目标（未提交）

`project.yml` 新增 `LITVSessionTests`（[project.yml:58-75](../../ios/project.yml#L58-L75)）：

- 只编译 4 个源文件：`Session.swift` / `Api.swift` / `Models.swift` / `KeychainStore.swift` + `Tests/`
- `TEST_HOST` 与 `BUNDLE_LOADER` 置空 → 逻辑测试，不启动 App
- 不装播放器 Pod，因此不会拉 MobileVLCKit

配套 `ios-check.yml` 新增 `test-session` job（macos-latest，20 分钟超时）：
`xcodegen generate` → 跑 `LITVSessionTests` → [`Tests/check-test-log.py`](../../ios/Tests/check-test-log.py) 校验。

`check-test-log.py` 是**防空跑**的门，不是摆设：它要求
① 源码里每个 `func test…` 都在日志里出现 `passed`；
② 日志里存在 `Executed N tests, with 0 failures` 且 `N ≥ 用例数`。
XCTSkip 显示 `skipped` 会被判失败。

## 三、C05「换服务端会话隔离」——**未实现**

这是本文件最重要的一节。审计要求的语义是：
*候选探测不得改动全局会话；探测失败或取消必须原子回滚；旧代际的异步结果不得写回新会话。*

**当前源码一条都没有落地**：

| 要求 | 现状 | 位置 |
|---|---|---|
| 探测期间 `serverBase` 保持旧值 | 探测**之前**就写 `serverBase = b` | [Session.swift:88](../../ios/LITVApp/Sources/Session.swift#L88) |
| 探测失败回滚 | catch 只写 `errorText`，不回滚 | [Session.swift:97-100](../../ios/LITVApp/Sources/Session.swift#L97-L100) |
| 切源清会话级数据 | `dropCredentials()` 只清 token + isBound | [Session.swift:194-198](../../ios/LITVApp/Sources/Session.swift#L194-L198) |
| 旧 origin 未知时不复用留存 token | `!previous.isEmpty && previous != b` 把空 previous 排除掉了 | [Session.swift:93](../../ios/LITVApp/Sources/Session.swift#L93) |
| 乱序探测只允许最后一个提交 | 无代际号 | — |
| 取消轮询不得报超时 | `try? await approvalPause()` 吞掉 `CancellationError`，循环走满 60 次 | [Session.swift:128](../../ios/LITVApp/Sources/Session.swift#L128) |

### 后果：一条可触发的凭据泄漏路径

1. 用户在绑定页输入错误地址（或该地址指向一台非 LITV 服务）；
2. `probe()` 先把 `serverBase` 改成候选地址，health 请求失败；
3. 失败后 `serverBase` **停在候选地址**，`token` 仍是原服务器的；
4. 之后每次冷启动 `LITVApp.swift` 调 `bootstrap()`，而 [`Session.swift:142`](../../ios/LITVApp/Sources/Session.swift#L142) 的 guard 只检查非空；
5. → 原服务器的 bearer token 被发往第三方 origin。

不需要并发、不需要轮询，正常操作就能触发。

### 测试与实现不一致

`ios/Tests/SessionIsolationTests.swift` 有 12 条行为测试，其中至少 9 条断言的正是上表缺失的能力
（例如 [:15](../../ios/Tests/SessionIsolationTests.swift#L15) 断言探测在途时 `serverBase` 仍是旧值）。

**所以当前工作区如果推上去，`test-session` 会红。** 这是静态推断，未运行——只可能被 `iOS Build Check` 推翻。

## 四、未验证项（都是真的没验证）

| 项 | 为什么没验证 | 怎么验 |
|---|---|---|
| Swift 能否编译 | 本地无 Apple SDK | `iOS Build Check` 首条 `error:` |
| 12 条测试红/绿 | 同上 | 同上 + `check-test-log.py` 输出 |
| `await fulfillment(of:timeout:)` 在 iOS 15 是否可用 | 无法离线确认可用性标注。**若它被标为 iOS 16+，测试目标会编译失败**——与 AGENTS §6 记录的 `buildIf` 同类坑 | 若 CI 报 `only available in iOS 16.0 or newer`，改用 `XCTWaiter` + continuation |
| 真机播放 | 无设备 | release 构建 + Activity/视图生命周期 + 快速切台 + 断网恢复 |
| `VLCPlayerView` 替身是否与真实实现一致 | 未做交叉检查 | 替身（`Tests/SessionTestSupport.swift:5-7`）与 `VLCPlayerView.swift` 的 `#else` 分支必须同签名；建议在测试里引用真实文件而非自建替身 |

## 五、安全基线现状

- token **只进 Keychain**（`KeychainStore`），旧明文键迁移后删除，写失败不回退明文——**已确认**
- 未发现关闭 TLS 证书或主机名校验的代码——**已确认**
- 未发现 Release 残留详细 HTTP 日志的代码——**已确认**
- `Session` 是 `@MainActor`，`ApiClient` 是值类型，注入的 `URLSession` 为 `let`——**已确认**
