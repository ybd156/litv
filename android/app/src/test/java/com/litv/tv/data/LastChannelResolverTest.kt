package com.litv.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 恢复「上次看的频道」的匹配优先级（C2 唯一能进 CI 的部分）。
 *
 * 为什么值得测：上游刷新会**重建**频道行（改名/改分组都生成新 id），
 * 只按 id 恢复必然失效，然后老代码静默落到第一个台——用户反馈的
 * 「退出重进没有到上次看的频道」。这里钉死三级兜底与确定性。
 */
class LastChannelResolverTest {

    private fun ch(id: Long, name: String, tvg: String = "", group: String = "央视",
                   sort: Int = 0, number: Int = 0) = Channel(
        id = id, number = number, name = name, group = group, tvgId = tvg,
        sort = sort,
    )

    @Test
    fun `id 没变时按 id 命中`() {
        val list = listOf(ch(1, "CCTV-1", "cctv1"), ch(2, "CCTV-2", "cctv2"))
        val m = LastChannelResolver.resolve(list, lastId = 2, lastTvgId = "cctv1", lastName = "CCTV-1")
        assertEquals(2L, m?.channel?.id)
        assertEquals("id", m?.via)
    }

    @Test
    fun `id 失配时回退 tvg_id`() {
        // 上游重新导入后 id 变了，但 tvg_id 不变
        val list = listOf(ch(10, "CCTV-1 综合", "cctv1"), ch(11, "CCTV-2", "cctv2"))
        val m = LastChannelResolver.resolve(list, lastId = 1, lastTvgId = "CCTV1", lastName = "CCTV-1")
        assertEquals(10L, m?.channel?.id)
        assertEquals("tvg_id", m?.via)
    }

    @Test
    fun `id 与 tvg_id 都失配时回退名字`() {
        val list = listOf(ch(20, "湖南卫视"), ch(21, "浙江卫视"))
        val m = LastChannelResolver.resolve(list, lastId = 99, lastTvgId = "", lastName = " 浙江卫视 ")
        assertEquals(21L, m?.channel?.id)
        assertEquals("name", m?.via)
    }

    @Test
    fun `三者都不命中返回 null 交给调用方兜底`() {
        val list = listOf(ch(1, "CCTV-1", "cctv1"))
        assertNull(LastChannelResolver.resolve(list, lastId = 0, lastTvgId = "x", lastName = "y"))
        // 空列表更不能炸
        assertNull(LastChannelResolver.resolve(emptyList(), lastId = 1, lastTvgId = "", lastName = ""))
    }

    @Test
    fun `没有历史记录时不乱选`() {
        val list = listOf(ch(1, "CCTV-1"), ch(2, "CCTV-2"))
        // lastId=0 且没有 tvg/name：不猜，返回 null（调用方用 pickFirst 兜底）
        assertNull(LastChannelResolver.resolve(list, lastId = 0, lastTvgId = "", lastName = ""))
    }

    @Test
    fun `兜底第一个台是确定性的 与输入顺序无关`() {
        // (sort, number, id) 升序：这里 0/0/7 < 0/0/8 < 1/0/1，所以答案是 id=7
        val base = listOf(
            ch(8, "B", sort = 0, number = 0),
            ch(1, "C", sort = 1, number = 0),
            ch(7, "A", sort = 0, number = 0),
        )
        val first = LastChannelResolver.pickFirst(base)
        assertEquals(7L, first?.id)

        // 打乱输入重复调用，结果必须不变（否则恢复行为会随机跳台，测试也会 flaky）
        val shuffled = listOf(base[2], base[0], base[1])
        assertEquals(7L, LastChannelResolver.pickFirst(shuffled)?.id)
        assertEquals(7L, LastChannelResolver.pickFirst(base.reversed())?.id)
    }
}
