# 03 · Android 模拟器验证（两端 flavor）

> 本文记录**真实执行过的验证**：在本机 Android 模拟器上装 x86 调试包，
> 跑 TV 与手机两个 flavor，确认界面渲染、输入方式、遥控器交互。
> 每条结论都附当时的命令或输出。没做的部分单列在最后。

## 一、为什么要本地跑

`AGENTS.md` §8 规定编译走 GitHub Actions，但那条规则的目的是「不要为了编译而搭工具链」，
不是禁止本地验证。这次的情况是：**CI 只能告诉你编过了，不能告诉你界面长什么样**。

本机原本没有 Android 构建环境，所以先装了 JDK 与 Gradle（见第二节）。

## 二、环境

> 具体安装路径不写进仓库（AGENTS §8）。命令一律用环境变量。

| 组件 | 版本 | 由谁提供 |
|---|---|---|
| JDK | Temurin **17.0.21** | `JAVA_HOME` |
| Gradle | **8.14.6** | `PATH` 里的 `gradle` |
| Android SDK | platform **35** / build-tools **35.0.0** | `ANDROID_HOME` |
| 模拟器加速 | **AEHD 2.2**（`emulator -accel-check` → `AEHD is installed and usable`） | 随 emulator 安装 |
| AVD | `litvtv`、`litvphone`（API 28 / x86） | `emulator -list-avds` |

`android/local.properties` 需要一行 `sdk.dir=<SDK 路径>`，该文件在 `.gitignore` 里，不要提交。

## 三、两台模拟器必须先分清

**踩过的坑**：`emulator -avd X` 不指定 `-port` 时端口是自动分配的，
不能假设「先启动的是 5554」。这次就因为搞反，把手机包装到 TV 镜像上测了一轮。

判断方法：

```bash
adb -s <serial> shell pm list features | grep -c leanback   # >0 才是电视
adb -s <serial> shell wm size                               # 电视 1920x1080，手机 1080x1920
```

本次实际：

| serial | 镜像 | 尺寸 | leanback |
|---|---|---|---|
| `emulator-5554` | **手机** | 1080×1920 | 0 |
| `emulator-5556` | **电视** | 1920×1080 | 2 |

## 四、编译与安装

```bash
export JAVA_HOME="<JDK 17>" ANDROID_HOME="<SDK>"
cd android && gradle --no-daemon assembleTvDebug assemblePhoneDebug
```

`BUILD SUCCESSFUL`。x86 包：

```
app/build/outputs/apk/tv/debug/app-tv-x86-debug.apk          58 MB
app/build/outputs/apk/phone/debug/app-phone-x86-debug.apk    58 MB
```

> **debug 包名带 `.debug` 后缀**：实际是 `com.litv.tv.debug` 与 `com.litv.tv.phone.debug`。
> 用 `aapt2 dump badging` 确认，别照抄正式包名。

启动 Activity 统一是 `com.litv.tv.ui.LauncherActivity`（源码包不随 applicationId 变）。

## 五、实测结果

| 项 | 电视（`litvtv` / 5556） | 手机（`litvphone` / 5554） |
|---|---|---|
| 包名 | `com.litv.tv.debug` | `com.litv.tv.phone.debug` |
| 首次启动落到 | `BindActivity` | `BindActivity` |
| `am start -W` TotalTime | 2463–2601 ms | 2632 ms |
| 本包 `FATAL EXCEPTION` | **0** | **0** |

## 六、输入方式：这次改的就是这里

### 改之前：手机端没有可用输入通道

`TvKeyboard` 原来**无条件**套在整页所有输入框上。它是按电视 16:9 画的，
放到 1080×1920 竖屏上被裁切，实测键盘上只剩：

```
1 2 3 4 5 a b c d e k l m n o u v w x ‹ 上一格 删除 确定
```

**15 个字母，缺 f–j、p–t、z 和 6–0。** 后果：`tv.example.com` 里的 `i/p/t/z`
一个都打不出来，含 6–9 的 IP 段也输不了。系统 IME 又被主动关掉了
（`showSoftInputOnFocus=false` + 窗口级 `SOFT_INPUT_STATE_ALWAYS_HIDDEN`），
所以手机端**根本没有任何可用的文字输入通道**。

### 改之后：按设备类型分流

`BindActivity` 新增 `isTv`（`DeviceType.isTV`）：

| 设备 | 输入方式 |
|---|---|
| 电视 | 自绘 `TvKeyboard`（系统 IME 会吃掉方向键，见 `TvKeyboard` 类注释） |
| 手机 | 系统 IME（`SOFT_INPUT_ADJUST_RESIZE`，触摸操作不存在抢方向键的问题） |

