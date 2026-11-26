package com.litv.tv.data

/**
 * 把一个「只有 3 片 ≈ 17 秒」的源站直播窗口，累积成一条更长的滑动窗口（默认 90 秒）。
 *
 * 为什么必须做这件事
 * ----------------
 * 实测（2026-11-26，模拟器 + 电视 `play.log`）：
 *   · 清单体检：`分片=3 窗口=17s 目标=7s`；
 *   · 同一时刻 Exo 自建源档位是 `min=22000ms max=32000ms`；
 *   · 于是 `重缓冲：播放中回到 BUFFERING（已缓冲 141~460ms）` 每分钟好几次。
 * 缓冲目标比整个窗口还长 —— 缓冲永远攒不满，播放头一碰到窗口末尾就退一次 BUFFERING。
 * 分片统计看起来一直是健康的（每片都取到了、p50 也不慢），所以以前的日志完全看不出问题。
 *
 * 做法
 * ----
 * 源站每次给的窗口都只是「最近 3 片」，但它们**首尾相接**。把这些窗口按分片去重后累积起来，
 * 回给播放器的就是一个 60~90 秒的窗口：播放器要 32 秒缓冲，窗口里有的是内容。
 * 分片地址是 `/rawseg/{b64}` / `/seg/{b64}` 这种稳定形式（同一分片每次编码相同），
 * 所以「按媒体行去重」既便宜又准确，不需要额外的序号表。
 *
 * 边界
 * ----
 * · 只保留最近 [maxSec] 秒：再长没有意义（没人往回看 2 分钟直播），只会白占内存；
 * · 源站窗口突然变短/变长都不影响累积，去重是幂等的；
 * · 不写 `#EXT-X-PLAYLIST-TYPE`、不写 `#EXT-X-ENDLIST`：这就是一条普通的直播滑窗，
 *   播放器按 `#EXT-X-MEDIA-SEQUENCE` 认进度，淘汰老分片是合规的。
 */
class LiveWindow(
    /** 累积窗口的总时长上限（秒）。90 秒 ≈ 3 倍于 Exo 的最大缓冲目标。 */
    private val maxSec: Double = 90.0,
) {

    /** 一个分片：它自己的标签行 + 媒体行；`sequence` 是源站给的媒体序号（可能没有）。 */
    class Seg(
        val key: String,
        val durationSec: Double,
        val lines: List<String>,
        val sequence: Long?,
    )

    private val items = ArrayList<Seg>()
    private val keys = HashSet<String>()

    val segmentCount: Int get() = items.size
    val sizeSec: Double get() = items.sumOf { it.durationSec }
    val firstSequence: Long? get() = items.firstOrNull()?.sequence

    /** 追加一次源站窗口；已存在的分片自动忽略（源站每 5~7 秒就重复给一遍旧分片）。 */
    fun append(newItems: List<Seg>) {
        var added = 0
        for (s in newItems) {
            if (!keys.add(s.key)) continue
            items.add(s)
            added++
        }
        if (added == 0) return
        trim()
    }

    /** 从头开始淘汰，直到总时长不超过 [maxSec]（至少留 1 片，否则清单会空）。 */
    private fun trim() {
        while (items.size > 1 && sizeSec > maxSec) {
            keys.remove(items.removeAt(0).key)
        }
    }

    fun clear() {
        items.clear()
        keys.clear()
    }

    /**
     * 渲染成播放器要的 m3u8。
     *
     * `#EXT-X-TARGETDURATION` 取**最大单片刻长向上取整**（HLS 规范要求它 ≥ 每片时长，
     * 写小了播放器会把清单判非法）。
     */
    fun render(): String {
        val target = (items.maxOfOrNull { it.durationSec } ?: 0.0).let {
            if (it <= 0.0) 10 else kotlin.math.ceil(it).toInt()
        }
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:3\n")
        sb.append("#EXT-X-TARGETDURATION:").append(target).append('\n')
        firstSequence?.let { sb.append("#EXT-X-MEDIA-SEQUENCE:").append(it).append('\n') }
        for (s in items) {
            for (line in s.lines) sb.append(line).append('\n')
        }
        return sb.toString()
    }

    companion object {
        private val extinfRe = Regex("#EXTINF:\\s*([0-9]+(?:\\.[0-9]+)?)")

        /**
         * 把一份（已改写过地址的）清单拆成分片。
         *
         * @param baseSequence 这份清单第一片的媒体序号（`#EXT-X-MEDIA-SEQUENCE`），
         *   之后的片子按顺序 +1 —— 播放器靠它认进度，序号跳变会被判「清单重载」。
         */
        fun parse(text: String, baseSequence: Long?): List<Seg> {
            val out = ArrayList<Seg>()
            // `#EXT-X-MAP` / `#EXT-X-KEY` 这类标签是「属于下一个分片」的，先攒着
            val pending = ArrayList<String>()
            var curDur = -1.0
            var curLines: MutableList<String>? = null
            var index = 0
            for (raw in text.lineSequence()) {
                val line = raw.trimEnd('\r').trim()
                if (line.isEmpty()) continue
                when {
                    line.startsWith("#EXT-X-MAP:") || line.startsWith("#EXT-X-KEY:") -> {
                        if (curLines == null) pending.add(line) else curLines.add(line)
                    }

                    line.startsWith("#EXTINF:") -> {
                        curDur = extinfRe.find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
                        val lines = ArrayList<String>(pending)
                        pending.clear()
                        lines.add(line)
                        curLines = lines
                    }

                    line.startsWith("#") -> {
                        // 其余清单级标签（VERSION / TARGETDURATION / MEDIA-SEQUENCE …）不参与累积，
                        // 但分片内部的标签（如 #EXT-X-DISCONTINUITY）要跟着这一片走。
                        // 注意用全等：`#EXT-X-DISCONTINUITY-SEQUENCE` 是清单级标签。
                        if (curLines != null && line == "#EXT-X-DISCONTINUITY") curLines.add(line)
                    }

                    else -> {
                        val lines = curLines
                        if (lines != null) {
                            lines.add(line)
                            out.add(
                                Seg(
                                    key = line,
                                    durationSec = curDur.coerceAtLeast(0.0),
                                    lines = lines,
                                    sequence = baseSequence?.let { it + index },
                                ),
                            )
                            index++
                            curLines = null
                            curDur = -1.0
                        }
                    }
                }
            }
            return out
        }
    }
}
