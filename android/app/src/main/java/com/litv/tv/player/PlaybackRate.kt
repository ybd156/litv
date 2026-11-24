package com.litv.tv.player

import java.util.Locale

/**
 * 播放推进的测量口径（纯逻辑，可在 JVM 里断言）。
 *
 * 为什么需要它（2026-11-25 真机验证暴露的空档）：
 * 原本的供需测量写在 `LocalHlsProxy.servePlaylist` 里，记上游 m3u8 的
 * `#EXT-X-MEDIA-SEQUENCE`。但 `direct` 模式的自建源直连 m3u8、不经本地代理，
 * 于是那类频道测量结果一行都没有。位置/帧数是三个内核都有的信号，
 * 与取流路径无关，所以改在播放器侧量。
 *
 * ★ 教训（2026-11-25 手机实测第一分钟就踩到）：
 *   直播 HLS 的**滑动窗口会让位置周期性回落**（`位置回落 11974ms -> 8022ms（HLS 重载，正常）`）。
 *   这时「位置增量 ÷ 墙钟」没有物理意义 —— 第一版直接相除，立刻给出
 *   `实时比 0.00x ← 在掉队`，而画面完全正常。**假警报比不测更糟**，
 *   所以：只要这段采样里发生过位置重置，就只报帧推进，不报实时比。
 */
object PlaybackRate {

    /** 低于这个值就值得在日志里标出来（不是判死，只标可疑）。 */
    const val SUSPICIOUS_BELOW = 0.95

    /**
     * 实时比 = 位置增量 ÷ 墙钟增量。样本不可用时返回 null：
     * 墙钟没走、位置回退（换台 / seek / 重建）、位置不可用（-1）。
     */
    fun ratio(posDeltaMs: Long, wallDeltaMs: Long): Double? {
        if (wallDeltaMs <= 0L) return null
        if (posDeltaMs < 0L) return null
        return posDeltaMs.toDouble() / wallDeltaMs.toDouble()
    }

    /** 帧率样本 = 帧增量 ÷ 墙钟（秒）。样本不可用时 null。 */
    fun framesPerSecond(framesDelta: Long, wallDeltaMs: Long): Double? {
        if (wallDeltaMs <= 0L || framesDelta < 0L) return null
        return framesDelta.toDouble() * 1000.0 / wallDeltaMs.toDouble()
    }

    /** 一行给人看的实时比；样本不可用返回 null。 */
    fun describe(posDeltaMs: Long, wallDeltaMs: Long): String? {
        val r = ratio(posDeltaMs, wallDeltaMs) ?: return null
        val mark = if (r < SUSPICIOUS_BELOW) "  ← 在掉队" else ""
        return String.format(
            Locale.US,
            "实时比 %.2fx（位置 +%dms / 墙钟 +%dms）%s",
            r,
            posDeltaMs,
            wallDeltaMs,
            mark,
        )
    }

    /**
     * 一段采样期的整行描述。
     *
     * @param framesDelta 帧增量（拿不到传 -1）
     * @param posDeltaMs 位置增量（拿不到传 -1）
     * @param wallDeltaMs 墙钟增量
     * @param resets 这段采样里发生过几次位置重置（HLS 窗口滑动、换台、重建）
     */
    fun describeSample(
        framesDelta: Long,
        posDeltaMs: Long,
        wallDeltaMs: Long,
        resets: Int,
    ): String {
        val fps = framesPerSecond(framesDelta, wallDeltaMs)
        val fpsPart = if (fps == null) {
            "帧数不可用"
        } else {
            String.format(Locale.US, "+%d 帧（%.1f 帧/秒）", framesDelta, fps)
        }
        // 有重置就不算比例：否则必然得到 0.00x 这种假警报
        val ratioPart = if (resets > 0) {
            "实时比不适用（位置重置 ${resets} 次）"
        } else {
            describe(posDeltaMs, wallDeltaMs) ?: "实时比样本不足"
        }
        return "推进 $fpsPart；$ratioPart"
    }

    /** 该不该标「在掉队」：只有在没有位置重置、且样本有效时才算。 */
    fun isBehind(posDeltaMs: Long, wallDeltaMs: Long, resets: Int = 0): Boolean {
        if (resets > 0) return false
        val r = ratio(posDeltaMs, wallDeltaMs) ?: return false
        return r < SUSPICIOUS_BELOW
    }
}
