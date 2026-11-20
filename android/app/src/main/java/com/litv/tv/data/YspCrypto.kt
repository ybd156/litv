package com.litv.tv.data

import java.security.SecureRandom
import java.util.UUID

/**
 * 央视频（YSP）取流签名。
 *
 * 链路：cKey（TEA 加密的鉴权票据）→ bkliveinfo 换 m3u8 直链。
 * 算法与常量来自公开实现（akiralereal/iptv 的 ckey.js、funwukong/ysp-live 的 Python 版），
 * 已用 Python 实测通过：CCTV-1 / CCTV-13 均返回 iretcode=0 且分片可下载。
 *
 * 常量会随央视频改版失效，届时需要更新 [PLATFORM] / [APP_VER] / 三组密钥。
 * 设计上刻意全部集中在本文件顶层，方便替换。
 */
object YspCrypto {

    // ---------------------------------------------------------------- 常量

    const val PLATFORM = 4330403L
    const val APP_VER = "V8.22.1035.3031"

    private val TEA_KEY = hex("59b2f7cf725ef43c34fdd7c123411ed3")
    private val GTEA_KEY = hex("110DBEC10C23E7D2E56A1CAD6914EF1B")
    private val XOR_16 = hex("842eed08f066e6ea48b4caa991ed6ff3")
    private val GXOR_8 = hex("b3c953a06913ad4d")

    private val rnd = SecureRandom()

    // ---------------------------------------------------------------- 对外

    /** 一次 cKey 生成的全部产物。 */
    data class Ticket(
        val cKey: String,
        val guid: String,
        val ts: Long,
        val flowId: String,
    )

    fun ticket(channelId: String): Ticket = ticket(channelId, null, null, null)

    /**
     * 可注入随机源的版本，仅供交叉验证用（生产走上面的单参重载）。
     *
     * 固定 guid / ts / uid 后 cKey 完全确定，可以拿给 Python 参考实现比对，
     * 也可以直接把这个 cKey 丢给央视频看 iretcode 是否为 0。
     */
    fun ticket(
        channelId: String,
        tsOverride: Long?,
        guidOverride: String?,
        uidOverride: String?,
    ): Ticket {
        val ts = tsOverride ?: (System.currentTimeMillis() / 1000)
        val guid = guidOverride ?: ByteArray(16).also { rnd.nextBytes(it) }.toHex()
        val guard = ckGuard(ts, guid)
        val uid = uidOverride ?: ByteArray(4).also { rnd.nextBytes(it) }.toHex().uppercase()

        val body = concat(
            hex("0000004200000004000004d2"),
            be32(PLATFORM),
            be32(0),
            be32(ts),
            lp("dcgh"),
            lp("_zj1A5Gh6QYcxWjIUGos2w=="),
            lp(APP_VER),
            lp(channelId),
            lp(guid),
            be32(1),
            be32(1),
            lp(uid),
            lp("nil"),
            lp("57eab0c4-2c58-44c6-8ae9-dd2757525dc5"),
            lp("nil"),
            lp("v0.1.000"),
            lp("com.cctv.yangshipin.app.iphone"),
            lp(PLATFORM.toString()),
            lp("ex_json_bus"),
            lp("ex_json_vs"),
            lp(guard),
        )

        val pkt = ByteArray(2 + body.size)
        pkt[0] = ((body.size ushr 8) and 0xFF).toByte()
        pkt[1] = (body.size and 0xFF).toByte()
        body.copyInto(pkt, 2)
        // 18..22 处回填校验（覆盖整包）
        be32(cksum(pkt)).copyInto(pkt, 18)

        val enc1 = teaPkt(pkt, TEA_KEY) + be32(cksum(pkt))
        val enc2 = ByteArray(enc1.size) { i -> (enc1[i].toInt() xor XOR_16[i and 15].toInt()).toByte() }
        val b64 = b64url(enc2)
        val flowId = UUID.randomUUID().toString().replace("-", "").uppercase() + "_" + PLATFORM
        return Ticket("--01$b64", guid, ts, flowId)
    }

    // ---------------------------------------------------------------- 算法

    internal fun teaBlock(block: ByteArray, key: ByteArray): ByteArray {
        var y = be32At(block, 0)
        var z = be32At(block, 4)
        val k0 = be32At(key, 0); val k1 = be32At(key, 4)
        val k2 = be32At(key, 8); val k3 = be32At(key, 12)
        var sum = 0L
        repeat(16) {
            sum = (sum + 0x9e3779b9L) and 0xFFFFFFFFL
            y = (y + ((((z shl 4) + k0) xor (z + sum) xor ((z ushr 5) + k1)) and 0xFFFFFFFFL)) and 0xFFFFFFFFL
            z = (z + ((((y shl 4) + k2) xor (y + sum) xor ((y ushr 5) + k3)) and 0xFFFFFFFFL)) and 0xFFFFFFFFL
        }
        return be32(y) + be32(z)
    }

