package com.litv.tv.data

/**
 * 播放地址归一：把服务端给的地址变成播放内核能直接用的绝对地址。
 *
 * 为什么从 Api 里抽出来：这段逻辑以前埋在 `Api.absolute()` 里，而 Api 依赖
 * Prefs（Android Context），所以只能在真机上验证。抽成**不依赖任何 Android 类**
 * 的纯函数之后可以进 JVM 单测——「服务端给相对路径时必须拼上 base」这种约定
 * 就不会被某次重构悄悄改掉（拼错就是全黑屏，且日志里只有一串 URI）。
 *
 * 语义保持与抽取前完全一致（包括空 base 的退化行为），不做顺手优化。
 */
object UrlJoin {

    /**
     * @param base 服务端根地址，如 `http://nas:28027`；允许尾随斜杠
     * @param url  服务端返回的地址。两种形态都合法：
     *             直连模式下是上游绝对地址；中转/站内模式下是 `/app/litv/live/1/stream.ts`
     * @return 可直接交给 ExoPlayer / VLC / mpv 的地址
     */
    fun absolute(base: String, url: String): String {
        // 直连源站：服务端已经把上游绝对地址原样给回来了，不能再拼 base，
        // 否则会拼成 http://nas:28027/http://源站/x.m3u8。
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        val b = base.trimEnd('/')
        return b + (if (url.startsWith("/")) url else "/$url")
    }
}
