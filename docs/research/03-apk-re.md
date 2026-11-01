# 03 · IPTV 播放器 APK 逆向分析报告

> 目的：为我方自研「Android TV + 手机端」客户端提供参照——**别人的交互逻辑 / UI 结构 / 播放器解码配置 / 接口协议**。
> 性质：**纯静态分析**。未运行任何 APK，未联网抓取任何人的源地址，未绕过任何版权保护。
> 分析日期：2026-11-01

---

## 0. 分析方法与工具链

| 项 | 说明 |
| --- | --- |
| 环境 | WSL2 · Ubuntu 26.04.1 LTS |
| 权限 | **无 sudo**（`sudo -n` 要求交互式认证）。全部工具装在 `~/tools` 与 `dpkg-deb -x` 解包的本地 sysroot 中 |
| JDK | `openjdk-21-jdk-headless` + `jre-headless` 的 `.deb` 用 `dpkg-deb -x` 解包到 `~/tools/jdk`，并把 `conf/security/*` 的绝对符号链接替换为实体文件（否则 `java` 报 `Error loading java.security file`） |
| apktool | Debian 包 `apktool 2.7.0`，需补 `lib*-java` 依赖包让 `../java/*.jar` 符号链接生效。**该版本 `d` 子命令只接受 `-o <dir>`，不接受位置参数输出目录** |
| aapt | `aapt 0.2-debian` + `android-libaapt/libandroidfw/libbase/libutils/liblog/libziparchive/libbacktrace/libcutils` + `7zip`（提供 `7z.so`），用 `LD_LIBRARY_PATH` 指向解包出的 sysroot |
| jadx | **未使用**。GitHub Release 下载被限速（~52 KB/s，72 MB 包在 900 s 内只到 47 MB，zip 截断）。因此 Java 层阅读改用 **smali + 资源 + native 字符串** 三路交叉验证，结论均标注了证据来源 |
| 辅助 | `pyaxmlparser` / `androguard 4.1.4`（仅用于交叉校验，最终以 apktool 产物为准） |

复现脚本见 `build/apk-re/_scripts/`：

| 脚本 | 作用 |
| --- | --- |
| `decode_all.sh` | 5 个 APK 全量 apktool 反编译到 `/tmp/apkwork/<app>/apktool_out` |
| `dump_manifest.py` | 结构化输出包名/版本/SDK/权限/组件/uses-feature/meta-data |
| `layout_tree.py` | 把 layout XML 打印成缩进树（含 `nextFocus*`、`focusable`、`id`） |
| `unesc.py` | 还原 smali 里的 `\uXXXX` 转义字符串（多家把中文写成转义） |
| `grep_player.sh` | 跨 5 个 APK 扫播放器内核关键字（hwdec/mediacodec/LoadControl/…） |
| `survey.sh` | 统计 smali 包结构与 `res/layout` 清单 |
| `collect.sh` | 把「够用」的产物收敛进 `build/apk-re/<app>/`（manifest / layout / xml / so 清单 / assets 清单） |

**结论可信度约定**：本报告中「播放器参数」一节给出的键值均直接来自 smali 常量或 assets 配置文件；标注「推断」的条目为间接证据。未能还原的项一律显式记录（见 §8 附表），不做猜测性补全。

### 0.1 与同系列文档的呼应

[02-stack-and-oss.md](./02-stack-and-oss.md) 给出的 Android 内核选型是「**Media3(ExoPlayer) 为主 + 可选 libmpv 兜底**」。本次逆向**在真实产品里找到了该组合的三个实现样本**，可作为该结论的实证补充：

| 02 篇的建议 | 本次在 APK 中找到的对应实现 |
| --- | --- |
| ExoPlayer 为主 | TVBox（Exo2 可选）、酷9（media3 EXO 硬/软两档）、mediaplayer（media3，`core≠3` 时默认） |
| libmpv 兜底 | mediaplayer（`libmpv.so`，`core==3`）、酷9（`libmpv.so` + `libmpvplayer.so`） |
| 两者共存的工程形态 | mediaplayer 的 **`PlayerHelper` 接口 + 双后端**（§1.3）、XPlayer 的 **`*_backend` 三实现**（§5.3） |

即：**「接口 + 多后端」不是理论设计，而是这三家的既有做法**；差别只在「怎么切」（mediaplayer 按 `core` 枚举 / 酷9 按 8 档 `PLAY_TYPE` / XPlayer 按布尔开关）。

---

## 1. mediaplayer v1.1.25（`com.mediaplayer.app`）

> 定位：**mpv-android 血统的「服务器托管型」直播客户端**。5 个 APK 里唯一以 mpv 为主内核、并把配置权交给服务端下发的。

### 1.1 基本信息

| 项 | 值 |
| --- | --- |
| 包名 | `com.mediaplayer.app`（`<application>` 上残留 `package="is.xyz.mpv"`，确证 fork 自 mpv-android） |
| 版本 | versionName `1.1.25` / versionCode `125` |
| SDK | minSdk **23** / targetSdk **34** / compileSdk **36** |
| ABI | 仅 `armeabi-v7a` |
| APK 体积 | 30.5 MB（其中 `assets/subfont.ttf` 6.3 MB、`classes.dex` 7.1 MB） |
| 加固/混淆 | **无加固**。类名语义完整（`ui.home.MainActivity`、`ui.player.PlayerActivity`、`data.api.ApiService`…），仅少量混淆（`j3/*` 为播放器适配层） |
| 应用类 | `com.mediaplayer.app.MediaPlayerApp`，`largeHeap=true`、`usesCleartextTraffic=true` |

### 1.2 清单与入口

**uses-feature**
- `android.software.leanback` `required=false`
- `android.hardware.touchscreen` `required=false`

**权限**（关注点已标粗）
`INTERNET`、`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`、`CHANGE_WIFI_MULTICAST_STATE`（组播/IGMP 用）、**`FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK`**、**`WAKE_LOCK`**、**`RECEIVE_BOOT_COMPLETED`**、**`REQUEST_INSTALL_PACKAGES`**、`WRITE_EXTERNAL_STORAGE(maxSdk=28)`、私有 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`。

**组件**
| 组件 | 说明 |
| --- | --- |
| `ui.home.MainActivity` | **同时挂 `LAUNCHER` 与 `LEANBACK_LAUNCHER`**；`configChanges` 覆盖 orientation/screenSize；`android:banner="@drawable/banner"` |
| `ui.player.PlayerActivity` | `screenOrientation=unspecified`，独立播放页（点播/回看用） |
| `ui.splash.SplashMediaActivity` | 启动图（服务端下发 `startupMedia`） |
| `service.PlaybackService` | 前台播放服务（`exported=false`） |
| `receiver.BootReceiver` | `BOOT_COMPLETED`，priority=1000 → 开机自启 |
| `androidx.core.content.FileProvider` | 配合 `REQUEST_INSTALL_PACKAGES` 做 APK 自更新 |

**设计要点**：`MainActivity` 既是「首页」也是「播放页」——**单 Activity 全屏 + 侧边抽屉**，而不是 TVBox 那种多 Activity 栈。TV/手机入口是同一个 Activity，靠 `isTvMode` 布尔分流按键语义。

### 1.3 播放内核

`lib/armeabi-v7a/`（16 个 `.so`）：

```
libmpv.so                  ← 主内核：mpv（JNI 类 is.xyz.mpv.MPV / BaseMPVView）
libplayer.so               ← 播放器封装层
libffmpegJNI.so            ← FFmpeg JNI
libavcodec/avformat/avutil/avfilter/avdevice/swresample/swscale.so
libAVS3AudioDec.so         ← AVS3 音频解码（国产标准）
libav3aJNI.so
libav3a_binaural_render.so ← AV3A 双耳渲染
libgojni.so                ← Go 编译产物（疑似代理/本地服务）
libxml2.so, libc++_shared.so
```

**判定：mpv（`libmpv.so`）为直播主内核，同时内置 media3/ExoPlayer 作为第二内核。**

两套内核由 `PlayerActivity.initPlayerWithCore(int core)` 选择，共用同一接口 `j3.q`（`PlayerHelper` 抽象：`b(url,ua,headers,pos,contentType,streamType,channel)` / `pause()` / `stop()` / `release()` / `getDuration()` / `isPlaying()` / `seekTo()` / `setSpeed()` …）：

| core 值 | 实现类 | 内核 |
| --- | --- | --- |
| `3` | `j3.s` | **mpv** |
| `1`、其它 | `j3.l` | **ExoPlayer（androidx.media3）** |

> 可借鉴：**「一个 PlayerHelper 接口 + N 个后端」** 是本项目最干净的架构点。设置项叫「播放内核：智能切换/…」，即按流类型自动挑后端。

### 1.4 播放器参数（核心，逐条来自 `j3/s.smali` / `j3/l.smali`）

#### A. mpv 参数（`MPV.setOptionString`，播放前一次性下发）

| 选项 | 值 | 备注 |
| --- | --- | --- |
| `hwdec` | `mediacodec,mediacodec-copy,auto` | **当且仅当 `decoder_mode ∈ {0, 3}`**（`if-eq j,3 → 开` / `j==0 → 开`，其余走 `hwdec=no`） |
| `hwdec` | `no` | `decoder_mode ∈ {1, 2}` |
| `hwdec-codecs` | `all` | 硬解时全编解码器尝试 |
| `profile` | `fast` | 低延迟档 |
| `vo` | `gpu` | |
| `gpu-context` | `android` | |
| `opengl-es` | `yes` | |
| `ao` | `audiotrack,opensles` | |
| `audio-set-media-role` | `yes` | 音频焦点/媒体角色 |
| **`video-latency-hacks`** | `yes` | **直播低延迟关键** |
| `vd-lavc-threads` | `0` | 0 = 自动 |
| `deinterlace` | `yes` | 隔行源默认开 |
| `video-sync` | `audio` | 音画同步以音频为基准 |
| `framedrop` | `decoder+vo` | 卡顿优先丢帧 |
| `tls-verify` | `no` | 直播源证书普遍不规范 |
| `cache` | `yes` | |
| `cache-pause` | 直播 `yes` / 点播 `no` | |
| `demuxer-max-bytes` | 见下表 | |
| `demuxer-max-back-bytes` | `max(前向/4, 4 MB)` | |
| `demuxer-lavf-o` | `probesize=1500000,analyzeduration=1500000` | **仅直播**（加快起播） |
| `demuxer-readahead-secs` | `max(network_cache_ms/1000, 1)`；未设时直播 `5`、点播 `20` | |
| `start` | 起播秒数，否则 `none` | 断点续播 |
| `rtsp-transport` | `tcp` | RTSP 强制 TCP |
| `network-timeout` | `15` | |
| `http-header-fields` | 自定义头 | |
| `user-agent` | `Mozilla/5.0 (Linux; Android 10; TV) AppleWebKit/537.36 MediaPlayer-TV/1.0.0` | |
| `proxy` | 仅当 `proxyType=="socks5"` 且 `proxyUrl` 非空 | |

> `decoder_mode` 是 `SharedPreferences` 的 int，取值域 **0–3**（默认 `0`，设置项默认文案「自动识别」）。按 `hwdec` 的开关条件推断：**`0` = 自动识别、`3` = 硬解，`1`/`2` = 两个软解档**（具体标签文案未在资源中确认）。同一 `player_core` 取值域为 **0–3**（默认 `0`），越界会被强制回写为 `0`。

**`demuxer-max-bytes` 取值表**（`network_cache_ms/1000` → 缓存秒数；`live` = 流类型为直播）：

| 缓存秒数 | 直播 | 点播 |
| --- | --- | --- |
| `≤ 0`（未设） | 16 MB | 64 MB |
| 1–2 s | 32 MB | 64 MB |
| 3–4 s | 64 MB | 128 MB |
| 5–9 s | 128 MB | 256 MB |
| ≥ 10 s | 256 MB | 256 MB |

#### B. ExoPlayer 参数（`DefaultLoadControl.Builder`）

```java
new DefaultLoadControl.Builder()
  .setTargetBufferBytes( min(0xFA00000 /*250MB*/, maxBufferMs / 3) )
  .setBackBuffer(0, true)                        // 直播不回退缓冲
  .setPrioritizeTimeOverSizeThresholds(false)    // 优先按字节封顶
```

`setBufferDurationsMs(minBufferMs, maxBufferMs, bufferForPlaybackMs, bufferForPlaybackAfterRebufferMs)` 三档：

| 场景 | min | max | 起播 | 重缓冲后起播 |
| --- | --- | --- | --- | --- |
| **直播**（`cache≤0` 且 live） | 1000 | 60000 | **500** | **1000** |
| **点播**（`cache≤0` 且非 live） | 5000 | 60000 | 1000 | 2000 |
| 自定义缓存（`cache>0`） | `max(2×maxBuf, 60000)` | `maxBuf` | `min(cache,1000)` | `min(cache,2000)` |

另：`MediaItem.Builder().setLiveConfiguration(...)` 用于直播窗口；`SimpleCache` 静态实例（`j3.l.H`）做磁盘缓存。

> **可借鉴**：直播起播阈值给到 **500 ms**、重缓冲 1000 ms，配合 mpv 的 `video-latency-hacks=yes` + `probesize=1.5 MB`，是「IPTV 秒开」的一组实测参数。

### 1.5 数据模型与接口协议（这是本项目最有价值的协议样本）

**架构：瘦客户端 + 自建服务端**。默认服务器 `http://0.0.0.0:9527`（用户可改），全链路带 `Authorization` 头。

`data/api/ApiService` 中的端点（`retrofit2` + `okhttp3`）：

