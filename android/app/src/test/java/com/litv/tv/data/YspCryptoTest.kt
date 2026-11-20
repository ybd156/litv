package com.litv.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 央视频签名算法的对齐测试。
 *
 * 期望值由 Python 参考实现（funwukong/ysp-live 的 _tea_blk / _cksum）算出，
 * 两边一致才说明 Kotlin 迁移正确——TEA 里 Int 溢出与无符号右移最容易出错。
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

    @Test
    fun ticket_hasExpectedShape() {
        val t = YspCrypto.ticket("2024078201")
        // cKey 以 --01 开头，base64url 字符集（无 + / =）
        assertTrue("cKey 前缀不对: ${t.cKey.take(8)}", t.cKey.startsWith("--01"))
        assertTrue("cKey 含非法字符", t.cKey.drop(4).none { it == '+' || it == '/' || it == '=' })
        assertEquals(32, t.guid.length)
        assertTrue("flowId 应以 _PLATFORM 结尾", t.flowId.endsWith("_4330403"))
        assertTrue("ts 不合理", t.ts > 1_700_000_000L)
    }
}
