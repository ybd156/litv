package com.litv.tv.data

/**
 * 播放参数的**唯一**默认值来源。
 *
 * 为什么要抽这个对象：这几个数字以前散在四处各写一遍，而且互相矛盾——
 *   `Prefs` 默认 15000ms / `PlayerManager` 里有个从没人用的 `START_TIMEOUT_MS = 5000`
 *   死常量 / PlayerManager 文件头注释写"默认 6s" / 设置页选项只有 3000~12000。
 * 结果：用户在设置里看到"15 秒"，点一下那一行就变成 5 秒（选项里没有 15000，
 * 索引静默回落到 0，再点一次是 1）。播放器于是被拖成"3 秒就判超时"。
 *
 * 现在默认值与选项都从这里取，并且有一条单测钉住
 * 「默认值必须出现在选项列表里」，杜绝下次再漂。
 */
object PlaybackDefaults {

    /** 起播超时（毫秒）。慢盒子 + HLS 握手 + 解初始化，5 秒经常不够。 */
    const val START_TIMEOUT_MS = 15000

    /** 超时后重试几次再换内核。网络抖动时多试几次比直接降级好。 */
    const val RETRY_COUNT = 1

    /** 网络缓冲（毫秒）。 */
    const val BUFFER_MS = 3000

    /**
     * 起播超时可选值（毫秒，**升序**）。必须包含 [START_TIMEOUT_MS]。
     *
     * 升序是硬要求：设置页的循环切换依赖它，顺序乱了用户体验会变成
     * "点一下从 15 秒跳到 3 秒"。
     */
    val START_TIMEOUT_OPTIONS_MS = listOf(3000, 5000, 8000, 12000, START_TIMEOUT_MS)

    /** 重试次数可选值（升序）。必须包含 [RETRY_COUNT]。 */
    val RETRY_OPTIONS = listOf(0, RETRY_COUNT, 2, 3)
}
