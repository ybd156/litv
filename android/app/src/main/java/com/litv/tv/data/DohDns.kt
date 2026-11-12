package com.litv.tv.data

import com.google.gson.JsonParser
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * DoH JSON 响应解析（**纯函数**，不联网、不碰 Android 框架 —— 可 JVM 单测）。
 *
 * 支持的三家（阿里 / 腾讯 / Cloudflare）都遵循 Google DoH JSON 规范：
 * ```json
 * {"Status":0,"TC":false,"RD":true,"RA":true,"AD":false,"CD":false,
 *  "Question":[{"name":"example.com.","type":1}],
 *  "Answer":[{"name":"example.com.","TTL":600,"type":1,"data":"93.184.216.34"}]}
 * ```
 * NXDOMAIN 时 `Status` = 3，`Answer` 缺失。
 *
 * 只取 A 记录（type=1），**不取 AAAA**：国内电视盒子的 IPv6 常常半通不通，
 * 先试 AAAA 会先卡几秒再回落，正好是"起播慢"的一部分。宁可用 A 直连。
 */
object DohJson {

    /** 缺 TTL / TTL 异常时用这个。 */
    const val DEFAULT_TTL_SEC = 60

    /** TTL 上限：10 分钟。再长就对上游变更不敏感了。 */
    const val MAX_TTL_SEC = 600

    sealed class Parsed {
        /** 解析成功：可用的 A 记录 + 缓存时长。 */
        data class Ok(val ips: List<String>, val ttlSec: Int) : Parsed()

        /** 需要回落到系统 DNS。reason 只用于日志，不含敏感内容。 */
        data class NeedFallback(val reason: String) : Parsed()
    }

    /**
     * @param body HTTP 响应体（null/空串表示请求失败）
     */
    fun parse(body: String?): Parsed {
        if (body.isNullOrBlank()) return Parsed.NeedFallback("empty-body")

        val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            ?: return Parsed.NeedFallback("malformed-json")

        // Status 缺失也当畸形：没有 Status 的响应不是 DoH JSON，盲目采信比回落更危险。
        val statusEl = root.get("Status")
        if (statusEl == null || !statusEl.isJsonPrimitive) {
            return Parsed.NeedFallback("no-status")
        }
        val status = runCatching { statusEl.asInt }.getOrNull()
            ?: return Parsed.NeedFallback("bad-status")
        if (status != 0) return Parsed.NeedFallback("status-$status")

        val answers = root.getAsJsonArray("Answer") ?: return Parsed.NeedFallback("no-answer")

        val ips = ArrayList<String>(answers.size())
        var minTtl: Int? = null
        answers.forEach { el ->
            val obj = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val type = obj.get("type")?.takeIf { it.isJsonPrimitive }?.asInt ?: return@forEach
            if (type != TYPE_A) return@forEach
            val data = obj.get("data")?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
            // 只接受像 IP 的字符串；数据是主机名就丢掉（别把解析权委托回去）
            if (!DohPolicy.looksLikeIpv4(data)) return@forEach
            if (!ips.contains(data)) ips.add(data)
            val ttl = obj.get("TTL")?.takeIf { it.isJsonPrimitive }?.asInt
            val t = ttlSec(ttl)
            if (minTtl == null || t < minTtl!!) minTtl = t
        }

        if (ips.isEmpty()) return Parsed.NeedFallback("no-a-record")
        return Parsed.Ok(ips, minTtl ?: DEFAULT_TTL_SEC)
    }

    /** 缺失/异常 → [DEFAULT_TTL_SEC]；超过 [MAX_TTL_SEC] → 截到上限。 */
    fun ttlSec(raw: Int?): Int = when {
        raw == null || raw <= 0 -> DEFAULT_TTL_SEC
        raw > MAX_TTL_SEC -> MAX_TTL_SEC
        else -> raw
    }

    private const val TYPE_A = 1
}

/**
 * "这个主机名该不该交给 DoH"的判定（**纯函数**，可单测）。
 *
 * 红线：局域网/回环/字面 IP 一律走系统 DNS ——
 * 用户家 NAS 的 `/live/...` 地址通常是内网 IP 或单标签主机名（`nas`、`fnos.local`），
 * 拿去问公共 DoH 要么查不到、要么把内网名泄漏给第三方。
 */
object DohPolicy {

    /** 内网/本地域后缀：这些名字不该发到公网 DNS。 */
    private val LOCAL_SUFFIX = listOf(".local", ".lan", ".home", ".home.arpa", ".localdomain")

    /**
     * true = 直接用系统 DNS，**不走 DoH**。
     */
    fun preferSystemDns(hostname: String): Boolean {
        val h = hostname.trim().lowercase()
        if (h.isEmpty()) return true
        if (h == "localhost") return true
        if (looksLikeIpv4(h)) return true
        if (h.contains(':')) return true // IPv6 字面量
        // 单标签主机名（`nas`、`olleh`）：LAN 内 NetBIOS/mDNS 名，公网 DNS 不可能有
        if (!h.contains('.')) return true
        if (LOCAL_SUFFIX.any { h.endsWith(it) }) return true
        return false
    }

    /** 是否为点分四段 IPv4 字面量（不做连通性判断，只看形状）。 */
    fun looksLikeIpv4(s: String): Boolean {
        val parts = s.split('.')
        if (parts.size != 4) return false
        return parts.all { p ->
            p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } &&
                (p.toIntOrNull() ?: -1) in 0..255
        }
    }
}

