# libplayer-jni（vendored）

这是 `is.xyz.mpv` 的 JNI 桥源码，取自 mpv-android（GPLv2+，见 `LICENSE.mpv-android`），
用来编出 `libplayer.so` —— Kotlin 侧 `android/app/src/main/java/is/xyz/mpv/MPVLib.kt`
通过 `System.loadLibrary("player")` 加载它。

## 为什么要 vendor 进仓库

以前 `libplayer.so` 只是一个**来历不明的预编译二进制**（arm64/armv7 各一份，x86 没有）。
它和 ffmpeg 是强耦合的，2026-11-24 真机证据：

```
MPVLib: libplayer.so 加载失败：UnsatisfiedLinkError:
dlopen failed: cannot locate symbol "av_jni_set_java_vm" referenced by .../lib/arm/libplayer.so
PlayerManager: 内核 mpv 不可用，跳过
```

查 APK 内的 ELF：`libavcodec.so`（我们编的 n6.1）**已定义** `av_jni_set_java_vm`，
但旧 `libplayer.so` 还引用 `av_jni_set_android_app_ctx` —— 那是 **ffmpeg 7/8 那代**才有的
JNI API。也就是说这个二进制是"新 ffmpeg 代"编的，而 mpv 0.36 只能配 n6.1，
**没有任何一个 ffmpeg 能同时满足两者**。

结论：JNI 桥必须和 ffmpeg/libmpv **同一代一起编**，所以源码放进仓库，
由 `native/build-libmpv.sh` 的 `build_player_jni()` 用 ndk-build 编出三个 ABI 的
`libplayer.so`，与 libmpv/ffmpeg 同批产出。

## 与上游的差异（只有两处，都很小）

1. `Android.mk`：`LOCAL_CFLAGS := -Werror` → `-Wno-error`。
   本机/CI 的 clang 比上游开发时新，一堆新警告会被当错误挡住编译；只放宽严格性，不改代码。
2. 本仓库 `MPVLib.kt` 的 `grabThumbnail()` 已对齐上游签名 `grabThumbnail(dimension: Int)`
   （JNI 按签名匹配，参数不同就是不同的符号）。

## 维护

- 升级 mpv/ffmpeg 时，**先确认这个 JNI 目录与 `MPVLib.kt` 的方法签名仍一致**：
  `external fun` 的名字与参数类型是 JNI 签名的一部分，不一致会以
  `UnsatisfiedLinkError` 的形式在运行时才暴露。
- 不要把编好的 `.so` 提交进这个目录；产物由 CI 的 native-build 发到 Release `native-libs`。
