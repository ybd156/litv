package com.litv.tv.data

/**
 * 分片级换线路的**纯逻辑**（不碰网络、不依赖 `android.util.Base64`，可在普通 JUnit 里断言）。
 *
 * 背景（2026-11-24 评审确认的 bug）：
 * `serveSegment` 的 403 分支先 `cache.clear()` / `lineCache.clear()`，然后才调
 * `alternateLineSegment`；而后者要靠「这一片在当前清单里排第几」去备线清单里取同一位，
 * 序号又只能从 `cache[channelId].text` 里数 —— 缓存已经清空，序号恒为 null，
 * 于是**换线路一次都没真正发生过**，403 依旧原样变成 502。
 *
 * 与序号配套的两条纪律：
 *   · 序号必须在清缓存**之前**取（[indexOf] 的入参就是「清之前的清单文本」）；
 *   · 备线清单的分片数比当前序号少时**不许 clamp**（[pickIndex]）：clamp 会取到最后一片，
 *     等于把一段已经播过的内容塞给播放器，画面直接跳。
 *
 * 另一条纪律是时间：换线是「这一片已经失败」之后的补救，不能把 handle 线程一直占着。
 * 旧实现 `YspClient.lines(5s)` + 每条备线清单（4+5s）+ 分片（OkHttp 默认 20s）
 * 叠起来最坏 34 秒，播放器那边早就判死了。这里给出总预算与逐条备线的分摊规则。
 */
object SegmentFallback {

    /** 换线总预算：一次播放器请求最多在这里花这么久，超了认输返回 502（让播放器换内核/重试）。 */
    const val TOTAL_BUDGET_MS = 4_500L

    /** 取线路列表的超时上限：真正的超时取 min(3000, 剩余预算)。 */
    const val LINES_TIMEOUT_MS = 3_000L

    /** 一条备线至少要分到这么多时间才值得试（清单一次请求 + 分片一次请求）。 */
    const val MIN_LINE_MS = 800L

    /** 内置源的分片路由前缀。 */
    const val SEG_PREFIX = "/seg/"

    /** 放弃换线的原因（只进日志；不含任何地址）。 */
    enum class Reason {
        /** 请求上下文里没有失败分片的真实地址。 */
        NO_SERVED,

        /** 只有一条线路，没有备线可换。 */
        SINGLE_LINE,

        /** 清缓存前没拿到当前清单，或清单里数不到这一片 → 无法对齐，不瞎猜位置。 */
        NO_INDEX,

        /** 剩余预算连一条备线都不够。 */
        BUDGET,
    }

    sealed class Outcome {
        data class Ready(val plan: Plan) : Outcome()
        data class Skip(val reason: Reason) : Outcome()
    }

    /**
     * @param index 对齐到的分片序号（0 起）
     * @param targets 按顺序要试的备线下标（不含当前线路）
     * @param linesTimeoutMs 取线路列表时可给的单次超时
     */
    data class Plan(
        val index: Int,
        val targets: List<Int>,
        val linesTimeoutMs: Int,
        val budgetMs: Long = SegmentFallback.TOTAL_BUDGET_MS,
    )

    /**
     * 这一片在清单里排第几（0 起，只数媒体行）。
     *
     * @param playlist 清单文本，**必须是清缓存之前**取到的那份（改写后的 `/seg/{b64}` 形态）
     * @param served 播放器要的那一片的真实地址
     * @param decode 解 `/seg/` 后面的地址（调用方传 `android.util.Base64` 的解码器）
     * @return 数不到返回 null —— 宁可不换线，也不按下标猜
     */
    fun indexOf(
        playlist: String?,
        served: String?,
        decode: (String) -> String?,
        segPrefix: String = SEG_PREFIX,
    ): Int? {
        if (served.isNullOrBlank()) return null
        val text = playlist ?: return null
        var i = 0
        for (line in text.lineSequence()) {
            val s = line.trim()
            if (s.isEmpty() || s.startsWith("#") || !s.startsWith(segPrefix)) continue
            val ref = s.removePrefix(segPrefix)
            if (decode(ref) == served) return i
            i++
        }
        return null
    }

    /**
     * 这个频道号能不能做分片级换线。
     *
     * ★ 判据是 `!= 0`，**不是 `> 0`**：内置源（央视频）的频道号是负数（-1001/-1002…），
     *   而它们的线路列表恰恰最全（playurl + backurl_list）。旧判据写成 `<= 0` 时，
     *   内置源一次分片 403 换线直接被跳过，只能等下一次清单轮换（约 6~10 秒）才恢复，
     *   表现就是「播着播着画面卡一下」。
     */
    fun canFallback(channelId: Long): Boolean = channelId != 0L

    /** 备线下标：跳过当前线路；单线时为空。 */
    fun targets(lineCount: Int, currentLine: Int): List<Int> =
        if (lineCount <= 1) emptyList() else (0 until lineCount).filter { it != currentLine }

    /**
     * 备线清单只有 [segCount] 片时，第 [index] 片能不能取。
     *
     * ★ 这里**刻意不做** `index.coerceIn(0, segCount - 1)`：备线窗口比当前线短时，
     *   clamp 会取到窗口末尾（一段早就播过的内容），看起来"换成功了"，
     *   实际是把播放器的时间轴往回拽。取不到就跳过这条备线。
     */
    fun pickIndex(index: Int, segCount: Int): Int? = index.takeIf { it in 0 until segCount }

    /** 取线路列表的超时：min(3000, 剩余预算)，并夹到非负。 */
    fun linesTimeoutMs(remainingMs: Long): Int {
        if (remainingMs <= 0L) return 0
        val cap = LINES_TIMEOUT_MS.toLong()
        return (if (remainingMs < cap) remainingMs else cap).toInt()
    }

    /**
     * 把剩余时间平摊给还没试的备线。
     *
     * @return 每条备线可用毫秒；不够 [minMs] 时返回 null（调用方应停止继续试）
     */
    fun perLineMs(remainingMs: Long, pending: Int, minMs: Long = MIN_LINE_MS): Long? {
        if (pending <= 0 || remainingMs <= 0L) return null
        val share = remainingMs / pending
        return if (share >= minMs) share else null
    }

    /**
     * 一次性决策：该不该换线、换哪几条、给多久。
     *
     * 调用方的时序必须是：**先**用清缓存前的清单算好 [Outcome.Ready.plan] 的 index，
     * **再**清缓存 / 置 `needRotateLine`，最后按 plan 去试备线。
     */
    fun plan(
        served: String?,
        playlist: String?,
        decode: (String) -> String?,
        lineCount: Int,
        currentLine: Int,
        remainingMs: Long,
        minLineMs: Long = MIN_LINE_MS,
    ): Outcome {
        if (served.isNullOrBlank()) return Outcome.Skip(Reason.NO_SERVED)
        val candidates = targets(lineCount, currentLine)
        if (candidates.isEmpty()) return Outcome.Skip(Reason.SINGLE_LINE)
        val index = indexOf(playlist, served, decode) ?: return Outcome.Skip(Reason.NO_INDEX)
        if (perLineMs(remainingMs, candidates.size, minLineMs) == null) {
            return Outcome.Skip(Reason.BUDGET)
        }
        return Outcome.Ready(
            Plan(
                index = index,
                targets = candidates,
                linesTimeoutMs = linesTimeoutMs(remainingMs),
            )
        )
    }
}
