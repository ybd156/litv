package com.litv.tv.player

/**
 * 「息屏/开屏之后要不要强制重绑视频输出」的判定。
 *
 * 为什么单独抽成纯函数：这个判断只有三个输入（内核、离开前台多久、是否真息过屏），
 * 但它的边界很容易被改错——比如把阈值改成 0，就会变成"每次回到前台都重绑"，
 * 画面会闪一下；改得太大，息屏唤醒又救不回来。抽出来才能用单测钉住。
 *
 * 背景（真机）：VLC 内核在息屏再开屏后**只有声音没有画面**。
 * 原因是 `VlcEngine.attach()` 里有一句
 * `if (surface === attachedSurface) return`（防止重复 setVideoSurface 把 vout 搞坏），
 * 而息屏唤醒时 SurfaceView 的 Surface **对象还是同一个**、`isValid` 也还是 true，
 * 短路成立 → 什么都不做 → vout 仍然挂着那个底层缓冲区已被回收的 Surface。
 *
 * 两个触发条件，任一成立即可：
 *   1. **真的收到过 `ACTION_SCREEN_OFF`** —— 硬证据，哪怕只离开 1 秒
 *   2. 离开前台 ≥ [MIN_AWAY_MS] —— 覆盖"没收到广播但确实中断过"的情况
 *
 * 短暂的 overlays（弹个对话框再回来）两个条件都不满足，不会重绑、不会闪。
 */
object WakeRebindPolicy {

    /**
     * 离开前台多久才认为是"真的中断过"。
     *
     * 1500ms 的取法：
     *   - 小于它：多半是同应用内的短暂失焦（弹窗、切一下通知栏），vout 没坏，不该重绑
     *   - 大于它：息屏、锁屏、切到别的 App —— 系统有机会回收 Surface 缓冲区
     * 重绑本身约 100~300ms（不重建播放器、不断流），代价可以接受。
     */
    const val MIN_AWAY_MS = 1_500L

    /**
     * @param engineKey    当前内核的 key（`EngineKind.key`：exo / vlc / mpv / avs3）
     * @param awayMs       离开前台的毫秒数；未知时传 0（保守：不重绑）
     * @param hadScreenOff 这次回前台之前是否真的经过 `ACTION_SCREEN_OFF`
     */
    fun shouldRebindVout(engineKey: String, awayMs: Long, hadScreenOff: Boolean = false): Boolean {
        if (engineKey != EngineKind.VLC.key) return false
        // 息屏是硬证据：哪怕只按了 1 秒，Surface 也可能已经被回收。
        // 只按"离开时长"判断会把这种最常见的情况整个漏掉。
        if (hadScreenOff) return true
        if (awayMs < MIN_AWAY_MS) return false
        return true
    }
}
