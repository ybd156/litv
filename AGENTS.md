# LITV2 开发指南（给 AI 与新加入的人）

这份文档写的是**做事的规则**，不是功能说明。功能见 [README](README.md)。

违反这里的约定，代码即使能跑也会在真机上出事——这个仓库里已经发生过多次。

---

## 1. 项目是什么

私有部署的 IPTV 源管理与播放平台，跑在飞牛 fnOS 上。

**不提供、不内置、不托管任何直播源。** 所有源由使用者导入，合法性自负。
任何让项目"自带源"的改动都不要做。

---

## 2. 边界

- `server/` — Go 服务端。认证、SQLite、源解析、EPG、流扇出、录制、时移、
  遥控中转、DLNA（服务端 + 投屏控制端）、HTTP API
- `admin/` — Vue 3 + TypeScript + Vite 管理台，部署在 `/app/litv/`
- `android/` — Android TV 与手机，**同一份代码、两个 flavor**
  （`tv` → `com.litv.tv`，`phone` → `com.litv.tv.phone`，可同时安装）。
  Media3 主，VLC / mpv 兜底
- `ios/` — SwiftUI。MobileVLCKit 主，AVPlayer 兜底
- `packaging/fnos/` — fpk 清单、生命周期脚本、安装向导
- `build/test/` — 集成测试脚本（版本管理，不是产物）
- `tools/` — 开发辅助脚本，分 `checks/`（一致性检查）、`inspect/`（分析外部产物）、
  `ops/`（对 NAS/设备操作）。见 `tools/README.md`
- `edge/` — Cloudflare Workers 边缘降级部署（NAS 不可用时的备选）。
  **代码开源、实例私有**：源地址只在部署者自己的 Secrets 里，
  仓库内不得出现任何源清单或凭据。部署说明见 `edge/README.md`
- `docs/` — 设计文档、调研、审查记录、安全分析
- `.github/workflows/` — 编译与发布（见 §8）

---

## 3. 安全模型（改动前先读）

1. 浏览器管理端正常经飞牛 Unix Socket 网关进入
2. `X-Trim-*` 身份头**只对确认来自网关的请求可信**
3. TCP 请求**绝不能**因为带了 `X-Trim-*` 头就成为管理员
4. 原生客户端用设备 bearer token 认证
5. 服务端存 token 的**哈希**，不存明文
6. 流地址用 HMAC 签名并带过期时间
7. 应急密码登录是例外路径，改动必须限流、审计、单独评审

### 设备身份

- 客户端 `device_key` 由系统稳定标识派生（Android 用 `ANDROID_ID`），
  重装/清数据后不变，服务端据此认回原设备
- 服务端遇到已存在的 `device_key`：**只有「已 bound 且平台一致」才换发可用 token**
- 同 key 换平台必须拒绝（`ErrDeviceKeyConflict`），否则知道 key 就能顶掉设备
- 未批准的设备重复注册**仍是 pending**，不能因为重复注册就被批准

---

## 4. 事实来源

- 只改源码，不改生成物
- 不要改 `admin/dist`、`server/internal/web/www`、Android 构建产物、Gradle 中间文件
- 改 API 时，Go handler、TS/Kotlin/Swift 类型、mock、测试、文档**一起改**
- 响应信封是 `{ code, msg, data }`。不接受其它形状，除非明确记录兼容性
- 页面与客户端**不得**用不可信输入拼特权 URL

---

## 5. 不要读/改

除非任务就是关于生成物，否则跳过：

```text
**/node_modules/**
**/dist/**
**/build/**
**/generated/**
**/intermediates/**
**/.gradle/**
**/.git/**
server/internal/web/www/**
packaging/fnos/app/server/litvd_*
```

---

## 6. 工程规则

### 管理台

- 生产构建**绝不能**通过 URL 参数或浏览器存储启用 mock API
- API 调用放在 `admin/src/api/modules`，不要写在模板里
- 401 = 未认证，403 = 已认证但无权限，不要混用
- 破坏性操作要有说明影响范围的确认文案
- **不要**记录密码、bearer token、签名流地址、导入源的凭据

### Go 服务端

