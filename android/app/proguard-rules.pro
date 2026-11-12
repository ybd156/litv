# LITV ProGuard 规则
#
# 核心原则：宁可多保留，也不要 release 包崩溃。
# 这个 App 是给家人用的，出问题没人会看 logcat。

# ---- Gson：反射读字段，混淆后字段名对不上就全 null ----
-keep class com.litv.tv.data.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**

# ---- libmpv JNI：native 方法名必须原样保留 ----
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.litv.tv.player.MpvNative { *; }

# ---- libVLC：大量 JNI 与反射 ----
-keep class org.videolan.** { *; }
-dontwarn org.videolan.**

# ---- Media3 ----
-dontwarn androidx.media3.**
-keep class androidx.media3.** { *; }

# ---- OkHttp ----
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# ---- Kotlin ----
-keepclassmembers class **$WhenMappings { <fields>; }
-keep class kotlin.Metadata { *; }

# 保留行号，崩溃栈才有意义
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- libmpv JNI 回调 ----
# native 侧用 GetStaticMethodID 反射查 is.xyz.mpv.MPVLib 的静态方法，
# R8 一改名/删掉就 abort（实测报 no static method eventProperty）。
# 整个包都要保。
-keep class is.xyz.mpv.** { *; }
-dontwarn is.xyz.mpv.**
