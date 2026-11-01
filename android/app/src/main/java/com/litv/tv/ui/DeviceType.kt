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

        // 1) 明确的电视特征：有 leanback 且没有触摸屏
        val hasLeanback = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val hasTouch = pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
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

    private fun hasHardwareKeyboard(context: Context): Boolean {
        val cfg = context.resources.configuration
        return cfg.keyboard != android.content.res.Configuration.KEYBOARD_NOKEYS &&
            cfg.hardKeyboardHidden == android.content.res.Configuration.HARDKEYBOARDHIDDEN_NO
    }

    /** 是否有触摸屏（决定要不要挂手势层）。 */
    fun hasTouch(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
}