- 在 HTTP 边界校验请求体、路径 ID、大小、取值范围
- 只用参数化 SQL，**绝不**拼接不可信值
- 多记录破坏性操作必须走事务
- 拒绝未知或畸形的认证上下文
- 认证接口要限流 + 安全日志（不含密钥）
- 出站抓取要考虑 SSRF，限制本机与链路本地地址
- 删除文件前必须证明规范化路径属于应用自有根目录

### Android / iOS

- 优先 HTTPS。用户自有 LAN 服务可以走 HTTP，但界面必须让用户明确知道
- **绝不**关闭 TLS 证书或主机名校验
- 不要在日志或崩溃上报里输出 token、完整签名 URL
- token 用平台安全存储（Android Keystore / iOS Keychain）
- Release 构建不能带详细 HTTP 日志
- 播放器改动必须验证：release、Activity 生命周期、快速切台、断网恢复、重试有上限

### iOS 特有

- **部署目标是 iOS 15。不要用 iOS 16+ 的 API。**
  踩过的坑：`.toolbar { if ... { ToolbarItem } }` 里的条件内容会编译成
  `buildIf`，那是 iOS 16 才有的，CI 直接报
  `'buildIf' is only available in iOS 16.0 or newer`。
  需要条件显示时，让元素始终存在，用 `.disabled` / `.opacity` 控制
- `UIViewRepresentable` 里的裸 `UIView` 没有 intrinsic size，
  必须显式 `.frame(maxWidth: .infinity, maxHeight: .infinity)`
  并设 `autoresizingMask`，否则画面会缩成一小块

### fnOS 生命周期脚本

- 卸载默认保留数据
- 递归删除只能在规范化路径校验通过后执行
- **不能**仅因为路径文本含 `litv` 就删除
- 不跟随指向批准根目录之外的符号链接
- 记录跳过与失败的动作，但不暴露密钥

---

## 7. 改动流程

改之前：

1. 读 `README.md`、相关设计文档、所有直接受影响的源文件
2. 看 `git status`，保留无关的本地改动
3. 说明这次改动涉及：认证 / 删除 / 网络 / 存储 / 播放 / 打包 中的哪些

改之后：

1. 先跑最窄的相关测试
2. 跑格式化与类型/编译检查
3. 检查 diff 里有没有生成物、凭据、意外二进制
4. 行为或契约变了就更新文档
5. **报告哪些验证过、哪些没验证**

---

## 8. 编译：一律放 GitHub Actions，不要在本地搭工具链

**这是硬规则。** 新接手的人或 AI，改完代码**推上去让 CI 编**，
不要花时间在本地装 Go / JDK / Android SDK / Gradle / Node。

理由：

- 本地要装一整套（Go 1.23 + JDK + Android SDK 35 + build-tools + NDK + Gradle 8.14.6 + Node 24），
  换台机器就得重搭一遍
- 版本稍有差异就会出现「我这里能编过」的问题
- iOS 本地根本编不了（需要 Apple SDK）

### 标准编译路径

| 工作流 | 触发 | 做什么 |
|---|---|---|
| `.github/workflows/build-check.yml` | push 到 main、PR、手动 | Go 编译+vet+test、管理台 typecheck+build、Android 两个 flavor 编译+单测 |
| `.github/workflows/ios-check.yml` | 只在 `ios/**` 变化时 | 编译 iOS（不发版） |
| `.github/workflows/release.yml` | `v*` tag 或手动 | 出三端产物（APK / fpk / ipa） |
| `.github/workflows/edge-deploy.yml` | 只在 `edge/**` 变化、每日定时、手动 | 边缘降级部署：typecheck + 自测 + 部署到 Cloudflare。**可选**，四个 Secrets 没配全时自动跳过部署（只跑检查），不会让 main 变红 |

改完代码看 `Build Check` 的结论就够了。要产物走 `Release`。

### 费用：只能用免费 runner

仓库是 **public**，所以标准 GitHub-hosted runner 全部免费（含 macOS）。

**必须遵守：**

- 只用标准 runner，并且**钉住版本**而不是 `-latest`：
  `ubuntu-24.04`、`macos-latest`。`ubuntu-latest` 正在迁移到 Ubuntu 26，
  钉版本可以避免某天构建环境被悄悄换掉
