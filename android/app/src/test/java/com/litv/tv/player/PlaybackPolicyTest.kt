package com.litv.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PlaybackPolicy] 的行为约束（S2）。
 *
 * 这片守的不是「某个数字好看」，而是三条**不可回归**的性质：
 *   · 源类型判错 → 用内置源的小缓冲跑自建源慢源（或反过来）→ 卡顿回归，所以判据要单独测；
 *   · `min < max` 且上限压进直播窗口 → 否则 ExoPlayer 报参数非法，或每 6 秒
 *     `BEHIND_LIVE_WINDOW` 一次；
 *   · 引擎里的历史取值在「收敛到一处」时不能被顺手改掉 —— 这里钉住当前值，
 *     改值必须同时改测试（也就是必须是有意识的决定，不是手滑）。
 */
class PlaybackPolicyTest {

    // ---- 源类型 ----

    @Test
    fun `内置源按回环地址判定`() {
        assertEquals(SourceKind.BUILTIN, SourceKind.ofUrl("http://127.0.0.1:35497/ch/12.m3u8"))
        assertEquals(SourceKind.BUILTIN, SourceKind.ofUrl("http://localhost:35497/ch/12.m3u8"))
        // 回看 /rp/ 也走本机代理，沿用原有 isLocalProxy 判据（不改行为）
        assertEquals(SourceKind.BUILTIN, SourceKind.ofUrl("http://127.0.0.1:35497/rp/12/0/3600.m3u8"))
    }

    @Test
    fun `自建源即使走本地代理也不算内置源`() {
        assertEquals(SourceKind.CUSTOM, SourceKind.ofUrl("http://127.0.0.1:35497/raw/YWJj.m3u8"))
        assertEquals(SourceKind.CUSTOM, SourceKind.ofUrl("http://example.com/live/cctv3.m3u8"))
        assertEquals(SourceKind.CUSTOM, SourceKind.ofUrl("https://cdn.example.com/hls/index.m3u8?sig=x"))
    }

    @Test
    fun `源类型判定大小写不敏感`() {
        assertEquals(SourceKind.CUSTOM, SourceKind.ofUrl("HTTP://127.0.0.1:1/RAW/x.m3u8"))
        assertEquals(SourceKind.BUILTIN, SourceKind.ofUrl("HTTP://LOCALHOST:1/ch/x.m3u8"))
    }

    // ---- Exo ----

    @Test
    fun `内置源用 14-16 秒窗口`() {
        val pol = PlaybackPolicy.exo(SourceKind.BUILTIN, bufferMs = 3_000, liveOffsetMs = 20_000)
        assertEquals(3_000, pol.cfgBufferMs)
        assertEquals(14_000, pol.minBufferMs)
        assertEquals(18_000, pol.maxBufferMs)
        assertEquals(800, pol.bufferForPlaybackMs)
        assertEquals(1_000, pol.afterRebufferMs)
        // 落后量夹到 10 秒上限
        assertEquals(10_000L, pol.targetOffsetMs)
    }

    @Test
    fun `内置源缓冲设置越大 min 越深但不超过 16 秒`() {
        val pol = PlaybackPolicy.exo(SourceKind.BUILTIN, bufferMs = 60_000, liveOffsetMs = 0)
        assertEquals(16_000, pol.minBufferMs)
        assertEquals(20_000, pol.maxBufferMs)
        // 下限夹到 4 秒
        assertEquals(4_000L, pol.targetOffsetMs)
    }

    @Test
    fun `自建源有 22 秒地板且落后量至少 12 秒`() {
        val pol = PlaybackPolicy.exo(SourceKind.CUSTOM, bufferMs = 3_000, liveOffsetMs = 6_000)
        assertEquals(22_000, pol.cfgBufferMs)
        // min 夹到 20 秒上限：播放器能提前缓冲多少，上限是「离直播边缘的距离」＝ targetOffset
        assertEquals(20_000, pol.minBufferMs)
        assertEquals(35_000, pol.maxBufferMs)
        // 落后量地板 12 秒：代理把窗口加深到 90 秒后，这才攒得出缓冲
        assertEquals(12_000L, pol.targetOffsetMs)
    }

