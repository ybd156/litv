package com.litv.tv.player

/**
 * 「画面比例」设置 → 内核该怎么缩放的**纯映射**。
 *
 * 为什么单独抽出来：`VlcEngine.scaleFor()` 里原来写着
 * ```
 * "16:9" -> SURFACE_16_9
 * "4:3"  -> SURFACE_4_3
 * "fill" -> SURFACE_FILL
 * else   -> SURFACE_FILL      ← 自动（auto）落到这里
 * ```
 * 而它上面的注释写的是「自动 → 保持原始比例（必要时留黑边）」——
 * **注释和代码相反**：`auto` 实际走的是 `SURFACE_FILL`，
 * 也就是拉伸/裁切填满，于是手机上看 4:3 或非 16:9 的台会被拉变形。
 *
 * 这就是「手机界面上 VLC 播放没有按比例」的直接原因。
 */
enum class AspectMode {
    /** 保持原始比例，必要时留黑边（= 设置里的「自动」） */
    BEST_FIT,

    /** 裁切填满（= 设置里的「铺满」） */
    FILL,

    /** 强制 16:9 */
    RATIO_16_9,

    /** 强制 4:3 */
    RATIO_4_3,
}

object AspectPolicy {

    /**
     * @param setting 设置里的原始值：`auto` / `16:9` / `4:3` / `fill`
     *                （iOS 侧还有 `zoom`，同名同义，一并认）
     *
     * 关键规则：**只有显式要求铺满时才 FILL，其余一律保持原始比例。**
     * 未知值也走 BEST_FIT —— 宁可留黑边，也不要把画面拉变形。
     */
    fun of(setting: String): AspectMode = when (setting.trim().lowercase()) {
        "16:9" -> AspectMode.RATIO_16_9
        "4:3" -> AspectMode.RATIO_4_3
        "fill", "zoom" -> AspectMode.FILL
        else -> AspectMode.BEST_FIT
    }
}
