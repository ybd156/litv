package com.litv.tv.data

import java.net.URI

/**
 * m3u8 的**唯一**改写入口（纯逻辑，不碰网络，可在普通 JUnit 里断言）。
 *
 * 为什么必须只留一份：内置源、自建源、回看三处原来各写一套改写循环，
 * 每套都有自己的毛病。2026-11-24 评审在代码里确认了两个真 bug：
 *
 *  ① **`/` 开头的地址被拼成 `base + //path`**
 *     `https://cdn/live/ch1/index.m3u8` + `/live/ch1/s1.ts`
 *     → `https://cdn/live//live/ch1/s1.ts`（双斜杠，部分 CDN 直接 404）。
 *     正确做法是按 playlist 的 **origin** 解析，不是按目录字符串拼接。
 *
 *  ② **`#EXT-X-MAP` / `#EXT-X-KEY` / `#EXT-X-MEDIA` 的 `URI="…"` 完全没改写**
 *     结果 fMP4/CMAF 源拿不到 init 段、AES-128 源拿不到密钥 —— 经代理后整条播不了
 *     （播放器只看到一次 404）。这三个标签的 URI 必须同样走代理前缀。
 *
 * 规则（顺序即优先级）：
 *   · 绝对 URL（`http://` / `https://`）原样；
 *   · `//host/path` 补 scheme；
 *   · `/path` 按 playlist 的 origin 解析；
 *   · 其余按 playlist 所在**目录**拼接（`./x` 的 `./` 由 URI 解析吃掉）；
 *   · 媒体行 → `{segPrefix}{encode(绝对地址)}`；
 *   · 三个标签的 `URI="…"` → `{tagUriPrefix}{encode(绝对地址)}`；
 *   · **其余行逐字节不变**（包括 CRLF、空行、缩进）—— 传进来的文本什么样，
 *     除被改写的那几行以外原样回去。
 */
object PlaylistRewrite {

    /** 带 `URI="…"` 且必须改写的那三个标签。别的标签一律不碰。 */
    private val TAG_URI_PREFIXES = listOf("#EXT-X-MAP:", "#EXT-X-KEY:", "#EXT-X-MEDIA:")

    private val URI_ATTR = Regex("URI=\"([^\"]*)\"")

    /**
     * @param text 清单原文
     * @param mediaUrls 媒体行解析出的绝对地址（按播放顺序），供预取窗口使用
     */
    data class Result(val text: String, val mediaUrls: List<String>)

    /** 只解析媒体行（分片级换线路要按序号取片，不需要改写）。 */
    fun mediaUrls(text: String, playlistUrl: String): List<String> {
        val out = ArrayList<String>(16)
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            out.add(resolve(line, playlistUrl))
        }
        return out
    }

    /**
     * 改写整份清单。
     *
     * @param playlistUrl 清单自己的地址（相对路径以它为基准解析）
     * @param segPrefix 媒体行前缀，内置源/回看是 `/seg/`，自建源是 `/rawseg/`
     * @param tagUriPrefix 密钥 / init 段前缀，固定 `/rawfile/`
     * @param encode 地址 → 路由段（调用方传 base64url 编码器，这样本文件不依赖 Android）
     */
    fun rewrite(
        text: String,
        playlistUrl: String,
        segPrefix: String,
        tagUriPrefix: String,
        encode: (String) -> String,
    ): Result {
        val urls = ArrayList<String>(16)
        val out = StringBuilder(text.length + 64)
        var first = true
        // 用 split('\n') 而不是 lineSequence()：后者会 trim 掉每行，
        // 达不到「其余行逐字节不变」的要求（CRLF 清单会被改成 LF）。
        for (raw in text.split('\n')) {
            if (!first) out.append('\n')
            first = false
            val cr = if (raw.endsWith("\r")) "\r" else ""
            val body = if (cr.isEmpty()) raw else raw.dropLast(1)
            val line = body.trim()
            when {
                line.isEmpty() -> out.append(raw)
                line.startsWith("#") -> {
                    val rewrittenTag = rewriteTagUri(line, playlistUrl, tagUriPrefix, encode)
                    if (rewrittenTag == null) out.append(raw) else out.append(rewrittenTag).append(cr)
                }
                else -> {
                    val abs = resolve(line, playlistUrl)
                    urls.add(abs)
                    out.append(segPrefix).append(encode(abs)).append(cr)
                }
            }
        }
        return Result(out.toString(), urls)
    }

    /**
     * 把一行里的 `URI="…"` 换成代理前缀；不是那三个标签、或没有 URI 属性时返回 null。
     *
     * 只替换属性值本身：`#EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x…`
     * 的 METHOD / IV 必须原样保留，否则播放器会解错。
     */
    private fun rewriteTagUri(
        line: String,
        playlistUrl: String,
        tagUriPrefix: String,
        encode: (String) -> String,
    ): String? {
        if (TAG_URI_PREFIXES.none { line.startsWith(it) }) return null
        if (!line.contains("URI=\"")) return null
        return URI_ATTR.replace(line) { m ->
            val ref = m.groupValues[1]
            if (ref.isBlank()) m.value
            else "URI=\"" + tagUriPrefix + encode(resolve(ref, playlistUrl)) + "\""
        }
    }

    /**
     * 把清单里的一个引用解析成绝对地址。
     *
     * 用 `java.net.URI.resolve` 而不是字符串拼接：它天然处理 `/` 开头（按 origin）、
     * `./` / `../`（按目录）、`//host`（补 scheme）三种形态。
     *
     * ★ 解析前先把基准地址的 query / fragment 去掉：清单地址的 `?sign=…&exp=…`
     *   是**清单自己**的签名，分片地址不该继承它（RFC 2396/3986 对「引用没有 query 时
     *   是否继承基准 query」的读法各家不一，Java 的 `URI.resolve` 与 `urljoin` 就有分歧）。
     *   这里显式剥掉，行为不依赖具体实现对规范的取舍。
     *
     * 解析失败时原样返回 —— 宁可让播放器报错，也不要自己编一个地址出来。
     */
    fun resolve(ref: String, playlistUrl: String): String {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
        val baseUrl = playlistUrl.substringBefore('#').substringBefore('?')
        val base = runCatching { URI(baseUrl) }.getOrNull() ?: return ref
        return runCatching { base.resolve(ref).toString() }.getOrNull() ?: ref
    }
}
