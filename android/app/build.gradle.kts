plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.litv.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.litv.tv"
        // minSdk 21：覆盖 2015 年之后绝大多数电视盒子。
        // 低端盒子（1GB RAM）是这个项目的性能下限。
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // 注意：abiFilters 与 splits.abi 互斥（AGP 会报冲突）。
        // 这里只用 splits 声明 ABI，debug 包同理生效。
    }

    // ABI 分包：libVLC 一个 ABI 就 40MB，不分包整包会到 138MB。
    // 分包后每个 APK 只带自己那套 .so，体积降一个数量级。
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { viewBinding = true }

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

    // ---- 播放内核 4/4：AVS3 —— 由带 libuavs3d 的 libmpv 承担，不额外引依赖 ----

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")
}
