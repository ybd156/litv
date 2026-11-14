package com.litv.tv.ui

import android.content.Context
import android.content.res.Configuration

/**
 * 电视界面按屏幕宽度等比例缩放。
 *
 * 为什么需要：整套尺寸是按 960dp 宽（1080p 电视）设计的。手机横屏只有 400~500dp，
 * 写死的 16sp/21sp 和 padding 在那种宽度下就"顶格"——台号被裁、文字贴边、
 * 列宽比例虽然对但整体观感严重放大。
 *
 * 做法：把 resources 的 densityDpi 按 (实际宽度dp / 设计宽度dp) 重算一次。
 * dp 与 sp 都跟随 density，于是一屏内容在任何宽度下比例完全一致，
 * 不需要给每个尺寸写多套 dimens，也不会漏掉某个写死的值。
 *
 * 只在「电视界面跑在非电视设备上」时生效：
 *   · 真电视（960dp 或 4K 的 1920dp）本来就按设计比例，不动它
 *   · 手机/平板打开电视界面时缩放，保证同样的布局关系
 */
object TvUiScaler {
    /** 设计基准宽度（dp）。1080p / 320dpi 电视上正好 960。 */
    private const val DESIGN_WIDTH_DP = 960f

    /** 缩放比限制在 0.4~2.5，避免极端分辨率把界面缩没了或撑爆。 */
    private const val MIN_SCALE = 0.4f
    private const val MAX_SCALE = 2.5f

    fun wrap(base: Context): Context {
        val dm = base.resources.displayMetrics
        val widthDp = dm.widthPixels / dm.density
        if (widthDp <= 0f) return base

        // 用户选的「界面缩放」也参与其中：小 0.90 / 中 1.00 / 大 1.18。
        // 以前这里写死一个收缩系数，等于绕过用户的设置，而且每台机器观感不同没法统一。
        val userScale = run {
            val key = base.getSharedPreferences("litv", Context.MODE_PRIVATE)
                .getString("ui_scale", "m") ?: "m"
            when (key) {
                "xs" -> 0.78f
                "s" -> 0.90f
                "l" -> 1.18f
                else -> 1.00f
            }
        }
        var scale = (widthDp / DESIGN_WIDTH_DP) * userScale
        if (scale !in MIN_SCALE..MAX_SCALE) {
            scale = scale.coerceIn(MIN_SCALE, MAX_SCALE)
        }
        // 已经接近设计宽度就不用折腾（1% 以内视为相同）
        if (kotlin.math.abs(scale - 1f) < 0.01f) return base

        val cfg = Configuration(base.resources.configuration)
        cfg.densityDpi = (dm.densityDpi * scale).toInt()
        return base.createConfigurationContext(cfg)
    }
}
