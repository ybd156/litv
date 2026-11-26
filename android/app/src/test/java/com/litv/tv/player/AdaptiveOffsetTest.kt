package com.litv.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveOffsetTest {

    @Test
    fun 一直健康就不动落后量() {
        val a = AdaptiveOffset()
        var t = 0L
        repeat(30) {
            t += 5_000
            assertFalse(a.onSample(9_000, t))
        }
        assertEquals(12_000L, a.currentMs)
    }

    @Test
    fun 连续两次垫子见底就加一档() {
        val a = AdaptiveOffset()
        assertFalse(a.onSample(400, 1_000))
        assertFalse(a.onSample(900, 6_000)) // 冷却期内不许连续加
        assertTrue(a.onSample(900, 62_000))
        assertEquals(18_000L, a.currentMs)
    }

    @Test
    fun 加档封在最大值() {
        val a = AdaptiveOffset()
        var t = 70_000L
        repeat(5) {
            a.onSample(300, t)
            a.onSample(300, t + 1_000)
            t += 70_000L
        }
        assertEquals(25_000L, a.currentMs)
    }

    @Test
    fun 垫子一直厚就退一档直到基准() {
        val a = AdaptiveOffset()
        // 先抬到 18 秒
        a.onSample(300, 1_000)
        a.onSample(300, 70_000)
        assertEquals(18_000L, a.currentMs)
        // 之后 12 次采样都富余 → 退回基准
        var t = 80_000L
        var changed = false
        repeat(AdaptiveOffset.HIGH_HITS) {
            t += 5_000
            changed = a.onSample(9_500, t) || changed
        }
        assertTrue(changed)
        assertEquals(12_000L, a.currentMs)
    }

    @Test
    fun 正常区间会衰减计数不会误判成连续() {
        val a = AdaptiveOffset()
        a.onSample(300, 1_000)   // lowHits=1
        a.onSample(5_000, 6_000) // 中间值，衰减回 0
        assertFalse(a.onSample(1_400, 70_000)) // 只算 1 次
        assertEquals(12_000L, a.currentMs)
    }

    @Test
    fun 退档不需要冷却且不会退到基准以下() {
        val a = AdaptiveOffset()
        a.onSample(300, 1_000)
        a.onSample(300, 70_000)
        var t = 80_000L
        repeat(AdaptiveOffset.HIGH_HITS * 3) {
            t += 5_000
            a.onSample(20_000, t)
        }
        assertEquals(12_000L, a.currentMs)
    }

    @Test
    fun reset回到基准并清计数() {
        val a = AdaptiveOffset()
        a.onSample(300, 1_000)
        a.onSample(300, 70_000)
        a.reset()
        assertEquals(12_000L, a.currentMs)
        assertFalse(a.onSample(300, 200_000)) // 计数已清，单次不触发
    }

    @Test
    fun stepBackMs给出需要往回退多少() {
        val a = AdaptiveOffset()
        val before = a.currentMs
        a.onSample(300, 1_000)
        a.onSample(300, 70_000)
        assertEquals(6_000L, a.stepBackMs(before))
        assertEquals(0L, a.stepBackMs(a.currentMs))
    }

    @Test
    fun 五分钟内两次见底中间健康过也算一段抖动() {
        val a = AdaptiveOffset()
        assertFalse(a.onSample(400, 1_000))
        assertFalse(a.onSample(9_000, 30_000)) // 健康采样把计数衰减到 0
        assertFalse(a.onSample(500, 120_000))  // 重新算第一次
        assertTrue(a.onSample(500, 180_000))   // 5 分钟内的第二次 → 加档
        assertEquals(18_000L, a.currentMs)
    }

    @Test
    fun 两次见底隔了六分钟就不算同一段抖动() {
        val a = AdaptiveOffset()
        assertFalse(a.onSample(300, 1_000))
        assertFalse(a.onSample(300, 1_000 + AdaptiveOffset.LOW_WINDOW_MS + 60_000))
        assertEquals(12_000L, a.currentMs)
    }
}
