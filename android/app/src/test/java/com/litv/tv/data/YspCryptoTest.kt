package com.litv.tv.data

import org.junit.Assert.assertEquals
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
}
