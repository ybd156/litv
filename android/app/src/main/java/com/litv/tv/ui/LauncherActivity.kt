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

        val isTV = when (app.prefs.uiMode) {
            "tv" -> true
            "phone" -> false
            else -> DeviceType.isTV(this)
        }
        // 把判定依据打出来：用户反馈"手机上进了电视界面"时，
        // 一眼就能看出是哪个信号判错了（leanback / uiMode / 屏幕宽度）。
        android.util.Log.i("LITV", "界面模式=${app.prefs.uiMode} " + DeviceType.describe(this))

        val target = when {
            app.prefs.token.isBlank() -> BindActivity::class.java
            isTV -> HomeActivity::class.java
            else -> PhoneHomeActivity::class.java
        }
        startActivity(Intent(this, target))
        finish()
    }
}
