package com.litv.tv.data

import java.net.URI
import java.util.Locale

/** Strict server-origin parsing; preserves HTTPS and never accepts embedded credentials. */
internal object ServerAddress {
    private const val DEFAULT_PORT = 9527

    fun parse(input: String): URI? {
        val raw = input.trim()
        if (raw.isEmpty() || raw.length > 2048 || raw.any { it.isWhitespace() }) return null
        val explicitScheme = raw.contains("://")
        val uri = runCatching { URI(if (explicitScheme) raw else "http://$raw") }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (host.isBlank() || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        if (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") return null
        val port = if (uri.port == -1 && !explicitScheme) DEFAULT_PORT else uri.port
        if (port != -1 && port !in 1..65535) return null
        return runCatching { URI(scheme, null, host, port, null, null, null) }.getOrNull()
    }

    fun normalize(input: String): String? = parse(input)?.toASCIIString()

    fun effectivePort(uri: URI): Int = when {
        uri.port != -1 -> uri.port
        uri.scheme == "https" -> 443
        else -> 80
    }
}