    /** 链式 XOR 前块（CBC-like），与 YSP 的 TEA 包格式一致。 */
    internal fun teaPkt(data: ByteArray, key: ByteArray): ByteArray {
        val pad = (8 - ((data.size + 10) % 8)) % 8
        val plain = ByteArray(1 + pad + 2 + data.size + 7)
        plain[0] = (((rnd.nextInt() and 0xF8) or pad) and 0xFF).toByte()
        rnd.nextBytes(plain.copyOfRange(1, 1 + pad))
        rnd.nextBytes(plain.copyOfRange(1 + pad, 3 + pad))
        data.copyInto(plain, 3 + pad)

        val out = ByteArray(plain.size)
        var pp = ByteArray(8)
        var pc = ByteArray(8)
        var off = 0
        while (off < plain.size) {
            val mixed = ByteArray(8) { i -> (plain[off + i].toInt() xor pc[i].toInt()).toByte() }
            val enc = teaBlock(mixed, key)
            val cipher = ByteArray(8) { i -> (enc[i].toInt() xor pp[i].toInt()).toByte() }
            cipher.copyInto(out, off)
            pp = mixed
            pc = cipher
            off += 8
        }
        return out
    }

    /** 0x83 多项式滚动校验。 */
    internal fun cksum(buf: ByteArray): Long {
        var v = 0L
        for (b in buf) v = (0x83L * v + (b.toInt() and 0xFF)) and 0x7fffffffL
        return v
    }

    private fun ckGuard(ts: Long, guid: String): String {
        fun tail(v: String) = if (v.length >= 5) v.takeLast(5) else ""
        val body = concat(
            be32(ts),
            lp(tail(guid)),
            lp(tail("null")),
            lp(tail("null")),
            lp("-1"),
        )
        val plain = lp(body)
        val enc1 = teaPkt(plain, GTEA_KEY) + be32(cksum(plain))
        val enc2 = ByteArray(enc1.size) { i -> (enc1[i].toInt() xor GXOR_8[i and 7].toInt()).toByte() }
        return enc2.toHex().uppercase()
    }

    // ---------------------------------------------------------------- 小工具

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private fun be32(v: Long): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte(),
    )

    private fun be32At(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or
            ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or
            (b[at + 3].toLong() and 0xFF)

    /** 长度前缀字符串（2 字节大端长度 + UTF-8）。 */
    private fun lp(s: String): ByteArray = lp(s.toByteArray(Charsets.UTF_8))

    /** 长度前缀字节块（ckGuard 里要把已拼好的 body 再包一层）。 */
    private fun lp(d: ByteArray): ByteArray =
        byteArrayOf(((d.size ushr 8) and 0xFF).toByte(), (d.size and 0xFF).toByte()) + d

    private fun concat(vararg parts: ByteArray): ByteArray {
        val n = parts.sumOf { it.size }
        val out = ByteArray(n)
        var p = 0
        for (x in parts) { x.copyInto(out, p); p += x.size }
        return out
    }

    /**
     * base64url（无填充）。
     *
     * 刻意手写而不用 android.util.Base64：这样整个 YspCrypto 在 JVM 单测里可直接跑，
     * 能把「真实的 cKey」算出来喂给 Python 参考实现做交叉验证。
     */
    private val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray()

    private fun b64url(data: ByteArray): String {
        val sb = StringBuilder((data.size * 4 + 2) / 3)
        var i = 0
        while (i + 2 < data.size) {
            val n = ((data[i].toInt() and 0xFF) shl 16) or
                ((data[i + 1].toInt() and 0xFF) shl 8) or
                (data[i + 2].toInt() and 0xFF)
            sb.append(B64[(n ushr 18) and 0x3F])
            sb.append(B64[(n ushr 12) and 0x3F])
            sb.append(B64[(n ushr 6) and 0x3F])
            sb.append(B64[n and 0x3F])
            i += 3
        }
        when (data.size - i) {
            1 -> {
                val n = (data[i].toInt() and 0xFF) shl 16
                sb.append(B64[(n ushr 18) and 0x3F])
                sb.append(B64[(n ushr 12) and 0x3F])
            }
            2 -> {
                val n = ((data[i].toInt() and 0xFF) shl 16) or ((data[i + 1].toInt() and 0xFF) shl 8)
                sb.append(B64[(n ushr 18) and 0x3F])
                sb.append(B64[(n ushr 12) and 0x3F])
                sb.append(B64[(n ushr 6) and 0x3F])
            }
        }
        return sb.toString()
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) sb.append("%02x".format(b.toInt() and 0xFF))
        return sb.toString()
    }
}
