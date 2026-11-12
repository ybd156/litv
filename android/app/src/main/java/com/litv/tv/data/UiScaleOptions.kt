package com.litv.tv.data

/**
 * 界面缩放档位（小 / 中 / 大）。
 *
 * 为什么需要：电视端的字号是按"55 寸、3 米外"定的，同一份 UI 放到 32 寸卧室电视
 * 或投影上就看不清；手机端横屏也太小。与其做两套布局，不如给一个缩放档位，
 * 让 ChannelAdapter / 设置页按比例放大字号与行高。
 *
 * 纯数据 + 纯函数（可 JVM 单测）：档位 key 落盘，**不落盘浮点数**——
 * 浮点落盘在升级/手改偏好后会出现 "0.9000001" 这种无法匹配的脏值。
 */
object UiScaleOptions {

    enum class Scale(
        val key: String,
        val label: String,
        /** 字号缩放系数，1.0 = 设计稿基准 */
        val fontScale: Float,
        /** 频道行高（dp），1.0 档位下约 68dp —— 一屏 9~10 个台 */
        val rowHeightDp: Int,
    ) {
        SMALL("s", "小", 0.90f, 60),
        MEDIUM("m", "中（推荐）", 1.00f, 68),
        LARGE("l", "大", 1.18f, 80),
    }

    val all: List<Scale> = Scale.entries

    /** 落盘的候选 key（设置页循环切换用）。 */
    val keys: List<String> = all.map { it.key }

    /** 未知/空 → 中档（升级后老用户的默认体验不变）。 */
    fun from(key: String?): Scale =
        all.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) } ?: Scale.MEDIUM

    /** 把基准字号换算成当前档位下的字号。 */
    fun scaled(sp: Float, key: String?): Float = sp * from(key).fontScale
}