| 端点 | 用途 |
| --- | --- |
| `client/register` | 设备注册 → `accessToken` |
| `client/verify` | 校验/续期 → `VerifyResponse` |
| `client/me` | 账号信息（套餐、到期） |
| `client/playing_status` | 上报播放状态（含 `session_id`、`speed_bytes`） |
| `client/logs` | 日志回传（`enableLog` 开关） |
| `channels`、`channels/{id}` | 频道列表/详情（`group_id`、`page`、`page_size`） |
| `groups` | 分组 |
| `epg` | 节目单 |
| `history` | 观看历史 |
| `search` | 搜索 |
| `update` | 版本更新 |
| `admin/login` | 管理端 |

**数据模型字段**（`data/model/*`，可作为我们后端 schema 的对照）：

- `Channel`：`id, name, logo, groupId, lines[], description, isDirect, proxyType, proxyUrl, catchupType, catchupSource, catchupDays, currentEpg, nextEpg, epgPercent, globalIndex`
- `ChannelLine`：`id, streamUrl, streamType, contentType, userAgent, customHeaders, supportCatchup, catchupDays`
- `ChannelGroup`：`id, name, icon, sortOrder, createdAt, updatedAt`
- `EPGProgram`：`id, channelId, title, description, startTime, endTime`
- `PageResponse<T>`：`items, page, pageSize, total`
- `AppUpdateConfig`：`version_code, version_name, update_log, download_url, force_update`
- `ServerEntry`：`name, url`（多服务器 + `backupServers` 容灾）

**服务端远程配置 `RemoteClientConfig`**（关键设计：**UI 与播放行为可由服务端远程改**）：
`decoderMode, playerCore, networkCacheMs, scaleMode, controlScheme, dnsPolicy, audioPassthrough, audioNormalizer, enablePip, gestureVolume, gestureBrightness, autoStart, autoCheckUpdate, globalProgressBar, appLanguage, preferredServerIndex, reverseChannelKeys, localProxyEnabled, showChannelLogo, showGroupSource, stopPreviousMedia, timeShowMode, hiddenKeys[], hideEpgPanel, hideOsdPanel, hideSettingsPanel, hideChannelList, hideQrConfig, hideCommunity`

`ClientRegisterResp` / `VerifyResponse` 还带：`announcement + announcementInterval`（公告轮播）、`startupMedia/startupMediaType/startupDuration/startupSkipAfter/startupMediaEnabled`（开机视频）、`globalMaintenance`（全局维护）、`isTester`、`planName`、`expiresAt`、`enableLog`。

**明文地址**：仅 `http://0.0.0.0:9527`（占位默认值，非真实源）。

### 1.6 UI 页面清单与层级

`res/layout` 共 216 个（含 androidx/leanback/exo 库布局）。**自有布局仅 10 个左右**：

| 布局 | 页面 |
| --- | --- |
| `activity_main.xml` | **主页 = 播放页**（全屏视频 + 5 个侧边抽屉 + 授权等待页） |
| `activity_player.xml` | 独立播放页（点播） |
| `activity_settings.xml` | 设置 |
| `activity_splash_media.xml` | 开机媒体 |
| `item_channel.xml` / `item_group.xml` / `item_epg.xml` | 列表项 |
| `dialog_cast.xml` / `dialog_exit.xml` / `dialog_disclaimer.xml` / `custom_dialog.xml` | 弹窗 |
| `image_frame.xml` | 台标容器 |

**`activity_main.xml` 层级（精简）**：

```
FrameLayout(black)
├─ FrameLayout @id/videoLayout                 ← 视频层（mpv/exo 挂这里）
├─ ProgressBar @id/globalProgressBar (2dip)    ← 顶部全局进度（EPG 进度）
├─ ProgressBar @id/progressBuffering (64dp)
├─ LinearLayout @id/layoutAnnouncement         ← 公告条 #b3000000
├─ OsdOverlayView @id/osdOverlayView           ← 自定义 OSD 绘制层
├─ ConstraintLayout @id/layoutZappingMenu      ← 【换台抽屉】
│   ├─ RecyclerView @id/rvGroups  (dimen/zapping_groups_width)
│   ├─ View divider (1dip)
│   ├─ RecyclerView @id/rvChannels
│   ├─ ProgressBar progressLoading / TextView tvEmptyText
├─ ConstraintLayout @id/layoutEpgMenu          ← 【EPG 抽屉】
│   ├─ TextView tvEpgMenuTitle
│   └─ RecyclerView rvEpgList (+loading/empty)
├─ FrameLayout @id/layoutLineMenu              ← 【线路切换】遮罩 #99000000 + containerLines(ScrollView)
├─ FrameLayout @id/layoutTrackMenu             ← 【音轨切换】containerTracks
├─ ScrollView @id/layoutAuthWaiting            ← 授权等待 + 二维码配置
│   └─ ImageView ivAuthQrCode / tvAuthQrConfigHint
└─ ScrollView @id/layoutSettingsMenu           ← 【设置抽屉】分组卡片式
    └─ LinearLayout 卡片：btnSettingsScale → btnSettingsMemory → btnSettingsDecoder
       → btnSettingsCore → btnSettingsAudioPassthrough → btnSettingsAudioNormalizer
       → btnSettingsStopPrevious …
```

**TV 焦点特征**：设置项每一行都是 `focusable=true` + **显式 `nextFocusDown`/`nextFocusUp` 串联**（`btnSettingsScale → btnSettingsMemory → btnSettingsDecoder → …`），`background=@drawable/selector_channel_item` 提供焦点态。抽屉用 `@dimen/menu_side_width`、`@dimen/menu_zapping_width` 统一宽度。

**`activity_player.xml`** 的播放页信息层（可作为「播放页 UI 元素」清单）：
`tvChannelName`（频道名）+ `tvStreamType`（流类型徽标）+ `btnCast`（投屏，带 `nextFocusLeft/nextFocusDown`）+ `tvStatus` + `tvResolution` + `layoutEpg`（`tvEpgNow` / `tvEpgNext`）+ `layoutRemoteHint`（遥控器提示条）+ `layoutGestureHint`（手势说明）+ `layoutVolumeIndicator` / `layoutBrightnessIndicator`（竖条）+ `tvSpeedIndicator`（倍速）。

### 1.7 交互逻辑（`PlayerActivity.onKeyDown`，逐分支还原）

先分流：`isTvMode == false` 走手机分支，`true` 走 TV 分支。

**TV 分支**

| 按键 | keyCode | 行为 |
| --- | --- | --- |
| `KEYCODE_CHANNEL_UP` | 92 | `nextChannel()` |
| `KEYCODE_DPAD_RIGHT` | 22 | `nextChannel()` |
| `KEYCODE_CHANNEL_DOWN` | 93 | `prevChannel()` |
| `KEYCODE_DPAD_LEFT` | 21 | `prevChannel()` |
| `KEYCODE_DPAD_CENTER` / `KEYCODE_ENTER` | 23 / 66 | `toggleChannelInfo()`（呼出/收起信息条） |
| `KEYCODE_DPAD_UP` | 19 | 音量 +1（`AudioManager.adjustStreamVolume(STREAM_MUSIC,+1)`）+ 音量条 |
| `KEYCODE_DPAD_DOWN` | 20 | 音量 −1 |
| `KEYCODE_MEDIA_PLAY_PAUSE` | 85 | 暂停/继续 |
| `KEYCODE_MEDIA_STOP` | 86 | `finish()` |
| `KEYCODE_BACK` | 4 | **2 秒内双击退出**（`backPressedTime + 2000 > now` → `saveProgress()` + `finish()`，否则 Toast 提示） |
| `KEYCODE_0..9` | 7–16 | **直接吞掉（return true，无动作）** —— 未实现数字跳台 |
| 166 / 167 | 0xa6/0xa7 | 与 CH+/CH− 同义（部分机顶盒固件把换台键映射到这一组） |

`reverse_channel_keys`（SharedPreferences `mediaplayer_prefs`，默认 `false`）的精确语义（`if-eqz` 分支已逐一核对）：

| 键 | `reverse=false`（默认） | `reverse=true` |
| --- | --- | --- |
| `CHANNEL_UP`(92) / `DPAD_RIGHT`(22) / 166 | `nextChannel()` | `prevChannel()` |
| `CHANNEL_DOWN`(93) / `DPAD_LEFT`(21) / 167 | `prevChannel()` | `nextChannel()` |

> ⚠️ 注意：**默认档下「CH−/左键」走的是 `prevChannel()`**（与常见「下键=下一个频道」相反）。这是代码事实（`cond_18 → if-eqz reverse → cond_19 → prevChannel`），我们自研时需自行决定默认方向。

**手机分支**：`DPAD_UP/DOWN` → 音量；`BACK` → 2 秒双击退出。手势在 `setupGestures()`（单击显隐信息、上下滑音量、左右滑亮度）。

**主页 `MainActivity`**：`dispatchKeyEvent` + `onKeyDown` + `onKeyUp` 三处联动；`MENU(82)` / `165` 打开设置抽屉；DPAD 在「换台抽屉 / EPG 抽屉 / 设置抽屉」之间切焦点；`onKeyUp` 用于区分长按与短按。

**播放容错文案（等于行为规格书）**，来自 `strings.xml`：

- `当前频道失效，自动为您跳过`
- `当前线路失效，自动切换线路 %s...`
- `多个频道连续播放失败，已停止自动换台`
- `检测到画面卡死，正在尝试恢复...`
- `网络连接超时，正在尝试切换线路...`
- `播放失败，%s 秒后自动重试...` / `尝试使用 %s 重试该线路...`
- `当前播放内核(%s)无法播放此频道，请在设置中切换为智能模式`

### 1.8 亮点与可借鉴点

1. **PlayerHelper 接口 + 双后端（mpv / Exo）**，运行时可切、可按流自动挑。
2. **服务端下发 UI 与播放策略**（`RemoteClientConfig` 的 `hiddenKeys`/`hide*Panel`/`decoderMode`/`networkCacheMs`）——一套 APK 服务多种客户，不用发版。
3. **「秒开」参数组合**已实测：mpv `video-latency-hacks=yes` + `probesize/analyzeduration=1.5s` + Exo 直播起播 500 ms。
4. **缓存大小按「缓存秒数 → MB」映射表**，而不是暴露裸字节数——用户可理解。
5. **手机扫码配置服务器**（`setupQrConfigServer`，本地起 HTTP 服务 + 二维码），解决 TV 上输地址的痛苦。
6. 状态机式容错：失效跳过 → 换线路 → 重试倒计时 → 连续失败停手，文案与行为一一对应。
7. 内置 **AVS3/AV3A**（国产音视频标准）解码库。

### 1.9 局限

- 未实现数字键跳台（数字键被吞掉）。
- 单 Activity 承载过多职责，`MainActivity` 逻辑极重（`onKeyDown` 单方法数百行）。
- 强依赖自建后端；离线/直连模式（`settings_local_source_mode`）能力较弱。

---

## 2. TVBox（`com.github.tvbox.osc.jun`，20260914-1520）

> 定位：**点播+直播一体的「爬虫/接口聚合」客户端**，TVBox 生态的一个分支。核心逻辑被 ProGuard 整体搬到 `androidx/base/*` 并改名，但**配置协议完全可读**。

### 2.1 基本信息

| 项 | 值 |
| --- | --- |
| 包名 | `com.github.tvbox.osc.jun` |
| 版本 | versionName `20260914-1520` / versionCode `1` |
| SDK | minSdk **21** / targetSdk **28** / compileSdk **33** |
| ABI | 仅 `arm64-v8a` |
| APK 体积 | 20.8 MB（`classes.dex` 9.35 MB + `classes2.dex` 3.05 MB） |
| 混淆 | **重度 ProGuard**：TVBox 原 `com.github.tvbox.osc.bean/api/server` 等被搬进 `androidx/base/*`（`j0`/`ge0`/`gq`/`px`/`hq`…），中文串以 `\uXXXX` 转义存放 |
| 应用类 | `com.github.tvbox.osc.base.App`，`largeHeap`、`networkSecurityConfig`、`autosize`（`design_width_in_dp=1280`, `design_height_in_dp=720`） |

### 2.2 清单与入口

**uses-feature**：`android.software.leanback required=false`、`android.hardware.touchscreen required=false`
**权限**：`INTERNET`、`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`、`CHANGE_WIFI_MULTICAST_STATE`、`READ/WRITE_EXTERNAL_STORAGE`、`ACCESS_FINE_LOCATION`、**`REQUEST_INSTALL_PACKAGES`**、**`FOREGROUND_SERVICE`**、`READ_PHONE_STATE`、**`WAKE_LOCK`**、`GET_TASKS`、`MOUNT_UNMOUNT_FILESYSTEMS`

**Activity（全部 `screenOrientation=sensorLandscape`）**

| Activity | 说明 |
| --- | --- |
| `HomeActivity` | **`LAUNCHER` + `LEANBACK_LAUNCHER`**，首页（点播分类/推荐） |
| `LivePlayActivity` | 直播播放页（TV 交互主战场） |
| `DetailActivity` | 详情/选集 |
| `SettingActivity` | 设置 |
| `SearchActivity` / `FastSearchActivity` | 搜索 / 快速搜索 |
| `HistoryActivity` / `CollectActivity` | 历史 / 收藏 |
| `PushActivity` | 推送（`PushReceiver`，`android.content.movie.push.Action`） |
| `LocalFileActivity` | 本地文件（`com.obsez` filechooser） |

