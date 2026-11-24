package com.litv.tv.player

import java.util.Locale

/**
 * 「实时比」：播放位置的前进速度 ÷ 墙钟流逝速度（纯逻辑，可在 JVM 里断言）。
 *
 * 为什么需要它（2026-11-25 真机验证暴露的空档）：
 * 原本的供需测量写在 `LocalHlsProxy.servePlaylist` 里，记上游 m3u8 的
 * `#EXT-X-MEDIA-SEQUENCE` 推进。但 `direct` 模式的自建源是**直连** m3u8、
 * 根本不经过本地代理 —— 于是电视上卡得最久的那类频道，测量结果一行都没有。
 *
 * 位置是三个内核都有的信号（`EngineStats.positionMs`），与取流路径无关，
 * 所以用「位置增量 ÷ 墙钟增量」来判「到底跟不跟得上实时」：
 *
 *   ≈ 1.00  正常跟播；
 *   < 1     在掉队（供不应求、或解码跟不上，缓冲只会越来越薄）；
 *   > 1     追赶中（刚起播/刚换台时的正常现象）；
 *   位置完全不动 → 这不是「慢」，是冻住，交给帧/位置看门狗。
 *
 * 注意：这里只做**测量与展示**，不参与任何决策 —— 先量准，再谈改参数。
 */
object PlaybackRate {

    /** 低于这个值就值得在日志里标出来（不是判死，只标可疑）。 */
    const val SUSPICIOUS_BELOW = 0.95

    /**
     * 计算实时比。样本不可用时返回 null：
     *   · 墙钟没走（同一毫秒采两次）；
     *   · 位置回退（换台、seek、内核重建都是这种）；
     *   · 位置不可用（-1）。
     */
    fun ratio(posDeltaMs: Long, wallDeltaMs: Long): Double? {
        if (wallDeltaMs <= 0L) return null
        if (posDeltaMs < 0L) return null
        return posDeltaMs.toDouble() / wallDeltaMs.toDouble()
    }

    /**
     * 一行给人看的描述；样本不可用返回 null（调用方据此不打印）。
     *
     * @param posDeltaMs 位置增量（毫秒）
     * @param wallDeltaMs 墙钟增量（毫秒）
     */
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

    /** 该不该在摘要里标红：低于阈值且样本有效。 */
    fun isBehind(posDeltaMs: Long, wallDeltaMs: Long): Boolean {
        val r = ratio(posDeltaMs, wallDeltaMs) ?: return false
        return r < SUSPICIOUS_BELOW
    }
}
