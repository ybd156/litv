package com.litv.tv.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/**
 * 定位 `ticket()` 实际产出的 pkt 与 `debugPacket()` 的差异。
 *
 * 背景：两者 body 构造代码逐行相同、teaBlock/cksum/b64url/teaPkt 也都与 Python 逐字节一致，
 * 但 App 生成的 cKey 被央视频拒绝（iretcode=32），解密后 `_zj1A5...==` 的末尾两个 '=' 变成乱码。
 * 所以直接让两者输出各自的 pkt，逐字节 diff。
 */
@RunWith(AndroidJUnit4::class)
class YspDiagTest {

    private val ts = 1_760_000_000L
    private val guid = "00112233445566778899aabbccddeeff"
    private val uid = "AABBCCDD"
    private val cid = "2024078201"

    @Test
    fun ticketPacketMatchesDebugPacket() {
        val t = YspCrypto.ticket(cid, ts, guid, uid)
        val fromTicket = YspCrypto.lastPacketForTest
        val fromDebug = YspCrypto.debugPacket(cid, ts, guid, uid)

        android.util.Log.w("YSP_DIAG", "TICKET_LEN=" + (fromTicket?.size ?: -1))
        android.util.Log.w("YSP_DIAG", "DEBUG_LEN=" + fromDebug.size)
        if (fromTicket == null) {
            android.util.Log.w("YSP_DIAG", "TICKET_PKT=null")
            return
        }
        val same = fromTicket.contentEquals(fromDebug)
        android.util.Log.w("YSP_DIAG", "SAME=" + same)
        if (!same) {
            val n = minOf(fromTicket.size, fromDebug.size)
            val diff = (0 until n).filter { fromTicket[it] != fromDebug[it] }
            android.util.Log.w("YSP_DIAG", "DIFF_COUNT=" + diff.size + " FIRST=" + diff.take(12))
            for (i in diff.take(8)) {
                android.util.Log.w("YSP_DIAG", "  @" + i + " ticket=" + "%02x".format(fromTicket[i].toInt() and 0xFF) + " debug=" + "%02x".format(fromDebug[i].toInt() and 0xFF))
            }
        }
        // 顺带验证 cKey 能否被自己解回来
        val dec = YspCrypto.decryptForTest(t.cKey)
        val selfOk = dec != null && dec.contentEquals(fromTicket)
        android.util.Log.w("YSP_DIAG", "SELFDEC_OK=" + selfOk)
    }

    /**
     * 用 ExoPlayer 同一个数据源（DefaultHttpDataSource）请求 App 拿到的 m3u8，
     * 与裸 HttpURLConnection 的结果对比，定位 403 的来源。
     */
    @Test
    fun probeM3u8WithExoDataSource() {
        val ch = YspClient.CHANNELS.first()
        val url = YspClient.lines(ch).first().url
        android.util.Log.w("YSP_DIAG", "PROBE_URL_LEN=" + url.length)

        fun tryFetch(label: String, block: () -> String) {
            val r = runCatching(block).getOrElse { e -> "EX=" + (e.message ?: e.toString()).take(120) }
            android.util.Log.w("YSP_DIAG", "$label -> $r")
        }

        // 1) 裸 HttpURLConnection（已知 200）
        tryFetch("plain") {
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 15000
            c.setRequestProperty("User-Agent", "qqlive")
            val code = c.responseCode
            val n = if (code == 200) c.inputStream.readBytes().size else -1
            "code=$code bytes=$n"
        }

        // 2) 带 Range（ExoPlayer 拉媒体时可能带）
        tryFetch("range0-") {
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 15000
            c.setRequestProperty("User-Agent", "qqlive")
            c.setRequestProperty("Range", "bytes=0-")
            val code = c.responseCode
            "code=$code"
        }

        // 3) ExoPlayer 的 DefaultHttpDataSource
        tryFetch("exoDataSource") {
            val f = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setUserAgent("qqlive")
                .setAllowCrossProtocolRedirects(true)
            val ds = f.createDataSource()
            val spec = androidx.media3.datasource.DataSpec.Builder()
                .setUri(android.net.Uri.parse(url))
                .build()
            val n = ds.open(spec)
            ds.close()
            "bytes=$n"
        }
    }

