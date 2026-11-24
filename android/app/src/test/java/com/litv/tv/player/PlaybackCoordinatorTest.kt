package com.litv.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 起播收敛点（`PlaybackCoordinator`）的纯逻辑测试。
 *
 * 两条主线：
 *  · **四条路径走同一条路**：缓存 / 内置源 / 本地解析 / 服务端都经 `play(source)`，
 *    来源标签与「是否该报心跳」由类型决定，不靠调用方记得。
 *  · **台账不改行为**：非播放态调 pause() 仍然下发（旧代码就这么调），只是状态记成非法；
 *    已释放之后才真的不再起播。
 */
class PlaybackCoordinatorTest {

    private class Rec {
        val started = mutableListOf<PlaySource>()
        var paused = 0
        var resumed = 0
        var stopped = 0
        var released = 0
        val illegal = mutableListOf<String>()
    }

    private fun coord(r: Rec) = PlaybackCoordinator(
        start = { r.started += it },
        pauseImpl = { r.paused++ },
        resumeImpl = { r.resumed++ },
        stopImpl = { r.stopped++ },
        releaseImpl = { r.released++ },
        onIllegal = { _, what -> r.illegal += what },
    )

    private fun remote(id: Long = 1) = PlaySource.Remote(id, "http://h/$id.m3u8", emptyMap(), "exo")

    @Test
    fun `起播到下发起播并进入取址态`() {
        val r = Rec()
        val c = coord(r)
        assertTrue(c.play(remote()))
        assertEquals(SessionState.Acquiring, c.state)
        assertEquals(1, r.started.size)
        assertEquals(1, c.attempt)
        assertEquals(1L, c.current?.channelId)
    }

    @Test
    fun `内核接管与首帧依次推进到播放`() {
        val r = Rec()
        val c = coord(r)
        c.play(remote())
        assertTrue(c.onEngineStarted())
        assertEquals(SessionState.Starting, c.state)
        assertTrue(c.onFirstFrame())
        assertEquals(SessionState.Playing, c.state)
    }

    @Test
    fun `换台先收尾再起播，起播次数累加`() {
        val r = Rec()
        val c = coord(r)
        c.play(remote(1))
        c.onEngineStarted()
        c.onFirstFrame()
        assertTrue(c.play(remote(2)))
        assertEquals(2, r.started.size)
        assertEquals(2, c.attempt)
        assertEquals(2L, c.current?.channelId)
        assertEquals(SessionState.Acquiring, c.state)
    }

    @Test
    fun `非播放态暂停仍然下发，只记非法`() {
        val r = Rec()
        val c = coord(r)
        c.play(remote())
        // 还没出首帧就暂停：旧代码会照发 pause()，这里行为必须一致
        c.pause()
        assertEquals(1, r.paused)
        assertEquals(SessionState.Acquiring, c.state)
        assertTrue(r.illegal.any { it.contains("暂停") })
    }

    @Test
    fun `播放态暂停与恢复`() {
        val r = Rec()
        val c = coord(r)
        c.play(remote())
        c.onEngineStarted()
        c.onFirstFrame()
        c.pause()
        assertEquals(SessionState.Paused, c.state)
        assertEquals(1, r.paused)
        c.resume()
        assertEquals(SessionState.Playing, c.state)
        assertEquals(1, r.resumed)
    }

    @Test
    fun `释放之后不再起播，重复释放只下发一次`() {
        val r = Rec()
        val c = coord(r)
        c.play(remote())
        c.onEngineStarted()
        c.onFirstFrame()
        c.release()
        assertEquals(SessionState.Released, c.state)
        assertEquals(1, r.released)

        assertFalse(c.play(remote(9)))
        assertEquals(1, r.started.size)

        c.release()
        assertEquals(1, r.released)
    }

    @Test
    fun `放弃后迟到首帧不算播放`() {
        val r = Rec()
        val c = coord(r)
        c.play(remote())
        c.onGiveUp()
        assertEquals(SessionState.GaveUp, c.state)
        assertFalse(c.onFirstFrame())
        assertEquals(SessionState.GaveUp, c.state)
    }

    @Test
    fun `停止清空来源但可再次起播`() {
        val r = Rec()
        val c = coord(r)
        c.play(remote())
        c.stop()
        assertEquals(SessionState.Idle, c.state)
        assertNull(c.current)
        assertEquals(1, r.stopped)
        assertTrue(c.play(remote(3)))
        assertEquals(2, c.attempt)
    }

    @Test
    fun `同源重试：没有来源时返回 false`() {
        val r = Rec()
        val c = coord(r)
        assertFalse(c.retryNow())
        c.play(remote(5))
        assertTrue(c.retryNow())
        assertEquals(2, r.started.size)
        assertEquals(5L, r.started.last().channelId)
    }

    @Test
    fun `只有服务端签发的来源需要心跳`() {
        assertTrue(remote().needsHeartbeat)
        assertFalse(PlaySource.Cached(1, "u", emptyMap(), "exo").needsHeartbeat)
        assertFalse(PlaySource.Local(1, "u", emptyMap(), "exo").needsHeartbeat)
        val builtin = PlaySource.Builtin(1, "u", emptyMap(), "exo")
        assertFalse(builtin.needsHeartbeat)
        // 内置源按 URL 签名校验，绝不能加 _t 缓存参数
        assertFalse(builtin.bustCache)
        assertEquals("内置源", builtin.label)
    }
}