**Service**：`dlna.DLNACastService`（`org.fourthline.cling` 全家桶）、`player.MusicPlaybackService`、`androidx.room.MultiInstanceInvalidationService`
**Receiver**：`SearchReceiver`、`PushReceiver`、`CustomWebReceiver`（`android.content.movie.custom.web.Action`）——三个都是**外部 App/浏览器可通过广播驱动**的入口。
**meta-data**：`design_width_in_dp=1280`、`design_height_in_dp=720`（`AutoSize` 按 1280×720 设计稿缩放，TV 适配关键）、`xwalk_enable_download_mode`、`xwalk_verify`

### 2.3 播放内核

`lib/arm64-v8a/`（11 个 `.so`）：

```
libijkffmpeg.so  libijksdl.so  libplayer.so   ← ijkplayer（B 站 ffmpeg 分支）
libffmpegJNI.so                                ← 自建 FFmpeg JNI
libconceal.so                                  ← 文件加解密
libcronet.101.0.4951.41.so                     ← Chromium Cronet 网络栈
libp2p.so                                      ← P2P 加速
libquickjs-android-wrapper.so                  ← QuickJS（JS 爬虫引擎）
librtmp-jni.so
libxl_stat.so  libxl_thunder_sdk.so            ← 迅雷下载 SDK
```

Java 侧同时存在 `com.google.android.exoplayer2`（Exo2）与 `xyz.doikki.videoplayer`（dkplayer 壳），`tv/danmaku/ijk` 62 个类。

**判定：ijkplayer 为主内核 + ExoPlayer2 可选；WebView 用 XWalk/Chromium；爬虫用 QuickJS。** `playerType` 取值直接是中文：**`"ijk硬解"` / `"ijk软解"` / `"exo"`**。

### 2.4 播放器参数

**IJK 侧**（`androidx/base/gq.smali`，`IjkMediaPlayer.setOption`）关键键：

| 选项 | 作用 |
| --- | --- |
| `protocol_whitelist` | 允许 `ijkio,ffio,async,cache,crypto,file,dash,http,https,ijkhttphook,ijkinject,ijklivehook,ijklongurl,ijksegment,ijktcphook,pipe,rtp,tcp,tls,udp,ijkurlhook,data` |
| `prefer_tcp` / `rtsp_transport=tcp` | RTSP 走 TCP |
| `infbuf` | 无限缓冲（点播用） |
| `analyzeduration` / `probesize` | 起播探测 |
| `max-fps` / `min-frames` | 帧率上限/最少帧 |
| `threads` | 解码线程 |
| `dns_cache_timeout` / `dns_cache_clear` | DNS 缓存 |
| `player_is_live` | 直播标记（决定缓冲策略） |
| **本地缓存** | `ijkio:cache:ffio:` + `cache_file_path=/ijkcaches/` + `max_cached_duration` + `cache_max_capacity` |
| `flush_packets` / `auto_save_map` / `parse_cache_map` | 边播边存 |

**Exo2 侧**：`DefaultLoadControl`（5 处引用），未见自定义 `setBufferDurationsMs` 常量（被混淆，**推断**为默认值 + 少量调整）。

**其它播放相关**：`audio-spdif` 类直通、`AUTO`/`ijk_codec` 记忆、`?playseek=` / `/PLTV/` / `/TVOD/` 回看地址改写。

### 2.5 接口协议（TVBox 配置 JSON —— 生态事实标准）

`androidx/base/j0.smali` 中的字段名完整还原了配置 schema：

```jsonc
{
  "spider": "https://.../spider.jar;md5;xxxx",   // 或 .py / .js
  "jarCache": "0|1|2",
  "wallpaper": "...",
  "logo": "...",
  "sites": [{
    "key": "站点唯一键", "name": "显示名", "type": 0|1|3,
    "api": "csp_XXX 或 http://...", "ext": "...", "jar": "...",
    "searchable": 0|1|2, "quickSearch": 0|1, "filterable": 0|1,
    "changeable": 0|1, "playUrl": "", "playerType": "ijk硬解|ijk软解|exo",
    "categories": ["..."], "timeout": 30, "click": "...", "style": {"type":"rect|oval","ratio":1.0}
  }],
  "parses": [{ "name": "...", "type": 0|1|2|3, "url": "...", "ext": {...}, "SuperParse": ... }],
  "flags": ["..."],
  "lives": [{ "name": "直播源名", "url": "http://.../live.txt 或 .m3u",
              "api": "csp_Xxx 或 proxy://...", "ext": "...",
              "playerType": "ijk硬解|ijk软解|exo", "timeout": 30, "click": "...", "style": {...} }],
  "hosts": ["a.com=b.com"],
  "rules": [{ "host": "...", "rule": "...", "filter": ["..."], "regex": ["..."], "script": "..." }],
  "doh": [{ "name": "360", "url": "https://doh.360.cn/dns-query" }],
  "ads": ["..."],
  "home_api": "...", "live_api_url": "...", "api_url": "..."
}
```

**直播源（`lives[].url`）内容格式**：`#EXTM3U` + `#EXTINF` + `tvg-*` + `group-title` 的 m3u，或 `分组名,#genre#` 开头的 txt。
**单频道字段**：`group, name, logo, epg, click, format, origin, referer, parse`
**回看字段**：`catchup` / `catchup-source` / `catchup-replace`
**自定义头**：`header`（含 `User-Agent`）

**URL 协议族**：`http(s)://`、`rtsp://`、`rtmp://`、`proxy://`、`clan://`、`clan://localhost/`、`file://`、`file/`
**内置本地 HTTP 服务**（`fi.iki.elonen.NanoHTTPD`）：`http://127.0.0.1:9978/proxy?do=live&type=txt&ext=`、`/proxy?go=live&type=m3u8&url=`

**默认 EPG（DIYP 格式）**：`http://epg.51zmt.top:8000/api/diyp/?ch={name}&date={date}`
**默认 UA**：`Mozilla/5.0 ... Chrome/138.0.0.0 Safari/537.36`；`TVBox-Format`、`TVBox-Player`

**spider 模板（`assets/js/lib/模板.js`，15 KB）**：站点适配模板字典（`mxpro`、`mxone5` …），每模板含 `title/host/url/searchUrl/searchable/quickSearch/filterable/headers/class_parse/play_parse/lazy/limit/推荐/一级/二级/搜索`，选择器为 `选择器;选择器&&属性;...` 的 DSL。→ **这是「同一套代码适配 N 个站点」的规则驱动思路。**

### 2.6 UI 页面清单与层级

`res/layout` 共 157 个，自有布局约 40 个：

| 类别 | 布局 |
| --- | --- |
| 页面 | `activity_home`、`activity_live_play`、`activity_detail`、`activity_setting`、`activity_search`、`activity_fast_search`、`activity_history`、`activity_collect`、`activity_push`、`activity_local_file`、`activity_play` |
| 直播/播放 | `box_vod_control_view`、`dkplayer_layout_*`（prepare/error/complete/gesture/live_control/vod_control/standard_controller/title） |
| 弹窗 | `dialog_episode`、`dialog_select`、`dialog_live_password`、`dialog_remote`、`dialog_danmu_api`、`dialog_danmu_setting`、`dialog_search_danmu`、`dialog_subtitle`、`dialog_search_subtitle`、`dialog_api`、`dialog_api_history`、`dialog_backup`、`dialog_xwalk`、`dialog_cast`、`dialog_desc`、`dialog_grid_filter`、`dialog_quick_search`、`dialog_checkbox_search`、`dialog_confirm`、`dialog_tip` |

**`activity_live_play.xml` 层级（TV 直播页，最有参考价值）**：

```
FrameLayout @id/live_root
├─ VideoView @id/mVideoView                         ← dkplayer 播放视图
├─ FrameLayout @id/switchChannelSnapshotOverlay     ← 【换台瞬时快照】盖住黑屏
│   ├─ ImageView switchChannelSnapshotImage (fitCenter, black bg)
│   └─ ProgressBar (45dip)
├─ TextView @id/tv_selected_channel                 ← 【数字跳台】输入的数字
├─ LinearLayout @id/tvLeftChannnelListLayout        ← 【左侧三级列表】
│   ├─ LinearLayout @id/ll_typeSelect
│   │   ├─ TvRecyclerView @id/mGroupGridView   (vs_220)   ← 一级：分组
│   │   ├─ [divLoadEpgleft]  tv_arrow0="更 多" / iv_arrow2 / tv_arrow3="节 目"
│   │   ├─ View (1dip 分隔)
│   │   ├─ TvRecyclerView @id/mChannelGridView (vs_300, focusable)  ← 二级：频道
│   │   ├─ LinearLayout @id/divLoadEpgDivider (1dip)
│   │   ├─ [divLoadEpg] tv_arrow="节 目" / iv_arrow / tv_arrow2="信 息"
│   │   └─ LinearLayout @id/divEPG  (vs_360)   ← 三级：EPG
│   │       ├─ TvRecyclerView @id/mEpgDateGridView (vs_100)  ← 日期
│   │       ├─ TvRecyclerView @id/lv_epg
│   │       └─ TextView @id/txtNoEpg "暂无节目信息…"
├─ LinearLayout @id/tvRightSettingLayout (vs_640)   ← 【右侧设置面板】
│   ├─ TvRecyclerView @id/mSettingItemView  (vs_420)
│   └─ TvRecyclerView @id/mSettingGroupView (vs_200)
├─ TextView @id/tvChannel                           ← 频道号徽标
├─ LinearLayout @id/tv_top_status                   ← 右上角：网速 + 时间
│   ├─ TextView tvNetSpeed / tvTime
├─ RelativeLayout @id/ll_epg  (vs_140)              ← 【底部信息条】
│   ├─ View @id/view_bg (shape_user_focus)
│   ├─ LinearLayout @id/channelinfo (vs_270)
│   │   ├─ TextView tv_channel_bottom_number
│   │   ├─ ImageView img_live_icon (vs_180×vs_60, centerInside) + live_icon_null_text
│   │   └─ MarqueeTextView tv_channel_bar_name
│   ├─ tv_current_program / tv_current_program_time / tv_current_program_name (Marquee)
│   ├─ View view_line (1px)
│   ├─ tv_next_program / tv_next_program_time / tv_next_program_name (Marquee)
│   └─ RelativeLayout @id/live_status_info (vs_220)
│       ├─ TextView tv_source "源 1/1"
│       └─ TextView tvResolution
├─ TextView tv_shownum / tv_showTime
└─ RelativeLayout @id/ll_right_top_huikan            ← 回看角标
```

**TV 焦点特征**：
- 全部用 `com.owen.tvrecyclerview.widget.TvRecyclerView`（自带焦点滚动/边界处理），尺寸统一走 `@dimen/vs_*`（`AutoSize` 缩放后的虚拟尺寸）。
- 长文本用 `MarqueeTextView`（跑马灯），`android:focusable` 由代码在面板显隐时切换。
- **不用** `nextFocusDown` 硬连线，而是**代码接管 `dispatchKeyEvent`** 做面板间焦点迁移（见下）。

### 2.7 交互逻辑（`LivePlayActivity.dispatchKeyEvent`，逐分支还原）

**ACTION_DOWN 且「频道列表可见」时：**

| 按键 | keyCode | 行为 |
| --- | --- | --- |
| `DPAD_RIGHT` | 22 | 焦点在分组列表 → 切到频道列表并按该分组加载频道；焦点已在频道列表 → `divLoadEpgRight()` **展开 EPG 面板** |
| `DPAD_LEFT` | 21 | 焦点在 EPG 列表 → `divLoadEpgLeft()` **收起 EPG** |
| `DPAD_RIGHT`（两个列表都无焦点） | 22 | 显示频道列表、隐藏 `W`/`V`、显示 EPG 区，并把分组/频道选中项同步回列表 |

**ACTION_DOWN 且「面板未展开」时：**

| 按键 | keyCode | 行为 |
| --- | --- | --- |
| `KEYCODE_MENU` | 82 | `A0()` → 打开设置面板 |
| （固件扩展键） | 165 / 259 | 同 MENU |
| `DPAD_UP` | 19 | `m0()` = **上一个频道**（`live_channel_reverse` 为真时改调 `j0()` 下一个） |
| `DPAD_DOWN` | 20 | `j0()` = **下一个频道**（`live_channel_reverse` 为真时改调 `m0()` 上一个） |
| `DPAD_LEFT` | 21 | `l0()` = **上一条线路**（`mt.m` 自减/环绕） |
| `DPAD_RIGHT` | 22 | `k0()` = **下一条线路**（`mt.m+1`，等于 `mt.n` 归零） |
| `DPAD_CENTER` | 23 | 不在此处理（交给 `onKeyUp`） |
| **数字键 `KEYCODE_0..9`** | 7–16 | `digit = keyCode - 7`；`A0 = A0*10 + digit`；显示 `tv_selected_channel`；**2500 ms**（`0x9c4`）后 `LivePlayActivity$a` 触发跳台 |
| **小键盘 `KEYCODE_NUMPAD_0..9`** | 144–153 | `digit = keyCode - 0x90`，同上 |

**ACTION_UP 且 `repeatCount == 0`：**

| 按键 | keyCode | 行为 |
| --- | --- | --- |
| `DPAD_CENTER` / `ENTER` / `MEDIA_PLAY_PAUSE` | 23 / 66 / 85 | `v0()` = 呼出/收起信息条 |

**长按菜单**：`onKeyDown` 里对 `DPAD_CENTER`/`ENTER`/`PLAY_PAUSE` 起 **800 ms** 延时 `LivePlayActivity$b`，`onKeyUp` 时 `removeCallbacks` —— 即**短按=信息条，长按=菜单**的标准实现（长按判定窗口 800 ms）。

