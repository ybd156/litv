# iOS 端构建：现状与卡点（2026-11-02）

> 目标：在 WSL 里用 xtool 产出**未签名** iOS `.ipa`，播放内核走 libmpv。
> 结论：**当前环境被 xtool 自身的编译问题挡住**，需要换一条路。下面把已排除的和剩下的选项写清楚。

---

## 一、已确认可行的部分

| 环节 | 状态 | 证据 |
|---|---|---|
| Swift 工具链 | ✅ | `swiftly` 装的 Swift 6.4.1，`swift --version` 正常 |
| xtool 源码 | ✅ | GitHub 克隆成功（经 `gh-proxy.com` 镜像，直连大仓库会被 TLS 重置） |
| SwiftPM 依赖 | ✅ | 20+ 个包全部拉取成功（swift-nio / OpenAPIKit / SwiftyMobileDevice …） |
| libimobiledevice 头文件 | ✅ | 用 `apt-get download` + `dpkg-deb -x` 解到 `~/android-dev/sysroot`，无需 root |
| C 模块编译 | ✅ | `swift build -Xcc -I$HOME/android-dev/sysroot/usr/include` 后，`libimobiledevice` 模块能过 |

## 二、卡点（唯一的）

编译 **XKit 目标**时报：

```
error closing '.../XKit-t.build/Objects-normal/x86_64/RCodesignSigner.o'
for output: '...RCodesignSigner-<hash>.o.tmp => ...RCodesignSigner.o':
No such file or directory
```

即：swift-frontend 把 `.o.tmp` 重命名为 `.o` 时目标目录"不存在"。
`SwiftyMobileDevice` 等目标都能编过，只有 XKit 这一批集中失败。

### 已排除的原因

| 猜测 | 验证 | 结论 |
|---|---|---|
| 磁盘满 | `/tmp` tmpfs 15G 用 2.5G，`/` 912G 可用 | 否 |
| inode 耗尽 | 使用率 7% | 否 |
| 路径含 `/tmp` 导致截断 | 挪到 `~/xtool` 重建（清掉 module cache） | 否，同样报错 |
| 并发过高 | `-j 4` 重试 | 否，同样报错 |
| module cache 路径失效 | `rm -rf .build/out .build/artifacts` 后重建 | 否 |
| C 头文件缺失 | 加 `-Xcc -I...`，单独用 clang 验证头文件可 include | 已解决，不是当前卡点 |

### 最可能的原因（判断，未证实）

**Swift 6.4.1 太新**。xtool 的 `Package.swift` 与构建脚本按 5.10~6.1 量级验证；
6.4 的 SwiftPM 在多目标并行编译的产物落盘路径处理上有变化。
社区也有人反馈 6.x 上 SwiftPM 偶发 `error closing ... .tmp => .o`。

## 三、四条可选路径

### A. 用 GitHub Actions 的 macOS runner 直接出 ipa（推荐）
runner 本身就是 macOS + Xcode，**不需要 xtool**，也不需要 Linux 系统头文件。
现有 `.github/workflows/ios-xcframework.yml` 已经能编 xcframework，
再加一个 job 用 `xcodebuild` 把 SwiftUI 工程打成未签名 `.ipa` 即可。
- 优点：绕开全部环境问题，一次配好长期可用
- 代价：需要把 iOS 工程写成 Xcode 能认的结构（`project.yml` + xcodegen，或手写 pbxproj）

### B. 在 WSL 里降到 Swift 6.0 / 5.10 再编 xtool
`swiftly install 6.0` 即可（不需要 root）。预期能过，但：
- 要重新下 500MB+ 工具链
- 编过之后 `xtool setup` 还要下 Darwin SDK（约 2GB）
- 依然可能踩新坑

### C. 放弃 xtool，WSL 里只做"能编出 .app 目录"的最小验证
用 `swiftc -target arm64-apple-ios14.0` 直接编单个 Swift 文件验证工具链，
但产不出可安装的 ipa，实际价值有限。

### D. iOS 端后置
先把 Android 双端 + 服务端打磨到可用，iOS 等前两条路之一条件具备再做。

## 四、无论走哪条路，iOS 端代码可以先写

iOS 端的业务逻辑（API 客户端、频道模型、播放器抽象）与 Android 端高度同构，
可以先按 SwiftUI 结构写好，等构建通路打通后直接编译。

已规划的 iOS 结构：

```
ios/
├── LITVKit/            # SwiftPM 包（业务逻辑，可独立测试）
│   ├── Sources/
│   │   ├── LITVAPI/    # 与服务端 docs/design/01-API.md 对齐
│   │   ├── LITVModel/  # Channel / EpgProgram / PlayInfo …
│   │   └── LITVPlayer/ # AVPlayer 与 libmpv 的统一抽象
└── LITVApp/            # SwiftUI 界面
    └── Sources/        # 首页 / 播放页 / 设置
```

`LITVPlayer` 的抽象与 Android 的 `PlayerEngine` 接口一一对应
（`prepare/play/pause/release/stats`），后端换成 AVPlayer 或 libmpv 不影响上层。

## 五、当前建议

**走 A**。理由：
1. WSL 里已经证明"能编 xcframework"，剩下的只是"把 xcframework 和 SwiftUI 一起打包"
2. macOS runner 上没有 libimobiledevice / Swift 版本这些坑
3. 你要求"不签名"——CI 产出未签名 ipa 正好，用户自己侧载
4. 唯一成本是写一份 `project.yml`（xcodegen），一次性工作

如果坚持"全流程在 WSL"，就走 B，我可以在后台装 Swift 6.0 重试。
