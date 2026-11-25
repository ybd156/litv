package com.litv.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回看播放头的行为验收。
 *
 * 为什么要单独测这个纯逻辑：进度条是**唯一**告诉用户「你跳到哪了」的反馈，
 * 一旦它和实际 seek 值不同源，用户按「快进 30 秒」看到的却是跳 10 秒，
 * 或者暂停一会儿回来进度条直接蹿到末尾——这类错在真机上极难看出来
 * （画面还在播，只有时间码不对）。
 */
class ReplayControlsTest {

    @Test
    fun `播放头按墙钟前进`() {
        val c = ReplayClock(durationMs = 60 * 60_000L)
        c.reset(nowMs = 0L)
        assertEquals(5_000L, c.tick(5_000L))
        assertEquals(12_000L, c.tick(12_000L))
    }

    @Test
    fun `播放头不会超过总时长`() {
        val c = ReplayClock(durationMs = 30_000L)
        c.reset(0L)
        assertEquals(30_000L, c.tick(99_000L))
        assertTrue(c.nearEnd)
    }

    @Test
    fun `暂停期间播放头不动`() {
        val c = ReplayClock(durationMs = 600_000L)
        c.reset(0L)
        c.tick(10_000L)
        c.pause(10_000L)
        assertEquals(10_000L, c.tick(40_000L))
        assertFalse(c.running)
    }

    @Test
    fun `继续时从暂停处接着走，不把暂停时长算进去`() {
        val c = ReplayClock(durationMs = 600_000L)
        c.reset(0L)
        c.tick(10_000L)
        c.pause(10_000L)
        c.resume(nowMs = 100_000L)
        assertEquals(10_000L, c.tick(100_000L))
        // 恢复后走 3 秒，应该是 13 秒，而不是「墙钟 103 秒」
        assertEquals(13_000L, c.tick(103_000L))
    }

    @Test
    fun `跳转会夹在 0 与总时长之间`() {
        val c = ReplayClock(durationMs = 60_000L)
        c.reset(0L)
        assertEquals(0L, c.seekTo(0L, -30_000L))
        assertEquals(60_000L, c.seekTo(0L, 99_000L))
    }

    @Test
    fun `暂停时按快进也以当前播放头为基准`() {
        val c = ReplayClock(durationMs = 600_000L)
        c.reset(0L)
        c.tick(20_000L)
        c.pause(20_000L)
        // 暂停 5 分钟后按快进 30 秒：应该是 20s + 30s，而不是墙钟 5 分钟 + 30s
        assertEquals(50_000L, c.seekBy(nowMs = 320_000L, deltaMs = ReplaySteps.BUTTON_STEP_MS))
    }

    @Test
    fun `跳转之后继续按墙钟前进`() {
        val c = ReplayClock(durationMs = 600_000L)
        c.reset(0L)
        c.seekTo(nowMs = 0L, targetMs = 300_000L)
        assertEquals(301_000L, c.tick(1_000L))
    }

    @Test
    fun `进度百分比与末尾判定`() {
        val c = ReplayClock(durationMs = 100_000L)
        c.reset(0L)
        c.seekTo(0L, 50_000L)
        assertEquals(50, c.progressPercent)
        assertFalse(c.nearEnd)
        c.seekTo(0L, 96_000L)
        assertTrue(c.nearEnd)
        c.seekTo(0L, 100_000L)
        assertEquals(100, c.progressPercent)
    }

    @Test
    fun `时长未知时不设上限但仍不接受负数`() {
        assertEquals(0L, ReplaySteps.clamp(-1L, 0L))
        assertEquals(3_600_000L, ReplaySteps.clamp(3_600_000L, 0L))
        assertEquals(0, ReplayClock(0L).progressPercent)
    }

    @Test
    fun `时间码格式`() {
        assertEquals("00:00", ClockText.fmt(0L))
        assertEquals("00:09", ClockText.fmt(9_400L))
        assertEquals("12:34", ClockText.fmt(754_000L))
        assertEquals("1:02:03", ClockText.fmt(3_723_000L))
        // 负数（内核偶尔报 -1）按 0 显示，不要出现 "-1:-1"
        assertEquals("00:00", ClockText.fmt(-1L))
        assertEquals("05:00 / 30:00", ClockText.pair(300_000L, 1_800_000L))
        assertEquals("05:00 / --:--", ClockText.pair(300_000L, -1L))
        // 总长跨过 1 小时，左边也要补齐位数，否则秒位错行（模拟器上就是 00:57 / 1:00:00）
        assertEquals("00:00:57 / 01:00:00", ClockText.pair(57_000L, 3_600_000L))
        assertEquals("01:02:03 / 02:02:00", ClockText.pair(3_723_000L, 7_320_000L))
    }
}
