package com.litv.tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「画面比例」设置 → 缩放模式的映射。
 *
 * 这条测试挡的是一个**注释与代码相反**的既有 bug：
 * 注释说「自动 → 保持原始比例」，代码里 `auto` 却落到了 `SURFACE_FILL`，
 * 于是设置里选「自动」时画面被拉伸/裁切填满 —— 手机上看非 16:9 的台就变形。
 */
class AspectPolicyTest {

    @Test
    fun `自动要保持原始比例 不能铺满`() {
        // ★ 这条在旧实现上会失败：旧代码 else 分支是 SURFACE_FILL
        assertEquals(AspectMode.BEST_FIT, AspectPolicy.of("auto"))
    }

    @Test
    fun `未知值也保持原始比例 宁可留黑边`() {
        assertEquals(AspectMode.BEST_FIT, AspectPolicy.of(""))
        assertEquals(AspectMode.BEST_FIT, AspectPolicy.of("something-new"))
    }

    @Test
    fun `显式的两种强制比例`() {
        assertEquals(AspectMode.RATIO_16_9, AspectPolicy.of("16:9"))
        assertEquals(AspectMode.RATIO_4_3, AspectPolicy.of("4:3"))
    }

    @Test
    fun `铺满只在显式选择时生效`() {
        assertEquals(AspectMode.FILL, AspectPolicy.of("fill"))
        // iOS 侧用的名字，同义
        assertEquals(AspectMode.FILL, AspectPolicy.of("zoom"))
    }

    @Test
    fun `大小写与空格不影响判定`() {
        assertEquals(AspectMode.FILL, AspectPolicy.of(" FILL "))
        assertEquals(AspectMode.RATIO_16_9, AspectPolicy.of("16:9"))
    }
}