**其它交互**：`switchChannelSnapshotOverlay` 在换台时先显示上一帧快照再起播（消除黑屏）；`live_cross_group` 允许跨分组换台；`live_connect_timeout` 换源超时 5–30 s。

### 2.8 明文接口地址（仅作格式参考）

| 地址 | 用途 |
| --- | --- |
| `http://epg.51zmt.top:8000/api/diyp/?ch={name}&date={date}` | **默认 EPG 兜底**（DIYP 格式） |
| `http://127.0.0.1:9978/proxy?do=live&type=txt&ext=` | 内置 NanoHTTPD 本地代理 |
| `http://127.0.0.1:9978/proxy?go=live&type=m3u8&url=` | 同上（m3u8 拉流） |
| `https://doh.360.cn/dns-query` | DoH 预设之一 |

> 这些是**格式/机制**参考，不含任何可用源；不要写进我们的产品。

### 2.9 亮点与可借鉴点

1. **`dispatchKeyEvent` 统一接管 + 面板焦点状态机**：比 `nextFocusDown` 硬连线更可控，适合「多面板 + 动态显隐」的 TV 布局。
2. **换台快照遮罩**（`switchChannelSnapshotOverlay`）：上一帧 → 新流，消除黑屏，成本极低。
3. **长按判定 800 ms / 数字跳台 2500 ms**：两个可直接照抄的交互常量。
4. **左侧三级联动列表**（分组 → 频道 → EPG 日期+节目）+ 右侧设置面板，一屏容纳全部直播操作。
5. **配置驱动一切**：`sites/parses/lives/hosts/rules/doh` 全在 JSON 里，客户端只做渲染与播放。
6. **`AutoSize` + `vs_*` 虚拟尺寸**：一套布局适配 720p/1080p/4K。
7. **外部广播入口**（`PushReceiver`/`CustomWebReceiver`）：手机/浏览器可通过广播把内容推到 TV。

### 2.10 局限

- 核心被 ProGuard 搬包改名，**播放参数常量未完全恢复**（Exo 侧仅有 `DefaultLoadControl` 引用，无显式数值）。
- 无「服务端远程配置」，一切靠用户改 JSON。
- 中文串写成 `\uXXXX`，可读性差（但可解）。
- 依赖 XWalk/Chromium 体积大（`libcronet` + `org.xwalk`）。

---

## 3. 配套_DIYP（`com.player.diyp2020`，v5.2.0）

> 定位：**纯直播（无点播）的运营商风格 IPTV 客户端**，内置 P2P 加速。**混淆最重的一个**。

### 3.1 基本信息

| 项 | 值 |
| --- | --- |
| 包名 | `com.player.diyp2020` |
| 版本 | versionName `5.2.0` / versionCode `21` |
| SDK | minSdk **17** / targetSdk **28** / compileSdk **28** |
| ABI | 仅 `armeabi-v7a` |
| APK 体积 | 17.2 MB |
| 加固/混淆 | **ProGuard + 资源混淆 + 控制流平坦化**。`res/layout/layoutXXXX.xml`、`@id/idXXXX`、`@drawable/drawXXXX`、`@style/styleXXXX` 全改名；smali 被拆成 `c/c/a`、`b/b/p`、`c/e/c` 等包，`PlayerActivity.onKeyDown`（30 030 行）被编译成 **`sparse-switch` on `String.hashCode()` 的状态机**，无法按分支还原按键语义 |
| 应用类 | `com.player.activity.MyApplication`，`SYSTEM_ALERT_WINDOW`（悬浮窗） |

### 3.2 清单与入口

**uses-feature**：**无**（未声明 leanback，也没声明 touchscreen 可选——TV 适配靠自身）
**权限**：`INTERNET`、`RECEIVE_BOOT_COMPLETED`、`READ/WRITE_EXTERNAL_STORAGE`、`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`、**`REQUEST_INSTALL_PACKAGES`**、**`SYSTEM_ALERT_WINDOW`**
> 注意：**没有** `WAKE_LOCK`、**没有** `FOREGROUND_SERVICE` —— 老架构（minSdk 17 时代）。

**组件**
| 组件 | 说明 |
| --- | --- |
| `com.player.activity.LoginActivity` | **唯一 `LAUNCHER`**，`sensorLandscape`（先登录/校验再进播放） |
| `com.hulytu.diypi.ui.PlayerActivity` | 播放页（`sensorLandscape`） |
| `com.tvbus.engine2333.TVService` | 电视总线 P2P 服务 |
| `com.gsoft.mitv.MainActivity`（声明为 **service**） | miTV 兼容层 |
| `com.forcetech.service.P2PService` … `P9PService` | **8 个独立进程 P2P 服务**（`process=:P2P` … `:P9P`） |
| `com.player.utils.BootReceiver` | 监听 **16 个** action（开机、网络变化、WiFi、蓝牙、电源、耳机、U 盘插拔…） |

**`BootReceiver` 的 action 全集**（说明它对「开机即播」的执念）：
`BOOT_COMPLETED`、`REBOOT`、`MEDIA_MOUNTED`、`MEDIA_UNMOUNTED`、`CONNECTIVITY_CHANGE`、`WIFI_STATE_CHANGED`、`USER_PRESENT`、`BLUETOOTH STATE_CHANGED`、`ACTION_POWER_CONNECTED/DISCONNECTED`、`AUDIO_BECOMING_NOISY`、`MEDIA_REMOVED`、`MEDIA_CHECKING`、`MEDIA_EJECT`

### 3.3 播放内核

`lib/armeabi-v7a/`（16 个 `.so`）+ `assets/libmitv.so`：

```
libijkplayer.so  libijkffmpeg.so  libijksdl.so   ← ijkplayer（主内核）
libplayer.so
libmpc_jni.so                                    ← 自研播放器 JNI
libp2p.so  libp2ptv.so  libp3p.so … libp9p.so    ← P2P 加速（7 个"pNp"）
libmitv.so                                       ← miTV SDK
libtvcore.so                                     ← TV 核心
assets/libmitv.so                                ← 同上的 assets 副本（运行时释放）
```

**判定：ijkplayer 为主 + 自研 `libmpc_jni` + 运营商级 P2P 加速。**

### 3.4 播放器参数

- IJK 相关引用 140 处（`IjkMediaPlayer` 13 处、`setOption` 6 处、`prepareAsync` 8 处），但**具体 option 名被资源/字符串混淆吞掉**，未能提取常量（诚实记录）。
- `mediacodec` 仅 1 处、`MediaCodec` 24 处 → 硬解走系统 MediaCodec。
- 播放视图：`com.player.views.IjkVideoView` + `com.player.views.GVideoPlayer`（自定义，叠加在 IjkVideoView 之上，**推断**为第二内核/软解兜底）。

### 3.5 数据模型与源格式

**播放列表格式（txt，事实标准之一）**：

```
央视频道,#genre#
CCTV-1,http://.../cctv1.m3u8
CCTV-2,http://.../cctv2.m3u8
卫视频道,#genre#
湖南卫视,http://.../hunan.m3u8
```

- 解析关键字：`#genre#`、`,#genre#`（分组行判定）
- 也支持 `.m3u`（`#EXTINF`）
- 编码：`utf-8` / `UTF-8` / GBK（`gbk.js` 同级能力在 TVBox；DIYP 侧按 BOM/探测）
- 数据字段：`id, name, channelIndex, category, category_name, channels`

**SharedPreferences 键**（`setting/MySettings`，`player` 命名空间）：
`CHANNEL_URL`、`CHANNEL_INDEX`、`CATEGORY_INDEX`、`XLIVE`、`EPG_URL`（默认 `http://egp.112114.com/epgapi`）、`APPOINTMENT`、`APPOINTMENTTS`（预约提醒）、`ExitSplashPng`、`列表风格`、`解码方式`、`省份选择`

**设置项清单**（从 `MySettings` 的 `\uXXXX` 串还原，即产品功能全集）：
`列表风格 / 传统列表`、`解码方式`、`画面比例`、`超时换源`、`线路选择`、`护眼模式`、`快速退出`、`记忆多源`、`换台反转`、`EPG功能`、`显示网速`、`开启时移`、`显示时间`、`回看列表`、`开机自启`、`跨选分类`、`开启传源`

> 这套词汇与酷9（第 4 节）几乎逐条对应（换台反转 / 跨选分组 / 记忆多源 / 超时换源 / 快速退出 / 护眼模式），说明**酷9 与 DIYP 同源或互相借鉴**。

**内置 Web 管理端**（`assets/index.html` + `bootstrap.min.css` + `bundle.js` 460 KB，标题「本地云平台」）：
REST 路径 `api/categories.json`、`api/category.json`、`api/delete.json`、`/script` —— 手机/PC 浏览器连本机端口即可增删频道分组，**免遥控器输入**。

### 3.6 UI 页面清单与层级

`res/layout` 64 个，全部 `layoutXXXX.xml`（0x0000–0x0040 + `layoutradio.xml` + `app_name.xml`）。按内容识别出的关键页面：

| 布局 | 识别依据 | 页面 |
| --- | --- | --- |
| `layout001d.xml` | 含 `IjkVideoView` + `GVideoPlayer` | **播放页** |
| `layout0000.xml` | 登录/启动 | LoginActivity |
| 其余 | 列表/弹窗/设置项 | 频道列表、分组、EPG、设置、弹窗 |

**播放页层级**（`layout001d.xml`，id 已混淆）：

```
FrameLayout @id0085 (black)
├─ IjkVideoView @id0098              ← ijk 渲染面
├─ GVideoPlayer @id00ce              ← 第二播放器叠加层
├─ FrameLayout @id0199 (gone)        ← 遮罩/缓冲层
├─ MarqueTextView @id0198 (gone)     ← 跑马灯提示
├─ LinearLayout @id00c5 (vertical)
│   ├─ TextClock @id0155             ← 时钟
│   └─ TextView @id0152              ← 频道号/状态
├─ TextView @id014d (gone)
├─ TextView @id0154                  ← 信息条
├─ TextView @id015b (gone, #99000000)
└─ include                           ← 列表/设置面板（另一个 layoutXXXX）
```

**焦点特征**：老式 `LinearLayout + 自定义 RecyclerView`（`com.player.views.RVListView`），无 `nextFocus*` 声明（**推断**靠代码 `requestFocus`）。

### 3.7 交互逻辑

**未能还原。** 原因（明确记录，不硬编）：

- `com.player.activity.PlayerActivity.onKeyDown` 编译为 **30 030 行**的控制流平坦化代码：外层是 `while(true){ sparse-switch(state) }`，`state` 是**字符串的 `hashCode()`**（如 `"\u06ec\u06e8\u06e5".hashCode()`），每个 case 执行片段后再算下一个 state 回到 `goto_0`；`sparse-switch` 的 key 全是 0x1a8xxx–0x1adxxx 区间的哈希值，**不含真实 keyCode**。
- 要恢复需做符号执行/去平坦化（如 angr + 定制规则），成本远超本次「够用」目标。

**可确认的间接结论**（来自设置项与字符串）：
- 存在 `换台反转`（对应 mediaplayer 的 `reverse_channel_keys`、TVBox 的 `live_channel_reverse`）——**三家都有**。
- 存在 `数字跳台` 的 UI 资源（`layout` 内有独立数字显示 TextView `@id014d`），**推断**支持数字键跳台。
- `快速退出`、`护眼模式`（画面锁定）、`开启时移`、`回看列表` 等行为开关存在。

### 3.8 明文接口地址（仅格式参考）

| 地址 | 用途 |
| --- | --- |
| `http://egp.112114.com/epgapi` | **默认 EPG（112114 格式）** |
| `http://epg.51zmt.top:8000/api/diyp/` | DIYP 格式 EPG（与 TVBox 同款） |
| `http://192.168.1.1/iptv/app/epg/tv_logo/` | 运营商网关台标路径模板 |
| `http://127.0.0.1:<port>` | 内置 Web 管理端 |

### 3.9 亮点与可借鉴点

1. **内置 Web 管理端**（浏览器配置频道）——TV 端输入难题的另一种解法，比 mediaplayer 的二维码更"重"但更灵活。
2. **P2P 加速 8 进程**（`:P2P`…`:P9P`）：运营商/多播场景下的省带宽方案（我们要不要抄见第 6 节）。
3. **`com.hulytu.diypi.plugin.*` 插件框架**（`BasePlayerActivitySettingsPlugin` / `BaseActivityEnhancePlugin` / `BaseEnhancePlugin`）+ `diyp2020.hotfix.*`（`LoginActivityHotfix`、`TelecastHotfix`、`Bugfix_cebD_V2`）——**运行期热修复 + 插件化设置项**，是很成熟的工程手法。
4. 设置项命名体系（换台反转/跨选分类/记忆多源/超时换源/护眼模式/快速退出）值得直接沿用为我们的配置项 ID。
5. 双播放器叠加（`IjkVideoView` + `GVideoPlayer`）做「切换内核不闪断」。

### 3.10 局限

- 混淆导致**按键映射与播放参数不可恢复**（本报告不做猜测）。
- minSdk 17 老架构，无前台服务/唤醒锁，长时间后台播放不可靠。
- 未声明 leanback，TV 上不会出现在 Leanback 启动器里。

---

## 4. 酷9（`com.player.ku9`，v2.2.21）

> 定位：**功能最全的「直播为主、TVBox 内核」客户端**。TVBox 的类名血统 + DIYP 的设置词汇 + 自研 mpv/EXO/IJK 三内核切换 + FCC/时移/多屏/网盘/ASR 字幕。**我们最应该精读的一个。**

### 4.1 基本信息

