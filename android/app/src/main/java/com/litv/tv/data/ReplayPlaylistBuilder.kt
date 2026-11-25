package com.litv.tv.data

/**
 * 回看清单累积器：把上游一窗一窗的应答**按顺序累积**起来，输出一条只追加、不回退的
 * EVENT 清单（`#EXT-X-PLAYLIST-TYPE:EVENT` + 自管 `#EXT-X-MEDIA-SEQUENCE:0`）。
 *
 * 为什么必须这样：代理是「每来一次请求就取下一窗」，而播放器重拉清单的频率比实时消费快
 * （Exo 对 live 清单按 targetDuration 秒级重拉）。按请求次数推进窗口的结果是内容被"快放"——
 * 一小时的节目约六分钟就被取完，然后播放器以为结束了，自动跳回直播。
 * 改成只追加以后，播放器播到哪儿由它自己决定，重拉多少次都不会再往前跳：
 * 每次重拉只是**多拿到后面几窗**，已播分片不 evict。
 *
 * 达到节目末尾（或累积到上限）后 [finish]，清单补 `#EXT-X-ENDLIST` 变成真正的点播，
 * 播放器自然收尾，走 `PlayerManager` 的回看结束路径而不是直播断流路径。
 */
class ReplayPlaylistBuilder(
    private val targetDurationSec: Int = 12,
    private val maxSegments: Int = 4000,
) {
    /** 已改写好的行（`#EXT-X-MAP` / `#EXT-X-KEY` / `#EXTINF` / 分片 URI …），按追加顺序。 */
    private val entries = ArrayList<String>()
    private var finished = false

    /** 已累积的分片数。 */
    val segmentCount: Int
        get() = entries.count { !it.trimStart().startsWith("#") }

    val isFinished: Boolean get() = finished

    /**
     * 追加一窗。`windowLines` 是该窗清单（已经过 PlaylistRewrite 改写）按行切开的结果。
     * 清单级标签（`#EXTM3U` / 版本 / targetDuration / media-sequence / endlist）由本类统一生成，
     * 所以这里剔掉；分片级标签（MAP / KEY / EXTINF / PROGRAM-DATE-TIME）原样保留。
     */
    fun appendWindow(windowLines: List<String>) {
        if (finished) return
        val keep = ArrayList<String>(windowLines.size)
        var hasSegment = false
        for (raw in windowLines) {
            val line = raw.trimEnd('\r')
            val t = line.trim()
            if (t.isEmpty()) continue
            if (t.startsWith("#")) {
                if (t.startsWith("#EXTM3U") ||
                    t.startsWith("#EXT-X-VERSION") ||
                    t.startsWith("#EXT-X-TARGETDURATION") ||
                    t.startsWith("#EXT-X-MEDIA-SEQUENCE") ||
                    t.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE") ||
                    t.startsWith("#EXT-X-ENDLIST")
                ) continue
                keep.add(line)
            } else {
                hasSegment = true
                keep.add(line)
            }
        }
        if (!hasSegment) return
        if (entries.isNotEmpty()) entries.add("#EXT-X-DISCONTINUITY")
        entries.addAll(keep)
        // 上限是防内存无界：按窗追加会略微冲过一点（一窗约 5 片），冲过就收尾，不必精确卡在 maxSegments
        if (segmentCount >= maxSegments) finished = true
    }

    /** 节目已到末尾（或不再追加）：清单补 `#EXT-X-ENDLIST`。 */
    fun finish() {
        finished = true
    }

    fun render(): String {
        val sb = StringBuilder(entries.size * 64 + 128)
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:3\n")
        sb.append("#EXT-X-TARGETDURATION:").append(targetDurationSec).append('\n')
        sb.append("#EXT-X-PLAYLIST-TYPE:EVENT\n")
        sb.append("#EXT-X-MEDIA-SEQUENCE:0\n")
        for (line in entries) {
            sb.append(line)
            sb.append('\n')
        }
        if (finished) sb.append("#EXT-X-ENDLIST\n")
        return sb.toString()
    }

    fun reset() {
        entries.clear()
        finished = false
    }
}
