package com.litv.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 回看地址分类：只按 host 判断，且不能把签名 query 当成判据。 */
class ReplaySourceTest {

    @Test
    fun `回看 CDN 认成回看`() {
        assertEquals(
            ReplaySource.Kind.Playback,
            ReplaySource.classify("https://tlivecloud-playback-cdn.ysp.cctv.cn/live/abc.m3u8?svrtime=123"),
        )
    }

    @Test
    fun `直播 CDN 认成直播`() {
        assertEquals(
            ReplaySource.Kind.Live,
            ReplaySource.classify("https://tlivecloud-live-cdn.ysp.cctv.cn/live/abc.m3u8?svrtime=123"),
        )
    }

    @Test
    fun `路径里带 live 不算直播`() {
        // 回看地址的路径里本身就有 /live/，判据只能看 host
        assertEquals(
            ReplaySource.Kind.Playback,
            ReplaySource.classify("https://tlivecloud-playback-cdn.ysp.cctv.cn/live/seg/1.ts"),
        )
    }

    @Test
    fun `空串与畸形地址归为未知`() {
        assertEquals(ReplaySource.Kind.Unknown, ReplaySource.classify(""))
        assertEquals(ReplaySource.Kind.Unknown, ReplaySource.classify("   "))
        assertEquals(ReplaySource.Kind.Unknown, ReplaySource.classify("not a url at all"))
    }

    @Test
    fun `时距不会出现负数`() {
        assertEquals(0L, ReplaySource.ageSec(2_000L, 1_000L))
        assertEquals(600L, ReplaySource.ageSec(1_000L, 1_600L))
    }

    @Test
    fun `刚播完的节目不算越界`() {
        val now = 1_800_000_000L
        // 差 3 分钟：上游给直播也说得过去，别报错
        assertFalse(ReplaySource.isHistorical(now - 180, now))
        // 差 1 小时：明显是历史节目，此时给直播就是错的
        assertTrue(ReplaySource.isHistorical(now - 3_600, now))
    }
}
