package com.litv.tv.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * 设备类型判定：同一份 APK 要同时服务电视盒子和手机。
 *
 * 判定顺序（从可靠到不可靠）：
 *  1. 有没有 LEANBACK_LAUNCHER 且没有触摸屏 → 一定是 TV
 *  2. UiModeManager 的 currentModeType == UI_MODE_TYPE_TELEVISION
 *  3. 有触摸屏且不是 TV → 手机/平板
 *
 * 为什么要判：TV 要网格 + 遥控器焦点，手机要列表 + 触摸手势，
 * 两套 UI 混在一起会让两边都难用。
 */
object DeviceType {

    fun isTV(context: Context): Boolean {
        val pm = context.packageManager
        val hasTouch = pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        val hasLeanback = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val sw = context.resources.configuration.smallestScreenWidthDp
        val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        val tvUiMode = uiMode?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION

        // ★ 判定顺序很重要：UiMode 是最强信号，必须**最先**看。
        //   原来它排在第 3 位，前面「有触摸屏 → 手机」的分支会先把带触摸屏的
        //   电视盒子判成手机（这类机器确实存在），自动识别就"识别不出来"了。
        val isTv = when {
            tvUiMode -> true
            hasLeanback && !hasTouch -> true
            hasTouch && sw in 1..599 && !hasLeanback -> false
            hasTouch -> false
            else -> !hasHardwareKeyboard(context)
        }
        android.util.Log.i(
            "DeviceType",
            "自动识别: uiMode=${uiMode?.currentModeType} leanback=$hasLeanback " +
                "touch=$hasTouch sw=${sw}dp -> " + if (isTv) "电视" else "手机",
        )
        return isTv
    }

    /** 给日志用：说明为什么判成了这个类型。 */
    fun describe(context: Context): String {
        val pm = context.packageManager
        val cfg = context.resources.configuration
        val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        return "isTV=${isTV(context)} touch=${pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)}" +
            " leanback=${pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)}" +
            " uiMode=${uiMode?.currentModeType}" +
            " sw=${cfg.smallestScreenWidthDp}dp" +
            " ${cfg.screenWidthDp}x${cfg.screenHeightDp}dp" +
            " orientation=${if (cfg.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) "portrait" else "landscape"}"
    }

    private fun hasHardwareKeyboard(context: Context): Boolean {
        val cfg = context.resources.configuration
        return cfg.keyboard != android.content.res.Configuration.KEYBOARD_NOKEYS &&
            cfg.hardKeyboardHidden == android.content.res.Configuration.HARDKEYBOARDHIDDEN_NO
    }

    /** 是否有触摸屏（决定要不要挂手势层）。 */
    fun hasTouch(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
}
