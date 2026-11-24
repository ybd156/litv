package com.litv.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「实时比」的判据测试。
 *
 * 这几条锁的是**测量口径**，不是播放行为：
 * 写错分母（漏掉暂停时段、把换台的负增量算进去）会让日志给出
 * 「0.3x 在掉队」这种假警报，比不测更糟。
 */
class PlaybackRateTest {

    @Test
    fun `正常跟播约等于一`() {
        assertEquals(1.0, PlaybackRate.ratio(30_000, 30_000)!!, 1e-9)
        assertFalse(PlaybackRate.isBehind(30_000, 30_000))
    }

    @Test
    fun `掉队时小于一，描述里带标记`() {
        assertEquals(0.7, PlaybackRate.ratio(21_000, 30_000)!!, 1e-9)
        assertTrue(PlaybackRate.describe(21_000, 30_000)!!.contains("在掉队"))
        assertTrue(PlaybackRate.isBehind(21_000, 30_000))
    }

    @Test
    fun `追赶时大于一，不报警`() {
        val r = PlaybackRate.ratio(45_000, 30_000)!!
        assertTrue(r > 1.4)
        assertFalse(PlaybackRate.isBehind(45_000, 30_000))
    }

    @Test
    fun `墙钟为零不产生样本`() {
        assertNull(PlaybackRate.ratio(1_000, 0))
        assertNull(PlaybackRate.describe(1_000, 0))
        assertFalse(PlaybackRate.isBehind(1_000, 0))
    }

    @Test
    fun `位置回退不算样本`() {
        // 换台 / seek / 内核重建都会让位置回退，算进分母就会出现负的「实时比」
        assertNull(PlaybackRate.ratio(-5_000, 30_000))
    }

    @Test
    fun `刚好等于阈值不算掉队`() {
        assertFalse(PlaybackRate.isBehind(95, 100))
        assertTrue(PlaybackRate.isBehind(94, 100))
    }

    @Test
    fun `帧率样本按秒折算`() {
        // 300 帧 / 30 秒 = 10 帧/秒
        assertEquals(10.0, PlaybackRate.framesPerSecond(300, 30_000)!!, 1e-9)
        assertNull(PlaybackRate.framesPerSecond(300, 0))
        assertNull(PlaybackRate.framesPerSecond(-1, 30_000))
    }

    /**
     * 直播 HLS 的滑动窗口会让位置周期性回落。第一版直接相除，实测立刻给出
     * `实时比 0.00x ← 在掉队`（画面却是好的）—— 这条锁住「有重置就不算比例」。
     */
    @Test
    fun `有位置重置时只报帧推进，不给实时比`() {
        val line = PlaybackRate.describeSample(
            framesDelta = 750,
            posDeltaMs = 19,
            wallDeltaMs = 30_000,
            resets = 3,
        )
        assertTrue(line.contains("750"))
        assertTrue(line.contains("帧/秒"))
        assertTrue(line.contains("实时比不适用"))
        assertFalse(line.contains("在掉队"))
        // 同一个样本，没有重置时才算比例
        assertFalse(PlaybackRate.isBehind(19, 30_000, resets = 3))
        assertTrue(PlaybackRate.isBehind(19, 30_000, resets = 0))
    }

    @Test
    fun `没有重置时正常给实时比`() {
        val line = PlaybackRate.describeSample(900, 30_000, 30_000, 0)
        assertTrue(line.contains("1.00x"))
        assertFalse(line.contains("不适用"))
    }
}
