package com.litv.tv.data

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns

/**
 * 按设置里的「IP 协议偏好」过滤/排序 DNS 结果。
 *
 * 为什么需要它：国内不少电视盒子的 IPv6 是「半通」的——实测某 Android 9 电视盒子
 * 移动宽带）：走 IPv6 连 Cloudflare，TCP 建连卡在 SYN_SENT，IPv4 那条是 ESTABLISHED。
 * 系统 DNS 会同时返回 A 和 AAAA，OkHttp 不保证先试哪个，于是一个个请求卡满超时。
 *
 * 与其让用户去路由器关 IPv6，不如在客户端给个开关。
 *
 * 取值（Prefs.ipMode）：
 *   auto         优先 IPv4，源只有 AAAA 时才用 IPv6（默认，也是"源只有 IPv6 就 IPv6"）
 *   prefer-ipv4  先 IPv4、再 IPv6（不抛错，交给连接失败后重试）
 *   prefer-ipv6  先 IPv6、再 IPv4
 *   ipv4         只要 IPv4，没有 A 记录就报错
 *   ipv6         只要 IPv6，没有 AAAA 记录就报错
 */
class IpModeDns(private val mode: () -> String) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        // 已经是 IP 字面量（含 rtp:// 之类解析出来的），系统 DNS 原样返回，不用处理
        val all = Dns.SYSTEM.lookup(hostname)
        val v4 = all.filterIsInstance<Inet4Address>()
        val v6 = all.filterIsInstance<Inet6Address>()

        // 只有一种地址时直接返回，不管偏好——否则会把能用的源误判成"没有记录"
        if (v4.isEmpty() && v6.isEmpty()) return all
        if (v6.isEmpty()) return v4
        if (v4.isEmpty()) return v6

        return when (mode().lowercase()) {
            "ipv4" -> v4
            "ipv6" -> v6
            "prefer-ipv6" -> v6 + v4
            "prefer-ipv4" -> v4 + v6
            else -> v4 + v6   // auto：优先 IPv4，IPv6 留作兜底
        }
    }

    companion object {
        /** 设置页展示用：值 → 人话 */
        val LABELS: List<Pair<String, String>> = listOf(
            "auto" to "自动（优先 IPv4，源只有 IPv6 才用）",
            "prefer-ipv4" to "优先 IPv4",
            "prefer-ipv6" to "优先 IPv6",
            "ipv4" to "只用 IPv4",
            "ipv6" to "只用 IPv6",
        )

        fun labelOf(value: String): String =
            LABELS.firstOrNull { it.first == value }?.second ?: LABELS.first().second
    }
}