**验证**（手机镜像 5554）：点地址框后 `dumpsys input_method` → `mInputShown=true`，
`adb shell input text 'tv.example.com'` 成功写入，输入框内容为 `tv.example.com`。

> 注意：flavor 与设备类型是两件事。手机包装在电视上，`DeviceType.isTV` 仍判真，
> 照样走自绘键盘——这是设计意图（界面形态优先级：设置 > 安装版本 > 设备特征）。

### 电视键盘重做（对齐 Leanback guided step 的通行做法）

| 改动 | 原因 |
|---|---|
| 宽度 `min(880dp, 屏宽×94%)` | 写死 dp 在窄屏上被裁切 |
| 键位改**显式高度** | 窗口被内容撑满时，LinearLayout 会把溢出子行压成 0 高——真机表现为键盘只画到字母行、符号行与「确定」全消失，面板却还是满屏高 |
| 字母区补 `⇧` 大小写 | 域名用小写，密码可能要大写 |
| 新增符号行 `. - _ : / @` | 反向代理地址可能要 `:` `/` |
| 标题显示 `n/max` | 到长度上限不再静默吞键 |
| 「清空」移到最右 | 原来紧挨「删除」，误触会清掉整段输入 |
| 逐键日志只在 `BuildConfig.DEBUG` 打 | Release 不带详细日志（AGENTS §6） |

实测渲染（1920×1080）：标题「服务器地址」→ `1–0` → `a–j` → `k–t` → `u–z ⇧` →
`. - _ : / @` → `‹ 上一格 / 删除 / 下一格 › / 清空` → `确定`，七行全部可见。
触摸点键输入 `tv.example.com`，预览条实时显示，按「确定」回填表单成功。

## 七、接真实服务端：两端都跑通了

目标实例是一台 **edge 模式** 的实例（域名此处用 `tv.example.com` 代替，真实地址不入库），`/api/v1/health` 返回 `"version":"edge"`）。

edge 模式不支持临时签发绑定码：

```
{"code":2006,"msg":"边缘模式不支持生成绑定码（边缘模式的绑定码就是 BIND_CODE，不临时签发）"}
```

所以必须先拿到这台实例的 `BIND_CODE`（固定 6 位）。拿到之后的完整链路：

| 步骤 | 手机（5554） | 电视（5556） |
|---|---|---|
| 输入地址 | 系统 IME，`input text` 直输 ✓ | 自绘键盘点键，预览回显 ✓ |
| 填绑定码 | 系统 IME ✓ | 自绘键盘标题显示 `6/6` ✓ |
| 点「绑定」 | 立即绑定 | 立即绑定 |
| 落到 | `PhoneHomeActivity` | `PlayerActivity` |
| 结果 | **CCTV1综合 正在播**，系统硬解 Media3，直连源站 | **CCTV1综合 全屏播放** |
| 本包 FATAL | **0** | **0** |

两个 flavor 绑完落到不同页面，与 `BindActivity.goHome()` 的 `DeviceType.isTV` 分流一致：
电视没有"首页"概念，绑完直接全屏播放，频道列表是按 ← 呼出的浮层。

> 播放截图没进仓库：画面是真实广播内容且带第三方水印，不适合放在「不内置源」的公开仓库里。

## 七点五、电视端界面与操作逻辑

逐条按键实测（TV 镜像 5556，软件渲染）：

| 项 | 按键 | 结果 |
|---|---|---|
| OSD 信息条 | `OK` | 显示「001 CCTV1综合 / 10:12 谷雨5/30」+ 键位说明条（↑↓换台 · ←频道列表 · →设置 · OK信息 · 长按OK切线路 · 数字键跳台） |
| 频道浮层 | `←` | 三列：分类（全部 58 / 收藏 / 央视 28 / 央视-MCP 30）、频道（台标 + 当前节目 + 进度）、节目单；焦点环正常 |
| 设置菜单 | `→` | 一级 9 项；二级从菜单左侧**就地展开**成选项条，画面不被遮挡 |
| 二级选项 | 一级上按 `OK` | 「画面比例」→ 自动 / 16:9 / 4:3 / 铺满，当前值高亮；选中后面板收起并生效 |
| 数字键跳台 | `5` | 直接跳到 CCTV5体育 |
| 上下切台 | `↓` / `↑` | CCTV5体育 ↔ CCTV5体育赛事，`slide=+1.0 / -1.0`（转场方向正确） |
| 线路选择 | 长按 `OK` | **入口未能模拟**：API 28 的 `input keyevent` 不支持 `--longpress`。接线本身存在（`longPressRunnable` 800ms）；同一个 `openLinePicker()` 已通过「设置 → 线路选择」验证，弹出「选择线路（1 条）→ 线路 0ms」 |
| 分类管理 | 设置 → 分类管理 | 直接打开频道面板（导航动作，不是选项） |

