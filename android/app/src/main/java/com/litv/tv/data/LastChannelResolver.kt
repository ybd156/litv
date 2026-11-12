package com.litv.tv.data

/**
 * 找回「上次看的那个台」——TV 与手机共用这一处，不许各写一套。
 *
 * 为什么不能只按 id 找（这就是用户反馈的「退出重进没有到上次看的频道」）：
 * 源刷新会**重建**频道行——上游改名、改分组、甚至只是重新导入，都可能让服务端
 * 生成新的 channel id。旧 id 立刻失效，`indexOfFirst { it.id == wantId }` 返回 -1，
 * 老代码就静默 `index = 0`（第一个台）。用户看到的是"又跳回第一个台了"。
 *
 * 匹配优先级与理由：
 *   1. **id** —— 最快最准，没变就命中；
 *   2. **tvg_id** —— EPG 标识，跨改名稳定。上游把 "CCTV-1" 改成 "CCTV1 综合" 时
 *      它通常不变，是改名场景下唯一可靠的锚；
 *   3. **name** —— 没有 tvg_id 的源只能靠名字（去空白、忽略大小写）。
 *
 * 已知局限：同名不同组会取第一个。要彻底消歧得再存分组，但那只在
 * "同一个源里存在两个完全同名频道"时才有差别，本轮不引第三个偏好键。
 *
 * 纯函数、不依赖 Android 框架 —— 单测见 `LastChannelResolverTest`（这是 C2 里
 * 唯一能进 CI 的部分）。
 */
object LastChannelResolver {

    /** 命中结果。via 用来打诊断日志：现场能一眼看出是"id 还在"还是"靠 tvg_id 救回来"。 */
    data class Match(val channel: Channel, val via: String)

    /**
     * @param lastId     上次频道的 id（<=0 表示没有）
     * @param lastTvgId  上次频道的 tvg_id（空表示没有）
     * @param lastName   上次频道的名字（空表示没有）
     * @return 命中的频道 + 依据（id / tvg_id / name）；全不中返回 null（调用方自己决定回落到哪）
     */
    fun resolve(
        channels: List<Channel>,
        lastId: Long,
        lastTvgId: String,
        lastName: String = "",
    ): Match? {
        if (channels.isEmpty()) return null

        if (lastId > 0) {
            channels.firstOrNull { it.id == lastId }?.let { return Match(it, "id") }
        }

        val tvg = lastTvgId.trim()
        if (tvg.isNotEmpty()) {
            channels.firstOrNull { it.tvgId.trim().equals(tvg, ignoreCase = true) }
                ?.let { return Match(it, "tvg_id") }
        }

        val name = lastName.trim()
        if (name.isNotEmpty()) {
            channels.firstOrNull { it.name.trim().equals(name, ignoreCase = true) }
                ?.let { return Match(it, "name") }
        }

        return null
    }

    /** 只要频道时用（调用方不关心命中依据）。 */
    fun find(
        channels: List<Channel>,
        lastId: Long,
        lastTvgId: String,
        lastName: String = "",
    ): Channel? = resolve(channels, lastId, lastTvgId, lastName)?.channel

    /**
     * 兜底：确定性地挑「第一个台」。
     *
     * **不要**用 `list.first()` 或任何依赖 map 遍历顺序的写法：服务端返回顺序
     * 可能随查询计划变化，恢复行为就会随机跳台，单测也会 flaky。
     * 排序键固定为 (sort, number, id)，全部升序 —— 与频道列表展示顺序一致。
     */
    fun pickFirst(channels: List<Channel>): Channel? =
        channels.minWithOrNull(compareBy({ it.sort }, { it.number }, { it.id }))
}
