// LITV Android 工程
//
// 仓库策略（按国内网络实测调过）：
//   1. 阿里云镜像优先 —— Maven Central 的依赖（okhttp/gson/libvlc）走它最快
//   2. Google 官方仓库 —— AGP / Media3 / AndroidX 只有它有
//   3. 官方 Central 兜底
//
// 注意：AGP 的 plugin marker 只存在于 Google 仓库，阿里云的 gradle-plugin
// 仓库里没有（会 404），所以 pluginManagement 里必须保留 google()。
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        // 阿里云的 google 镜像覆盖大部分 AndroidX/Media3
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        // libVLC 偶尔只在官方仓库有
        maven("https://oss.sonatype.org/content/repositories/releases")
    }
}

rootProject.name = "LITV"
include(":app")
