# 评审项状态

> 这份文件只写**当前能验证的**结论。三档严格分开，不许把推测写成结论。
>
> 上一版把未落地的改动写成"已处理"，导致下一个人以为做完了——本文件存在的唯一目的就是不再重复这件事。
> 历史评审清单（P01–P15 / U01–U14 / M01–M03 共 29 项）已从工作区移除，
> 定义仍在 git 历史里：`git show 861269f:audit-results/LITV2-project-review-2026-11-12.md`。

## 一、已确认（有源码证据或已执行命令）

| 项 | 结论 | 证据 |
|---|---|---|
| C05 iOS 换服务端会话隔离 | **未实现**。只有依赖注入重构，没有代际号/回滚/取消检查/会话数据清理 | [Session.swift:88](../../ios/LITVApp/Sources/Session.swift#L88) 探测前即改写 `serverBase`；[:97-100](../../ios/LITVApp/Sources/Session.swift#L97-L100) 失败不回滚；[:93](../../ios/LITVApp/Sources/Session.swift#L93) `previous` 为空时跳过清凭据 |
| 由此产生的泄漏路径 | 打错一次地址后，旧 bearer 会随每次冷启动发往候选 origin | [LITVApp.swift](../../ios/LITVApp/Sources/LITVApp.swift) 冷启动调 `bootstrap()`；[Session.swift:142](../../ios/LITVApp/Sources/Session.swift#L142) guard 只查非空 |
| C05 的测试 | 12 条行为测试中至少 9 条对着当前源码是红的 | [SessionIsolationTests.swift:15](../../ios/Tests/SessionIsolationTests.swift#L15) 等；**未运行**，静态推断 |
| iOS token 存储 | 只进 Keychain，旧明文迁移后删除，写失败不回退明文 | `KeychainStore.swift` |
| TLS 校验 | 两端均未发现关闭证书/主机名校验的代码 | 全仓 grep 无 `TrustManager` / `HostnameVerifier` / `serverTrust` 命中 |
| 服务端时间口径 | 统一 Unix 秒 | [`store.Now()`](../../server/internal/store/db.go#L361) |
| API 规模 | 92 条路由：公开 12 / 管理台 59 / 终端 21 | [01-API](01-API.md)，脚本从 `api.go` 机械抽取 |
| CI 工作流数量 | **8 条**，AGENTS §8 只列了 4 条 | `.github/workflows/` 实际文件 |

## 二、待验证（必须由 CI 或设备出结论，本地做不了）

| 项 | 卡在哪 | 怎么验 | 判据 |
|---|---|---|---|
| C05 的 12 条测试红/绿 | 无 Apple SDK | `iOS Build Check` 的 `test-session` job | `check-test-log.py` 输出 `all N named behavioral tests executed and passed` |
| 测试目标能否编译 | `await fulfillment(of:timeout:)` 若标注 iOS 16+，与 `deploymentTarget: 15.0` 冲突 | 同上 | CI 首条 `error:`；报 `only available in iOS 16.0 or newer` 则换 `XCTWaiter` |
| 服务端测试是否真测到行为 | 交接文档自述"只写了测试，没做回退验证" | 把修复临时回退，确认测试变红，再恢复 | 两次日志都留档 |
| Android 播放器状态机 | `PlayerManager` 硬绑 `Handler(Looper.getMainLooper())`，无 `androidTest` | 抽纯类单测（仿 `PlayRequestGate` 先例），跑在现有 Build Check | release 后到点不启动 / 已排回调被丢弃 / play 后可重启 |
| 真机播放 | 无设备 | release 构建 + 生命周期 + 快速切台 + 断网恢复 | 重试有上限，不无限重连 |

## 三、未复核（没读实现，不给结论）

以下方向**没有**在本轮核过，不因为文档里写过就算数：

- 服务端 O01–O05（SSRF 本机网卡防线、pending 设备绑定码路径、已 bound 会话续期、配置保存回滚、删源依赖清理）
- 管理台 U01 / U02 / U04 / U06（配置 GET 部分失败、异步刷新失败报成功、EPG 只加载 500 条、直播页窄屏）
- 播放链路 C01 / C02 / C04 / C06（首帧看门狗、release 终止栅栏、缓存 key、起播预算）
- 打包 M01 / M02（卸载删除边界、发布 tag 与 SHA）
- Android 双 flavor 的两个疑点：`device_key` 是否跨 flavor 冲突、绑定页是否在探测前清凭据

要推进这些，逐项读实现 + 跑对应 CI，不要凭旧文档的"已处理"结案。

## 四、写这份文件的规则

1. **只有跑过的才写"已验证"**；没跑的一律写"未验证"，并写清卡在哪。
2. 引用必须带文件路径 + 行号，能点开核对。
3. 修完一项，把证据（CI run 链接或日志摘要）附在行内，再挪到"已确认"。
4. 测试红的时候不许改文档说绿了——这正是上一版犯的错。
