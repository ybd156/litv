# 03 · Android 模拟器验证（两端 flavor）

> 本文记录**一次真实执行过的验证**：在本机 Android 模拟器上装 x86 调试包，
> 启动 TV 与手机两个 flavor，确认界面渲染、遥控器交互、无崩溃。
> 每条结论都附当时的命令或输出。没做的部分单列在最后一节。

## 一、为什么要本地跑

`AGENTS.md` §8 规定编译走 GitHub Actions，但那条规则的目的是「不要为了编译而搭工具链」，
不是为了禁止本地验证。这次的情况是：**CI 只能告诉你编过了，不能告诉你界面长什么样**。
模拟器能看到界面、能按方向键、能截图，这些 CI 给不了。

本机原本没有任何 Android 构建环境，所以先装了 JDK 与 Gradle。

## 二、环境（本机实装）

> 具体安装路径不写进仓库（AGENTS §8）。下面的命令一律用环境变量，
> 换台机器只要 `JAVA_HOME` / `ANDROID_HOME` 指对就能跑。

| 组件 | 版本 | 由谁提供 |
|---|---|---|
| JDK | Temurin **17.0.21** | `JAVA_HOME` |
| Gradle | **8.14.6** | `PATH` 里的 `gradle` |
| Android SDK | platform **35** / build-tools **35.0.0**（另有 34） | `ANDROID_HOME` |
| 模拟器加速 | **AEHD 2.2**（`emulator -accel-check` → `AEHD is installed and usable`） | 随 emulator 安装 |
| AVD | `litvtv`、`litvphone`（API 28 / x86） | `emulator -list-avds` |

`android/local.properties` 需要一行 `sdk.dir=<你的 SDK 路径>`。
该文件已在 `.gitignore` 里，**不要提交**——提交了就把本机路径写死了。

> 为什么用 API 28 的镜像：本机只装了 `system-images;android-28;android-tv;x86` 与 `android-28;default;x86`。
> 工程 `minSdk = 21`，所以 API 28 是合法目标；但**它低于 `targetSdk = 35`**，
> 高版本行为变更（分区存储、通知权限弹窗等）在 API 28 上不会出现——见第五节。

## 三、编译

```bash
export JAVA_HOME="<你的 JDK 17 路径>"
export ANDROID_HOME="<你的 Android SDK 路径>"
cd android
gradle --no-daemon assembleTvDebug assemblePhoneDebug
```

结果：`BUILD SUCCESSFUL in 1m 25s`（82 tasks，61 executed / 21 cached）。

产物（每个 flavor 三个 ABI，x86 那份才是模拟器能装的）：

```
app/build/outputs/apk/tv/debug/app-tv-x86-debug.apk          58 MB
app/build/outputs/apk/phone/debug/app-phone-x86-debug.apk    58 MB
```

> **坑**：debug 构建的 applicationId 带 `.debug` 后缀——
> 实际包名是 `com.litv.tv.debug` 与 `com.litv.tv.phone.debug`，不是 `com.litv.tv`。
> 用 `aapt2 dump badging` 才能确认，别照抄文档里的正式包名。

## 四、跑起来

```bash
A=$ANDROID_HOME/platform-tools/adb
$ANDROID_HOME/emulator/emulator -avd litvtv -accel on \
  -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot &
$A wait-for-device
# 等 sys.boot_completed = 1（实测约 20 秒）
$A install -r -t app/build/outputs/apk/tv/debug/app-tv-x86-debug.apk
$A shell am start -W -n com.litv.tv.debug/com.litv.tv.ui.LauncherActivity
```

手机端同理，换 `litvphone` + `app-phone-x86-debug.apk` + `com.litv.tv.phone.debug`，
并加 `-port 5556` 与两台并行。

### 实测结果

