package com.litv.tv.player

import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 还活着的 [PlayerManager] 清单 —— 退后台兜底唯一能拿到「谁真的在播」的地方。
 *
 * 为什么必须存在（2026-11-25 真机实测）：
 * 原来 `LitvApp.pauseAll` 暂停的是 Application 里一个 `by lazy` 的实例，
 * 而真正在播的播放器由各 Activity 自己持有、从没交给它。结果是：
 * 手机首页 → 设置页 → 按 HOME，日志打了「应用退到后台，暂停播放」，
 * 但 `dumpsys audio` 里 `u/pid:10182 state:started` 纹丝不动 —— **声音继续**。
 *
 * 这里用弱引用，避免清单本身把已销毁的 Activity 的播放器留住。
 */
object LivePlayers {

    private val refs = CopyOnWriteArrayList<WeakReference<PlayerManager>>()

    fun register(p: PlayerManager) {
        purge()
        refs.add(WeakReference(p))
    }

    fun unregister(p: PlayerManager) {
        refs.removeAll { it.get() == null || it.get() === p }
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
    fun pauseAll(): Int {
        var n = 0
        for (r in refs) {
            val p = r.get() ?: continue
            if (runCatching { p.pause() }.isSuccess) n++
        }
        return n
    }

    private fun purge() {
        refs.removeAll { it.get() == null }
    }
}
