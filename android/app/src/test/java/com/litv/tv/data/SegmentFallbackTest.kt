package com.litv.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SegmentFallback] 的行为约束。
 *
 * 守的是 2026-11-24 评审确认的那个 bug：403 分支先清缓存再算序号，
 * 序号恒 null → 分片换线路一次都没真正发生过。所以这里钉住三件事：
 *   · 序号必须在**清缓存之前**从清单里数出来（清单为 null 就不猜）；
 *   · 备线窗口比当前序号短时**不许 clamp**（clamp 会把已播过的片段塞回去）；
 *   · 换线是补救路径，必须有总预算，预算不够就不发起。
 */
class SegmentFallbackTest {

    /** 假解码器：真实实现是 base64url 解码，这里只要「token → 地址」一一对应。 */
    private val decode: (String) -> String? = { tok -> "u:$tok" }

    /** 造一条改写后的清单：媒体行是 `/seg/t0`…，中间夹着标签行。 */
    private fun playlistOf(n: Int): String = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:4\n")
        for (i in 0 until n) {
            append("#EXTINF:4.0,\n/seg/t").append(i).append('\n')
        }
        append("#EXT-X-ENDLIST\n")
    }

    @Test
    fun `served 为空时直接放弃，不许瞎猜序号`() {
        val o = SegmentFallback.plan(
            served = null,
            playlist = playlistOf(5),
            decode = decode,
            lineCount = 2,
            currentLine = 0,
            remainingMs = SegmentFallback.TOTAL_BUDGET_MS,
        )
        assertEquals(SegmentFallback.Outcome.Skip(SegmentFallback.Reason.NO_SERVED), o)
        // 空白串同样不算
        assertNull(SegmentFallback.indexOf(playlistOf(5), "  ", decode))
    }

    @Test
    fun `序号只数媒体行，标签行不算`() {
        assertEquals(0, SegmentFallback.indexOf(playlistOf(5), "u:t0", decode))
        assertEquals(3, SegmentFallback.indexOf(playlistOf(5), "u:t3", decode))
        assertEquals(4, SegmentFallback.indexOf(playlistOf(5), "u:t4", decode))
    }

    @Test
    fun `清单里数不到这一片就不换线（清缓存后清单为 null 的等价场景）`() {
        assertNull(SegmentFallback.indexOf(playlistOf(3), "u:t9", decode))
        assertNull(SegmentFallback.indexOf(null, "u:t1", decode))
        val o = SegmentFallback.plan(
            served = "u:t1",
            playlist = null,
            decode = decode,
            lineCount = 3,
            currentLine = 0,
            remainingMs = SegmentFallback.TOTAL_BUDGET_MS,
        )
        assertEquals(SegmentFallback.Outcome.Skip(SegmentFallback.Reason.NO_INDEX), o)
    }

    @Test
    fun `单线时没有备线可换`() {
        val o = SegmentFallback.plan(
            served = "u:t1",
            playlist = playlistOf(4),
            decode = decode,
            lineCount = 1,
            currentLine = 0,
            remainingMs = SegmentFallback.TOTAL_BUDGET_MS,
        )
        assertEquals(SegmentFallback.Outcome.Skip(SegmentFallback.Reason.SINGLE_LINE), o)
        assertTrue(SegmentFallback.targets(1, 0).isEmpty())
        assertTrue(SegmentFallback.targets(0, 0).isEmpty())
    }

    @Test
    fun `跳过当前线路，其余按原顺序`() {
        assertEquals(listOf(0, 2), SegmentFallback.targets(3, 1))
        assertEquals(listOf(1, 2), SegmentFallback.targets(3, 0))
        assertEquals(listOf(0, 1), SegmentFallback.targets(3, 2))
    }

    @Test
    fun `备线窗口更短时不做 clamp，宁可跳过这条备线`() {
        // 旧语义 `index.coerceIn(0, segCount - 1)` 在 (7, 5) 上返回 4：
        // 那是备线窗口的**末尾**，一段早就播过的内容 —— 换"成功"了但画面往回跳。
        assertNull(SegmentFallback.pickIndex(7, 5))
        assertNull(SegmentFallback.pickIndex(0, 0))
        assertEquals(4, SegmentFallback.pickIndex(4, 5))
    }

    @Test
    fun `预算不够一条备线就不发起换线`() {
        val o = SegmentFallback.plan(
            served = "u:t1",
            playlist = playlistOf(4),
            decode = decode,
            lineCount = 3,
            currentLine = 0,
            remainingMs = 500,
        )
        assertEquals(SegmentFallback.Outcome.Skip(SegmentFallback.Reason.BUDGET), o)
        assertNull(SegmentFallback.perLineMs(500, 1))
        assertNull(SegmentFallback.perLineMs(2_000, 1, minMs = 3_000))
        assertNull(SegmentFallback.perLineMs(4_000, 0))
    }

    @Test
    fun `预算平摊，取线路超时取 min(3000, 剩余)`() {
        val o = SegmentFallback.plan(
            served = "u:t1",
            playlist = playlistOf(4),
            decode = decode,
            lineCount = 3,
            currentLine = 0,
            remainingMs = SegmentFallback.TOTAL_BUDGET_MS,
        )
        assertTrue("预算够的时候应该给出可执行的计划", o is SegmentFallback.Outcome.Ready)
        val plan = (o as SegmentFallback.Outcome.Ready).plan
        assertEquals(1, plan.index)
        assertEquals(listOf(1, 2), plan.targets)
        // linesTimeoutMs 是 Int（要直接喂给 YspClient.lines 的 timeoutMs），
        // 常量 LINES_TIMEOUT_MS 是 Long（与 TOTAL_BUDGET_MS 同族），比较前必须转换。
        assertEquals(SegmentFallback.LINES_TIMEOUT_MS.toInt(), plan.linesTimeoutMs)
        assertEquals(SegmentFallback.TOTAL_BUDGET_MS, plan.budgetMs)

        // 三条备线分 4200ms → 每条 1400ms
        assertEquals(1_400L, SegmentFallback.perLineMs(4_200, 3) ?: -1L)
        // 剩余比 3000 小的时候按剩余给，不能还给 3000
        assertEquals(1_000, SegmentFallback.linesTimeoutMs(1_000))
        assertEquals(0, SegmentFallback.linesTimeoutMs(-5))
        assertNotNull(SegmentFallback.perLineMs(SegmentFallback.MIN_LINE_MS, 1))
    }

    /**
     * 内置源频道号是负数（-1001/-1002…），它们的线路列表最全（playurl + backurl_list）。
     * 判据写成 `> 0` 会把内置源挡在分片换线之外，一次 403 只能等清单轮换（6~10 秒），
     * 表现就是「播着播着卡一下」。
     */
    @Test
    fun `负数的内置源频道也能走分片换线，只有 0 才是非法`() {
        assertTrue(SegmentFallback.canFallback(-1001L))
        assertTrue(SegmentFallback.canFallback(-1002L))
        assertTrue(SegmentFallback.canFallback(8831L))
        assertEquals(false, SegmentFallback.canFallback(0L))
    }
}
