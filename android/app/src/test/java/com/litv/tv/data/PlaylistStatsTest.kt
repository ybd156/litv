package com.litv.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistStatsTest {

    @Test
    fun 标准直播清单能算出窗口与序号() {
        val text = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:5
            #EXT-X-MEDIA-SEQUENCE:1786801664
            #EXTINF:5.000,
            seg1.ts
            #EXTINF:5.000,
            seg2.ts
            #EXTINF:5.000,
            seg3.ts
        """.trimIndent()
        val st = PlaylistStats.of(text)
        assertEquals(3, st.segments)
        assertEquals(15.0, st.windowSec, 0.001)
        assertEquals(5, st.targetDurationSec)
        assertEquals(1786801664L, st.sequence)
        assertEquals("15s", st.windowLabel())
    }

    @Test
    fun 空清单不抛异常() {
        val st = PlaylistStats.of("")
        assertEquals(0, st.segments)
        assertEquals(0.0, st.windowSec, 0.001)
        assertEquals(0, st.targetDurationSec)
        assertNull(st.sequence)
    }

    @Test
    fun CRLF行尾也能解析() {
        val text = "#EXTM3U\r\n#EXT-X-TARGETDURATION:6\r\n#EXTINF:6.000,\r\na.ts\r\n"
        val st = PlaylistStats.of(text)
        assertEquals(1, st.segments)
        assertEquals(6.0, st.windowSec, 0.001)
        assertEquals(6, st.targetDurationSec)
    }

    @Test
    fun 小数时长按实际值累加() {
        val text = "#EXTM3U\n#EXTINF:4.5,\na.ts\n#EXTINF:5.5,\nb.ts\n"
        val st = PlaylistStats.of(text)
        assertEquals(2, st.segments)
        assertEquals(10.0, st.windowSec, 0.001)
        assertEquals("10s", st.windowLabel())
    }

    @Test
    fun 没有媒体序号时为null() {
        val text = "#EXTM3U\n#EXTINF:5.000,\na.ts\n"
        assertNull(PlaylistStats.of(text).sequence)
    }

    @Test
    fun 带结束标记的点播清单也照常计数() {
        val text = "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXTINF:5.000,\na.ts\n#EXT-X-ENDLIST\n"
        val st = PlaylistStats.of(text)
        assertEquals(1, st.segments)
        assertEquals(5.0, st.windowSec, 0.001)
    }
}
