package com.litv.tv.player

import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 还活着的 [PlayerManager] 清单 —— 「谁真的在播」的唯一来源。
 *
 * 为什么必须存在（2026-11-25 真机实测）：
 * 原来 `LitvApp.pauseAll` 暂停的是 Application 里一个 `by lazy` 的实例，
 * 而真正在播的播放器由各 Activity 自己持有、从没交给它。结果是：
 * 手机首页 → 设置页 → 按 HOME，日志打了「应用退到后台，暂停播放」，
 * 但 `dumpsys audio` 里 `u/pid:10182 state:started` 纹丝不动 —— **声音继续**。
 *
 * 这里用弱引用，避免清单本身把已销毁的 Activity 的播放器留住。
 *
 * 两处调用方：
 *  1. 退到后台（所有 Activity 都不在前台）→ [pauseAll]，不再自动恢复；
 *  2. 屏幕熄灭 → [pauseAllForScreenOff]，亮屏时 [resumeAfterScreenOn]
 *     **只恢复这一批**，不会把用户自己暂停的、或息屏之后才起播的播放器拉起来。
 */
object LivePlayers {

    private val refs = CopyOnWriteArrayList<WeakReference<ScreenPausable>>()

    /** 这次息屏暂停了哪些（亮屏只恢复这批）。 */
    private val pausedByScreenOff = CopyOnWriteArrayList<WeakReference<ScreenPausable>>()

    fun register(p: ScreenPausable) {
        purge()
        refs.add(WeakReference(p))
    }

    fun unregister(p: ScreenPausable) {
        refs.removeAll { it.get() == null || it.get() === p }
        pausedByScreenOff.removeAll { it.get() == null || it.get() === p }
    }

    /** 当前还活着的数量（诊断用）。 */
    fun count(): Int {
        purge()
        return refs.size
    }

    /**
     * 暂停所有还活着的播放器，返回**命中几个**。
     *
     * 返回值不是装饰：0 意味着「兜底没找到任何在播的实例」，
     * 这正是这次要修的 bug 的判据，所以调用方必须把它打进日志。
     */
    fun pauseAll(): Int = pauseAllInternal().size

    /**
     * 屏幕熄灭：暂停并把这一批记账，亮屏时只恢复它们。
     *
     * 为什么不复用 [pauseAll] 后直接 `resumeAll`：用户可能是自己按了暂停
     * 才关屏的，亮屏不该替他播放；息屏期间也可能换了台起了新播。
     */
    fun pauseAllForScreenOff(): Int {
        val paused = pauseAllInternal()
        pausedByScreenOff.clear()
        for (p in paused) pausedByScreenOff.add(WeakReference(p))
        return paused.size
    }

    /** 屏幕点亮：只恢复这次因息屏被暂停的那些，返回命中几个。 */
    fun resumeAfterScreenOn(): Int {
        var n = 0
        for (r in pausedByScreenOff) {
            val p = r.get() ?: continue
            if (runCatching { p.resume() }.isSuccess) n++
        }
        pausedByScreenOff.clear()
        return n
    }

    /** 息屏记账里还有多少个（诊断/单测用）。 */
    fun screenOffPausedCount(): Int {
        pausedByScreenOff.removeAll { it.get() == null }
        return pausedByScreenOff.size
    }

    /** 清空登记表（只给单测与进程级重置用）。 */
    fun reset() {
        refs.clear()
        pausedByScreenOff.clear()
    }

    private fun pauseAllInternal(): List<ScreenPausable> {
        val out = ArrayList<ScreenPausable>(refs.size)
        for (r in refs) {
            val p = r.get() ?: continue
            if (runCatching { p.pause() }.isSuccess) out.add(p)
        }
        return out
    }

    private fun purge() {
        refs.removeAll { it.get() == null }
    }
}