### OSD 对齐运营商机顶盒样式

对照参考实现（机顶盒 OSD：台标卡 + 台号台名 + 当前/下一档带时间段 + 右侧角标）改了三处：

| 改动 | 改前 | 改后 |
|---|---|---|
| 当前节目 | `10:12 谷雨5/30`（只有开始时间） | `11:01-11:48 谷雨6/30` |
| 下一档 | `下一档 11:01 标题` | 同样带时间段 |
| 右侧角标 | `osdTags` 建了但从没赋值，一直空白 | `线路 1/1` |

实测截图确认：台号台名、时间段、线路角标、键位说明条同屏显示，`com.litv` 崩溃 0。

**仍未对齐参考实现的**：台标卡（布局里有 `osdLogo`，OSD 里没显示）、清晰度/音轨角标（FHD / 立体声）——后者需要播放器上报流参数，目前拿不到。

### 一个绑定行为（容易误判为"点击没反应"）

`pm clear` 清掉 token 后重新绑定，**即使服务端还认这台设备（已 bound、同平台），
edge 实例仍然要求填 6 位 `BIND_CODE`**，否则返回 `2004 绑定码不正确`。

界面上的表现是：点「绑定」后页面不动、只在状态栏出现一行小字「绑定码不正确」。
排查时**先看状态栏那行字**，不要先怀疑点击没生效——我在这上面浪费了一轮。


全程 `Process: com.litv` 崩溃 **0**。

### 渲染与加载（两个独立问题，别混）

| 现象 | 原因 | 处理 |
|---|---|---|
| 画面全黑但日志显示在播（`bootstrap 完成`、`play 返回`、AudioTrack 已初始化） | `-gpu host` 在本机不出图 | 换 `-gpu swiftshader_indirect` 立刻出画 |
| 冷启动长时间纯黑 | ① 播放页窗口背景是纯黑 ② 开屏层原来挂在 `onPlaying`，那一刻画面还是黑的 | ① 加 `Theme.Litv.Splash` 与全屏开屏层 ② 改到 `onFirstFrame` 才撤，并让文案跟阶段走 |

## 八、没验证的部分

| 项 | 为什么没验 | 怎么补 |
|---|---|---|
| API 29+ 行为 | 本机只有 API 28 镜像 | `sdkmanager` 装 35 的镜像另建 AVD |
| release 包 / R8 | 只编了 debug | `assembleRelease` 需要签名配置 |
| TV 全页焦点流 | 只走了绑定页与播放页 | 在频道浮层、设置弹层逐格走一遍 |
| 时移 / 录制 / 投屏 | 需要在模拟器里逐条点 | 同一套设备，逐功能验证 |
| 长时间播放 / 断网恢复 | 未做 | 播放 30 分钟观察内存与重连 |

**已验的部分覆盖「装 → 起 → 绑 → 播」，但没覆盖时移、录制、投屏、异常恢复。**

## 九、一个容易误判的现象

跑 TV 镜像时 `logcat` 会出现 `com.android.tv.dvr` 的 `FATAL EXCEPTION`
（`SQLiteException: no such column: failed_reason`）。那是 **Android TV 系统自带的 DVR 应用**
在 API 28 镜像上的已知问题，与本项目无关。判断我们的 App 有没有崩必须按包名过滤：

```bash
adb logcat -d | grep -iE "com\.litv" | grep -iE "FATAL|Exception"
```

## 十、复现

```bash
export JAVA_HOME="<JDK 17>" ANDROID_HOME="<SDK>"
cd android && gradle --no-daemon assembleTvDebug assemblePhoneDebug

$ANDROID_HOME/emulator/emulator -avd litvtv -accel on -no-window -no-audio \
  -no-boot-anim -gpu swiftshader_indirect -no-snapshot -port 5556 &
$ANDROID_HOME/platform-tools/adb -s emulator-5556 install -r -t \
  app/build/outputs/apk/tv/debug/app-tv-x86-debug.apk
$ANDROID_HOME/platform-tools/adb -s emulator-5556 shell am start -W \
  -n com.litv.tv.debug/com.litv.tv.ui.LauncherActivity
```

截图见 [`docs/images/client/`](../images/client/)。