    /** 把 App 拿到的 m3u8 地址与拉取结果打印出来（定位 403）。 */
    @Test
    fun probeM3u8Exactly() {
        val ch = YspClient.CHANNELS.first()
        val lines = YspClient.lines(ch)
        android.util.Log.w("YSP_DIAG", "LINES=" + lines.size)
        for (l in lines) {
            android.util.Log.w("YSP_DIAG", "URL=" + l.url)
            val res = runCatching {
                val c = java.net.URL(l.url).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 15000
                c.setRequestProperty("User-Agent", "qqlive")
                val code = c.responseCode
                val n = if (code == 200) c.inputStream.readBytes().size else -1
                "code=$code bytes=$n"
            }.getOrElse { e -> "ex=" + (e.message ?: e.toString()) }
            android.util.Log.w("YSP_DIAG", "  -> " + res)
        }
    }

    /** 固定随机源下输出 pkt hex 与 cKey：两边可逐块对比密文。 */
    @Test
    fun dumpDeterministicPktAndCkey() {
        val fixed: (Int) -> ByteArray = { n -> ByteArray(n) { i -> (i + n).toByte() } }
        val t = YspCrypto.ticket(cid, ts, guid, uid, fixed)
        val pkt = YspCrypto.lastPacketForTest
        if (pkt == null) {
            android.util.Log.w("YSP_DIAG", "PKT=null")
            return
        }
        val h = pkt.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        android.util.Log.w("YSP_DIAG", "DET_PKTLEN=" + pkt.size)
        h.chunked(220).forEachIndexed { i, part -> android.util.Log.w("YSP_DIAG", "DET_PKTHEX_$i=$part") }
        t.cKey.chunked(200).forEachIndexed { i, part -> android.util.Log.w("YSP_DIAG", "DET_CK_$i=$part") }
    }

    /** 固定输入下把 pkt hex 与 cKey 一起输出，供 Python 侧一次对齐。 */
    @Test
    fun dumpPktAndCkeyForOfflineCompare() {
        val t = YspCrypto.ticket(cid, ts, guid, uid)
        val pkt = YspCrypto.lastPacketForTest
        if (pkt == null) {
            android.util.Log.w("YSP_DIAG", "PKT=null")
            return
        }
        val h = pkt.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        android.util.Log.w("YSP_DIAG", "PKTLEN=" + pkt.size)
        h.chunked(220).forEachIndexed { i, part ->
            android.util.Log.w("YSP_DIAG", "PKTHEX_$i=$part")
        }
        // cKey 分段打，避免单行过长被 logcat 截断
        t.cKey.chunked(200).forEachIndexed { i, part ->
            android.util.Log.w("YSP_DIAG", "CK_$i=$part")
        }
    }

    /** 用固定随机源算 guard，输出 hex 供 Python 逐字节比对。 */
    @Test
    fun dumpGuardForOfflineCompare() {
        val g = YspCrypto.debugGuardForTest(ts, guid)
        android.util.Log.w("YSP_DIAG", "GUARD_LEN=" + g.length)
        android.util.Log.w("YSP_DIAG", "GUARD=" + g)
        println("YSP_DIAG_GUARD=" + g)
    }

    /** 直接跑一次真实取流，把 iretcode 打出来（诊断是否需要修改常量）。 */
    @Test
    fun liveProbeWithReportedCkey() {
        val ch = YspClient.CHANNELS.first()
        val t0 = System.currentTimeMillis()
        val result = runCatching { YspClient.lines(ch) }
        val ms = System.currentTimeMillis() - t0
        result.onSuccess { lines ->
            android.util.Log.w("YSP_DIAG", "LIVE_OK lines=" + lines.size + " " + ms + "ms")
            lines.take(2).forEach { android.util.Log.w("YSP_DIAG", "  " + it.url.take(100)) }
        }.onFailure { e ->
            android.util.Log.w("YSP_DIAG", "LIVE_FAIL " + ms + "ms " + (e.message ?: e.toString()))
        }
    }
}
