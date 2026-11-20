package com.litv.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 央视频取流的**真实联网**验证（JVM 单测，不需要设备）。
 *
 * 为什么放在 test 而不是 androidTest：
 * 整条链路只用 YspCrypto（纯算法）+ HttpURLConnection（JDK 自带），
 * 不碰任何 android.* API，所以能在 CI 里直接跑。
 *
 * 断言的是「iretcode == 0」——这只有 cKey 完全正确才会成立，
 * 因此它同时验证了 TEA / cksum / XOR / 结构体拼装 / base64url 全部环节。
 *
 * 需要外网。CI 有外网，本地也能跑。
 */
class YspLiveSmokeTest {

    private val ckAppVer = YspCrypto.APP_VER
    private val spvcode = "SChIMzA6MTA4MCw2MDoxMDgwfDMwOjEwODAsNjA6MTA4MCk=".let {
        // 与 YspClient 里一致：base64("H(30:1080,60:1080|30:1080,60:1080)")
        java.util.Base64.getEncoder().encodeToString(
            "H(30:1080,60:1080|30:1080,60:1080)".toByteArray()
        )
    }

    @Test
    fun cctv1_iretcode_is_zero() {
        val sid = "2024078201"
        val pid = "600001859"
        val t = YspCrypto.ticket(sid)

        val q = buildString {
            fun p(k: String, v: String) {
                if (isNotEmpty()) append('&')
                append(URLEncoder.encode(k, "UTF-8")).append('=').append(URLEncoder.encode(v, "UTF-8"))
            }
            p("atime", "120"); p("livepid", pid); p("cnlid", sid)
            p("appVer", ckAppVer); p("app_version", "300090")
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

        val body = httpGet("https://bkliveinfo.ysp.cctv.cn/?$q")
        println("YSP_RESPONSE=" + body.take(500))

        // iretcode 是数字；32 = 权限验证失败（说明签名不对）
        val code = Regex("\"iretcode\"\\s*:\\s*(-?\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull()
        assertEquals("iretcode 应为 0，实际响应：${body.take(300)}", 0, code)

        // 必须带回至少一条 .cctv. 直链
        val urls = Regex("\"https://[^\"]*\\.cctv\\.[^\"]*\"")
            .findAll(body).map { it.value.trim('"') }.filter { it.isNotBlank() }.toList()
        assertTrue("没有返回 .cctv. 直链：${body.take(300)}", urls.isNotEmpty())
        println("YSP_URLS=" + urls.joinToString(" , ").take(400))
    }

    private fun httpGet(u: String): String {
        val c = (URL(u).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20000
            readTimeout = 20000
            setRequestProperty("User-Agent", "qqlive")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            check(code in 200..299) { "HTTP $code: ${text.take(200)}" }
            return text
        } finally {
            c.disconnect()
        }
    }
}
