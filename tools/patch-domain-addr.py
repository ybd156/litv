"""修域名模式：不再强行拼端口和 http。

问题：用户填 iptv.683868.xyz（走 Cloudflare 反代），被拼成 http://iptv.683868.xyz:28027，
当然连不上——反代域名应该是 https://iptv.683868.xyz（443，不带端口）。

新规则：
  填了协议（http:// 或 https://）就尊重它
  没填协议 -> 默认 https（反代基本都是 https）
  没写端口 -> 不拼端口（用协议默认 443/80）
  写了端口 -> 保留
"""
import io

p = "android/app/src/main/java/com/litv/tv/ui/BindActivity.kt"
s = io.open(p, encoding="utf-8").read()

old = """    private fun composeAddr(): String? {
        if (usingDomain) {
            val raw = domainInput.text.toString().trim()
            if (raw.isBlank()) return null
            val withScheme = if (raw.startsWith("http")) raw else "http://$raw"
            // 必须带主机名，且端口合法（没写端口就用默认）
            val u = runCatching { java.net.URI(withScheme) }.getOrNull() ?: return null
            val host = u.host ?: return null
            if (host.isBlank()) return null
            val port = if (u.port == -1) DEFAULT_PORT.toInt() else u.port
            if (port !in 1..65535) return null
            return "http://$host:$port"
        }"""

new = """    private fun composeAddr(): String? {
        if (usingDomain) {
            val raw = domainInput.text.toString().trim()
            if (raw.isBlank()) return null

            // 填了协议就尊重；没填默认 https（域名基本都是反代过来的）
            val hasScheme = raw.startsWith("http://") || raw.startsWith("https://")
            val scheme = when {
                raw.startsWith("https://") -> "https"
                raw.startsWith("http://") -> "http"
                else -> "https"
            }
            val withScheme = if (hasScheme) raw else "$scheme://$raw"

            val u = runCatching { java.net.URI(withScheme) }.getOrNull() ?: return null
            val host = u.host ?: return null
            if (host.isBlank()) return null

            // 关键：没写端口就不要拼端口。
            // 反代域名（如 iptv.example.com）走 443，拼上服务端口 28027 反而连不上。
            if (u.port != -1) {
                if (u.port !in 1..65535) return null
                return "$scheme://$host:${u.port}"
            }
            return "$scheme://$host"
        }"""

assert old in s, "找不到 composeAddr"
s = s.replace(old, new, 1)
io.open(p, "w", encoding="utf-8").write(s)
print("BindActivity: 域名模式已改")
