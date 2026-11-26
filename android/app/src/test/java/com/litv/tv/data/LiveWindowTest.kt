package com.litv.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 窗口加深度：源站每次只给 3 片（约 17 秒），连续几次窗口首尾相接，
 * 累积成 60~90 秒才对得上播放器 22~32 秒的缓冲目标。
 */
class LiveWindowTest {

    private fun playlist(vararg segs: Pair<String, Double>, sequence: Long? = null): String {
        val sb = StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:8\n")
        sequence?.let { sb.append("#EXT-X-MEDIA-SEQUENCE:").append(it).append('\n') }
        for ((url, dur) in segs) {
            sb.append("#EXTINF:").append(dur).append(",\n").append(url).append('\n')
        }
        return sb.toString()
    }

    @Test
    fun 一次窗口就是三片() {
        val segs = LiveWindow.parse(playlist("/rawseg/a" to 5.0, "/rawseg/b" to 6.0, "/rawseg/c" to 6.0, sequence = 100), 100)
        assertEquals(3, segs.size)
        assertEquals(5.0, segs[0].durationSec, 0.001)
        assertEquals(100L, segs[0].sequence)
        assertEquals(102L, segs[2].sequence)
        assertEquals(listOf("#EXTINF:5.0,", "/rawseg/a"), segs[0].lines)
    }

    @Test
    fun 重复分片只累积一次() {
        val win = LiveWindow(maxSec = 90.0)
        win.append(LiveWindow.parse(playlist("/rawseg/a" to 5.0, "/rawseg/b" to 6.0, "/rawseg/c" to 6.0, sequence = 100), 100))
        assertEquals(3, win.segmentCount)
        // 下一轮源站窗口整体右移一格，前两片是旧的
        win.append(LiveWindow.parse(playlist("/rawseg/b" to 6.0, "/rawseg/c" to 6.0, "/rawseg/d" to 6.0, sequence = 101), 101))
        assertEquals(4, win.segmentCount)
        assertEquals(23.0, win.sizeSec, 0.001)
    }

    @Test
    fun 超过上限从头部淘汰() {
        val win = LiveWindow(maxSec = 20.0)
        win.append(LiveWindow.parse(playlist("/rawseg/a" to 6.0, "/rawseg/b" to 6.0, "/rawseg/c" to 6.0, sequence = 1), 1))
        assertEquals(18.0, win.sizeSec, 0.001)
        win.append(LiveWindow.parse(playlist("/rawseg/c" to 6.0, "/rawseg/d" to 6.0, "/rawseg/e" to 6.0, sequence = 3), 3))
        // 30 秒超过 20 → 从头部一直淘汰到 ≤20（a、b 都被丢掉，留下 c/d/e = 18 秒）
        assertEquals(18.0, win.sizeSec, 0.001)
        val rendered = win.render()
        assertTrue(rendered.contains("/rawseg/c\n"))
        assertTrue(rendered.contains("/rawseg/e\n"))
        assertTrue(!rendered.contains("/rawseg/a\n"))
        assertTrue(!rendered.contains("/rawseg/b\n"))
    }

    @Test
    fun 渲染出的清单带序号且没有结束标记() {
        val win = LiveWindow()
        win.append(LiveWindow.parse(playlist("/rawseg/a" to 5.0, "/rawseg/b" to 6.5, sequence = 4200), 4200))
        val text = win.render()
        assertTrue(text.startsWith("#EXTM3U\n"))
        assertTrue(text.contains("#EXT-X-TARGETDURATION:7\n"))
        assertTrue(text.contains("#EXT-X-MEDIA-SEQUENCE:4200\n"))
        assertTrue(!text.contains("#EXT-X-ENDLIST"))
        assertTrue(!text.contains("#EXT-X-PLAYLIST-TYPE"))
    }

    @Test
    fun 没有媒体序号时不写序号行() {
        val win = LiveWindow()
        win.append(LiveWindow.parse(playlist("/rawseg/a" to 6.0), null))
        assertTrue(!win.render().contains("#EXT-X-MEDIA-SEQUENCE"))
    }

    @Test
    fun 清单级标签不进累积区间() {
        val win = LiveWindow()
        win.append(LiveWindow.parse(playlist("/rawseg/a" to 6.0, sequence = 7), 7))
        val rendered = win.render()
        // 只有一份 #EXTM3U / TARGETDURATION，不会因为累积多次而重复
        assertEquals(1, Regex("#EXTM3U").findAll(rendered).count())
        assertEquals(1, Regex("#EXT-X-TARGETDURATION").findAll(rendered).count())
        assertEquals(1, Regex("#EXTINF").findAll(rendered).count())
    }

    @Test
    fun CRLF与空行都能解析() {
        val text = "#EXTM3U\r\n#EXT-X-TARGETDURATION:6\r\n\r\n#EXTINF:6.0,\r\n/rawseg/a\r\n"
        val segs = LiveWindow.parse(text, 9)
        assertEquals(1, segs.size)
        assertEquals("/rawseg/a", segs[0].key)
        assertEquals(9L, segs[0].sequence)
    }

    @Test
    fun MAP标签跟着后面的分片走() {
        val text = "#EXTM3U\n#EXT-X-MAP:URI=\"/rawfile/init.mp4\"\n#EXTINF:6.0,\n/rawseg/a\n"
        val segs = LiveWindow.parse(text, 1)
        assertEquals(1, segs.size)
        assertEquals("#EXT-X-MAP:URI=\"/rawfile/init.mp4\"", segs[0].lines[0])
        // 累积后 MAP 仍然在分片前面
        val win = LiveWindow()
        win.append(segs)
        val rendered = win.render()
        assertTrue(rendered.indexOf("#EXT-X-MAP:") < rendered.indexOf("#EXTINF:"))
    }
}