| 项 | 值 |
| --- | --- |
| 包名 | `com.player.ku9`（应用类仍是 `com.github.tvbox.osc.base.App`） |
| 版本 | versionName `2.2.21_20261018_2014` / versionCode `17` |
| SDK | minSdk **21** / targetSdk **28** / compileSdk **35** |
| ABI | 仅 `armeabi-v7a` |
| APK 体积 | 42.8 MB |
| 加固 | **NP Manager「控制流混淆 8.0」**（`assets/protected_by_np/ApkControlFlowConfusion_8.0_*.txt`，ProtectTime 2026-10-18）。**非整体加密**：`classes.dex` 仍可 baksmali，部分类名可读（`LoginActivity`、`LivePlayActivity`、`BootReceiver`），部分变成 `۟ۤ۟ۡۧ` 这类 Unicode 名；`LivePlayActivity` 膨胀到 **156 435 行**（平坦化） |
| 应用类 | `com.github.tvbox.osc.base.App`，`autosize`（`design_width_in_dp=960`, `design_height_in_dp=720`） |

### 4.2 清单与入口

**uses-feature**：`android.software.leanback required=false`、`android.hardware.touchscreen required=false`
**权限**：`INTERNET`、`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`、`READ/WRITE_EXTERNAL_STORAGE`、**`MANAGE_EXTERNAL_STORAGE`**、**`REQUEST_INSTALL_PACKAGES`**、**`RECEIVE_BOOT_COMPLETED`**、`ACCESS_FINE/COARSE_LOCATION`、`CHANGE_WIFI_MULTICAST_STATE`、**`WAKE_LOCK`**、**`SYSTEM_ALERT_WINDOW`**、**`FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SHORT_SERVICE`**、**`POST_NOTIFICATIONS`**、`MOUNT_UNMOUNT_FILESYSTEMS`

**组件**
| 组件 | 说明 |
| --- | --- |
| `com.github.tvbox.osc.ui.activity.LoginActivity` | **`LAUNCHER` + `LEANBACK_LAUNCHER`**，`sensorLandscape`（启动即登录/拉配置） |
| `...ui.activity.LivePlayActivity` | 直播播放页，`theme=@style/LiveActivityTheme` |
| `...ui.activity.CrashActivity` | 崩溃页 |
| `com.forcetech.service.P2PService`…`P9PService` | 8 个 P2P 进程（与 DIYP 同） |
| `com.gsoft.mitv.MainActivity`（service） | miTV 兼容 |
| `com.tvbus.engine.TVService` | TV 总线 |
| `com.tencent.smtt.export.external.DexClassLoaderProviderService`（`:dexopt`） | **X5/TBS 内核** |
| `com.android.cast.dlna.dmc.DLNACastService` / `dmr.DLNARendererService` | DLNA 投屏 **+ 被投屏（Renderer）** |
| `com.github.tvbox.osc.ui.tv.live.BootReceiver` | 监听 **17 个** action，含厂商私有：`LETV_SCREENON`、`com.duokan.duokanplayer.BOOT_COMPLETED`、`com.android.server.action.NETWORK_STATS_UPDATED`、`mitv.network.ethernet.action.ETHERNET_STATE_CHANGED`、`android.net.ethernet.ETH_STATE_CHANGED` |
| `...receiver.CustomWebReceiver` | `exported=true`，`android.content.movie.custom.web.Action`（**外部可驱动**） |

### 4.3 播放内核（三家内核并存）

`lib/armeabi-v7a/`（23 个 `.so`）：

```
libijkffmpeg.so  libijkplayer.so  libijksdl.so   ← IJK（硬解/软解两档）
libmpv.so  libmpvplayer.so                       ← MPV（libmpvplayer 导出 Java_is_xyz_mpv_MPV_create）
libvjplayer_jni.so                               ← VJ 播放器（自研 JNI）
libcore.so                                       ← **Go 编译**（strings 含 go-build 临时路径、x_cgo_init）
libtvcore.so                                     ← C++ TV 核心（sply_monitor / splayer 符号）
libmitv.so（+ assets 副本）                       ← miTV SDK
libp2p.so libp2pcore.so libp2ptv.so libp3p…p9p.so ← P2P
libquickjs.so libquickjs-android.so              ← JS 引擎（订阅脚本）
librtmp-jni.so  libconceal.so  libjnidispatch.so ← RTMP / 加密 / JNA
libffmpegJNI.so  libc++_shared.so
```

Java 侧：`androidx/media3/exoplayer` 142 类（EXO 内核）、`tv/danmaku/ijk` 66 类、`com.k2fsa.sherpa` 237 类（**离线语音识别 ASR**）、`org.eclipse.jetty` 87 类（**内嵌 Web 服务器**）、`com.sun.jna` 119 类、`org.fourthline.cling` 878 类（DLNA）。

**判定：IJK / EXO(media3) / MPV 三内核可切 + VJ 播放器 + Go 编写的 `libcore`（FCC/代理等）+ Jetty 内嵌服务。**

### 4.4 播放器参数（`assets/Configuration.json`，官方注释齐全）

**解码方式 `PLAY_TYPE`**（这是本项目最值得抄的枚举）：

| 值 | 含义 |
| --- | --- |
| 0 | 系统解码（MediaPlayer） |
| 1 | IJK 硬解 |
| 2 | IJK 软解 |
| 3 | EXO 硬解 |
| 4 | EXO 软解 |
| 5 | MPV 硬解 |
| 6 | MPV 软解 |
| 7 | **自动解码**（默认） |

**画面比例 `PLAY_SCALE`**：`0=默认, 1=16:9, 2=4:3, 3=填充(默认), 4=原始, 5=裁剪, 6=电影`

**渲染 `PLAY_RENDER`**：`0=texture, 1=surface(默认)`

**超时换源 `LIVE_CONNECT_TIMEOUT`**：`0=5s, 1=10s(默认), 2=15s, 3=20s, 4=25s, 5=30s`

**断线重连 `RECONNECT_INDEX`**：`0=关, 1=1s, 2=3s, 3=5s, 4=10s, 5=20s`

**RTSP 通道 `RTSP_TCP_SELECT`**：`0=TCP(默认), 1=UDP`

**安全 DNS `DOH_URL`**：`0=关, 1=腾讯, 2=阿里, 3=360, 4=Google, 5=AdGuard, 6=Quad9`

**回放方式 `PLAY_BACK_TYPE`**：`0=重新加载 url 更新进度(默认), 1=播放器 seekTo`

**时移**：`TIME_SHIFT_ON=true`；`TIME_SHIFT_MODE`：`0=时移结束自动刷新继续播, 1=回到直播`

**FCC（快速频道切换，运营商特性）**：`FCC_ENABLED`、`FCC_SERVER`（`IP:端口`）、`FCC_TYPE`（`0=telecom 中兴/烽火, 1=huawei`），URL 可带 `?fcc=` / `fcc-type=`

**本机代理**：`PROXY_RTSP_GLOBAL` / `PROXY_RTP_GLOBAL` + 每内核覆盖 `PROXY_RTSP_{EXO,IJK,MPV,SYS}` / `PROXY_RTP_*`（`0=跟随全局, 1=强制直连, 2=强制代理`）

**画中画 `PIC_IN_PIC`**、**开机启动 `BOOT_START`**、**快速退出 `QUICK_EXIT`**、**画面锁定 `EYE_PROTECTION`**（切源保持最后画面）、**记忆解码 `PL_MEMORYS_ET_SELECT`**、**遍历多源 `PLAY_ALL_SOURCE`**、**记忆多源 `MEMORYS_SOURCE`**、**记忆进度 `MEMORYS_POSITION`**、**轨道记忆 `TRACK_MEMORY`**

**解码参数已移到 App 内对话框**（配置注释原文：「IJK/EXO/MPV 解码参数已移至 App 内「其他设置」对应项及管理页「解码参数」」）：

`dialog_ijk_setting.xml`：`analyzeduration (μs)`、`probesize (字节)`、`缓存 (ms, -1 关闭)`、`去隔行`、`音频软解`、恢复默认/保存
`dialog_mpv_setting.xml`：`硬解`（默认 `mediacodec-copy`）、`渲染`（默认 `gpu`）、`图形API`（默认 `opengl`）、`去隔行`、`音频直通`、`杜比直通`、`音频软解`、**`缓存大小 (MB, -1 关闭)` 默认 32**、自定义 `conf`（`dialog_mpv_conf_edit/pick/source`）、恢复默认/保存
`dialog_exo_setting.xml`：EXO 对应项

> **可借鉴**：mpv 的 `硬解` 默认给 `mediacodec-copy`（不是 `mediacodec`）—— 兼顾兼容性与画质；缓存默认 32 MB。

### 4.5 接口协议与源格式

**预置源 `LIVE_URLS`**（`||` 分隔，`地址$自定义名称`）：
- 纯 URL 直链
- **JSON 形式带 `format`**：`{"url":"http://host","user":"u","pass":"p","format":"xtream"}`，`format` 也可写 `type`
- 支持的 `format`：**`xtream`（Xtream Codes）、`stalker`（Stalker Portal）、`ftp` / `smb` / `webdav`（网盘）**

**预置 EPG `EPG_URLS`**：必须带 `format`（或 `type`）：**`XML` / `DIYP` / `SPTV`**；无 `format` 时按后缀猜（`.xml`/`.gz` → XML，否则 DIYP）

**其它预置**：`LIVE_JSONS`（JSON 接口源，默认不勾选）、`HEADERS_URLS`（预置请求头，`地址$名称`）、`USER_AGENTS`（默认 `OKhttp/1.31 || Mozilla/5.0 || SYTV/1.6`）

**列表分组 `GROUP_PARS_SET_SELECT`**：`0=传统分组(仅网络列表分组), 1=列表分组(全部), 2=二级分组模式1, 3=二级分组模式2(分组数<1 隐藏二级)`

**订阅/运行环境**（从 layout 与 assets 反推，功能面极广）：
- `dialog_epg_subscribe_list` / `dialog_json_subscribe_list` / `dialog_portal_subscribe_*` / `dialog_netdisk_subscribe_edit` —— **EPG / JSON / Portal(Stalker) / 网盘 四类订阅管理**
- `dialog_python3_runtime` + `assets/python3_runner/runner.py` —— **内置 Python3 运行器**（脚本源）
- `dialog_node_runtime` + `assets/node_runner/{runner.js,spider.js}` —— **内置 Node 运行器**
- `dialog_php_runtime` —— PHP 运行器
- `dialog_alist_runtime` —— **AList**（网盘聚合）
- `dialog_debug_quickjs` —— QuickJS 调试
- `dialog_subtitle_asr_model` + `assets/asr_models_catalog.json` + `com.k2fsa.sherpa` —— **本地 ASR 生成字幕**；`dialog_subtitle_llm_config` —— LLM 翻译/润色
- `assets/js/{crypto.js, jsencrypt.js}`、`assets/webview_jscode.js` —— 页面注入脚本

**其它资产**：`assets/update.json`（在线更新：`hasUpdate/versionCode/apkSize/updateLog/apkUrl`）、`assets/epg_data.json`（`epgs[] = {epgid, logo, name[]}`，用 `name` 数组把多个频道名映射到同一 EPG/台标）

### 4.6 UI 页面清单与层级

`res/layout` 218 个。自有页面/弹窗（节选，全部为业务相关）：

| 类别 | 布局 |
| --- | --- |
| 页面 | `activity_login`、`activity_live_play`、`activity_tv_time_group`（时移/时间分组）、`activity_webview`、`activity_crash` |
| 底部信息条 | **`bottom_info_default` / `bottom_info_mediaplayer` / `bottom_info_mytv`**（对应 `INFO_PANEL_STYLE 0/1/2` 三种风格） |
| 播放器设置 | `dialog_ijk_setting`、`dialog_exo_setting`、`dialog_mpv_setting`、`dialog_mpv_conf_edit/pick/source` |
| 直播操作 | `dialog_fcc_setting`、`dialog_shortcuts_menu`（快捷菜单）、`dialog_seek_bar`、`dialog_seekbar_channel`、`dialog_multi_screen`（多屏）、`dialog_remote`（遥控）、`dialog_device_cast`（投屏）、`dialog_bottom_group`、`dialog_channel_group`、`dialog_set_group`、`dialog_group_config_edit/list` |
| 订阅/运行时 | `dialog_epg_subscribe_list`、`dialog_json_subscribe_list`、`dialog_portal_subscribe_edit/list`、`dialog_netdisk_subscribe_edit`、`dialog_alist_runtime`、`dialog_python3_runtime/service_list`、`dialog_node_runtime/service_list`、`dialog_php_runtime`、`dialog_debug_quickjs` |
| 字幕 | `dialog_subtitle_pick`、`dialog_subtitle_setting`、`dialog_subtitle_asr_model`、`dialog_subtitle_llm_config` |
| 系统 | `dialog_system_information`、`dialog_crash`、`dialog_disclaimer`、`dialog_backup`、`dialog_about`、`dialog_exit`、`dialog_delete`、`dialog_play_messgae`、`dialog_search`、`dialog_interface_setting`、`dialog_interface_seek_popup`、`dialog_tbs_debug` |

**`activity_live_play.xml` 层级**：

