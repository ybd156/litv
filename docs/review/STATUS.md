# 评审项状态

> 这份文件只写**当前能验证的**结论。三档严格分开，不许把推测写成结论。
>
> 上一版把未落地的改动写成"已处理"，导致下一个人以为做完了——本文件存在的唯一目的就是不再重复这件事。
> 历史评审清单（P01–P15 / U01–U14 / M01–M03 共 29 项）已从工作区移除。
> **注意：仓库历史已于 2026-11-13 压缩为单一提交，旧提交号不再可达**；
> 旧审计文档只存在于当时的备份包里（`litv-backup-20261113.bundle`，仓库外），删掉备份即永久丢失。

> **2026-11-16 更新**：本轮在真机上核了 Android 播放链路，并完成 iOS 功能对齐。
> 下面把「已确认 / 待验证 / 未复核」重新按事实划了一遍。

## 一、已确认（有源码证据或已执行命令）

| 项 | 结论 | 证据 |
|---|---|---|
| C05 iOS 换服务端会话隔离 | 已修复并经 CI 验证 | [Session.swift](../../ios/LITVApp/Sources/Session.swift) 的 `epoch` / `probeEpoch`；`iOS Build Check #62` 的会话隔离 job success |
| iOS token 存储 | 只进 Keychain，旧明文迁移后删除，写失败不回退明文 | `KeychainStore.swift`；`Session.register` |
| TLS 校验 | 两端均未发现关闭证书/主机名校验的代码 | 全仓 grep 无 `TrustManager` / `HostnameVerifier` / `serverTrust` 命中 |
| 服务端时间口径 | 统一 Unix 秒 | [`store.Now()`](../../server/internal/store/db.go#L361) |
| API 规模 | 92 条路由：公开 12 / 管理台 59 / 终端 21 | [01-API](../design/01-API.md) |
| CI 工作流数量 | 8 条（AGENTS §8 只列了 4 条） | `.github/workflows/` 实际文件 |
| **iOS 节目单为空** | **已修复**。`Bootstrap` 的自定义 `init(from:)` 漏解 `epg_url` / `client_parse`，`epgUrl` 恒为空导致 `EpgStore.ensure` 直接 return | [Models.swift](../../ios/LITVApp/Sources/Models.swift) 补两行；`DecodeFixtures` 新增回归用例；`iOS Build Check` on `e6f0111` success |
| **iOS 播放器功能对齐** | 画面比例（含工具栏入口）、节目单常驻、OSD 4 按钮、左右滑切台、分组 chip、时移入口、QoE 上报 —— 全部编译通过 | `iOS Build Check` on `1c3883e` / `8b2955b` success |
| **iOS 构建曾连续失败 4 次** | 根因是 `VLCMediaPlayer.videoAspectRatio` 是 `char *` 而非 Swift `String` | 错误取自 `ci-logs` 分支；修法 `strdup`；`e6f0111` 起 success |
| **Android 视频输出契约** | 收敛为 `PlayerEngine.attach` 单一出口；`PlayerManager` 记住 Surface、换内核自动重放；`ExoEngine` 去掉 `PlayerView` 分支 | [ExoEngine.kt](../../android/app/src/main/java/com/litv/tv/player/ExoEngine.kt) / [PlayerManager.kt](../../android/app/src/main/java/com/litv/tv/player/PlayerManager.kt) |
| **Android 退后台再进黑屏** | 已修。真机验证：`后台 8s 回来 → 重建播放器 → Surface 未就绪挂起 → Surface 就绪继续 → 视频首帧已渲染`，音频 `state:started` | Redmi 22081212C（Android 13）logcat |
| **Android 息屏唤醒** | 已修。真机验证：息屏期间反复挂起，亮屏后 `Surface 就绪，继续挂起的起播` → 首帧 | 小米电视 MiTV-ASTP0（Android 9）logcat |
| **Android 重复实例叠窗口** | 已修。`PhoneHomeActivity` 加 `launchMode="singleTask"`，否则每次打开都新建实例、播放器绑在其中一个上 | 修复前 `SurfaceFlinger` 里多个 `SurfaceView[...PhoneHomeActivity]` 层 |
| **退后台仍出声** | 已修。`LitvApp` 注册 `ActivityLifecycleCallbacks`，全部 Activity 不在前台即停播（应用内切页不受影响） | 真机日志 `应用退到后台，暂停播放` |
| **mpv 内核实际不可用** | 两台测试设备 `libplayer.so` 均 `UnsatisfiedLinkError: av_jni_set_java_vm`，降级链只有 Exo → VLC 两级 | AGENTS §13 已记录 |
| **「两个 flavor」是过期说法** | 实际是单包 `com.litv.tv` + 界面模式，`splits.abi` 只按架构拆 | [build.gradle.kts](../../android/app/build.gradle.kts)；设备上 `pm list packages` 只有 `com.litv.tv` |

## 二、待验证（必须由 CI 或设备出结论，本地做不了）

| 项 | 卡在哪 | 怎么验 | 判据 |
|---|---|---|---|
| **播久了卡顿** | 怀疑 `minBufferMs = 10s` 与 HLS 窗口 12s 冲突，但**没有长时观测数据** | 电视连播 ≥30 分钟，读 `Android/data/com.litv.tv/files/play.log` 里 `掉出直播窗口` / `换内核` 的频率 | 若频繁出现 → 调 `minBufferMs` 3s、`targetOffsetMs` 6s 后再测 |
| **真断网恢复** | `NetState` 的 `registerDefaultNetworkCallback` 路径**没做过拔网实验** | 拔网 30s 再恢复 | 日志出现 `网络已恢复，重试取地址第 N/5 次`，随后出画面 |
| C05 测试是否真能抓回归 | 测试全绿但**没做回退验证** | 把 `Session.swift` 的 `epoch` 临时回退 | 至少 9 条变红；恢复后全绿，两次日志留档 |
| 服务端测试是否真测到行为 | 交接文档自述"没做回退验证" | 逐项回退 | 两次日志留档 |
| Release/R8 下的播放与 AV3A | `Build Check` 只编 debug | 走 `Release` 工作流出的包 | 装上去能播、AV3A 不崩 |

## 三、未复核（没读实现，不给结论）

- 服务端 O01–O05（SSRF 本机网卡防线、pending 设备绑定码路径、已 bound 会话续期、配置保存回滚、删源依赖清理）
- 管理台 U01 / U02 / U04 / U06（配置 GET 部分失败、异步刷新失败报成功、EPG 只加载 500 条、直播页窄屏）
- 播放链路 C01 / C02 / C04 / C06（首帧看门狗、release 终止栅栏、缓存 key、起播预算）
- 打包 M01 / M02（卸载删除边界、发布 tag 与 SHA）

要推进这些，逐项读实现 + 跑对应 CI，不要凭旧文档的"已处理"结案。

## 四、写这份文件的规则

1. **只有跑过的才写"已验证"**；没跑的一律写"未验证"，并写清卡在哪。
2. 引用必须带文件路径 + 行号，能点开核对。
3. 修完一项，把证据（CI run 链接或日志摘要）附在行内，再挪到"已确认"。
4. 测试红的时候不许改文档说绿了——这正是上一版犯的错。
5. **CI 结论只认 API 的 `conclusion`**。`ci-logs` 分支只在失败时更新，成功时保留旧内容，
   把它的 404 当成"构建成功"是错的（2026-11-16 真踩过）。
