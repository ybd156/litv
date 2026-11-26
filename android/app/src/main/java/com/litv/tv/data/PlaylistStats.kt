package com.litv.tv.data

import kotlin.math.roundToInt

/**
 * 直播清单的供给侧体检：分片数、窗口时长、目标时长、媒体序号。
 *
 * 为什么值得单独算一遍：
 *   「播久了偶尔卡一下」有两种完全不同的成因 ——
 *     ① 取流慢/源站不产片 → 分片 p50/p90 会体现出来，现有的分片统计能看到；
 *     ② 清单窗口太短 → 播放器的目标缓冲永远攒不满，播放头一碰到「已下载内容的末尾」
 *        就会退一次 BUFFERING，表现是「一两秒就恢复、每分钟好几次」，而分片统计
 *        看起来一切正常（每片都很快，只是不够多）。
 *   这类判断要能单测，所以解析规则放在这里；[LocalHlsProxy] 只负责取值与打日志。
 */
data class PlaylistStats(
    val segments: Int,
    val windowSec: Double,
    val targetDurationSec: Int,
    val sequence: Long?,
) {
    /** 窗口时长按秒取整，日志里不出现小数位（`36s` 比 `36.0s` 好读）。 */
    fun windowLabel(): String = "${windowSec.roundToInt()}s"

    companion object {
        private val seqRe = Regex("#EXT-X-MEDIA-SEQUENCE:\\s*(\\d+)")
        private val targetRe = Regex("#EXT-X-TARGETDURATION:\\s*(\\d+)")
        private val extinfRe = Regex("#EXTINF:\\s*([0-9]+(?:\\.[0-9]+)?)")

        fun of(text: String): PlaylistStats {
            var seq: Long? = null
            var target = 0
            var window = 0.0
            var segments = 0
            for (raw in text.lineSequence()) {
                // 清单可能是 CRLF 来的，行尾的 \r 会让最后一条标签解析不到
                val line = raw.trim()
                if (line.isEmpty()) continue
                when {
                    line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                        seq = seqRe.find(line)?.groupValues?.get(1)?.toLongOrNull()

                    line.startsWith("#EXT-X-TARGETDURATION:") ->
                        target = targetRe.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0

                    line.startsWith("#EXTINF:") -> {
                        segments++
                        window += extinfRe.find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
                    }
                }
            }
            return PlaylistStats(segments, window, target, seq)
        }
    }
}
