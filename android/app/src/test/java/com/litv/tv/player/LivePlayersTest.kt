package com.litv.tv.player

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 息屏暂停 / 亮屏恢复的记账。
 *
 * 真机上要验的是「电视息屏后 AudioTrack 消失」，但那条路径依赖系统广播，
 * 单测只能守住最容易写错的部分：**亮屏时到底恢复了谁**。
 */
class LivePlayersTest {

    private class Fake(val name: String) : ScreenPausable {
        var pauses = 0
        var resumes = 0
        override fun pause() {
            pauses++
        }

        override fun resume() {
            resumes++
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
        assertEquals(0, LivePlayers.screenOffPausedCount())
        assertEquals(0, LivePlayers.resumeAfterScreenOn())
        assertEquals(0, a.resumes)
    }

    @Test
    fun `息屏暂停的那一批，亮屏时会被恢复`() {
        val a = Fake("a")
        LivePlayers.register(a)

        assertEquals(1, LivePlayers.pauseAllForScreenOff())
        assertEquals(1, LivePlayers.screenOffPausedCount())
        assertEquals(1, LivePlayers.resumeAfterScreenOn())
        assertEquals(1, a.resumes)
        // 恢复只发生一次
        assertEquals(0, LivePlayers.resumeAfterScreenOn())
        assertEquals(1, a.resumes)
    }

    @Test
    fun `息屏之后才起播的播放器，亮屏时不会被动到`() {
        val a = Fake("a")
        LivePlayers.register(a)
        LivePlayers.pauseAllForScreenOff()

        val late = Fake("late")
        LivePlayers.register(late)

        assertEquals(1, LivePlayers.resumeAfterScreenOn())
        assertEquals(1, a.resumes)
        assertEquals(0, late.resumes)
        assertEquals(0, late.pauses)
    }

    @Test
    fun `已注销的播放器不会被亮屏恢复`() {
        val a = Fake("a")
        val b = Fake("b")
        LivePlayers.register(a)
        LivePlayers.register(b)
        LivePlayers.pauseAllForScreenOff()

        LivePlayers.unregister(b)

        assertEquals(1, LivePlayers.resumeAfterScreenOn())
        assertEquals(1, a.resumes)
        assertEquals(0, b.resumes)
    }

    @Test
    fun `reset 之后登记表与息屏记账都清空`() {
        LivePlayers.register(Fake("a"))
        LivePlayers.pauseAllForScreenOff()

        LivePlayers.reset()

        assertEquals(0, LivePlayers.count())
        assertEquals(0, LivePlayers.screenOffPausedCount())
    }
}
