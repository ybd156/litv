package com.litv.tv.data

import java.net.URI

/**
 * 回看地址到底是不是回看。
 *
 * 央视频的回看与直播是**两套 CDN**：回看落在 `*.playback*.cctv.cn`，
 * 直播落在 `*.live*.cctv.cn`。请求的时段超出源侧保留范围时，接口照样返回
 * `iretcode=0`，但给回来的是**直播**的地址。
 *
 * 后果（2026-11-25 模拟器实测）：点 05:00-06:00 的节目，播了约 6 分钟上游断流，
 * 画面是当时正在播的《中华古树》，与节目单毫无关系；客户端没有任何察觉，
 * 只在下游变成「回看结束」后自动切回直播，看起来像"回看结束了"。
 *
 * 这里只做**分类**（纯函数，不碰网络），判定交给调用方：
 * 分类是 [Kind.Live] 而请求时段又离现在很远 ⇒ 上游退回了直播，应当拒绝这次回看。
 */
object ReplaySource {

    enum class Kind { Playback, Live, Unknown }

    /**
     * 按 host 判断地址属于哪套 CDN。
     *
     * 只认 host，不看 path/query —— query 里带签名（`svrtime` 等），
     * 且路径里出现 `live` 字样并不代表是直播 CDN。
     */
    fun classify(url: String): Kind {
        if (url.isBlank()) return Kind.Unknown
        val host = runCatching { URI(url).host ?: "" }.getOrDefault("").lowercase()
        // URI 解析失败时退回整串匹配：真实地址都是合法 URL，畸形串只出现在测试里。
        val probe = if (host.isNotEmpty()) host else url.lowercase()
        return when {
            probe.contains("playback") -> Kind.Playback
            probe.contains("live") -> Kind.Live
            else -> Kind.Unknown
        }
    }

    /** 请求时刻距现在多少秒；未来时刻按 0 处理。 */
    fun ageSec(fromSec: Long, nowSec: Long): Long = (nowSec - fromSec).coerceAtLeast(0L)

    /**
     * 这个请求是不是「回看」而非「刚播完、仍能当直播看」。
     *
     * 刚结束几分钟的节目，上游给直播/回看都说得过去，不该报错；
     * 差得远的才算越界。阈值取 5 分钟（覆盖节目拖尾与 EPG 误差）。
     */
    fun isHistorical(fromSec: Long, nowSec: Long): Boolean = ageSec(fromSec, nowSec) > 300L
}