    @Test
    fun `自建源上限封在 35 秒`() {
        val pol = PlaybackPolicy.exo(SourceKind.CUSTOM, bufferMs = 45_000, liveOffsetMs = 6_000)
        assertEquals(20_000, pol.minBufferMs)
        assertEquals(35_000, pol.maxBufferMs)
    }

    @Test
    fun `自建源的落后量跟着设置走但不超过 25 秒`() {
        assertEquals(20_000L, PlaybackPolicy.exo(SourceKind.CUSTOM, 8_000, liveOffsetMs = 20_000).targetOffsetMs)
        assertEquals(25_000L, PlaybackPolicy.exo(SourceKind.CUSTOM, 8_000, liveOffsetMs = 60_000).targetOffsetMs)
    }

    @Test
    fun `任意设置下 min 都小于 max 且上限不进直播窗口`() {
        for (source in SourceKind.entries) {
            for (buffer in 0..60_000 step 500) {
                val pol = PlaybackPolicy.exo(source, buffer, liveOffsetMs = 6_000)
                assertTrue("$source buffer=$buffer min=${pol.minBufferMs} max=${pol.maxBufferMs}",
                    pol.maxBufferMs >= pol.minBufferMs + 2_000)
                assertTrue("$source buffer=$buffer max=${pol.maxBufferMs} 超上限",
                    pol.maxBufferMs <= 40_000)
                if (source == SourceKind.BUILTIN) {
                    // 上限必须明显小于窗口（36 秒），min 必须小于窗口一半
                    assertTrue("内置源 max 贴窗口: ${pol.maxBufferMs}", pol.maxBufferMs <= 20_000)
                    assertTrue("内置源 min 超过窗口一半: ${pol.minBufferMs}",
                        pol.minBufferMs < PlaybackPolicy.BUILTIN_WINDOW_MS / 2)
                }
            }
        }
    }

    // ---- VLC ----

    @Test
    fun `VLC 缓存夹在 500 到 20000 毫秒`() {
        assertEquals(500, PlaybackPolicy.vlc(SourceKind.CUSTOM, 0).networkCachingMs)
        assertEquals(20_000, PlaybackPolicy.vlc(SourceKind.BUILTIN, 60_000).networkCachingMs)
        val pol = PlaybackPolicy.vlc(SourceKind.CUSTOM, 8_000)
        assertEquals(8_000, pol.networkCachingMs)
        assertEquals(8_000, pol.liveCachingMs)
    }

    // ---- mpv ----

    @Test
    fun `mpv 秒到字节映射的边界`() {
        assertEquals(MpvPolicy(1, 32, 8), PlaybackPolicy.mpv(SourceKind.CUSTOM, 0))
        assertEquals(MpvPolicy(2, 32, 8), PlaybackPolicy.mpv(SourceKind.CUSTOM, 2_000))
        assertEquals(MpvPolicy(4, 64, 16), PlaybackPolicy.mpv(SourceKind.CUSTOM, 4_000))
        assertEquals(MpvPolicy(9, 128, 32), PlaybackPolicy.mpv(SourceKind.CUSTOM, 9_000))
        assertEquals(MpvPolicy(10, 256, 64), PlaybackPolicy.mpv(SourceKind.CUSTOM, 10_000))
        // 两小时设置也夹到 30 秒，别把低端盒子内存吃光
        assertEquals(30, PlaybackPolicy.mpv(SourceKind.CUSTOM, 7_200_000).readaheadSecs)
    }

    // ---- 日志 ----

    @Test
    fun `生效参数日志含四个缓冲数字与档位`() {
        val text = PlaybackPolicy.exo(SourceKind.BUILTIN, 8_000, 6_000).describe(SourceKind.BUILTIN)
        assertTrue(text, text.contains("min=14000ms"))
        assertTrue(text, text.contains("max=18000ms"))
        assertTrue(text, text.contains("起播=800ms"))
        assertTrue(text, text.contains("档位=内置源"))
    }
}