- **不要**用 larger runner（`ubuntu-latest-4-core`、`macos-14-xlarge` 等），那些按分钟计费
- 仓库一旦转成 **private**，macOS 立刻按 **10 倍**扣分钟——
  届时必须把 `ios-check.yml` 改成只在手动触发时跑，或干脆停掉

### CI 上的几个坑（改工作流前必读）

- `android/local.properties` 被 gitignore，CI 靠 `ANDROID_HOME` 环境变量找 SDK。
  **不要**把它提交上去，会写死本地路径
- `server/` 用 `modernc.org/sqlite`（纯 Go），不需要 cgo；测试跑 `-race` 也没问题
- iOS 单独一个工作流、只在 `ios/**` 变化时跑：macOS runner 排队久、单次耗时长，没必要每次主分支推送都编一遍（私有仓库时它还是 10 倍计费）

### 如果 CI 编不过

先看日志里第一条 `error:`。常见原因：

- Go：忘了 `gofmt -w`（build-check 会先查格式）
- Android：`e:` 开头的 Kotlin 编译错误；`assembleRelease` 才暴露的 R8/JNI 问题
- iOS：用了 iOS 16+ 的 API（部署目标是 **15**）

### 不要在仓库里写本机信息

路径、IP、设备型号、账号、代理地址这些**一律不进仓库**——换台机器就失效，
也会把环境细节暴露出去。

脚本要能跑在任何机器上：

- 仓库内路径用 `$(cd "$(dirname "$0")/../.." && pwd)` 推，不写绝对路径
- SDK / 工具用 `$ANDROID_HOME`、`PATH` 找，不写安装位置
- 需要外部参数（socket、数据库、设备地址）走环境变量，
  **且不给默认值**：`S="${LITV_SOCK:?请设置 LITV_SOCK}"`
- 参考文件（别人的 APK 之类）从命令行传，不写进代码

## 9. 验证命令

**标准做法：推上去让 `Build Check` 跑**（见 §8）。
下面列的是它内部执行的命令，本地排查时对照用。

### Go 服务端

```bash
cd server
gofmt -w <changed-go-files>
go build ./...
go vet ./...
go test -race ./...
```

**单元测试是存在的**，覆盖 `api` / `config` / `epg` / `store` / `stream` / `timeshift`
六个包。`go test ./...` 全绿是有意义的——但只对**已经写了测试的行为**有意义。

新增或修改行为时，**必须**同时加 `_test.go`。

### 管理台

```bash
cd admin
npm ci
npm run typecheck
npm run build
```

### Android

```bash
cd android
gradle lintDebug testDebugUnitTest assembleDebug
```

Release/R8 行为（JNI 回调、Gson 反射）需要 `assembleRelease` 验证，
只过 debug 不算数。

### fnOS 包

```bash
cd packaging/fnos
./build.sh              # x86；ARM 用 --arch arm
```

要验证**前端真的嵌进去了**，不只是 fpk 生成成功——
`go:embed` 只服务 `server/internal/web/www`，而打包脚本还会额外暂存一份
`www`，handler 根本不读那份。用 `build/test/verify-frontend-embed.sh` 检查。

### iOS

本地编不了。推上去看 `iOS Build Check`。

### 集成

跑 `build/test/` 里相关的脚本。认证、绑定、流签名、录制、卸载、升级
这几类改动**必须**有专门的回归测试。

---

## 10. 测试纪律

这个仓库有过两次「测试是绿的但根本没测到东西」的事故。所以：

- **修 bug 先写一条在旧代码上会失败的测试。**
  写不出失败的测试，说明还没定位到根因
- 断言要打在**行为**上，不是实现细节
- 测试自己也要能证明有效：写完把修复回退掉，确认它变红，再恢复
- 别用「能编译」当「能工作」的证据

---

## 11. 跨端约定

### 时间

- 服务端内部一律用 **Unix 秒**（`store.Now()`）
- **JavaScript `Date` 要毫秒**。传秒进去会得到 1970 年——
  这个 bug 已经出过一次（界面上显示"57年"）
- 前端统一用 `admin/src/utils/format.ts` 的 `toDate()` 做转换
- 节目单的日边界由**服务端**按时区算好下发（`date` / `day_start` / `day_end`），
  客户端不要自己按设备时区算

