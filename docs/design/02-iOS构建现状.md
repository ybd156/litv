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

## 三、C05「换服务端会话隔离」——**已修复并经 CI 验证**

审计要求的语义是：
*候选探测不得改动全局会话；探测失败或取消必须原子回滚；旧代际的异步结果不得写回新会话。*

实现方式：`Session` 里有两条代际号——`epoch`（会话代际，换源/解绑/重新注册都 +1）
与 `probeEpoch`（探测代际，只有最后发起的那次能提交）。
所有异步操作在入口记下当时的代际，`await` 回来后先比对，不一致就丢弃结果。

| 要求 | 现状 |
|---|---|
| 探测期间 `serverBase` 保持旧值 | `probe` 只操作局部变量，健康检查显式用空 token |
| 探测失败/取消 | 一律不提交、不改任何状态；取消时连 `errorText` 都不写 |
| 切源清会话级数据 | `commitServerSwitch` → `dropCredentials` + `clearSessionData`（channels/groups/favorites/nowTitles/lastChannel/playRequest），保留 `deviceKey` |
| 旧 origin 未知时不复用留存 token | `previous` 为空也走丢弃分支 |
| 乱序探测只允许最后一个提交 | `probeEpoch` 比对 |
| 取消轮询不得报超时 | `do/catch` 捕获取消后静默返回，且不发请求 |

**验证**：`iOS Build Check #62` 的 `iOS 会话隔离行为回归` job 通过，
`Executed 16 tests, with 0 failures`，`check-test-log.py` 的防空跑门放行。

**尚未做的一步**：回退验证。测试现在全绿，但没人证明把它对着旧实现跑会红。
这条记在 [评审项状态](../review/STATUS.md) 的「待验证」里。

### 仍然存在的局限

`epoch` 只能保证**同一进程内**的时序正确。它不能防：
- 服务端被同名域名换掉（DNS / 中间人）——那要靠 TLS 校验，本仓库没有关闭它
- 用户在另一个 App 里手动清 Keychain 后本进程仍持有内存中的 token

## 四、未验证项（都是真的没验证）

| 项 | 为什么没验证 | 怎么验 |
|---|---|---|
| 真机播放 | 无设备 | release 构建 + Activity/视图生命周期 + 快速切台 + 断网恢复 |
| `VLCPlayerView` 替身是否与真实实现一致 | 未做交叉检查 | 替身（`Tests/SessionTestSupport.swift:5-7`）与 `VLCPlayerView.swift` 的 `#else` 分支必须同签名；建议在测试里引用真实文件而非自建替身 |

> 已经验过的两项（Swift 能否编译、16 条测试红/绿）已由上表移出，
> 证据是 `iOS Build Check #62` 全绿——**能编译不等于能工作，但测试跑过且断言落在行为上，这是本仓库目前最强的证据形式**。

## 五、安全基线现状

- token **只进 Keychain**（`KeychainStore`），旧明文键迁移后删除，写失败不回退明文——**已确认**
- 未发现关闭 TLS 证书或主机名校验的代码——**已确认**
- 未发现 Release 残留详细 HTTP 日志的代码——**已确认**
- `Session` 是 `@MainActor`，`ApiClient` 是值类型，注入的 `URLSession` 为 `let`——**已确认**
