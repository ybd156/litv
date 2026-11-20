package com.litv.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 央视频签名算法的对齐测试。
 *
 * 期望值由 Python 参考实现（funwukong/ysp-live 的 _tea_blk / _cksum）算出，
 * 两边一致才说明 Kotlin 迁移正确——TEA 里 Int 溢出与无符号右移最容易出错。
 *
 * 这里只测纯算法（不碰 android.util.Base64），所以能在 JVM 单测里跑。
 * ticket() 的端到端验证放在设备上做（yield 见 YspClient 的注释）。
 */
class YspCryptoTest {

    private val ckTea = hex("59b2f7cf725ef43c34fdd7c123411ed3")

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private fun toHex(b: ByteArray): String =
        b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    @Test
    fun teaBlock_matchesReferenceImplementation() {
        val cases = listOf(
            "0000000000000000" to "3017822a19141e00",
            "0001020304050607" to "98b5f8365d27c035",
            "ffffffffffffffff" to "69b93e57f8401f76",
            "0123456789abcdef" to "ce084e2ba724b4e5",
        )
        for ((input, expected) in cases) {
            assertEquals(
                "TEA($input) 与 Python 参考实现不一致",
                expected,
                toHex(YspCrypto.teaBlock(hex(input), ckTea)),
            )
        }
    }

    @Test
    fun cksum_matchesReferenceImplementation() {
        assertEquals(0L, YspCrypto.cksum(ByteArray(0)))
        assertEquals(0L, YspCrypto.cksum(byteArrayOf(0)))
        assertEquals(1677554L, YspCrypto.cksum("abc".toByteArray()))
        assertEquals(203761096L, YspCrypto.cksum(ByteArray(16) { it.toByte() }))
    }

    /**
     * 交叉验证：把 Kotlin 算出的真实 cKey 打印出来（输出在 `gradle testDebugUnitTest --info` 日志里），
     * 然后用它直接请求央视频看 iretcode，或喂给 Python 参考实现比对。
     *
     * 注意：cKey **不是**确定性的——TEA 包里的 padding 是随机的，
     * 只要解密结果一致就算正确，所以这里只校验形状与能否生成，不做等值断言。
     */
    @Test
    fun ticket_isGeneratedAndWellFormed() {
        val t = YspCrypto.ticket(
            channelId = "2024078201",
            tsOverride = 1_760_000_000L,
            guidOverride = "00112233445566778899aabbccddeeff",
            uidOverride = "AABBCCDD",
        )
        println("YSP_CKEY=" + t.cKey)
        println("YSP_GUID=" + t.guid)
        println("YSP_TS=" + t.ts)
        println("YSP_FLOWID=" + t.flowId)

        assertEquals("cKey 前缀", "--01", t.cKey.take(4))
        assertTrue("cKey 太短: ${t.cKey.length}", t.cKey.length > 100)
        assertTrue("cKey 含非法字符", t.cKey.drop(4).none { it == '+' || it == '/' || it == '=' })
        assertEquals(32, t.guid.length)
        assertEquals(1_760_000_000L, t.ts)
    }
}
