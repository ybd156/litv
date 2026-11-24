package com.litv.tv.player

/**
 * 播放会话的合法状态（评审 P0-4：现在是一堆布尔位 + 8 个定时器，非法状态可表示）。
 *
 * 这里只定义**状态与迁移**，不含任何 Android 依赖，因此能在普通 JVM 单测里断言。
 * 接线留到 `PlaybackCoordinator` 那一步 —— 先有可被测试的模型，再拿它替换布尔丛。
 */
enum class SessionState {
    /** 没有会话（页面刚建、或已 Stop）。 */
    Idle,

    /** 正在取地址/解析源（还没把媒体交给引擎）。 */
    Acquiring,

    /** 已交给引擎，等首帧。 */
    Starting,

    /** 首帧已出，正常播放。 */
    Playing,

    /** 用户暂停（内核可能仍持有媒体，但不再推进）。 */
    Paused,

    /** 出错但还有下一级可试（换内核 / 换线路 / 重建）。 */
    Degraded,

    /** 本轮放弃（等冷却或用户手动重试）。 */
    GaveUp,

    /** 终态：资源已释放，任何事件都不再生效。 */
    Released,
}

/** 驱动状态迁移的事件。 */
sealed class SessionEvent {
    /** 用户/系统要求起播（含换台、重试）。 */
    object Start : SessionEvent()

    /** 地址已取到、媒体已交给引擎。 */
    object EngineStarted : SessionEvent()

    /** 首帧渲染出来了。 */
    object FirstFrame : SessionEvent()

    object Pause : SessionEvent()
    object Resume : SessionEvent()

    /** 降级到下一个候选（换内核 / 换线路 / 重建）。 */
    object Degrade : SessionEvent()

    /** 本轮放弃。 */
    object GiveUp : SessionEvent()

    /** 停止（回到 Idle，可再次 Start）。 */
    object Stop : SessionEvent()

    /** 释放（终态）。 */
    object Release : SessionEvent()
}

/**
 * 纯状态机：给定当前状态与事件，返回下一个状态；非法组合返回 null 且状态不变。
 *
 * 设计取舍：
 *  · **不抛异常**。播放路径上抛异常会把「非法事件」变成崩溃，而现实中事件来自
 *    引擎回调、定时器、生命周期，乱序是常态；返回 null 让调用方记一行日志就够了。
 *  · **终态真的终**。`Released` 之后任何事件都不生效（对应评审里「终态不终」那条：
 *    旧代码 release() 之后还能被 play() 拉回来）。
 *  · `GaveUp` 只有 Start 能出去 —— 冷却到期或用户手动重试都必须显式 Start。
 */
class SessionMachine {

    var state: SessionState = SessionState.Idle
        private set

    /** 已经起播过多少次（换台/重试都算），供日志与预算复用。 */
    var attempt: Int = 0
        private set

    fun accept(e: SessionEvent): SessionState? {
        val next = next(state, e) ?: return null
        if (e === SessionEvent.Start) attempt++
        state = next
        return next
    }

    companion object {
        /** 迁移表。返回 null = 该事件在当前状态非法。 */
        fun next(from: SessionState, e: SessionEvent): SessionState? = when (from) {
            SessionState.Released -> null

            SessionState.Idle -> when (e) {
                SessionEvent.Start -> SessionState.Acquiring
                SessionEvent.Release -> SessionState.Released
                else -> null
            }

            SessionState.Acquiring -> when (e) {
                SessionEvent.EngineStarted -> SessionState.Starting
                // 首帧没等到「内核接管」也算数：某些路径（VLC 无布局起播、直接
                // 复用同一内核）不报 prepared，画面已经在播了就不能判非法。
                SessionEvent.FirstFrame -> SessionState.Playing
                // 取地址失败：还有下一级就 Degraded，没有就 GaveUp
                SessionEvent.Degrade -> SessionState.Degraded
                SessionEvent.GiveUp -> SessionState.GaveUp
                SessionEvent.Stop -> SessionState.Idle
                SessionEvent.Release -> SessionState.Released
                else -> null
            }

            SessionState.Starting -> when (e) {
                SessionEvent.FirstFrame -> SessionState.Playing
                SessionEvent.Degrade -> SessionState.Degraded
                SessionEvent.GiveUp -> SessionState.GaveUp
                SessionEvent.Stop -> SessionState.Idle
                SessionEvent.Release -> SessionState.Released
                else -> null
            }

            SessionState.Playing -> when (e) {
                SessionEvent.Pause -> SessionState.Paused
                SessionEvent.Degrade -> SessionState.Degraded
                SessionEvent.GiveUp -> SessionState.GaveUp
                SessionEvent.Stop -> SessionState.Idle
                SessionEvent.Release -> SessionState.Released
                else -> null
            }

            SessionState.Paused -> when (e) {
                SessionEvent.Resume -> SessionState.Playing
                SessionEvent.Stop -> SessionState.Idle
                SessionEvent.Release -> SessionState.Released
                else -> null
            }

            SessionState.Degraded -> when (e) {
                // 降级后重新交给引擎：回到 Starting 等首帧
                SessionEvent.EngineStarted, SessionEvent.Start -> SessionState.Starting
                SessionEvent.GiveUp -> SessionState.GaveUp
                SessionEvent.Stop -> SessionState.Idle
                SessionEvent.Release -> SessionState.Released
                else -> null
            }

            SessionState.GaveUp -> when (e) {
                SessionEvent.Start -> SessionState.Acquiring
                SessionEvent.Stop -> SessionState.Idle
                SessionEvent.Release -> SessionState.Released
                else -> null
            }
        }
    }
}
