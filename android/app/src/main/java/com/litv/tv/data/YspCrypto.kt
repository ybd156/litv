package com.litv.tv.data

import java.security.SecureRandom
import java.util.UUID

/**
 * 澶棰戯紙YSP锛夊彇娴佺鍚嶃€? *
 * 閾捐矾锛歝Key锛圱EA 鍔犲瘑鐨勯壌鏉冪エ鎹級鈫?bkliveinfo 鎹?m3u8 鐩撮摼銆? * 绠楁硶涓庡父閲忔潵鑷叕寮€瀹炵幇锛坅kiralereal/iptv 鐨?ckey.js銆乫unwukong/ysp-live 鐨?Python 鐗堬級锛? * 宸茬敤 Python 瀹炴祴閫氳繃锛欳CTV-1 / CCTV-13 鍧囪繑鍥?iretcode=0 涓斿垎鐗囧彲涓嬭浇銆? *
 * **甯搁噺浼氶殢澶棰戞敼鐗堝け鏁?*锛屽眾鏃堕渶瑕佹洿鏂?[PLATFORM] / [APP_VER] / 涓夌粍瀵嗛挜銆? * 璁捐涓婂埢鎰忓叏閮ㄩ泦涓湪鏈枃浠堕《灞傦紝鏂逛究鏇挎崲銆? */
object YspCrypto {

    // ---------------------------------------------------------------- 甯搁噺

    const val PLATFORM = 4330403L
    const val APP_VER = "V8.22.1035.3031"

    private val TEA_KEY = hex("59b2f7cf725ef43c34fdd7c123411ed3")
    private val GTEA_KEY = hex("110DBEC10C23E7D2E56A1CAD6914EF1B")
    private val XOR_16 = hex("842eed08f066e6ea48b4caa991ed6ff3")
    private val GXOR_8 = hex("b3c953a06913ad4d")

    private val rnd = SecureRandom()

    // ---------------------------------------------------------------- 瀵瑰

    /** 涓€娆?cKey 鐢熸垚鐨勫叏閮ㄤ骇鐗┿€?*/
    data class Ticket(
        val cKey: String,
        val guid: String,
        val ts: Long,
        val flowId: String,
    )

    fun ticket(channelId: String): Ticket {
        val ts = System.currentTimeMillis() / 1000
        val guid = ByteArray(16).also { rnd.nextBytes(it) }.toHex()
        val guard = ckGuard(ts, guid)
        val uid = ByteArray(4).also { rnd.nextBytes(it) }.toHex().uppercase()

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
        // 18..22 澶勫洖濉牎楠岋紙瑕嗙洊鏁村寘锛?        be32(cksum(pkt)).copyInto(pkt, 18)

        val enc1 = teaPkt(pkt, TEA_KEY) + be32(cksum(pkt))
        val enc2 = ByteArray(enc1.size) { i -> (enc1[i].toInt() xor XOR_16[i and 15].toInt()).toByte() }
        val b64 = b64url(enc2)
        val flowId = UUID.randomUUID().toString().replace("-", "").uppercase() + "_" + PLATFORM
        return Ticket("--01$b64", guid, ts, flowId)
    }

    // ---------------------------------------------------------------- 绠楁硶

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

    /** 閾惧紡 XOR 鍓嶅潡锛圕BC-like锛夛紝涓?atta TEA 鍖呮牸寮忎竴鑷淬€?*/
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

    /** 0x83 澶氶」寮忔粴鍔ㄦ牎楠屻€?*/
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

    // ---------------------------------------------------------------- 灏忓伐鍏?
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

    /** 闀垮害鍓嶇紑瀛楃涓诧紙2 瀛楄妭澶х闀垮害 + UTF-8锛夈€?*/
    private fun lp(s: String): ByteArray {
        val d = s.toByteArray(Charsets.UTF_8)
        return byteArrayOf(((d.size ushr 8) and 0xFF).toByte(), (d.size and 0xFF).toByte()) + d
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val n = parts.sumOf { it.size }
        val out = ByteArray(n)
        var p = 0
        for (x in parts) { x.copyInto(out, p); p += x.size }
        return out
    }

    private fun b64url(data: ByteArray): String =
        android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
            .replace('+', '_').replace('/', '-').trimEnd('=')

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) sb.append("%02x".format(b.toInt() and 0xFF))
        return sb.toString()
    }
}
