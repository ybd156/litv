## 下载哪个

| 设备 | 文件 |
|---|---|
| **电视 / 盒子，32 位**（Android 9 及以下装这个） | `LITV-<版本>-tv-armv7.apk` |
| 电视 / 盒子，64 位 | `LITV-<版本>-tv-arm64.apk` |
| 手机，32 位（2018 年前的老机型） | `LITV-<版本>-phone-armv7.apk` |
| 手机，64 位（绝大多数手机） | `LITV-<版本>-phone-arm64.apk` |
| 飞牛 NAS（N100 等 x86） | `litv_x86_<版本>.fpk` |
| 飞牛 NAS（ARM 机型） | `litv_arm_<版本>.fpk` |
| iPhone / iPad | `LITV-<版本>-unsigned.ipa` |

不确定架构：设备上跑 `adb shell getprop ro.product.cpu.abi`，
`armeabi-v7a` 装 armv7，`arm64-v8a` 装 arm64。
电视版和手机版包名不同（`com.litv.tv` / `com.litv.tv.phone`），可以同时装。

## 安装说明

**飞牛 NAS**：应用中心 → 手动安装 → 选 `.fpk`，向导里填端口和管理员密码。
装完从桌面图标打开，走飞牛统一网关，自动是登录状态。

**安卓电视 / 手机**：直接装 apk。首次启动显示 6 位绑定码，在管理台「终端设备」页批准。
已签名（v1 + v2 + v3），可直接覆盖升级。

**iOS**：ipa 未签名，需要自己签：
- Sideloadly（Windows / Mac，需要 Apple ID，免费证书 7 天）
- SideStore（首次装好后可在设备上自动续签）

## 合规声明

本软件是中立的**源管理与播放外壳**，不提供、不预装、不分发任何直播源或内容。
所有源由用户自行导入，并自负合规责任。
