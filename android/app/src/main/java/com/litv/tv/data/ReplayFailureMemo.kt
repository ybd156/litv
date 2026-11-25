package com.litv.tv.data

/**
 * 回看「最近失败过」的内存记忆。
 *
 * 为什么要它：`ReplayCapability` 判的是**这个台的部署方式支不支持回看**，
 * 不是**此刻能不能取到**。央视频回看 CDN 整体抽风时，角标照画、点进去必失败，
 * 用户能做的就是反复进同一条死路。这里记一笔「刚失败过」，冷却期内不画角标，
 * 让界面不再承诺一件当下做不到的事；成功播放一次就清掉，不需要用户手动复位。
 *
 * 只作用于显示，不缓存数据、不改播放行为、不上报服务端——失败本身已经由
 * `PlayerActivity` 的 onError / onReplayFinished 记账。
 */
object ReplayFailureMemo {

    /** 冷却时长：10 分钟。够短，上游恢复后不会长时间挡住入口。 */
    const val COOLDOWN_MS = 10 * 60 * 1000L

    /** 频道 id → 最近一次失败的系统时间（毫秒）。 */
    private val failedAt = HashMap<Long, Long>()

    /**
     * 记一次失败。
     *
     * 只挡 `0`：内置源频道 id 是负数（`YspClient.ID_BASE - i`，如 -1001），
     * 这里若按 `<= 0` 挡掉，等于内置源的失败一条都记不上——角标照样画。
     */
    @Synchronized
    fun markFailed(channelId: Long, nowMs: Long) {
        if (channelId == 0L) return
        failedAt[channelId] = nowMs
    }

    /** 是否还在冷却期。未知频道、或记录的失败时间在未来（时钟回拨）都按「不在冷却」。 */
    @Synchronized
    fun isCoolingDown(channelId: Long, nowMs: Long): Boolean {
        val at = failedAt[channelId] ?: return false
        val age = nowMs - at
        if (age < 0L) return false
        return age < COOLDOWN_MS
    }

    /** 播成功一次就清掉——入口恢复正常，不需要等冷却到期。 */
    @Synchronized
    fun clear(channelId: Long) {
        failedAt.remove(channelId)
    }

    @Synchronized
    fun reset() {
        failedAt.clear()
    }

    @Synchronized
    fun size(): Int = failedAt.size
}
