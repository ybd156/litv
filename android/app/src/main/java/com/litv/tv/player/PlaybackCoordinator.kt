package com.litv.tv.player

/**
 * 起播/会话的**唯一入口**（决策稿 `docs/design/05-播放器所有权收敛.md` §7 第 2 步）。
 *
 * 现在起播点在 `PlayerActivity` 里有四处（缓存 / 内置源 / 本地解析 / 服务端），
 * 每处都直接调 `player.play(...)`，于是「起播发生了」这件事没有任何单一事实——
 * 心跳、QoE、状态日志只能各挂一处，漏掉的那几处就静默不记账（评审 P0-5）。
 *
 * 本类做两件事，且**只做**这两件：
 *  1. 把起播收敛成一个 `play(source)`，四种来源走同一条路；
 *  2. 用 [SessionMachine] 记录会话状态，非法迁移只记日志、**不改行为**
 *     （这一轮不接管暂停/停止的语义，避免在没真机验证前改变现状）。
 *
 * 因此 `pause/stop/release` 一律**照旧下发**给底层，状态是否合法只影响台账。
 * 不引入 Android 依赖，纯 JVM 可测。
 */
class PlaybackCoordinator(
    private val start: (PlaySource) -> Unit,
    private val pauseImpl: () -> Unit = {},
    private val resumeImpl: () -> Unit = {},
    private val stopImpl: () -> Unit = {},
    private val releaseImpl: () -> Unit = {},
    /** 非法迁移的观察点：默认丢弃，真机上接到日志即可看到「谁在什么状态发了什么」。 */
    private val onIllegal: (SessionState, String) -> Unit = { _, _ -> },
) {

    private val machine = SessionMachine()

    /** 状态迁移回调（from, to, 当前来源），用于把会话状态写进日志。 */
    var listener: ((SessionState, SessionState, PlaySource?) -> Unit)? = null

    var current: PlaySource? = null
        private set

    val state: SessionState get() = machine.state
    val attempt: Int get() = machine.attempt

    /**
     * 起播（含换台、重试）。
     *
     * 换台在语义上是「先结束旧会话、再开新的」——`SessionMachine` 里 `Playing + Start`
     * 是非法组合，所以这里显式 Stop 一次再 Start，attempt 才会正确累加。
     *
     * @return false 表示**没有**下发起播（当前已 [SessionState.Released]）。
     */
    fun play(source: PlaySource): Boolean {
        if (state == SessionState.Released) {
            onIllegal(state, "已释放，拒绝起播（${source.label}）")
            return false
        }
        if (state != SessionState.Idle) {
            // Stop 在 Idle 上非法，这里状态必然不是 Idle，直接接受
            transition(SessionEvent.Stop, "换台/重试前收尾")
        }
        // 先记来源再迁移：状态回调里要能看到「这轮是谁」
        current = source
        if (!transition(SessionEvent.Start, "起播")) {
            current = null
            return false
        }
        start(source)
        return true
    }

    /** 地址已取到、媒体已交给内核。 */
    fun onEngineStarted(): Boolean = transition(SessionEvent.EngineStarted, "内核已接管")

    /** 首帧真的出来了。 */
    fun onFirstFrame(): Boolean = transition(SessionEvent.FirstFrame, "首帧")

    /** 降级到下一个候选（换内核/换线路/重建）。 */
    fun onDegraded(): Boolean = transition(SessionEvent.Degrade, "降级")

    /** 本轮放弃。 */
    fun onGiveUp(): Boolean = transition(SessionEvent.GiveUp, "放弃")

    /**
     * 暂停。**永远下发**——旧代码在非播放态也会调 pause()，
     * 这一轮若因为状态不合法就跳过，等于悄悄改了行为。
     */
    fun pause() {
        transition(SessionEvent.Pause, "暂停")
        pauseImpl()
    }

    fun resume() {
        transition(SessionEvent.Resume, "恢复")
        resumeImpl()
    }

    fun stop() {
        transition(SessionEvent.Stop, "停止")
        current = null
        stopImpl()
    }

    /** 释放：终态。重复调用只下发一次底层释放。 */
    fun release() {
        if (state == SessionState.Released) return
        transition(SessionEvent.Release, "释放")
        current = null
        releaseImpl()
    }

    /** 同源重试（失败重试用）。没有当前来源时返回 false。 */
    fun retryNow(): Boolean {
        val src = current ?: return false
        return play(src)
    }

    private fun transition(e: SessionEvent, what: String): Boolean {
        val from = machine.state
        val to = machine.accept(e)
        if (to == null) {
            // 不打 `e.javaClass.simpleName`：R8 会改掉这些内部类名，release 包里只会看到「a、b」
            onIllegal(from, "$what 在该状态非法，已忽略")
            return false
        }
        listener?.invoke(from, to, current)
        return true
    }
}