```
FrameLayout @id/live_root
├─ MultiVideoView @id/mMultiVideoView        ← 多屏播放视图（自带多路）
├─ SubtitleView @id/mSubtitleView (gone)
├─ TextView @id/tv_selected_channel (gone)   ← 数字跳台输入
├─ include  @layout/activity_tv_time_group   ← 时移/EPG 时间轴
├─ TextView @id/tvNetSpeed                   ← 网速
├─ TextView @id/mTvSeekBack (gone)           ← 时移回退提示
├─ include  @layout/dialog_bottom_group      ← 底部信息条
├─ ImageView @id/portrait_center_loading     ← 【竖屏】中央加载
├─ LinearLayout @id/portrait_mini_loading    ← 【竖屏】迷你条
│   ├─ portrait_mini_channel_num / _channel_name / _channel_group
│   └─ FrameLayout > View @id/portrait_mini_loading_bar
├─ LinearLayout @id/live_side_controls       ← 侧边按钮组
│   ├─ btn_live_side_lock  (icon_unlock)     ← 锁屏
│   ├─ btn_live_side_rotate (ic_screen_rotation) ← 旋转
│   └─ btn_live_side_shrink (ic_portrait_shrink) ← 竖屏收缩
├─ FrameLayout @id/portrait_list_host        ← 竖屏频道抽屉
└─ FrameLayout @id/admin_webview_host        ← 内置管理页 WebView
```

**`bottom_info_default.xml`（底部信息条 = 播放页 UI 元素全集，强烈建议照抄）**：

```
LinearLayout(horizontal)
├─ ImageView @id/tv_logo (215dip)
└─ LinearLayout(vertical)
    ├─ ll_line1: MarqueeTextView tv_channel_name
    │            + TextView tv_can_shiyi   (可时移徽标, gone)
    │            + TextView tv_size        (1920x1080)
    │            + TextView tv_fps
    │            + TextView tv_audio_track (音轨)
    │            + TextView tv_is_ipv6     (IPv6 徽标)
    │            + TextView tv_source      ("线路1/1")
    ├─ LinearLayout: ProgressBar time_progressbar (2dip) + bottom_buffer_drawable
    │               + TextView Program_length
    ├─ ll_line3: tv_playing("正在播放：") + tv_current_program_time("00:00 - 23:59")
    │            + MarqueeTextView tv_current_program_name
    ├─ TextView tv_desc (节目简介)
    └─ ll_line4: tv_playing2("下一节目：") + tv_next_program_time + tv_next_program_name
```

**TV 焦点特征**：与 TVBox 同源（`TvRecyclerView` + `MarqueeTextView` + 代码接管按键）；竖屏（手机）路径用 `portrait_*` 前缀的一组视图，`live_side_controls` 在手机端出现。

### 4.7 交互逻辑

**未能逐键还原。** `LivePlayActivity` 的 `dispatchKeyEvent` 位于 156 435 行平坦化代码中（NP 控制流混淆），`onKeyDown`/`onKeyUp` 无独立方法。

**可确认的行为（来自配置项 + 资源 + 布局，均为产品级事实）**：
- **数字跳台**：`tv_selected_channel` + 配置项「数字选台字体颜色 `selected_ChannelNumber_Color`（默认 `#FF70C439` 清新绿）」→ 明确实现，且可配色。
- **换台反转** `LIVE_CHANNEL_REVERSE`、**跨选分组** `LIVE_CROSS_GROUP`。
- **快捷菜单** `SHORTCUTS_MENU` + `SHORTCUTS_MENU_SELECT`，默认项：**`媒体信息, 列表订阅, EPG订阅, 无线投屏, 频道搜索`** → 说明「长按/菜单键呼出快捷菜单」是一等公民。
- **导航栏模式** `NAVIGATION_SELECT`：`0=滑动显示(默认), 1=一直显示, 2=触摸显示`。
- **EPG 展示** `EPG_SHOW_TYPE_SELECT`：`0=折叠(默认), 1=一直展示`。
- **信息面板** `INFO_PANEL_STYLE`：`0=风格一, 1=风格二, 2=风格三`。
- **列表样式** `LIST_UI_STYLE`：`0=悬浮, 1=贴边(默认)`。
- **界面主题可调**：`TEXT_SIZE`（-6~6）、`BOTTOM_WIDTH`（-9~9）、以及 9 个 `LIST_COL_*` 列宽偏移（一级分类/二级分类/频道名/EPG 日期/EPG 列表/设置一级/设置二级/设置子列表/快捷菜单），**每档约 8dp（竖屏 12dp）** → **列宽可微调**是 TV 端「适配各种分辨率」的实用手法。
- **推送频道后自动播放** `PUSH_AUTOPLAY`、**只播音频时显示固定壁纸** `AUDIO_WAKKPAPER`（系统解码下无效）。

### 4.8 明文接口地址

仅 `assets/update.json` 中 `http://ku9/app/update.json`、`http://app/ku9.apk`、`http://ku9/cc99.png`（**全是占位符，无真实源**）。

### 4.9 亮点与可借鉴点（本项目参考价值最高）

1. **`PLAY_TYPE` 0–7 的八档解码枚举**（系统/IJK 硬软/EXO 硬软/MPV 硬软/自动）+ **每内核独立参数对话框** —— 直接可用的产品设计。
2. **底部信息条三风格 + 元素全集**（台标/频道名/可时移/分辨率/FPS/音轨/IPv6/线路 x/y/节目进度/正在播/下一节目/简介）。
3. **列宽/字号/底栏宽度可微调**（±6、±9 档），解决 TV 端「不同分辨率下文字被截断」。
4. **订阅体系**（EPG/JSON/Portal/网盘 + Python/Node/PHP/AList 四种脚本运行时）——把「源适配」做成可扩展运行时，而不是硬编码。
5. **FCC**（中兴/烽火/华为）与 **RTSP/RTP 本机代理（可按内核覆盖）** —— 运营商 IPTV 场景的必备件。
6. **本地 ASR 字幕**（sherpa-onnx）+ LLM 字幕翻译 —— 差异化功能。
7. **`epg_data.json` 的 `name` 数组映射**：一个 EPG/台标映射多个频道名，解决频道名不规范问题。
8. **DLNA 双向**（`dmc` 投出去 + `dmr` 被投进来）。
9. 竖屏/手机端完整路径（`portrait_*`、侧边按钮、频道抽屉）——**与我们「TV + 手机」双端目标一致**。

### 4.10 局限

- NP 控制流混淆，按键映射需去平坦化才能恢复。
- targetSdk 28 + `MANAGE_EXTERNAL_STORAGE`，上架合规风险高。
- 功能面过大（P2P、Python/Node/PHP 运行时、ASR、AList），APK 42 MB、攻击面大。
- 8 个 P2P 常驻进程，低端盒子内存压力大。

---

## 5. XPlayer 2.6.0（`com.tntlikely.xplayer`）

> 定位：**Flutter 写的跨端（TV + 手机）IPTV 播放器，开源**。唯一一个 UI 层不是 Android 原生 View 的。

### 5.1 基本信息

| 项 | 值 |
| --- | --- |
| 包名 | `com.tntlikely.xplayer` |
| 版本 | versionName `2.6.0` / versionCode `62` |
| SDK | minSdk **24** / targetSdk **36** / compileSdk **36** |
| ABI | 仅 `arm64-v8a` |
| APK 体积 | 25.5 MB |
| 技术栈 | **Flutter（`flutterEmbedding=2`）**：`libflutter.so` 11.3 MB + `libapp.so` 7.7 MB（Dart AOT）+ `libffmpegJNI.so` + `libsqlite3.so` + `libdatastore_shared_counter.so` |
| 加固 | 无加固（Dart AOT 天然不可读 Java，但**类名/包路径字符串完整保留**，可重建架构） |
| 开源 | 更新检查指向 `https://api.github.com/repos/TNT-Likely/xplayer/releases/latest` → 项目为 **TNT-Likely/xplayer** |

### 5.2 清单与入口

**uses-feature**：**无**（未声明 leanback / touchscreen）
**权限**：`INTERNET`、`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`、`CHANGE_WIFI_MULTICAST_STATE`、`ACCESS_FINE_LOCATION`、**`NEARBY_WIFI_DEVICES`**（局域网发现用）、`WRITE_EXTERNAL_STORAGE`、`READ_EXTERNAL_STORAGE(maxSdk=32)`、**`REQUEST_INSTALL_PACKAGES`**、**`QUERY_ALL_PACKAGES`**、**`WAKE_LOCK`**、`READ_MEDIA_IMAGES/VIDEO/AUDIO`
**queries**：`ACTION_GET_CONTENT` + `mimeType=*/*`

**组件**
| 组件 | 说明 |
| --- | --- |
| `com.tntlikely.xplayer.MainActivity` | **唯一 Activity**，`LAUNCHER`（**无 LEANBACK_LAUNCHER**）、`singleTop`、`configChanges` 覆盖 density/fontScale/layoutDirection/locale/orientation/uiMode 等全部 |
| `io.flutter.plugins.urllauncher.WebViewActivity` | 内置浏览器 |
| `com.crazecoder.openfile.FileProvider` | 打开本地文件/装 APK |
| meta-data | `flutterEmbedding=2` |

> **关键设计**：**单 Activity + 全 Flutter 路由**。整个 app 只有 1 个 Activity、24 个 layout（全是插件自带），UI 全在 Dart 层。

### 5.3 播放内核

- **无 libmpv / 无 libijk**。`libffmpegJNI.so` + `libflutter.so` + 三方 Flutter 插件 `video_player`（`dev.flutter.pigeon.video_player_android.*`、`createForTextureView`）。
- Dart 层有**三个播放后端**（`services/player/`）：`x_player_backend.dart`（自研，走 native）、`native_player_backend.dart`、`video_player_backend.dart`（官方插件）。

**判定：Flutter `video_player`（底层仍是 Android MediaPlayer/Exo）+ 自研 native FFmpeg 后端，二者可切换。** 设置项 `player_use_native_engine`、`player_use_surface_view` 就是这两个开关。

### 5.4 播放器参数

| 项 | 证据 |
| --- | --- |
| 缓冲策略抽象 | `services/player/buffer_profile.dart`（**`BufferProfile`** 类，预设档位） |
| 卡死检测 | `services/player/stall_detector.dart`（对应文案「检测到播放卡死」「长时间缓冲超限」） |
| 渲染方式 | `player_use_surface_view`（SurfaceView ↔ TextureView） |
| 引擎选择 | `player_use_native_engine` |
| 画中画 | `native_pip`、`pip_on_leave`、`mini_player_on_exit`（退出播放页→右下角小窗） |
| HLS 探测 | `utils/hls_probe.dart`（对应文案「单档清单 / 无多码率可选」→ **解析 master playlist 拿码率档位**，做清晰度切换） |

> 具体数值常量在 Dart AOT 里（未反编译），但**文件名即设计意图**：`BufferProfile` + `StallDetector` 两个可复用抽象。

### 5.5 数据模型（SQLite，schema 完整可读）

```sql
CREATE TABLE Playlists (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL, url TEXT NOT NULL, epgUrl TEXT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE favorites (
  id TEXT PRIMARY KEY, name TEXT NOT NULL, logo TEXT,
  source TEXT NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE recent (
  id TEXT PRIMARY KEY, name TEXT NOT NULL, logo TEXT,
  source TEXT NOT NULL, played_at INTEGER NOT NULL
);
-- 另有 cacheObject（sqflite 缓存，用于 EPG）
```

> 注意：`Playlists` 表本身没有 `channels` 列 —— 频道列表**落文件**（`data/repositories/channels_file_storage.dart`），DB 只存播放列表元数据。文案「__ 个播放列表的 channels 数据到文件」印证。

**模型**：`channel_model`、`playlist_model`、`programme_model`、`channel_test_result`、`iptv_presets`

**m3u 解析**（`extensions/m3u.dart`）：`#EXTM3U`、`x-tvg-url`（EPG 地址）、`#EXTINF:`、`tvg-id`、`tvg-name`、`tvg-logo`、`group-title`

**预置源**（`data/models/iptv_presets.dart`）：`https://iptv-org.github.io/iptv/index.m3u`、`https://iptv-org.github.io/iptv/categories/news.m3u`

### 5.6 UI 页面清单（Dart 源码路径即页面清单）

**Screens**（`presentation/screens/`）
| 文件 | 页面 |
| --- | --- |
| `splash.dart` | 启动 |
| `home.dart` | 首页（最近播放 / 收藏行） |
| `playlist.dart` | 播放列表管理 |
| `player.dart` | 播放页 |
| `epg_screen.dart` | 节目单（时间轴） |
| `remote_input.dart` | **手机遥控输入** |
| `lan_sync_screen.dart` / `lan_sync_preview_screen.dart` / `lan_sync_open_screen.dart` | **局域网同步**（跨设备搬配置） |
| `log_center_screen.dart` | 日志中心 |

**Widgets**（`presentation/widgets/`）
`player_actions_widget`（播放操作）、`channel_selector_widget`、`channel_list_widget`、`channel_item_widget`、`channel_source_widget`、`channel_filter_dialog`、`epg_time_axis`、`epg_channel_column`、`epg_programme_block`、`quality_selector_widget`（清晰度）、`audio_track_selector_widget`、`mini_player_overlay`、`cast_device_sheet`、`playlist_dialog`、`playlist_widget`、`preset_source_dialog`、`theme_color_dialog`、`operation_hint_dialog`、`update_proxy_dialog`、`recent_played_widget`、`bg_wrapper`

**共享组件**（`shared/components/`）：`dpad_escapable`（**遥控器焦点逃逸**）、`ipv6_badge`、`x_base_button`、`x_dialog_shell`、`x_icon_button`、`x_text_button`

