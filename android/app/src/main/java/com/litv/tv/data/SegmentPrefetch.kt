package com.litv.tv.data

/**
 * 分片预取的**纯逻辑**（不碰网络，可单测）。
 *
 * 背景：自建源站拉一个 1~2KB 的 m3u8 中位要 3.1 秒、最慢 10 秒；分片更慢，
 * 真机实测 p50 约 1.9 秒、尾部 5~11.5 秒。播放器只能**串行**来要分片，
 * 于是链路吞吐约等于「一片的耗时」——一秒一片的源就等于 1 倍速，缓冲永远填不满，
 * 任何一次尾延迟都直接变成画面停住。
 *
 * 客户端唯一能做的不是把上游变快，而是**把串行变并行**：拿到 playlist 就把
 * 窗口尾部（播放器下一步真正会要的那几片）一起预取，播放器来要时直接命中缓存。
 *
 * 这里只做「取哪几片」的决策，取数由 [LocalHlsProxy] 的线程池执行。
 */
object SegmentPrefetch {

    /** 默认预取多少片（约 12~24 秒内容）。 */
    const val AHEAD = 6

    /** 预取并发上限：再高容易被源站限流，收益也趋于饱和。 */
    const val MAX_PARALLEL = 6

    /**
     * 从一条 playlist 的分片列表里挑出「该预取哪些」。
     *
     * @param window 清单里的分片地址，按播放顺序（越靠后越新）
     * @param cached 已经在缓存里的
     * @param inFlight 已经有人在拉的（避免重复请求）
     * @param ahead 最多预取几片
     *
     * 取的是**尾部**而不是头部：
     * 直播是滑动窗口，播放器从边缘往前播，头部那几片它早就播过了。
     * 旧实现写的是 `segs.take(2)`（最旧的两片），命中的永远不是播放器要的那片。
     */
    fun plan(
        window: List<String>,
        cached: Set<String>,
        inFlight: Set<String>,
        ahead: Int = AHEAD,
    ): List<String> {
        if (window.isEmpty() || ahead <= 0) return emptyList()
        return window.takeLast(ahead).filter { it !in cached && it !in inFlight }
    }
}

/**
 * 分片健康统计。
 *
 * 为什么需要：要证明「并发预取有用」，不能只看体感，得有两组可比的数——
 * 分片耗时 p50/p90/max、慢片数、失败数、预取命中率。每 [REPORT_EVERY] 片
 * 输出一行（不含 URL，签名参数不能进日志）。
 */
class SegmentHealth(private val reportEvery: Int = 20) {

    private val samples = LongArray(64)
    private var sampleCount = 0
    private var sampleCursor = 0

    private var count = 0
    private var totalMs = 0L
    private var maxMs = 0L
    private var slowCount = 0
    private var failCount = 0
    private var hitCount = 0
    private var prefetchHitCount = 0

    /** 记一次真实拉取。 */
    @Synchronized
    fun onFetch(costMs: Long, ok: Boolean) {
        count++
        totalMs += costMs
        if (costMs > maxMs) maxMs = costMs
        if (costMs >= SLOW_MS) slowCount++
        if (!ok) failCount++
        samples[sampleCursor] = costMs
        sampleCursor = (sampleCursor + 1) % samples.size
        if (sampleCount < samples.size) sampleCount++
    }

    /** 记一次缓存命中；[fromPrefetch] 表示是后台预取提前拉好的。 */
    @Synchronized
    fun onHit(fromPrefetch: Boolean) {
        hitCount++
        if (fromPrefetch) prefetchHitCount++
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        count = count,
        avgMs = if (count > 0) totalMs / count else 0,
        p50Ms = percentile(50),
        p90Ms = percentile(90),
        maxMs = maxMs,
        slowCount = slowCount,
        failCount = failCount,
        hitCount = hitCount,
        prefetchHitCount = prefetchHitCount,
    )

    /**
     * 每 [reportEvery] 片返回一行摘要，否则 null。取完即清零，便于按窗口观察。
     */
    @Synchronized
    fun takeSummaryIfDue(): String? {
        if (count < reportEvery) return null
        val s = snapshot()
        val rate = if (s.hitCount > 0) s.prefetchHitCount * 100 / s.hitCount else 0
        count = 0
        totalMs = 0
        maxMs = 0
        slowCount = 0
        failCount = 0
        hitCount = 0
        prefetchHitCount = 0
        sampleCount = 0
        sampleCursor = 0
        return "分片统计 ${s.count} 片：p50 ${s.p50Ms}ms p90 ${s.p90Ms}ms max ${s.maxMs}ms" +
            " 慢片 ${s.slowCount} 失败 ${s.failCount} 预取命中 $rate%"
    }

    private fun percentile(p: Int): Long {
        if (sampleCount == 0) return 0
        val copy = samples.copyOf(sampleCount)
        copy.sort()
        val idx = ((sampleCount - 1) * p / 100).coerceIn(0, sampleCount - 1)
        return copy[idx]
    }

    data class Snapshot(
        val count: Int,
        val avgMs: Long,
        val p50Ms: Long,
        val p90Ms: Long,
        val maxMs: Long,
        val slowCount: Int,
        val failCount: Int,
        val hitCount: Int,
        val prefetchHitCount: Int,
    )

    companion object {
        /** 超过这个耗时算「慢片」——真机上 5 秒的片子必然造成一次画面停顿。 */
        const val SLOW_MS = 5_000L
    }
}