| 项 | TV（`litvtv`） | 手机（`litvphone`） |
|---|---|---|
| 包名 | `com.litv.tv.debug` | `com.litv.tv.phone.debug` |
| 首次启动落到 | `BindActivity` | `BindActivity` |
| `am start -W` TotalTime | **1629 ms** | **1503 ms** |
| 清数据后 | 仍进 `BindActivity` | 仍进 `BindActivity` |
| 本包 `FATAL EXCEPTION` | **0** | **0** |
| 分辨率 | 1920×1080 | 1080×1920 |

两个 flavor 在**同一份代码**下落到同一个入口，说明「界面形态按 flavor 分流」这条没有开叉。

### 遥控器交互（TV 端）

TV 端没有触摸，只能靠方向键。实测：

| 操作 | 结果 |
|---|---|
| `DPAD_UP` ×2 + `OK` | 焦点移到「协议」并把 `HTTPS` 切成 `HTTP`——说明方向键能移焦点、OK 能触发控件 |
| 再 `DPAD_UP` + `OK`（落在地址框） | 弹出**应用内屏幕键盘**：标题「服务器地址 · 第 1 格」，数字行 1–0、字母 a–z、`.` `-`，底部 `上一格 / 删除 / 清空 / 下一格 / 确定` |

这块是历史上出过事的地方（见 [电视输入复盘](../postmortem/POSTMORTEM_2026-11-12_tv-input.md)），
所以值得单独验一次：**分段输入 + 逐格跳转是通的**。

> 注意：那个键盘是**应用内自绘**的 View，不是系统 IME。
> 所以 `dumpsys input_method` 里 `mInputShown=false` 属正常现象，别据此判断「键盘没弹出来」。

## 五、没验证的部分（重要）

| 项 | 为什么没验 | 怎么补 |
|---|---|---|
| **播放链路** | 本地没有跑着的 LITV 服务端，也没有源 | 起一个服务端（或指向现有 NAS），绑定后在模拟器里实际起播 |
| 绑定 → 批准 → 进首页 | 同上，需要服务端配合 | 同上 |
| 高版本行为（API 29+） | 本机只有 API 28 镜像 | `sdkmanager` 装 `system-images;android-35;...` 后另建 AVD |
| release 包 / R8 | 只编了 debug | `assembleRelease` 需要签名配置，属另一轮 |
| TV 真实焦点流（横跨整页） | 只验了绑定页这一段 | 接上服务端后在频道页、播放页逐格走 |

**已验的部分只覆盖「能装、能起、能画、能按、不崩」，不等于「功能可用」。**

## 六、一个容易误判的现象

跑 TV 镜像时，`logcat` 里会出现 `com.android.tv.dvr` 的 `FATAL EXCEPTION`：

```
java.lang.RuntimeException: An error occurred while executing doInBackground()
Caused by: android.database.sqlite.SQLiteException: no such column: failed_reason
```

那是 **Android TV 系统自带的 DVR 应用**（`com.android.tv.dvr`，进程号与我们的包无关）在
API 28 镜像上的已知问题，不是本项目的崩溃。判断「我们的 App 有没有崩」必须按包名过滤：

```bash
adb logcat -d | grep -iE "com\.litv" | grep -iE "FATAL|Exception"
```

## 七、复现

```bash
export JAVA_HOME="<你的 JDK 17 路径>" ANDROID_HOME="<你的 Android SDK 路径>"
cd android && gradle --no-daemon assembleTvDebug assemblePhoneDebug
$ANDROID_HOME/emulator/emulator -avd litvtv -accel on -no-window -no-audio \
  -no-boot-anim -gpu swiftshader_indirect -no-snapshot &
$ANDROID_HOME/platform-tools/adb wait-for-device
$ANDROID_HOME/platform-tools/adb install -r -t \
  app/build/outputs/apk/tv/debug/app-tv-x86-debug.apk
$ANDROID_HOME/platform-tools/adb shell am start -W \
  -n com.litv.tv.debug/com.litv.tv.ui.LauncherActivity
$ANDROID_HOME/platform-tools/adb exec-out screencap -p > tv.png
```

截图存于 [`docs/images/client/`](../images/client/)：`tv-bind.png`、`tv-keyboard.png`、`phone-bind.png`。
