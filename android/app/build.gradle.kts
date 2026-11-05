// 注意：Gradle Kotlin DSL 里 `java` 指向 JavaPluginExtension，会遮蔽 java.* 包，
// 所以必须显式 import，否则 java.util.Properties 报 Unresolved reference。
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名口令从 local.properties 读（本地覆盖用）；没配置就用仓库内的自签 keystore。
//
// ⚠️ 这个 keystore 是随仓库分发的（CI 要用它出包），口令是公开的。
// 它的作用只是"让包能装、且后续版本能覆盖升级"，**不构成任何安全边界**。
// 真要发布到商店请换成自己的 keystore 且不要提交。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun prop(key: String, def: String): String = localProps.getProperty(key) ?: def

android {
    namespace = "com.litv.tv"
    compileSdk = 35

    signingConfigs {
        create("litv") {
            storeFile = rootProject.file(prop("LITV_STORE_FILE", "keystore/litv-release.jks"))
            storePassword = prop("LITV_STORE_PASSWORD", "LITV156980")
            keyAlias = prop("LITV_KEY_ALIAS", "litv")
            keyPassword = prop("LITV_KEY_PASSWORD", "LITV156980")

            // 三种签名方案全开：
            //   v1 (JAR signing) —— Android 6 及以下必须，老盒子靠它
            //   v2 (APK Signature Scheme v2) —— Android 7+ 的主力
            //   v3 —— Android 9+，支持密钥轮换
            // 电视是 Android 9，v3 能生效；但 v1/v2 不能关，否则老设备装不上。
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = false // v4 只用于增量安装，侧载用不到
        }
    }

    defaultConfig {
        applicationId = "com.litv.tv"
        // minSdk 21：覆盖 2015 年之后绝大多数电视盒子。
        // 低端盒子（1GB RAM）是这个项目的性能下限。
        minSdk = 21
        targetSdk = 35
        versionCode = 23
        versionName = "0.1.13"

        // 架构过滤走下面的 splits.abi —— 两者互斥，不能同时用。
    }

    // 按 ABI 拆包：每个 flavor 各出 armv7 / arm64 两个包。
    //
    // 一个"全架构通用包"要 138MB，拆开后每个约 70~75MB——
    // 电视盒子存储普遍紧张，装包体积是实际痛点。
    // x86 系列不参与发布：只有模拟器用得上，白白让包体翻倍。
    splits {
        abi {
            isEnable = true
            reset()
            // x86 只给模拟器用：本地/CI 跑 AVD 验证界面时需要有 x86 包，
            // 发布工作流只挑 arm 的两个文件，x86 不会进 Release。
            include("armeabi-v7a", "arm64-v8a", "x86")
            isUniversalApk = false
        }
    }

    // 电视版 / 手机版分开出包。
    //
    // 代码完全相同，差别只有两点：
    //   1) 默认界面形态（电视走全屏播放+浮层，手机走竖屏列表）
    //   2) 包名：手机版加 .phone 后缀，这样电视版和手机版可以同时装在一台机器上
    // 用户仍可在设置里用「界面模式」覆盖。
    flavorDimensions += "form"
    productFlavors {
        create("tv") {
            dimension = "form"
            buildConfigField("String", "UI_MODE", "\"tv\"")
        }
        create("phone") {
            dimension = "form"
            applicationIdSuffix = ".phone"
            buildConfigField("String", "UI_MODE", "\"phone\"")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("litv")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("litv")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // buildConfig：AGP 8 起默认不生成 BuildConfig，
    // 而我们要把 VERSION_NAME 上报给服务端（管理台显示"装的哪版"）。
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0", "META-INF/LGPL2.1",
                "META-INF/*.kotlin_module",
                "META-INF/DEPENDENCIES",
            )
        }
        // libmpv / libvlc 都是 .so，不要压缩（否则启动时要解压，慢且占双份空间）
        jniLibs { useLegacyPackaging = false }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("com.google.android.material:material:1.12.0")

    // ---- 播放内核 1/4：Media3 (ExoPlayer) —— 默认，硬解，起播最快 ----
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")

    // ---- 播放内核 2/4：libVLC —— 异常封装容错好 ----
    // 用 Maven 上的预编译包，避免自己编（自己编要 macOS + 一整套工具链）
    implementation("org.videolan.android:libvlc-all:3.5.1")

    // ---- 播放内核 3/4：libmpv —— 全格式兜底（.so 需自己交叉编译，见 native/build-libmpv.sh）----
    // 没有 Maven 坐标，靠 jniLibs 目录里的 libmpv.so + MPVLib.kt 的 JNI 绑定。
    // 未提供 .so 时 MpvEngine 会自报不可用，播放器自动降级，不影响运行。

    // ---- 播放内核：ijkplayer —— 直播起播最快（ffmpeg 内核）----
    //
    // 为什么加它：拆了三个参考 APK（mediaplayer / 酷9 / DIYP），
    // 酷9 和 DIYP 都用 ijkplayer，mediaplayer 用 mpv——**都是 ffmpeg 内核**。
    // 共同点是把 ffmpeg 起播前的流分析压到 0.1 秒：
    //   analyzeduration=100000  probesize=409600  packet-buffering=0
    // ExoPlayer 的 HLS 管线必须等一整个分片下载完才能起播，这是结构差距。
    //
    // 依赖原本在 jcenter（已关停），阿里云 public 镜像仍有缓存，
    // 所以不用自己交叉编译。x86 也带上，方便在模拟器上验证。
    implementation("tv.danmaku.ijk.media:ijkplayer-java:0.8.8")
    implementation("tv.danmaku.ijk.media:ijkplayer-armv7a:0.8.8")
    implementation("tv.danmaku.ijk.media:ijkplayer-arm64:0.8.8")
    implementation("tv.danmaku.ijk.media:ijkplayer-x86:0.8.8")

    // ---- 播放内核 4/4：AVS3 —— 由带 libuavs3d 的 libmpv 承担，不额外引依赖 ----

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // 二维码：TV 上打字太痛苦，绑定页显示二维码让手机扫，
    // 手机打开管理台直接批准这台设备。
    implementation("com.google.zxing:core:3.5.3")

    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")
}
