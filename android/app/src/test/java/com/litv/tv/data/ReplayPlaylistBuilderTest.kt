package com.litv.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回看清单累积器。核心行为只有一条：**只追加，不回退**。
 * 播放器重拉多少次都不应该让内容往前跳（这是"回看不一会儿就跳回直播"的根因）。
 */
class ReplayPlaylistBuilderTest {

    private fun window(vararg durs: String, pdt: String = "2026-11-25T05:00:00+08:00"): List<String> {
        val lines = mutableListOf("#EXTM3U", "#EXT-X-VERSION:3", "#EXT-X-TARGETDURATION:12",
            "#EXT-X-MEDIA-SEQUENCE:1786779822", "#EXT-X-PROGRAM-DATE-TIME:$pdt")
        for ((i, d) in durs.withIndex()) {
            lines.add("#EXTINF:$d,")
            lines.add("/seg/SEG$i")
        }
        return lines
    }

    @Test
    fun `空清单只有头，没有 endlist`() {
        val b = ReplayPlaylistBuilder()
        val text = b.render()
        assertTrue(text.startsWith("#EXTM3U\n"))
        assertTrue(text.contains("#EXT-X-PLAYLIST-TYPE:EVENT\n"))
        assertTrue(text.contains("#EXT-X-MEDIA-SEQUENCE:0\n"))
        assertFalse(text.contains("#EXT-X-ENDLIST"))
        assertEquals(0, b.segmentCount)
    }

    @Test
    fun `追加一窗后分片出现在清单里`() {
        val b = ReplayPlaylistBuilder()
        b.appendWindow(window("5.36", "4.96", "5.40"))
        val text = b.render()
        assertEquals(3, b.segmentCount)
        assertTrue(text.contains("#EXTINF:5.36,\n/seg/SEG0\n"))
        assertTrue(text.contains("/seg/SEG2"))
    }

    @Test
    fun `重拉只追加，前面的分片不消失`() {
        val b = ReplayPlaylistBuilder()
        b.appendWindow(window("5", "5"))
        val first = b.render()
        b.appendWindow(window("5", "5"))
        val second = b.render()
        // 第二次的内容是在第一次基础上往后长，不是替换
        assertTrue(second.contains("/seg/SEG0"))
        assertEquals(4, b.segmentCount)
        assertTrue(second.length > first.length)
    }

    @Test
    fun `两窗之间插 discontinuity`() {
        val b = ReplayPlaylistBuilder()
        b.appendWindow(window("5"))
        b.appendWindow(window("5"))
        val text = b.render()
        assertEquals(1, Regex("#EXT-X-DISCONTINUITY\n").findAll(text).count())
    }

    @Test
    fun `清单级标签不重复输出`() {
        val b = ReplayPlaylistBuilder()
        b.appendWindow(window("5"))
        b.appendWindow(window("5"))
        val text = b.render()
        assertEquals(1, Regex("#EXTM3U").findAll(text).count())
        assertEquals(1, Regex("#EXT-X-TARGETDURATION").findAll(text).count())
        // 上游每窗都带的 MEDIA-SEQUENCE 一律丢弃，改用我们自己的 0 起编号
        assertEquals(0, Regex("#EXT-X-MEDIA-SEQUENCE:1786").findAll(text).count())
    }

    @Test
    fun `到末尾 finish 后带 endlist，且不再追加`() {
        val b = ReplayPlaylistBuilder()
        b.appendWindow(window("5"))
        b.finish()
        assertTrue(b.render().trimEnd().endsWith("#EXT-X-ENDLIST"))
        val before = b.render()
        b.appendWindow(window("5", "5"))
        assertEquals(before, b.render())
    }

    @Test
    fun `没有分片的窗不算一窗`() {
        val b = ReplayPlaylistBuilder()
        b.appendWindow(window("5"))
        b.appendWindow(listOf("#EXTM3U", "#EXT-X-TARGETDURATION:12"))
        assertEquals(1, b.segmentCount)
        assertEquals(0, Regex("#EXT-X-DISCONTINUITY").findAll(b.render()).count())
    }

    @Test
    fun `累积到上限自动收尾`() {
        val b = ReplayPlaylistBuilder(maxSegments = 3)
        assertFalse(b.isFinished)
        b.appendWindow(window("5", "5"))
        assertFalse(b.isFinished)
        // 第二窗会把总数冲过上限（一窗 5 片左右，按窗追加无法精确卡点），冲过就收尾
        b.appendWindow(window("5", "5"))
        assertTrue(b.isFinished)
        assertTrue(b.render().contains("#EXT-X-ENDLIST"))
        assertEquals(4, b.segmentCount)
        val frozen = b.render()
        b.appendWindow(window("5"))
        assertEquals(frozen, b.render())
    }

    @Test
    fun `CRLF 行被吸收掉`() {
        val b = ReplayPlaylistBuilder()
        b.appendWindow(window("5").map { "$it\r" })
        val text = b.render()
        assertFalse(text.contains("\r"))
        assertTrue(text.contains("#EXTINF:5,\n"))
    }

    @Test
    fun `reset 清空`() {
        val b = ReplayPlaylistBuilder()
        b.appendWindow(window("5"))
        b.finish()
        b.reset()
        assertEquals(0, b.segmentCount)
        assertFalse(b.render().contains("#EXT-X-ENDLIST"))
    }
}
