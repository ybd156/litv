package com.litv.tv.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.litv.tv.LitvApp

/**
 * 统一入口：按设备类型分流到 TV 首页或手机首页。
 *
 * 这样一份 APK 两边都能装，用户不用纠结下哪个版本。
 * manifest 里这个 Activity 同时挂了 LAUNCHER 和 LEANBACK_LAUNCHER。
 */
class LauncherActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as LitvApp

        // 最可靠的信号：从电视桌面（leanback launcher）点进来的。
        // 部分电视盒子会同时上报"有触摸屏 + 屏宽 < 600dp"，
        // 光看设备特征会把电视误判成手机，跑出竖屏小窗的界面。
        val fromLeanback = intent?.categories?.contains(
            android.content.Intent.CATEGORY_LEANBACK_LAUNCHER
        ) == true

        // 优先级：用户在设置里手动指定 > 打包时的 flavor > 设备特征自动判断。
        // 电视版/手机版是两个独立的包，flavor 决定了默认形态，
        // 免得把手机版装到电视上时又跑成竖屏小窗。
        val isTV = when (app.prefs.uiMode) {
            "tv" -> true
            "phone" -> false
            else -> when (com.litv.tv.BuildConfig.UI_MODE) {
                "tv" -> true
                "phone" -> false
                else -> fromLeanback || DeviceType.isTV(this)
            }
        }
        // 把判定依据打出来：用户反馈"电视上跑成了手机界面"时，
        // 一眼就能看出是哪个信号判错了。
        android.util.Log.i(
            "LITV",
            "界面模式=${app.prefs.uiMode} leanbackIntent=$fromLeanback " + DeviceType.describe(this)
        )

        val target = when {
            app.prefs.token.isBlank() -> BindActivity::class.java
            // 电视端没有「首页」这个概念：开机直接全屏播放上次的台，
            // 返回就退出 App。频道列表是按 ← 呼出的浮层。
            // 之前这里进 HomeActivity（三列首页），所以从播放页返回会掉回那个页面，
            // 和参考实现（DIYP/TVBox 系）的操作逻辑不一致。
            isTV -> PlayerActivity::class.java
            else -> PhoneHomeActivity::class.java
        }
        startActivity(Intent(this, target))
        finish()
    }
}
