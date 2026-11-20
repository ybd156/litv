package com.litv.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 央视频取流的**真实联网**验证（JVM 单测，不需要设备）。
 *
 * 整条链路只用 YspCrypto（纯算法）+ HttpURLConnection（JDK 自带），不碰 android.* API。
 *
 * ## 为什么默认不跑
 *
 * 央视频对**非中国大陆出口 IP** 返回 `iretcode=32 权限验证失败`
 * （GitHub Actions 的 runner 在美国，实测如此）。
 * 这是地域风控，不是代码错误——同一个 cKey 在国内网络下 iretcode=0。
 *
 * 所以：
 * - CI 默认 **跳过**（`assumeTrue`），保持绿灯；
 * - 需要验证时设环境变量 `YSP_LIVE_TEST=1`，并且在**国内网络**下跑。
 *
 * ## 断言什么
 *
 * `iretcode == 0` 只有 cKey 完全正确才会成立，
 * 因此它同时验证 TEA / cksum / XOR / 结构体拼装 / base64url 全部环节。
 */
class YspLiveSmokeTest {

    private val enabled: Boolean =
        System.getenv("YSP_LIVE_TEST") == "1"

    @Test
    fun cctv1_iretcode_is_zero() {
        assumeTrue(
            "YSP_LIVE_TEST != 1，跳过联网验证（需要中国大陆出口 IP）",
            enabled
        )

        val sid = "2024078201"
        val pid = "600001859"
        val t = YspCrypto.ticket(sid)
        val spvcode = java.util.Base64.getEncoder()
            .encodeToString("H(30:1080,60:1080|30:1080,60:1080)".toByteArray())

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

        val body = httpGet("https://bkliveinfo.ysp.cctv.cn/?$q")
        println("YSP_PARAMS=" + q.replace(t.cKey, "<CKEY>").take(1200))
        println("YSP_CKEY_LEN=" + t.cKey.length)
        println("YSP_RESPONSE=" + body.take(500))

        val code = Regex("\"iretcode\"\\s*:\\s*(-?\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull()
        assertEquals("iretcode 应为 0，实际响应：${body.take(300)}", 0, code)

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
