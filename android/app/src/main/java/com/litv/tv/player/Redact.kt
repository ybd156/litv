package com.litv.tv.player

import java.net.URI

/**
 * 日志与上报 `detail` 的**唯一**脱敏出口。
 *
 * 为什么必须有这一层：签名流地址的 query 里就是凭据
 * （`?exp=…&sig=…`、`?token=…`），direct 模式下更是**用户私有源站的原始地址**
 * ——host/IP 本身就是内网拓扑。电视盒子普遍能 adb，logcat 可以被直接拉走。
 *
 * 已经出过两次：
 *   ① `VlcEngine` 用 `url.take(60)` 打日志 —— 短域名下 60 字符正好吃到
 *      `/live/12/stream.ts?sig=`；
 *   ② 同一台机器上 `-vvv` 让 libVLC 自己把完整 input MRL 打进 logcat。
 *
 * 规则：**只保留 scheme + host + port +「有没有 query」这个布尔**。
 * path 也不保留——源站路径经常带着账号（`/iptv/13800138000/...`）。
 *
 * 用 `java.net.URI` 而不是 `android.net.Uri`：这样它是纯 JVM 代码，
 * 能在普通 JUnit 单测里断言（`android.net.Uri` 在单测里返回 null，
 * 会让"脱敏测试"变成"测了个空"）。
 */
object Redact {

    /** 给日志用：`http://host:port?<redacted>`。解析失败时只给长度。 */
    fun url(raw: String): String = describe(raw)

    /**
     * @return 形如 `http://192.0.2.1:8080?<redacted>`；
     *         无法解析时返回 `<url 42B>`（长度不敏感，但也不打内容）
     */
    fun describe(raw: String): String {
        if (raw.isEmpty()) return "<empty url>"
        if (!raw.contains("://")) return "<url ${raw.length}B>"
        val u = runCatching { URI(raw) }.getOrNull() ?: return "<url ${raw.length}B>"
        val scheme = u.scheme?.takeIf { it.isNotEmpty() } ?: return "<url ${raw.length}B>"
        val host = u.host?.takeIf { it.isNotEmpty() } ?: "<host>"
        val port = if (u.port > 0) ":${u.port}" else ""
        val q = if (u.rawQuery.isNullOrEmpty()) "" else "?<redacted>"
        return "$scheme://$host$port$q"
    }
}
