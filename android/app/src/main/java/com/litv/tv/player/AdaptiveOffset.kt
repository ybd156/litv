package com.litv.tv.player

/**
 * 直播落后量的自适应调节（纯逻辑，不吃 Android API，便于单测）。
 *
 * 背景（2026-11-26 实测）：代理把源站 3 片 ≈17 秒的清单攒成 90 秒送出之后，
 * 「缓冲攒不满」的结构性问题没有了，但取流时延抖动仍在——网络好时落后 12 秒绰绰有余，
 * 赶上源站清单挂十几秒、分片 p50 冲到 800ms 时，12 秒的垫子会被吃干，
 * 表现为「偶尔卡一下，已缓冲 170ms」。
 *
 * 规则：
 *  - 连续 [LOW_HITS] 次采样已缓冲低于 [LOW_MS] → 加一档落后量（最多 [maxMs]），
 *    冷却 [COOLDOWN_MS]，避免一次抖动就把落后量顶到上限；
 *  - 连续 [HIGH_HITS] 次采样已缓冲高于 [HIGH_MS] → 退一档（最低回到 [baseMs]）；
 *    网络恢复后延迟自己降回来，不用用户手动调。
 *
 * 调用方负责按 `currentMs` 真正调整播放位置；本类只做判定。
 */
class AdaptiveOffset(
    private val baseMs: Long = 12_000L,
    private val maxMs: Long = 25_000L,
    private val stepMs: Long = 6_000L,
) {
    var currentMs: Long = baseMs
        private set

    private var lowHits = 0
    private var highHits = 0
    private var lastChangeAt = 0L

    /** 返回 true 表示 `currentMs` 变了，调用方需要据此调整播放位置。 */
    fun onSample(bufferedMs: Long, nowMs: Long): Boolean {
        if (bufferedMs < LOW_MS) {
            lowHits++
            highHits = 0
            if (lowHits >= LOW_HITS && currentMs < maxMs && nowMs - lastChangeAt >= COOLDOWN_MS) {
                currentMs = (currentMs + stepMs).coerceAtMost(maxMs)
                lastChangeAt = nowMs
                lowHits = 0
                return true
            }
            return false
        }
        if (bufferedMs > HIGH_MS) {
            highHits++
            lowHits = 0
            if (highHits >= HIGH_HITS && currentMs > baseMs) {
                currentMs = (currentMs - stepMs).coerceAtLeast(baseMs)
                highHits = 0
                lastChangeAt = nowMs
                return true
            }
            return false
        }
        // 中间区间：两侧计数都慢慢衰减，避免「卡一次、顺十秒、又卡一次」被算成连续。
        if (lowHits > 0) lowHits--
        if (highHits > 0) highHits--
        return false
    }

    fun reset() {
        currentMs = baseMs
        lowHits = 0
        highHits = 0
        lastChangeAt = 0L
    }

    /** 加档时需要往回退多少：新落后量与原落后量之差（判据来自 currentMs 变化）。 */
    fun stepBackMs(previousMs: Long): Long = (currentMs - previousMs).coerceAtLeast(0L)

    companion object {
        /** 低于这个已缓冲量算「垫子快没了」。实测卡顿发生时的值是 141~460ms。 */
        const val LOW_MS = 1_500L

        /** 高于这个值算「垫子很厚」，可以退档。 */
        const val HIGH_MS = 8_000L

        const val LOW_HITS = 2
        const val HIGH_HITS = 12

        /** 加档冷却：60 秒内不再加，避免一次网络抖动直接把落后量顶到上限。 */
        const val COOLDOWN_MS = 60_000L
    }
}
