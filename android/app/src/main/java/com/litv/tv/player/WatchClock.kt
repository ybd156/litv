package com.litv.tv.player

/**
 * 「本台累计观看时长」的唯一计算处。
 *
 * 为什么要抽成纯函数：这个值直接进 `/api/v1/tv/stats` 的 `watch_ms`，
 * 而服务端 `handlers_client.go` 按**毫秒**夹取（`maxDurationMs = 86_400_000`），
 * 并用它当 `rebuffer_ratio = Σstall_ms / Σwatch_ms` 的**分母**。
 *
 * 真机事故：这里曾经返回**秒**（`(now - start) / 1000`），而字段名是 `watch_ms`。
 * 于是分母小了 1000 倍 —— 60 秒观看里卡 3.2 秒，真实 5.3%，报表上是 **5333%**。
 * 更坏的是服务端的夹取拦不住它：秒的数值只会更小，永远不会越界，
 * 所以这个错误**完全静默**，不会在任何日志或 400 里暴露。
 *
 * 因此只有一个规则：**线上单位是毫秒，这里就返回毫秒，任何调用方不得再除以 1000。**
 */
object WatchClock {

    /**
     * @param startedAtWall 第一帧渲染时记下的 `System.currentTimeMillis()`；
     *                      0 表示尚未起播（此时返回 0，而不是"从 1970 年算起"）
     * @param nowWall       当前 `System.currentTimeMillis()`
     * @return 累计观看毫秒；恒 ≥ 0
     */
    fun watchedMs(startedAtWall: Long, nowWall: Long): Int {
        if (startedAtWall <= 0L) return 0
        val delta = nowWall - startedAtWall
        if (delta <= 0L) return 0
        // 夹到 Int 上限：Int.MAX_VALUE ms ≈ 24.8 天，远超单次观看；
        // 溢出成负数会让 rebuffer_ratio 变负，比截断更难排查。
        return if (delta > Int.MAX_VALUE) Int.MAX_VALUE else delta.toInt()
    }
}
