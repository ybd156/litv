package com.litv.tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 屏幕状态轮询的判据。
 *
 * 为什么要有轮询：电视上按电源键只关面板，`ACTION_SCREEN_OFF` 广播在某些
 * 路径上不投递（2026-11-26 实测：用户在客厅看到「息屏还有声音」，而
 * `play.log` 里没有一行「屏幕熄灭」），所以必须自己每 5 秒问一次
 * `PowerManager.isInteractive`。
 *
 * 这段逻辑一旦判错，表现是「亮屏了声音还不回来」或「息屏还在响」，
 * 都很难在真机上一眼看出谁错，所以在这里把边界钉死。
 */
class ScreenPowerWatchTest {

    @Test
    fun `第一次只建立基线，不动作`() {
        // 冷启动时屏幕大概率是亮的；若此时就当「从熄灭变亮」去恢复音量，
        // 会把用户自己调低的声音重设成 1.0。
        assertEquals(ScreenPowerEvent.None, ScreenPowerWatch.decide(null, true))
        assertEquals(ScreenPowerEvent.None, ScreenPowerWatch.decide(null, false))
    }

    @Test
    fun `状态没变就什么都不做`() {
        assertEquals(ScreenPowerEvent.None, ScreenPowerWatch.decide(true, true))
        assertEquals(ScreenPowerEvent.None, ScreenPowerWatch.decide(false, false))
    }

    @Test
    fun `从亮变暗要静音`() {
        assertEquals(ScreenPowerEvent.Pause, ScreenPowerWatch.decide(true, false))
    }

    @Test
    fun `从暗变亮要恢复音量`() {
        assertEquals(ScreenPowerEvent.Resume, ScreenPowerWatch.decide(false, true))
    }
}
