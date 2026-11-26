package com.litv.tv.player

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 退后台暂停 / 息屏静音的记账。
 *
 * 真机上要验的是「息屏后 AudioTrack 不再 state:started」与「亮屏后声音回来」，
 * 但那条路径依赖系统广播；单测只能守住最容易写错的部分：
 * **亮屏时到底恢复了谁**（2026-11-26 用户改口径：息屏只静音，不停播）。
 */
class LivePlayersTest {

    private class Fake(val name: String) : ScreenControllable {
        var pauses = 0
        var resumes = 0
        var mutes = 0
        var unmutes = 0

        override fun pause() {
            pauses++
        }

        override fun resume() {
            resumes++
        }

        override fun mute() {
            mutes++
        }

        override fun unmute() {
            unmutes++
        }
    }

    @Before
    fun setUp() {
        LivePlayers.reset()
    }

    @After
    fun tearDown() {
        LivePlayers.reset()
    }

    @Test
    fun `退到后台的兜底会暂停所有还活着的播放器`() {
        val a = Fake("a")
        val b = Fake("b")
        LivePlayers.register(a)
        LivePlayers.register(b)

        assertEquals(2, LivePlayers.pauseAll())
        assertEquals(1, a.pauses)
        assertEquals(1, b.pauses)
        // 普通兜底不记账，亮屏时不该顺手恢复
        assertEquals(0, LivePlayers.screenOffMutedCount())
        assertEquals(0, LivePlayers.unmuteAfterScreenOn())
        assertEquals(0, a.unmutes)
    }

    @Test
    fun `息屏的那一批，亮屏时会被恢复音量`() {
        val a = Fake("a")
        LivePlayers.register(a)

        assertEquals(1, LivePlayers.muteAllForScreenOff())
        assertEquals(1, LivePlayers.screenOffMutedCount())
        // 息屏只静音，不动播放状态
        assertEquals(0, a.pauses)

        assertEquals(1, LivePlayers.unmuteAfterScreenOn())
        assertEquals(1, a.unmutes)
        // 恢复只发生一次
        assertEquals(0, LivePlayers.unmuteAfterScreenOn())
        assertEquals(1, a.unmutes)
    }

    @Test
    fun `息屏之后才起播的播放器，亮屏时不会被动到`() {
        val a = Fake("a")
        LivePlayers.register(a)
        LivePlayers.muteAllForScreenOff()

        val late = Fake("late")
        LivePlayers.register(late)

        assertEquals(1, LivePlayers.unmuteAfterScreenOn())
        assertEquals(1, a.unmutes)
        assertEquals(0, late.unmutes)
        assertEquals(0, late.mutes)
    }

    @Test
    fun `已注销的播放器不会被亮屏恢复`() {
        val a = Fake("a")
        val b = Fake("b")
        LivePlayers.register(a)
        LivePlayers.register(b)
        LivePlayers.muteAllForScreenOff()

        LivePlayers.unregister(b)

        assertEquals(1, LivePlayers.unmuteAfterScreenOn())
        assertEquals(1, a.unmutes)
        assertEquals(0, b.unmutes)
    }

    @Test
    fun `reset 之后登记表与息屏记账都清空`() {
        LivePlayers.register(Fake("a"))
        LivePlayers.muteAllForScreenOff()

        LivePlayers.reset()

        assertEquals(0, LivePlayers.count())
        assertEquals(0, LivePlayers.screenOffMutedCount())
    }

    @Test
    fun `连续两次息屏只记最后一次，亮屏不会重复恢复`() {
        val a = Fake("a")
        LivePlayers.register(a)

        LivePlayers.muteAllForScreenOff()
        LivePlayers.muteAllForScreenOff()

        assertEquals(1, LivePlayers.screenOffMutedCount())
        assertEquals(1, LivePlayers.unmuteAfterScreenOn())
        assertEquals(1, a.unmutes)
    }
}