**服务层**（`services/`）
- `player/`：`x_player_backend`、`native_player_backend`、`video_player_backend`、`buffer_profile`、`stall_detector`
- `cast/`：`dlna_controller`、`dlna_device`、`dlna_discovery`、`dlna_xml`
- `remote/`：`remote_protocol`、`remote_server`（**`/remote` 端点 + Token 鉴权**）
- `sync/`：`config_bundle`、`config_export_service`、`config_import_service`、`lan_sync_discovery`、`lan_sync_server`、`sync_device`、`sync_selection`
- `update/`：`update_checker`、`update_downloader`、`update_installer`、`update_permissions`、`update_proxy`、`update_cache`、`update_result`、`update_service`
- 其它：`channel_test_service`（频道连通性测速）、`sleep_timer`、`log_store`

**Providers**：`global`、`media`、`cast`、`locale`、`remote`、`mini_player_controller`

### 5.7 交互逻辑

**TV 焦点**：`shared/components/dpad_escapable.dart` —— 「D-pad 可逃逸」组件：内层列表/横向滚动到边界时，把方向键事件交还给外层（或触发面板关闭）。这是 Flutter 在 TV 上做焦点管理的关键技巧。
**操作提示**：`operation_hint_dialog.dart` + `player_hint_seen` 设置项 → 首次进入播放页弹「操作说明」（一次），与 mediaplayer 的 `showGestureHintOnce` 是同一思路。
**可读文案**（从 Dart 快照提取，等于交互规格）：

| 文案 | 含义 |
| --- | --- |
| `切换频道：___ 键 或 上下滑动` | **遥控器上下键 + 手机上下滑动** 双通道换台 |
| `检测到播放卡死` / `长时间缓冲超限` | 卡死检测 → 重连/换源 |
| `播放视频失败 / 重试` / `播放器初始化失败 将重试` | 重试链 |
| `从播放页返回首页时 在右下角小窗继续播放` | mini player |
| `离开应用回到系统桌面时 用系统画中画继续播放` | 系统 PiP |
| `一个免费、开源的 IPTV/M3U 播放器` | 定位 |
| `直播流本身无法走 HTTP 代理` | 代理的边界提示 |
| `在播放列表里添加带 EPG(x-tvg-url) 的源后这里就能看到节目时间表` | 空态引导 |
| `该频道暂无节目单` / `找不到频道～` / `正在测试频道...` | 空态/测速 |
| `单档清单 / 无多码率可选` | HLS 清晰度探测结果 |

**设置项键名**（SharedPreferences / DataStore）：
`player_use_native_engine`、`player_use_surface_view`、`native_pip`、`pip_on_leave`、`mini_player_on_exit`、`auto_refresh_channels`、`auto_refresh_on_launch`、`auto_refresh_programmes`、`hide_unplayable`、`home_show_favorites_row`、`home_show_recent`、`grid_size_level`、`preferred_sources`、`seeded_default_preset`、`player_hint_seen`、`proxy_use_source`、`proxy_use_update`、`http_proxy`、`https_proxy`、`no_proxy`、`language_code`、`country_code`

### 5.8 明文接口地址（仅格式参考）

| 地址 | 用途 |
| --- | --- |
| `https://iptv-org.github.io/iptv/index.m3u` | 预置源（iptv-org 公开索引） |
| `https://iptv-org.github.io/iptv/categories/news.m3u` | 预置分类源 |
| `https://api.github.com/repos/TNT-Likely/xplayer/releases/latest` | 自更新检查 |
| `/remote` | 手机遥控本地 HTTP 端点 |

### 5.9 亮点与可借鉴点（对「TV + 手机双端」最贴）

1. **一套 Flutter 代码出 TV + 手机双端**，`dpad_escapable` 解决 Flutter 的 TV 焦点难题。
2. **手机当遥控器**：`remote_server` + `/remote` + Token + `remote_protocol`，TV 端起 HTTP 服务，手机端 `remote_input` 页面发指令。**比二维码配置更进一层**。
3. **局域网同步**（`lan_sync_*` + `config_bundle` 导出/导入）：换盒子不用重新配源。
4. **`BufferProfile` + `StallDetector` 两个抽象**：缓冲策略与卡死判定解耦，可复用。
5. **`hls_probe` 探测 master playlist 码率档** → 清晰度选择有依据（而不是硬猜）。
6. **`channel_test_service`**：批量测频道连通性 → 支撑 `hide_unplayable`（隐藏放不了的频道）。
7. **DB 只存元数据、频道列表落文件**：几千频道的列表不进 SQLite，冷启动快。
8. **`preferred_sources`（源优先级）** + `proxy_use_source` / `proxy_use_update`（代理只用于拉源/更新，不代理直播流）。

### 5.10 局限

- 未声明 `LEANBACK_LAUNCHER` → **在 Android TV 的 Leanback 启动器里不会出现**（只能从「应用」里进），这是 TV 端硬伤。
- Flutter 在低端 TV 盒子上首帧与内存表现弱于原生。
- Dart AOT 不可反编译为可读源码（本次只做了架构级重建，未还原算法）。

---

## 6. 横向对比表

### 6.1 基础与形态

| 维度 | mediaplayer | TVBox(jun) | DIYP 5.2.0 | 酷9 2.2.21 | XPlayer 2.6.0 |
| --- | --- | --- | --- | --- | --- |
| 包名 | `com.mediaplayer.app` | `com.github.tvbox.osc.jun` | `com.player.diyp2020` | `com.player.ku9` | `com.tntlikely.xplayer` |
| 版本 | 1.1.25 (125) | 20260914-1520 (1) | 5.2.0 (21) | 2.2.21 (17) | 2.6.0 (62) |
| minSdk / target | 23 / 34 | 21 / 28 | **17** / 28 | 21 / 28 | 24 / **36** |
| ABI | v7a | arm64 | v7a | v7a | arm64 |
| 体积 | 30.5 MB | 20.8 MB | 17.2 MB | **42.8 MB** | 25.5 MB |
| UI 技术 | 原生 View（单 Activity） | 原生 View（多 Activity） | 原生 View（混淆） | 原生 View（多 Activity） | **Flutter** |
| 加固/混淆 | 无 | ProGuard 重度 | ProGuard+资源+控制流 | NP 控制流混淆 8.0 | 无（Dart AOT） |
| 业务定位 | 服务端托管直播 | 点播+直播聚合 | 纯直播 | 直播为主 + 全能 | 直播/IPTV 播放器 |

### 6.2 TV 适配

| 维度 | mediaplayer | TVBox | DIYP | 酷9 | XPlayer |
| --- | --- | --- | --- | --- | --- |
| `uses-feature leanback` | ✅ required=false | ✅ | ❌ | ✅ | ❌ |
| `LEANBACK_LAUNCHER` | ✅ | ✅ | ❌ | ✅ | ❌ |
| `android:banner` | ✅ `@drawable/banner` | ✅ `app_banner` | ❌ | ✅ `app_banner` | ❌ |
| 屏幕方向 | `unspecified`（自适应） | `sensorLandscape` | `sensorLandscape` | `sensorLandscape` | 不锁（全 configChanges） |
| 设计稿缩放 | 手写 dp/dimen | `AutoSize` 1280×720 | 手写 | `AutoSize` 960×720 | Flutter 自适应 |
| 焦点实现 | `nextFocus*` 显式连线 | `dispatchKeyEvent` + TvRecyclerView | 代码 requestFocus | `dispatchKeyEvent` + TvRecyclerView | `dpad_escapable` |
| 手机端形态 | 同 Activity（`isTvMode` 分流） | 无独立手机 UI | 横屏为主 | **完整竖屏路径**（`portrait_*`） | **同套代码双端** |

### 6.3 播放内核与参数

| 维度 | mediaplayer | TVBox | DIYP | 酷9 | XPlayer |
| --- | --- | --- | --- | --- | --- |
| 内核 | **mpv + Exo(media3)** | **ijk + Exo2** | **ijk + 自研 mpc** | **IJK + EXO(media3) + MPV + VJ** | **video_player + native FFmpeg** |
| 内核切换 | core 1/3（智能切换） | `playerType`：ijk硬解/ijk软解/exo | 解码方式（未还原） | **`PLAY_TYPE` 0–7 八档** | `player_use_native_engine` |
| 硬解参数 | mpv `hwdec=mediacodec,mediacodec-copy,auto` + `hwdec-codecs=all` | ijk mediacodec | MediaCodec | 每内核独立对话框（mpv 默认 `mediacodec-copy`） | 系统 MediaCodec |
| 直播起播缓冲 | Exo **500 ms** | ijk `probesize/analyzeduration` | — | ijk `analyzeduration/probesize` 可调 | `BufferProfile` 预设 |
| 直播缓冲上限 | Exo min 1000/max 60000；mpv 16–128 MB | ijk `infbuf` / `max_cached_duration` | — | mpv 缓存 MB 可填（默认 32） | `BufferProfile` |
| 低延迟手段 | mpv `video-latency-hacks=yes` + `probesize=1.5s` | `prefer_tcp` / `infbuf` | — | 超时换源 5–30 s、断线重连 1–20 s | `stall_detector` |
| 渲染面 | mpv `vo=gpu` `gpu-context=android` | SurfaceRenderView | IjkVideoView | `PLAY_RENDER` texture/surface | `player_use_surface_view` |
| 缓存落盘 | media3 `SimpleCache` | ijk `ijkio:cache:ffio:` + `/ijkcaches/` | — | — | — |
| 特殊能力 | **AVS3/AV3A 解码** | 迅雷下载 / P2P | **P2P ×8 进程** | FCC / 时移 / 多屏 / ASR 字幕 / AList | HLS 码率探测 |

### 6.4 交互（换台 / 呼出列表 / EPG / 数字跳台）

| 交互 | mediaplayer | TVBox | DIYP | 酷9 | XPlayer |
| --- | --- | --- | --- | --- | --- |
| 换台 | CH+/CH−、DPAD 上/下/左/右 | **DPAD 上/下** | 未还原（有换台反转开关） | 未逐键还原（有换台反转/跨选分组） | **上下键 或 上下滑动** |
| 呼出频道列表 | MENU/165 → 换台抽屉 | DPAD 右（面板内）/ MENU → 设置 | 未还原 | 快捷菜单（`SHORTCUTS_MENU`） | `channel_selector_widget` |
| 呼出 EPG | 侧边 EPG 抽屉 | DPAD 右展开 EPG 三级（日期+节目） | 有 EPG 功能开关 | `EPG_SHOW_TYPE` 折叠/常显 | 独立 `epg_screen` 时间轴 |
| **数字跳台** | ❌ 数字键被吞 | ✅ **缓冲 2500 ms 后跳台** | 推断有 | ✅ 数字可配色 | 未确认 |
| 长按菜单 | ❌ | ✅ **800 ms** 判定 | ❌（有快速退出） | ✅ 快捷菜单 | `operation_hint_dialog` |
| 双击退出 | ✅ **2000 ms** | — | 有「快速退出」开关 | 有「快速退出」开关 | — |
| 换台反转 | ✅ `reverse_channel_keys` | ✅ `live_channel_reverse` | ✅ 换台反转 | ✅ `LIVE_CHANNEL_REVERSE` | — |
| 换台防黑屏 | 快照（`captureSnapshotAsync`） | ✅ **快照遮罩** | — | 有「画面锁定」 | mini player |
| 手机遥控 | 二维码配置服务器 | 广播推送（`PushReceiver`） | **内置 Web 管理端** | 内置 WebView 管理页 | ✅ **`/remote` HTTP 遥控** |

### 6.5 源格式与协议

| 维度 | mediaplayer | TVBox | DIYP | 酷9 | XPlayer |
| --- | --- | --- | --- | --- | --- |
| txt `#genre#` | ❌ | ✅ | ✅ | ✅ | ❌ |
| m3u / `#EXTINF` | ❌ | ✅ `tvg-*`/`group-title` | ✅ | ✅ | ✅ `tvg-id/name/logo`、`x-tvg-url` |
| 自有 JSON 接口 | ✅ **REST（channels/groups/epg…）** | ✅ `sites/parses/lives/hosts/rules/doh` | ❌ | ✅ `LIVE_JSONS` + 订阅 | ❌（只有 m3u） |
| 脚本源 | ❌ | ✅ QuickJS（jar/py/js） | ❌ | ✅ QuickJS + **Python3/Node/PHP/AList** | ❌ |
| Xtream / Stalker | ❌ | ❌ | ❌ | ✅ `format=xtream/stalker` | ❌ |
| 网盘 | ❌ | ❌ | ❌ | ✅ `ftp/smb/webdav` + AList | ❌ |
| EPG 格式 | 自有 REST | XML / DIYP（`51zmt`） | 112114 / DIYP | **XML / DIYP / SPTV** | XML（`x-tvg-url`） |
| 回看/时移 | ✅ `catchupType/catchupDays` | ✅ `catchup`/`catchup-source`/`catchup-replace` | ✅ 开启时移/回看列表 | ✅ 时移 + `TIME_SHIFT_MODE` | ❌ |
| 服务端远程配置 | ✅ **`RemoteClientConfig` 全套** | ❌ | ❌ | ❌ | ❌ |
| 局域网同步/备份 | ❌ | ✅ `dialog_backup` | ❌ | ✅ `dialog_backup` | ✅ **`lan_sync_*`** |

### 6.6 播放页 UI 元素对照

