package com.litv.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SegmentPrefetch] / [SegmentHealth] 的行为约束。
 *
 * 守的是「并发预取到底有没有意义」这件事：
 *   · 必须预取窗口**尾部**（播放器下一步真正要的那几片），不是头部；
 *   · 已经在缓存里、或已经有人在拉的，不许再排一次；
 *   · 预取数量有上限（不能把整条窗口打一遍，源站会限流）；
 *   · 健康统计要能给出 p50/p90/max 与失败数，否则「改完变好了」只能靠体感。
 */
class SegmentPrefetchTest {

    private fun seg(n: Int) = "https://cdn.example/s$n.ts"

    private fun window(n: Int) = (1..n).map { seg(it) }

    @Test
    fun `取窗口尾部而不是头部`() {
        val got = SegmentPrefetch.plan(window(10), emptySet(), emptySet(), ahead = 6)
        // 尾部 6 片：s5..s10
        assertEquals((5..10).map { seg(it) }, got)
        assertTrue("不许再预取最旧的 s1（播放器早就播过了）", got.none { it == seg(1) })
    }

    @Test
    fun `已缓存和正在拉的不重复排`() {
        val w = window(10)
        val cached = setOf(seg(10), seg(9))
        val inFlight = setOf(seg(8))
        val got = SegmentPrefetch.plan(w, cached, inFlight, ahead = 6)
        assertEquals(listOf(seg(5), seg(6), seg(7)), got)
    }

    @Test
    fun `窗口比预取数还短时全取`() {
        val w = window(3)
        assertEquals(w, SegmentPrefetch.plan(w, emptySet(), emptySet(), ahead = 6))
    }

    @Test
    fun `空窗口与零预取都不排`() {
        assertTrue(SegmentPrefetch.plan(emptyList(), emptySet(), emptySet(), ahead = 6).isEmpty())
        assertTrue(SegmentPrefetch.plan(window(5), emptySet(), emptySet(), ahead = 0).isEmpty())
    }

    @Test
    fun `健康统计给出分位数与失败数`() {
        val h = SegmentHealth(reportEvery = 3)
        h.onFetch(1_000, ok = true)
        h.onFetch(2_000, ok = true)
        h.onFetch(11_000, ok = false)

        val s = h.snapshot()
        assertEquals(3, s.count)
        assertEquals(11_000, s.maxMs)
        assertEquals(1, s.failCount)
        assertEquals(1, s.slowCount) // 只有 11s 那片超过 5s
        // p50 落在这三个样本的中间档
        assertEquals(2_000, s.p50Ms)

        val line = h.takeSummaryIfDue()
        assertTrue("够片数就该给摘要", line != null && line!!.contains("分片统计 3 片"))
        // 取过即清零：不会每片都刷一行
        assertNull(h.takeSummaryIfDue())
        assertEquals(0, h.snapshot().count)
    }

    @Test
    fun `预取命中率只统计命中里来自预取的部分`() {
        val h = SegmentHealth(reportEvery = 2)
        h.onFetch(500, ok = true)
        h.onFetch(500, ok = true)
        h.onHit(fromPrefetch = true)
        h.onHit(fromPrefetch = true)
        h.onHit(fromPrefetch = false)
        val s = h.snapshot()
        assertEquals(3, s.hitCount)
        assertEquals(2, s.prefetchHitCount)
    }

    @Test
    fun `换线记账不受片数门槛限制，摘要行能看见`() {
        // reportEvery 故意设得比片数大得多：内置源不走预取，片计数一直是 0，
        // 如果换线也必须等够 20 片才出摘要，那新路径在只播内置源的设备上永远不可观测。
        val h = SegmentHealth(reportEvery = 100)
        h.onLineSwitchTry()
        h.onLineSwitchTry()
        h.onLineSwitchOk()
        // ★ 断言顺序有讲究：takeSummaryIfDue() 取完即清零（见它的实现），
        //   所以计数必须在**取摘要之前**看，否则永远读到 0。
        val snap = h.snapshot()
        assertEquals(2, snap.switchTry)
        assertEquals(1, snap.switchOk)
        val line = h.takeSummaryIfDue()
        assertTrue("一次片都没拉、但有换线，也要给摘要", line != null)
        assertTrue("摘要行必须带换线次数", line!!.contains("换线 2 次/成功 1 次"))
        // 取过即清零，不会每次请求都刷一行
        assertNull(h.takeSummaryIfDue())
        assertEquals(0, h.snapshot().switchTry)
    }
}

class PlaylistUrlsTest {

    private val playlist = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:4
        #EXTINF:4.0,
        seg100.ts
        #EXTINF:4.0,
        ./seg101.ts
        #EXTINF:4.0,
        https://cdn2.example/abs/seg102.ts
    """.trimIndent()

    @Test
    fun `解析媒体行并按目录拼相对地址`() {
        val urls = PlaylistUrls.mediaUrls(playlist, "https://cdn1.example/live/ch1/index.m3u8?sign=abc")
        assertEquals(
            listOf(
                "https://cdn1.example/live/ch1/seg100.ts",
                "https://cdn1.example/live/ch1/seg101.ts",
                "https://cdn2.example/abs/seg102.ts",
            ),
            urls,
        )
    }

    @Test
    fun `标签行一个有都不能混进来`() {
        val urls = PlaylistUrls.mediaUrls(playlist, "https://cdn1.example/live/ch1/index.m3u8")
        assertTrue("不能把 #EXTINF 当成分片", urls.none { it.contains("#") })
        assertEquals(3, urls.size)
    }

    @Test
    fun `空清单返回空`() {
        assertTrue(PlaylistUrls.mediaUrls("", "https://a/b.m3u8").isEmpty())
        assertTrue(PlaylistUrls.mediaUrls("#EXTM3U\n#EXT-X-ENDLIST", "https://a/b.m3u8").isEmpty())
    }
}