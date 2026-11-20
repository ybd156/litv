package com.litv.tv.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 诊断用：把完整 cKey 打出来（不打码），供 Python 侧解密比对。
 *
 * 之所以要这样：算法各环节（teaBlock / cksum / b64url / teaPkt / pkt 结构）
 * 都已与 Python 参考实现逐字节对齐，但央视频仍返回 iretcode=32，
 * 只剩「cKey 密文本身」没被看过。解密后能直接看出哪个字段错位。
 *
 * **仅诊断用，验证完应删除。**
 */
@RunWith(AndroidJUnit4::class)
class YspDiagTest {

    @Test
    fun dumpCkeyForOfflineDecrypt() {
        // 固定输入，便于 Python 侧解密后逐字段核对
        val ts = 1_760_000_000L
        val guid = "00112233445566778899aabbccddeeff"
        val uid = "AABBCCDD"
        val t = YspCrypto.ticket("2024078201", ts, guid, uid)
        val pkt = YspCrypto.debugPacket("2024078201", ts, guid, uid)
        val realPkt = YspCrypto.lastPacketForTest
        android.util.Log.w("YSP_DIAG", "CKEY=" + t.cKey)
        android.util.Log.w("YSP_DIAG", "GUID=" + t.guid)
        android.util.Log.w("YSP_DIAG", "TS=" + t.ts)
        val h = pkt.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        h.chunked(200).forEachIndexed { i, part -> android.util.Log.w("YSP_DIAG", "PKT_$i=$part") }
        if (realPkt != null) {
            val rh = realPkt.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            rh.chunked(200).forEachIndexed { i, part -> android.util.Log.w("YSP_DIAG", "REALPKT_$i=$part") }
            android.util.Log.w("YSP_DIAG", "REALPKT_LEN=" + realPkt.size)
        } else {
            android.util.Log.w("YSP_DIAG", "REALPKT_LEN=null")
        }
        println("YSP_DIAG_CKEY=" + t.cKey)
        println("YSP_DIAG_GUID=" + t.guid)
        println("YSP_DIAG_TS=" + t.ts)
        println("YSP_DIAG_PKTLEN=" + pkt.size)
    }

    @Test
    fun dumpRawResponseForCkey() {
        val sid = "2024078201"
        val pid = "600001859"
        val t = YspCrypto.ticket(sid)
        val spvcode = android.util.Base64.encodeToString(
            "H(30:1080,60:1080|30:1080,60:1080)".toByteArray(), android.util.Base64.NO_WRAP
        )
        val q = buildString {
            fun p(k: String, v: String) {
                if (isNotEmpty()) append('&')
                append(URLEncoder.encode(k, "UTF-8")).append('=').append(URLEncoder.encode(v, "UTF-8"))
            }
            p("atime", "120"); p("livepid", pid); p("cnlid", sid)
            p("appVer", YspCrypto.APP_VER); p("app_version", "300090")
            p("caplv", "1"); p("cmd", "2"); p("defn", "fhd")
            p("device", "iPhone"); p("encryptVer", "4.2"); p("getpreviewinfo", "0")
            p("hevclv", "0"); p("lang", "zh-Hans_CN"); p("livequeue", "0")
            p("logintype", "1"); p("nettype", "1"); p("newnettype", "1")
            p("newplatform", YspCrypto.PLATFORM.toString())
            p("platform", YspCrypto.PLATFORM.toString())
            p("sdtfrom", "v3021"); p("spacode", "23"); p("spaudio", "1")
            p("spdemuxer", "6"); p("spdrm", "2"); p("spdynamicrange", "1")
            p("spflv", "1"); p("spflvaudio", "1"); p("sphdrfps", "60"); p("sphttps", "1")
            p("spvcode", spvcode); p("spvideo", "4"); p("stream", "1"); p("system", "1")
            p("sysver", "ios18.2.1"); p("uhd_flag", "0")
            p("cKey", t.cKey); p("guid", t.guid); p("fntick", t.ts.toString())
            p("flowid", t.flowId); p("playbacktime", "0")
        }
        val c = (URL("https://bkliveinfo.ysp.cctv.cn/?$q").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 20000; readTimeout = 20000
            setRequestProperty("User-Agent", "qqlive"); setRequestProperty("Accept", "application/json")
        }
        val body = try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
        android.util.Log.w("YSP_DIAG", "CKEY=" + t.cKey)
        android.util.Log.w("YSP_DIAG", "GUID=" + t.guid)
        android.util.Log.w("YSP_DIAG", "TS=" + t.ts)
        android.util.Log.w("YSP_DIAG", "RESP=" + body)
        println("YSP_DIAG_CKEY=" + t.cKey)
        println("YSP_DIAG_GUID=" + t.guid)
        println("YSP_DIAG_TS=" + t.ts)
        println("YSP_DIAG_RESP=" + body.take(400))
    }
}
