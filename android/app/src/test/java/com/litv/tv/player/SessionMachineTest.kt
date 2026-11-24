package com.litv.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 会话状态机的迁移测试。
 *
 * 这些用例锁的是评审 P0-4 点名过的两类事故：
 *  ① **终态不终**（release 之后还能被 play() 拉回来）；
 *  ② **非法状态可表示**（没起播就暂停、没首帧就暂停、放弃后又被隐式唤醒）。
 * 只测纯逻辑，不碰 Android。
 */
class SessionMachineTest {

    private fun machineAt(vararg events: SessionEvent): SessionMachine =
        SessionMachine().apply { events.forEach { accept(it) } }

    @Test
    fun `正常起播链：空闲 取址 等首帧 播放`() {
        val m = SessionMachine()
        assertEquals(SessionState.Acquiring, m.accept(SessionEvent.Start))
        assertEquals(SessionState.Starting, m.accept(SessionEvent.EngineStarted))
        assertEquals(SessionState.Playing, m.accept(SessionEvent.FirstFrame))
        assertEquals(SessionState.Playing, m.state)
    }

    @Test
    fun `暂停与恢复只在播放态合法`() {
        val m = machineAt(SessionEvent.Start, SessionEvent.EngineStarted, SessionEvent.FirstFrame)
        assertEquals(SessionState.Paused, m.accept(SessionEvent.Pause))
        assertEquals(SessionState.Playing, m.accept(SessionEvent.Resume))

        // 等首帧阶段没有「暂停」语义：位置还没建立，暂停只会让预算与看门狗打架
        val starting = machineAt(SessionEvent.Start, SessionEvent.EngineStarted)
        assertNull(starting.accept(SessionEvent.Pause))
        assertEquals(SessionState.Starting, starting.state)
    }

    @Test
    fun `未起播不能暂停`() {
        val m = SessionMachine()
        assertNull(m.accept(SessionEvent.Pause))
        assertEquals(SessionState.Idle, m.state)
    }

    /**
     * 这条对应旧代码里的真事故：release() 把 released 置 true，但 play() 又把它写回 false，
     * 于是「已释放」的实例还能被重新起播。这里要求 Released 之后**任何事件都不再生效**。
     */
    @Test
    fun `释放是终态，之后任何事件都无效`() {
        val m = machineAt(SessionEvent.Start, SessionEvent.EngineStarted, SessionEvent.FirstFrame)
        assertEquals(SessionState.Released, m.accept(SessionEvent.Release))

        val all = listOf(
            SessionEvent.Start, SessionEvent.EngineStarted, SessionEvent.FirstFrame,
            SessionEvent.Pause, SessionEvent.Resume, SessionEvent.Degrade,
            SessionEvent.GiveUp, SessionEvent.Stop,
        )
        for (e in all) {
            assertNull("Released 之后 ${e.javaClass.simpleName} 不该生效", m.accept(e))
            assertEquals(SessionState.Released, m.state)
        }
    }

    @Test
    fun `降级后要重新等首帧，不能直接算播放`() {
        val m = machineAt(
            SessionEvent.Start, SessionEvent.EngineStarted, SessionEvent.FirstFrame,
            SessionEvent.Degrade,
        )
        assertEquals(SessionState.Degraded, m.state)
        // 直接给首帧是非法：新内核还没起，这个首帧来自上一个内核的残留回调
        assertNull(m.accept(SessionEvent.FirstFrame))
        assertEquals(SessionState.Starting, m.accept(SessionEvent.EngineStarted))
        assertEquals(SessionState.Playing, m.accept(SessionEvent.FirstFrame))
    }

    @Test
    fun `放弃只能靠显式 Start 离开`() {
        val m = machineAt(SessionEvent.Start, SessionEvent.GiveUp)
        assertEquals(SessionState.GaveUp, m.state)
        // 引擎的迟到回调不该把会话拉回播放（冷却期还在）
        assertNull(m.accept(SessionEvent.FirstFrame))
        assertNull(m.accept(SessionEvent.EngineStarted))
        assertEquals(SessionState.Acquiring, m.accept(SessionEvent.Start))
    }

    @Test
    fun `停止回到空闲，可以再次起播`() {
        val m = machineAt(SessionEvent.Start, SessionEvent.EngineStarted, SessionEvent.FirstFrame)
        assertEquals(SessionState.Idle, m.accept(SessionEvent.Stop))
        assertEquals(SessionState.Acquiring, m.accept(SessionEvent.Start))
    }

    @Test
    fun `取址阶段收到首帧属于乱序，按非法处理`() {
        val m = machineAt(SessionEvent.Start)
        assertNull(m.accept(SessionEvent.FirstFrame))
        assertEquals(SessionState.Acquiring, m.state)
    }

    @Test
    fun `起播次数只在 Start 时累加`() {
        val m = SessionMachine()
        m.accept(SessionEvent.Start)
        m.accept(SessionEvent.EngineStarted)
        m.accept(SessionEvent.FirstFrame)
        m.accept(SessionEvent.Degrade)
        assertEquals(1, m.attempt)

        m.accept(SessionEvent.GiveUp)
        m.accept(SessionEvent.Start)
        assertEquals(2, m.attempt)
    }
}