| 元素 | mediaplayer | TVBox | DIYP | 酷9 | XPlayer |
| --- | --- | --- | --- | --- | --- |
| 频道名 | ✅ | ✅（跑马灯） | ✅ | ✅（跑马灯） | ✅ |
| 台标 | ✅（列表图标） | ✅ | ✅ | ✅（+首字占位可配色） | ✅ |
| 时间 / 时钟 | ✅（`timeShowMode`） | ✅ 右上角 | ✅ `TextClock` | ✅（`time_format`） | — |
| 网速 | — | ✅ | ✅ 显示网速开关 | ✅ `LIVE_SHOW_NET_SPEED` | — |
| EPG 进度条 | ✅ `globalProgressBar` | ✅ 底部条 | — | ✅ `time_progressbar` + 缓冲条 | ✅ 时间轴 |
| 当前/下一节目 | ✅ `tvEpgNow/Next` | ✅ | — | ✅ + `desc` 简介 | ✅ |
| 分辨率 | ✅ | ✅ | — | ✅ + **FPS** + **音轨** + **IPv6** | — |
| 线路/源切换 | ✅ 线路抽屉 | ✅ **DPAD 左/右 直切** | ✅ 线路选择 | ✅ `线路x/y` + 遍历多源 | `preferred_sources` |
| 清晰度切换 | — | — | — | — | ✅ **`quality_selector`（HLS 探测）** |
| 解码方式切换 | ✅ 设置内（解码模式+内核） | ✅ `playerType` | ✅ 解码方式 | ✅ **每内核独立对话框** | ✅ `player_use_native_engine` |
| 画面比例 | ✅ `scaleMode` | ✅ `live_play_scale` | ✅ 画面比例 | ✅ `PLAY_SCALE` 7 档 | — |
| 音轨切换 | ✅ `layoutTrackMenu` | ✅ `dialog_subtitle` | — | ✅ `tv_audio_track` | ✅ `audio_track_selector` |
| 字幕 | — | ✅ `dialog_subtitle` | — | ✅ 外挂 + **ASR 生成 + LLM 翻译** | — |
| 收藏 | — | ✅ `CollectActivity` | ✅ 我的收藏 | ✅ `HIDE_FAVOR` | ✅ `favorites` 表 |
| 时移/回看 | ✅ `catchup*` | ✅ `catchup-*` | ✅ 开启时移 | ✅ `TIME_SHIFT_*` | ❌ |
| 录制 | ❌ | ❌ | ❌ | ❌ | ❌ |
| 投屏 | ✅ `btnCast` | ✅ DLNA(cling) | — | ✅ DLNA dmc + **dmr** | ✅ `dlna_*` |
| 画中画 | ✅ `settings_pip` | — | — | ✅ `PIC_IN_PIC` | ✅ `native_pip` + mini player |
| 锁屏/旋转 | — | — | — | ✅ 侧边按钮组 | — |

> **五家都没有「录制」** —— 这是空档（见第 7 节）。

### 6.7 接口协议形态对比

| 形态 | 代表 | 特点 | 适合我们？ |
| --- | --- | --- | --- |
| A. 服务端 REST + 设备注册/授权 | mediaplayer | 后端可控、可远程改 UI/播放策略、可计费 | **部分适合**（配置下发思路值得抄） |
| B. 配置 JSON 全量驱动（sites/parses/lives） | TVBox、酷9 | 用户自带源、无需后端、生态大 | **不适合**（版权与合规风险） |
| C. 播放列表（txt/m3u）+ 本地管理端 | DIYP、XPlayer | 最轻、最通用 | **适合**（作为基础能力） |
| D. 内置运行时执行第三方脚本 | 酷9（Py/Node/PHP）、TVBox（QuickJS） | 扩展性极强 | **不要抄**（安全与合规双重风险） |

---

## 7. 我们要抄什么 / 不要抄什么

### 7.1 建议直接照抄（低成本、高收益）

| # | 做法 | 出处 | 落地建议 |
| --- | --- | --- | --- |
| 1 | **`PlayerHelper` 接口 + 多内核后端** | mediaplayer(`j3.q`)、XPlayer(`*_backend`) | 定义 `IPlayerEngine`，先实现 Exo 与 mpv 两套，设置项给「自动/Exo/mpv」 |
| 2 | **解码枚举八档**：系统 / IJK 硬软 / EXO 硬软 / MPV 硬软 / 自动 | 酷9 `PLAY_TYPE` | 我们收敛为 `系统 / Exo硬 / Exo软 / mpv硬 / mpv软 / 自动`，**默认自动** |
| 3 | **直播起播参数**：Exo `(1000, 60000, 500, 1000)`；mpv `video-latency-hacks=yes` + `probesize=analyzeduration=1.5s` + `hwdec=mediacodec-copy` | mediaplayer / 酷9 | 直接作为「直播预设」写死，另给高级项 |
| 4 | **缓存用「秒」表达，内部映射 MB** | mediaplayer 映射表 | 设置项写「缓冲 1/2/3/5 秒」，别让用户填字节 |
| 5 | **数字跳台：2500 ms 缓冲 + 独立数字层** | TVBox | 常量照抄；数字层支持配色 |
| 6 | **长按判定 800 ms / 双击退出 2000 ms** | TVBox / mediaplayer | 常量照抄，抽成 `KeyTiming` 常量类 |
| 7 | **换台快照遮罩**（上一帧盖住黑屏） | TVBox | `ImageView(fitCenter) + ProgressBar` 叠在播放视图上，切源时显示 |
| 8 | **`dispatchKeyEvent` 统一接管 + 面板焦点状态机** | TVBox / 酷9 | 比 `nextFocus*` 连线可维护；面板显隐即改状态 |
| 9 | **底部信息条元素全集**（台标/频道名/可时移/分辨率/FPS/音轨/IPv6/线路x/y/进度/正在播/下一节目/简介） | 酷9 `bottom_info_default` | 按此清单做 1 个「标准风格」+ 1 个「极简风格」即可，不必做 3 套 |
| 10 | **`reverse_channel_keys` 换台反转** | 三家都有 | 必须有，用户强需求 |
| 11 | **列宽/字号可微调（±6 档，每档 8dp）** | 酷9 `LIST_COL_*` / `TEXT_SIZE` | 解决 TV 端文字截断，比做多套布局便宜 |
| 12 | **`epg_data.json` 的 `name[]` 多对一映射** | 酷9 | 用一个 EPG/台标覆盖多个不规范频道名 |
| 13 | **`BufferProfile` + `StallDetector` 抽象** | XPlayer | 缓冲策略可预设；卡死检测独立于播放器 |
| 14 | **`hls_probe` 解析 master playlist 取码率档** | XPlayer | 清晰度切换有据可依 |
| 15 | **频道连通性批量测试 + 隐藏不可播** | XPlayer `channel_test_service` + `hide_unplayable` | 首启动/换源后跑一遍，直接提升体验 |
| 16 | **手机当遥控（TV 起 HTTP + Token，手机发指令）** | XPlayer `/remote` | 我们的双端产品的杀手锏，优先做 |
| 17 | **手机扫码/网页配置 TV** | mediaplayer 二维码 / DIYP Web 管理端 | 先做二维码（轻），后续加 Web 管理 |
| 18 | **局域网配置同步（导出/导入 bundle）** | XPlayer `lan_sync_*` | 换盒子/多端同步 |
| 19 | **DB 只存元数据、频道列表落文件** | XPlayer | 几千频道冷启动不卡 |
| 20 | **容错状态机文案与行为一一对应**（失效跳过→换线路→重试倒计时→连续失败停手） | mediaplayer | 直接把 7 条文案当行为规格实现 |

### 7.2 可以借鉴但需改造

| # | 做法 | 出处 | 改造点 |
| --- | --- | --- | --- |
| 1 | **服务端下发 UI 与播放策略**（`RemoteClientConfig`） | mediaplayer | 只下发「能力开关 + 参数」，**不下发内容源**；且必须可离线降级 |
| 2 | **`AutoSize` 虚拟尺寸（`vs_*`）** | TVBox / 酷9 | 我们用 `constraintlayout` + 最小宽度断点亦可；若抄 AutoSize 需注意与 Compose 冲突 |
| 3 | **内置 WebView 管理页** | 酷9 / DIYP | 只做「源管理 + 诊断」，不做通用浏览器 |
| 4 | **`quickSearch` / `filterable` / `style` 等站点元数据** | TVBox | 只保留「源优先级 / 是否可搜 / 超时」三个字段 |
| 5 | **FCC（快速频道切换）** | 酷9 | 仅在明确对接运营商组播时实现，且必须可关 |
| 6 | **RTSP/RTP 本机代理（按内核覆盖）** | 酷9 | 保留「直连/代理」二态 + 全局默认，不要做 8 个内核开关 |
| 7 | **离线 ASR 字幕** | 酷9（sherpa-onnx） | 体积大（模型几十 MB），建议做成可选下载 |
| 8 | **P2P 加速** | DIYP / 酷9（8 进程） | **不建议**；若要做，单进程 + 明确开关 + 明确告知用户 |

### 7.3 明确不要抄

| # | 不要抄 | 出处 | 原因 |
| --- | --- | --- | --- |
| 1 | **配置 JSON 里塞别人的源地址**（`sites/lives/parses`） | TVBox、酷9 | 版权与合规风险；我们的源必须来自用户自有/授权 |
| 2 | **内置 Python3 / Node / PHP / QuickJS 运行时执行第三方脚本** | 酷9、TVBox | 远程代码执行面；被投毒脚本即 RCE；应用商店必拒 |
| 3 | **`MANAGE_EXTERNAL_STORAGE`** | 酷9 | 上架合规硬伤，改用 SAF |
| 4 | **`SYSTEM_ALERT_WINDOW` 悬浮窗** | DIYP、酷9 | 权限敏感，TV 上意义不大 |
| 5 | **8 个常驻 P2P 进程（`:P2P`…`:P9P`）** | DIYP、酷9 | 内存/电量/隐私三重问题 |
| 6 | **`QUERY_ALL_PACKAGES`** | XPlayer | 合规风险，改为定向 `<queries>` |
| 7 | **`READ_PHONE_STATE` / `GET_TASKS` / `MOUNT_UNMOUNT_FILESYSTEMS`** | TVBox | 老权限，无用且敏感 |
| 8 | **把配置和代码混淆当"安全"**（资源改名 + 控制流平坦化） | DIYP、酷9 | 只增加自己维护成本；真要保护用 R8 + 服务端校验 |
| 9 | **多 Activity 栈 + 每个页面各自处理按键** | TVBox | 我们单 Activity + 状态机更可控（mediaplayer 的方向对，但它的单 Activity 又太重） |
| 10 | **用别人的默认 EPG 地址做兜底**（`51zmt` / `112114`） | TVBox、DIYP | 依赖第三方且不可控；我们自带 EPG 或让用户配 |
| 11 | **`REQUEST_INSTALL_PACKAGES` 静默自更新** | 全部 | 至少改为显式确认 + 校验签名 |
| 12 | **`targetSdk 28` 躺平** | TVBox、DIYP、酷9 | 新系统上后台/前台服务/通知行为不可控；我们从 34+ 起步 |
| 13 | **无 `LEANBACK_LAUNCHER` 就上 TV** | DIYP、XPlayer | TV 用户找不到入口（XPlayer 是开源项目的明显疏漏） |

### 7.4 三家"空档"= 我们的机会点

1. **录制 / 时移落盘**：5 个 APK **全部没有录制功能**。直播录制（含"看即录/预约录"）是差异化点。
2. **多用户 / 家长控制**：仅 DIYP 有"关闭密码"雏形，无人做真正的儿童锁 + 分级。
3. **统一的 EPG 体验**：TVBox 是三级列表、XPlayer 是时间轴、酷9 是折叠/常显两态——**没人把「时间轴 + 频道列」做成可横向滚动的标准 EPG 网格**（XPlayer 的 `epg_time_axis` + `epg_channel_column` 最接近，但只在独立页面）。
4. **TV ↔ 手机 双向连续性**：XPlayer 有手机遥控和 LAN 同步，但**没有「手机上看一半，回 TV 接着看」的跨端续播**。
5. **可观测性**：只有 mediaplayer 有 `client/logs` + `client/playing_status` 上报。播放失败率/卡顿率/换台耗时是 IPTV 体验的核心指标，其余四家都没有。

---

## 8. 产物清单

```
build/apk-re/
├─ _scripts/                    复现脚本（decode_all / dump_manifest / layout_tree / unesc / grep_player / survey / collect）
├─ mediaplayer/                 AndroidManifest.xml · apktool.yml · res_layout/(216) · res_xml/ · strings_*.xml · native_libs.txt · assets_list.txt
├─ tvbox_q215613905/            同上 + spider_template.js（站点模板）· epg_data.json
├─ diyp/                        同上 + web_admin_index.html · web_admin_bundle.js
├─ ku9/                         同上 + Configuration.json（★ 配置全量注释）· update.json · epg_data.json
└─ xplayer/                     同上（无 res_layout 业务布局：UI 在 Dart 层）
```

- 完整反编译树（含 smali）在 `/tmp/apkwork/<app>/apktool_out/`，**未入库**（体积 46–170 MB/个）。
- 各 APK 的 `.so` 体积清单见 `build/apk-re/<app>/native_libs.txt`。

### 附：本报告未覆盖/未能还原的项（诚实记录）

| APK | 未还原内容 | 原因 |
| --- | --- | --- |
| mediaplayer | `MainActivity.onKeyDown` 的完整分支表 | Kotlin 编译后 packed-switch 分散在多个 `$lambda` 中，已还原到「面板级」粒度 |
| TVBox | Exo 侧具体缓冲数值 | 常量被 ProGuard 内联，仅有 `DefaultLoadControl` 引用 |
| DIYP | **按键映射全表、IJK option 常量** | 控制流平坦化（`sparse-switch` on `String.hashCode()` 状态机）+ 资源混淆 |
| 酷9 | `LivePlayActivity` 按键映射全表 | NP 控制流混淆（156 435 行） |
| XPlayer | Dart 层算法与具体数值常量 | Dart AOT 快照不可反编译，仅做了架构与字符串级重建 |

以上均**未做猜测性补全**。
