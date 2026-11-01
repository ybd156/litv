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

        // 0) 有触摸屏 + 屏幕窄 + **不是 leanback 设备** → 一定是手机。
        //
        //    为什么要加 !hasLeanback 这个条件：部分电视盒子（小米电视棒之类）
        //    会同时上报 FEATURE_TOUCHSCREEN 和较窄的 smallestScreenWidthDp，
        //    只看这两项会把电视误判成手机，跑出竖屏小窗的界面。
        //    上报了 leanback 就说明它有电视桌面，按电视处理。
        val sw = context.resources.configuration.smallestScreenWidthDp
        if (hasTouch && sw in 1..599 && !hasLeanback) return false

        // 1) 明确的电视特征：有 leanback 且没有触摸屏
        if (hasLeanback && !hasTouch) return true

        // 2) UiModeManager 判定
        val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        if (uiMode?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) {
            return true
        }

        // 3) 有触摸屏基本就是手机/平板
        if (hasTouch) return false

        // 4) 兜底：没有触摸屏且没有输入法（盒子常见）
        return !hasHardwareKeyboard(context)
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
