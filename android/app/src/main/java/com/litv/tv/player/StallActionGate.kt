package com.litv.tv.player

/**
 * 画面停帧之后「该做什么」。
 *
 * 四档与恢复阶梯一一对应：重建同内核 → 换内核 → 放弃。
 */
enum class StallAction {
    /** 这次采样不动手（没到阈值、同一段停帧已经动过手、或离上次动作太近）。 */
    NONE,

    /** 重建当前内核（换台不换内核，最轻的一档）。 */
    REBUILD_SAME,

    /** 换下一个内核（同一台重建过还是不出画，说明当前内核处理不了这路码流）。 */
    NEXT_ENGINE,

    /** 反复停帧，本轮不再自动恢复，交给上层提示 + 延时重试。 */
    GIVE_UP,
}

/**
 * 停帧动作闸门（纯逻辑，可单测）。
 *
 * 为什么需要单独一层
 * ------------------
 * [FrameStallDetector] 只回答「画面停了多久」，并且为了不刷屏，它只在
 * **首行**与 30/60/120/300/600s 的里程碑上返回 STALLED。旧的接线把
 * `stalledMs >= 10s` 直接当成动作条件，于是同一段停帧会：
 *
 * ```
 *   首行(5s)  → 不到 10s，不动
 *   30s 里程碑 → 重建
 *   60s 里程碑 → 再重建
 *   120s 里程碑 → 再重建 → stallRecovered 到 3 → 判死
 * ```
 *
 * 一次长停帧被记成三次「卡死」并直接判死；而 `stallRecovered` 同时驱动
 * 「直连 → 服务端中转」，等于把解码/渲染问题记成线路问题。
 *
 * 这里的判据只有三条：
 *  1. **同一个 episode 只动作一次**。episode 从首行开始，到画面恢复 / 暂停 /
 *     息屏 / 换台时由调用方 [endEpisode] 关闭；关闭后阶梯计数保留（跨 episode
 *     升级），下一次停帧才有资格再动作。
 *  2. 两次动作之间至少隔 [minIntervalMs]（默认 20s：一次重建本身要 2~3s，
 *     刚重建完就判定「还卡着」会把慢放大成重建循环）。
 *  3. 阶梯升级：第 1 次 [StallAction.REBUILD_SAME]，第 2 次
 *     [StallAction.NEXT_ENGINE]，第 3 次 [StallAction.GIVE_UP]；之后一律
 *     [StallAction.NONE]，直到 [reset]（换台 / 用户重试）。
 *
 * 本类不碰 `stallRecovered` / `directMode`，也不改位置看门狗（[isStalled]）的语义——
 * 位置不动与画面不出是两种卡，动作必须各算各的账。
 *
 * @param actionAfterMs 同一段停帧持续多久才动手（默认与 `FRAME_STALL_ACTION_MS` 一致，10s）
 * @param minIntervalMs 两次动作之间的最小间隔
 */
class StallActionGate(
    private val actionAfterMs: Long = 10_000L,
    private val minIntervalMs: Long = 20_000L,
) {

    /**
     * 当前 episode 的起点（毫秒）；-1 表示当前没有打开的 episode。
     *
     * 不用 0 当哨兵：`elapsedRealtime()` 在开机最初几秒确实可能是 0，
     * 用 0 会把「真实时间戳 0」误当成「没开 episode」。
     */
    private var episodeStartAt = -1L

    /** 本段停帧是否已经动作过（同 episode 只动作一次）。 */
    private var actedInEpisode = false

    /** 上一次动作的时刻；[Long.MIN_VALUE] 表示还没动作过（同样不用 0 当哨兵）。 */
    private var lastActionAt = Long.MIN_VALUE

    /** 本轮起播以来动作了几次（决定阶梯走到哪一档）。 */
    private var actionsTaken = 0

    /** 已经给出过 [StallAction.GIVE_UP]：本轮不再动作。 */
    private var givenUp = false

    /** 上一次动作时算出的停帧时长（QoE 上报与日志用）。 */
    var lastActionStalledMs: Long = 0L
        private set

    /** 本轮已经动作了几次。 */
    val actionCount: Int get() = actionsTaken

    /** 是否已经放弃（调用方据此停掉采样，等延时的下一次重试）。 */
    val isGivenUp: Boolean get() = givenUp

    /**
     * 检测到仍在停帧（`FrameStallDetector` 返回 STALLED 时调用）。
     *
     * @param nowMs      单调时钟
     * @param stalledMs  检测器给出的停帧时长
     * @param firstReport 是否是**这一段**停帧的第一行
     */
    fun onStalled(nowMs: Long, stalledMs: Long, firstReport: Boolean): StallAction {
        if (firstReport || episodeStartAt < 0L) {
            // 新的一段停帧：重新开始计时，并恢复「本段可以动作一次」的额度。
            // 阶梯计数**不清零** —— 第 2 段停帧才轮得到换内核。
            episodeStartAt = nowMs
            if (firstReport) actedInEpisode = false
        }
        // 检测器在里程碑之间不报时长（OK），所以实际时长取「它报的数」与
        // 「本段已经过去多久」中较大的那个。
        val frozen = maxOf(stalledMs, nowMs - episodeStartAt)
        return decide(nowMs, frozen)
    }

    /**
     * 本拍没有新的停帧报告（`FrameStallDetector` 返回 OK）。
     *
     * OK 有两种含义：画面在推进，或者停帧还没到 5 秒的首行阈值 / 正处在两个
     * 里程碑之间。只有当前 episode 已经打开时才会按时长判断——这样动作能在
     * 首行之后 ~10s 发生，而不是傻等下一个 30s 里程碑。
     */
    fun onTick(nowMs: Long): StallAction {
        if (episodeStartAt < 0L) return StallAction.NONE
        return decide(nowMs, nowMs - episodeStartAt)
    }

    /** 停帧结束 / 暂停 / 息屏：关闭当前 episode（阶梯计数不动）。 */
    fun endEpisode() {
        episodeStartAt = -1L
    }

    /** 换台或用户主动重试：整轮阶梯重新开始。 */
    fun reset() {
        episodeStartAt = -1L
        actedInEpisode = false
        lastActionAt = Long.MIN_VALUE
        actionsTaken = 0
        givenUp = false
        lastActionStalledMs = 0L
    }

    private fun decide(nowMs: Long, frozenMs: Long): StallAction {
        if (givenUp || actedInEpisode) return StallAction.NONE
        if (frozenMs < actionAfterMs) return StallAction.NONE
        // 最小间隔：刚动作过就再判一次，等于把一次卡顿放大成连续重建。
        if (lastActionAt != Long.MIN_VALUE && nowMs - lastActionAt < minIntervalMs) return StallAction.NONE
        val action = when (actionsTaken) {
            0 -> StallAction.REBUILD_SAME
            1 -> StallAction.NEXT_ENGINE
            else -> StallAction.GIVE_UP
        }
        actionsTaken++
        actedInEpisode = true
        lastActionAt = nowMs
        lastActionStalledMs = frozenMs
        if (action == StallAction.GIVE_UP) givenUp = true
        return action
    }
}