/**
 * 应用内加密 DNS（DNS over HTTPS）。
 *
 * 为什么要有它：电视直连源站时是**它自己去解析域名**。运营商 DNS 污染 / SNI 重置 /
 * CDN 限 IP 都会让直连在握手阶段就被 reset（用户截图里的
 * `SSLHandshakeException ... Connection reset by peer`），于是每次都得先失败一次。
 * 走 DoH 至少把"解析被污染"这一类排除掉。
 *
 * 两个 client（解决鸡生蛋）：
 *   1. `bootstrapClient` —— 只用来**问 DoH 服务**。它的 Dns 是硬编码的 host→IP 表，
 *      但 **URL 仍然是域名**，所以 SNI 和证书校验照常（这是红线：不关校验）。
 *   2. `dohClient` —— 给播放器用，Dns = 本对象（DoH 解析 → 失败回落系统 DNS）。
 *
 * 回落语义（用户口径）：**DoH 优先，失败回落系统 DNS**，不做"只用 DoH"。
 *
 * 已知局限：VLC / mpv 兜底内核自带解析器，改不了；DoH 只覆盖 Media3 主路径。
 */
object DohDns : Dns {

    enum class Provider(val key: String, val label: String, val url: String,
                        val bootstrapHost: String, val bootstrapIps: List<String>) {
        ALI(
            key = "ali", label = "阿里 DNS（推荐）",
            url = "https://dns.alidns.com/resolve",
            bootstrapHost = "dns.alidns.com",
            bootstrapIps = listOf("223.5.5.5", "223.6.6.6"),
        ),
        TENCENT(
            key = "tencent", label = "腾讯 DNSPod",
            url = "https://doh.pub/dns-query",
            bootstrapHost = "doh.pub",
            bootstrapIps = listOf("119.29.29.29", "182.254.116.116"),
        ),
        CLOUDFLARE(
            key = "cloudflare", label = "Cloudflare",
            url = "https://cloudflare-dns.com/dns-query",
            bootstrapHost = "cloudflare-dns.com",
            bootstrapIps = listOf("1.1.1.1", "1.0.0.1"),
        );

        companion object {
            fun from(key: String?): Provider =
                entries.firstOrNull { it.key == key } ?: ALI
        }
    }

    private data class CacheEntry(val ips: List<InetAddress>, val expireAtMs: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    @Volatile private var enabled = true
    @Volatile private var provider = Provider.ALI

    /** 由 LitvApp / 设置页调用：把用户偏好同步进来。 */
    fun configure(enabled: Boolean, providerKey: String?) {
        this.enabled = enabled
        this.provider = Provider.from(providerKey)
        if (!enabled) cache.clear()
    }

    fun isEnabled(): Boolean = enabled

    /** 给播放器用的 client；关闭 DoH 时返回普通 client（行为与旧版一致）。 */
    fun client(): OkHttpClient = if (enabled) dohClient else plainClient

    private val plainClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 引导 client：只用来访问 DoH 端点本身。
     *
     * 它的 Dns 是**硬编码映射**，所以不会递归回到本对象；但请求 URL 仍是域名，
     * TLS 的 SNI 与证书校验完全正常（不做任何 trustAll）。
     */
    private val bootstrapClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // Dns 不是 Kotlin 的 fun interface，不能写 lambda，只能用匿名对象
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = bootstrapResolve(hostname)
            })
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    private val dohClient: OkHttpClient by lazy {
        bootstrapClient.newBuilder()
            .dns(this)
            // 播放是长连接：读超时必须放开，否则直播几分钟后被 OkHttp 掐断
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /** 引导解析：只认三家 DoH 端点自己的域名，其余交给系统。 */
    private fun bootstrapResolve(hostname: String): List<InetAddress> {
        val hit = Provider.entries.firstOrNull {
            it.bootstrapHost.equals(hostname, ignoreCase = true)
        }
        if (hit != null) {
            val ips = hit.bootstrapIps.mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
            if (ips.isNotEmpty()) return ips
        }
        return Dns.SYSTEM.lookup(hostname)
    }

    override fun lookup(hostname: String): List<InetAddress> {
        if (!enabled) return Dns.SYSTEM.lookup(hostname)
        // 内网名 / 字面 IP：不改变原有行为（NAS 地址不能拿去问公网 DNS）
        if (DohPolicy.preferSystemDns(hostname)) return Dns.SYSTEM.lookup(hostname)

        val now = System.currentTimeMillis()
        cache[hostname]?.let { if (it.expireAtMs > now) return it.ips }

        val resolved = queryDoh(hostname)
        if (resolved == null) {
            // DoH 失败/超时/非 200/Status!=0 —— 一律回落系统 DNS（用户口径）
            return Dns.SYSTEM.lookup(hostname)
        }
        cache[hostname] = CacheEntry(resolved.first, now + resolved.second * 1000L)
        return resolved.first
    }

    /** @return (地址列表, TTL 秒)；失败返回 null 表示"该回落"。 */
    private fun queryDoh(hostname: String): Pair<List<InetAddress>, Int>? {
        return try {
            val q = URLEncoder.encode(hostname, "UTF-8")
            val req = Request.Builder()
                .url("${provider.url}?name=$q&type=A")
                .header("Accept", "application/dns-json")
                .get()
                .build()
            // 注意：这里必须用 bootstrapClient —— dohClient 的 Dns 是本对象，会递归
            bootstrapClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val parsed = DohJson.parse(resp.body?.string())
                if (parsed !is DohJson.Parsed.Ok) return null
                val ips = parsed.ips.mapNotNull {
                    runCatching { InetAddress.getByName(it) }.getOrNull()
                }
                if (ips.isEmpty()) return null
                ips to parsed.ttlSec
            }
        } catch (t: Throwable) {
            // 超时 / TLS 失败 / 网络不可达：全部回落。这里不打印 hostname，
            // 避免把用户看的域名写进日志（Release 也不该有这种痕迹）。
            null
        }
    }
}
