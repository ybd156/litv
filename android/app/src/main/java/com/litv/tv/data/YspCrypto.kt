package com.litv.tv.data

import java.security.SecureRandom
import java.util.UUID

/**
 * 澶棰戯紙YSP锛夊彇娴佺鍚嶃€? *
 * 閾捐矾锛歝Key锛圱EA 鍔犲瘑鐨勯壌鏉冪エ鎹級鈫?bkliveinfo 鎹?m3u8 鐩撮摼銆? * 绠楁硶涓庡父閲忔潵鑷叕寮€瀹炵幇锛坅kiralereal/iptv 鐨?ckey.js銆乫unwukong/ysp-live 鐨?Python 鐗堬級锛? * 宸茬敤 Python 瀹炴祴閫氳繃锛欳CTV-1 / CCTV-13 鍧囪繑鍥?iretcode=0 涓斿垎鐗囧彲涓嬭浇銆? *
 * 甯搁噺浼氶殢澶棰戞敼鐗堝け鏁堬紝灞婃椂闇€瑕佹洿鏂?[PLATFORM] / [APP_VER] / 涓夌粍瀵嗛挜銆? * 璁捐涓婂埢鎰忓叏閮ㄩ泦涓湪鏈枃浠堕《灞傦紝鏂逛究鏇挎崲銆? */
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

    fun ticket(channelId: String): Ticket = ticket(channelId, null, null, null)

    /**
     * 鍙敞鍏ラ殢鏈烘簮鐨勭増鏈紝浠呬緵浜ゅ弶楠岃瘉鐢紙鐢熶骇璧颁笂闈㈢殑鍗曞弬閲嶈浇锛夈€?     *
     * 鍥哄畾 guid / ts / uid 鍚?cKey 瀹屽叏纭畾锛屽彲浠ユ嬁缁?Python 鍙傝€冨疄鐜版瘮瀵癸紝
     * 涔熷彲浠ョ洿鎺ユ妸杩欎釜 cKey 涓㈢粰澶棰戠湅 iretcode 鏄惁涓?0銆?     */
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
        // 18..22 澶勫洖濉牎楠岋紙瑕嗙洊鏁村寘锛?        be32(cksum(pkt)).copyInto(pkt, 18)

        val enc1 = teaPkt(pkt, TEA_KEY) + be32(cksum(pkt))
        val enc2 = ByteArray(enc1.size) { i -> (enc1[i].toInt() xor XOR_16[i and 15].toInt()).toByte() }
        val b64 = b64url(enc2)
        val flowId = UUID.randomUUID().toString().replace("-", "").uppercase() + "_" + PLATFORM
        return Ticket("--01$b64", guid, ts, flowId)
    }

    // ---------------------------------------------------------------- 绠楁硶

    /**
     * For cross-validation only: the exact pkt bytes BEFORE TEA encryption
     * (cksum already back-filled). Deterministic, so it can be compared
     * byte-for-byte against the Python reference implementation.
     */
    internal fun debugPacket(
        channelId: String,
        ts: Long,
        guid: String,
        uid: String,
    ): ByteArray {
        val guard = ckGuard(ts, guid)
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
        be32(cksum(pkt)).copyInto(pkt, 18)
        return pkt
    }

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

    /** 閾惧紡 XOR 鍓嶅潡锛圕BC-like锛夛紝涓?YSP 鐨?TEA 鍖呮牸寮忎竴鑷淬€?*/
    internal fun teaPkt(data: ByteArray, key: ByteArray): ByteArray = teaPkt(data, key, null)

    /**
     * @param rndBytes 可注入的随机源（测试用固定实现）。null 走真随机。
     *                 调用顺序是 rand(1) → rand(pad) → rand(2)，与 Python 参考实现一致。
     */
    internal fun teaPkt(data: ByteArray, key: ByteArray, rndBytes: ((Int) -> ByteArray)?): ByteArray {
        val rand: (Int) -> ByteArray = rndBytes ?: { n -> ByteArray(n).also { rnd.nextBytes(it) } }
        val pad = (8 - ((data.size + 10) % 8)) % 8
        val plain = ByteArray(1 + pad + 2 + data.size + 7)
        plain[0] = (((rand(1)[0].toInt() and 0xF8) or pad) and 0xFF).toByte()
        // 注意：不能写 rnd.nextBytes(plain.copyOfRange(..)) —— copyOfRange 返回副本，
        // 填的是副本，原数组仍是全 0。必须先生成到临时数组再 copyInto。
        rand(pad).copyInto(plain, 1)
        rand(2).copyInto(plain, 1 + pad)
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
    private fun lp(s: String): ByteArray = lp(s.toByteArray(Charsets.UTF_8))

    /** 闀垮害鍓嶇紑瀛楄妭鍧楋紙ckGuard 閲岃鎶婂凡鎷煎ソ鐨?body 鍐嶅寘涓€灞傦級銆?*/
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
     * base64url锛堟棤濉厖锛夈€?     *
     * 鍒绘剰鎵嬪啓鑰屼笉鐢?android.util.Base64锛氳繖鏍锋暣涓?YspCrypto 鍦?JVM 鍗曟祴閲屽彲鐩存帴璺戯紝
     * 鑳芥妸銆岀湡瀹炵殑 cKey銆嶇畻鍑烘潵鍠傜粰 Python 鍙傝€冨疄鐜板仛浜ゅ弶楠岃瘉銆?     */
    internal val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray()

    internal fun b64url(data: ByteArray): String {
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