### 版本号

发版前这些地方必须同步，缺一个就会出现"关于里版本不对"：

| 文件 | 字段 |
|---|---|
| `packaging/fnos/manifest` | `version` |
| `android/app/build.gradle.kts` | `versionCode`（+1）、`versionName` |
| `ios/project.yml` | `CFBundleShortVersionString`、`CFBundleVersion`（+1） |
| `.github/workflows/release.yml` | `DEFAULT_VERSION` |
| `CHANGELOG.md` | `## [x.y.z]` |

客户端**不要**硬编码版本号，读 `Bundle.main` / `BuildConfig`。

### 文本与换行

- `.gitattributes` 强制 `.sh` 与 `packaging/fnos/**` 用 LF。
  CRLF 会让 fnOS 上的 bash 脚本直接语法错误
- 安装向导写 YAML 时，密码/密钥必须走 `yaml_dq` 转义

---

## 12. 发版

1. 同步 §11 的五个版本号位置
2. 在 `CHANGELOG.md` 写这一版改了什么（面向用户，不写实现细节）
3. 等 `Build Check` 变绿（不要用本地编译代替，见 §8）
4. `git tag -a vX.Y.Z && git push origin main --tags`
5. 等 `Release` 工作流出三端产物

**README 的下载表不用手动改**——`readme-downloads.yml` 在 release published
之后自动把「当前版本」和下载表同步成最新产物，并逐个校验链接可用。

它的边界：只替换 README 里这两对标记之间的内容，别处不碰。
**不要手动编辑标记之间的文字**（下次会被覆盖）：

```
<!-- AUTO:VERSION:START -->  ...  <!-- AUTO:VERSION:END -->
<!-- AUTO:DOWNLOAD:START --> ...  <!-- AUTO:DOWNLOAD:END -->
```

想手动核对：`GITHUB_TOKEN=xxx python3 tools/ops/update-downloads.py --check`
（有不一致返回 1）。

**注意**：release 工作流会先删掉同名 tag 再发布。所以
**已发布的版本号不能复用**，只能往上加。

---

## 13. 已知限制

### AV3A（AVS3-P3）音频

AV3A 是 Audio Vivid，中国国家标准。**没有任何开源解码器**：
FFmpeg 主线没有 → VLC / mpv 同样不支持；ExoPlayer 完全不认这个编码。

本项目补了一套 media3 扩展把链路接通：

| 环节 | 类 | 作用 |
|---|---|---|
| 解复用 | `Av3aReader` + `Av3aTsPayloadReaderFactory` | 认出 AV3A 的 stream_type（0xD5 等） |
| 解复用 | `Av3aHlsExtractorFactory`（Java） | 把上面的工厂接到 HLS 分片解析 |
| 解码 | `Av3aDecoder`（`SimpleDecoder`） | 调原生库 |
| 输出 | `Av3aAudioRenderer`（`DecoderAudioRenderer`） | 交给 AudioSink |
| 装配 | `LitvRenderersFactory` + `LitvMediaSourceFactory` | 只在库加载成功时才挂上去 |

**硬约束，别越线：**

- **解码库不进仓库、不进 Release**。没有合法来源，用户自己放
  `Android/data/<包名>/files/av3a/`（`Av3aLoader` 负责拷进 `filesDir` 再 `System.load`，
  Android 9+ 不允许从 `/sdcard` 直接 dlopen）
- **绝不**从网上找来路不明的 `.so` 提交进来，也不写进打包脚本
- 没库时这条链路完全不参与，其它音频照常走系统解码——不要让它影响常规播放

**状态：代码接通了，真机还没验证出声。** 所以遇到 AV3A 源没声音，
优先建议换同频道非 AV3A 线路，不要先怀疑别的地方。

iOS 侧没有对应的解码库，AV3A 频道在 iOS 上只能有画面。

## 14. 完成的标准

- 源码、客户端、mock、文档互相一致
- 相关的类型检查、测试、构建都过
- 没有引入密钥、签名凭据、生成物
- 破坏性行为有明确范围、安全的失败方式、回归测试
- 安全相关改动写清了「防的是什么威胁、还剩什么局限」
- 报告里说明了**哪些验证过、哪些没验证**
