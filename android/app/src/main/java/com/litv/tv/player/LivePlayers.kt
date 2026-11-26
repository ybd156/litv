package com.litv.tv.player

import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 还活着的 [PlayerManager] 清单 —— 「谁真的在播」的唯一来源。
 *
 * 为什么必须存在（2026-11-25 真机实测）：
 * 原来 `LitvApp.pauseAll` 暂停的是 Application 里一个 `by lazy` 的实例，
 * 而真正在播的播放器由各 Activity 自己持有、从未交给它。结果是：
 * 手机首页 → 设置页 → 按 HOME，日志打了「应用退到后台，暂停播放」，
 * 但 `dumpsys audio` 里 `u/pid:10182 state:started` 纹丝不动 —— 声音继续。
 *
 * 这里用弱引用，避免清单本身把已销毁的 Activity 的播放器留住。
 *
 * 两类调用方：
 *  1. 退到后台（所有 Activity 都不在前台）→ [pauseAll]，不自动恢复；
 *  2. 屏幕熄灭 → [muteAllForScreenOff]，亮屏 [unmuteAfterScreenOn]
 *     **只恢复这一批**，不会去动用户自己静音/调过音量的播放器。
 *     2026-11-26 改口径：息屏不再停播（用户要求「息屏静音就行」），
 *     直播继续往前走，亮屏即回到现场，也省掉「暂停久了要不要重连」的判断。
 */
object LivePlayers {

    private val refs = CopyOnWriteArrayList<WeakReference<ScreenControllable>>()

    /** 这次息屏静音了哪些（亮屏只恢复这批）。 */
    private val mutedByScreenOff = CopyOnWriteArrayList<WeakReference<ScreenControllable>>()

    fun register(p: ScreenControllable) {
        purge()
        refs.add(WeakReference(p))
    }

    fun unregister(p: ScreenControllable) {
        refs.removeAll { it.get() == null || it.get() === p }
        mutedByScreenOff.removeAll { it.get() == null || it.get() === p }
    }

    /** 当前还活着的数量（诊断用）。 */
    fun count(): Int {
        purge()
        return refs.size
    }

    /**
     * 暂停所有还活着的播放器，返回**命中几个**。
     * 返回值不是装饰：0 意味着「兜底没找到任何在播的实例」，
     * 这正是「退后台声音还在」那类 bug 的判据，所以调用方必须把它打进日志。
     */
    fun pauseAll(): Int = pauseAllInternal().size

    /**
     * 屏幕熄灭：静音并把这批记账，亮屏时只恢复它们。
     *
     * 不恢复所有播放器：用户可能是自己调过音量才关屏的，
     * 也不该在亮屏时把「息屏期间换了台起播」的那个算进来。
     */
    fun muteAllForScreenOff(): Int {
        val muted = ArrayList<ScreenControllable>(refs.size)
        for (r in refs) {
            val p = r.get() ?: continue
            if (runCatching { p.mute() }.isSuccess) muted.add(p)
        }
        mutedByScreenOff.clear()
        for (p in muted) mutedByScreenOff.add(WeakReference(p))
        return muted.size
    }

    /** 屏幕点亮：只恢复这次因息屏静音的那些，返回命中几个。 */
    fun unmuteAfterScreenOn(): Int {
        var n = 0
        for (r in mutedByScreenOff) {
            val p = r.get() ?: continue
            if (runCatching { p.unmute() }.isSuccess) n++
        }
        mutedByScreenOff.clear()
        return n
    }

    /** 息屏静音记账里还有多少个（诊断/单测用）。 */
    fun screenOffMutedCount(): Int {
        mutedByScreenOff.removeAll { it.get() == null }
        return mutedByScreenOff.size
    }

    /** 清空登记表（只给单测与进程级重置用）。 */
    fun reset() {
        refs.clear()
        mutedByScreenOff.clear()
    }

    private fun pauseAllInternal(): List<ScreenControllable> {
        val out = ArrayList<ScreenControllable>(refs.size)
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
