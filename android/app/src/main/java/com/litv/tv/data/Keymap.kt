package com.litv.tv.data

/**
 * 遥控器键位表 —— **唯一事实来源**。
 *
 * 为什么要把它做成数据表：同一份逻辑散在 `PlayerActivity.dispatchKeyEvent` 里
 * 200 多行 if/when，谁也说不清"这个键到底干什么、在哪个状态下"。用户按错一次
 * 就再也不敢按。现在设置页「关于 → 遥控器操作说明」直接渲染这张表，代码与文档同源。
 *
 * 硬规则（[conflicts] 会检查）：**同一个键在同一个状态下只能做一件事**。
 * 同一个键在不同状态下做不同事是允许的，但表里必须写清"在什么状态下"——
 * 这就是 [Row.context] 存在的理由。
 *
 * 纯数据 + 纯函数，可 JVM 单测（见 KeymapTest）。
 */
object Keymap {

    enum class Context(val label: String) {
        PLAYING("播放中"),
        PANEL("频道列表打开"),
        EPG("节目单打开"),
        MENU("右侧设置菜单打开"),
    }

    data class Row(val context: Context, val key: String, val action: String)

    /** 播放中：上下换台、OK 呼出 OSD（再按展开详情）、长按 OK 选线路。 */
    private val playing = listOf(
        Row(Context.PLAYING, "↑ / ↓", "上一个台 / 下一个台"),
        Row(Context.PLAYING, "CH+ / CH-", "下一个台 / 上一个台（与 ↑↓ 同义）"),
        Row(Context.PLAYING, "←", "打开频道列表"),
        Row(Context.PLAYING, "→", "打开右侧设置菜单"),
        Row(Context.PLAYING, "OK", "呼出信息条；再按一次展开线路/内核等排障信息"),
        Row(Context.PLAYING, "长按 OK", "选择线路（同一频道多条线路时）"),
        Row(Context.PLAYING, "数字键 0-9", "直接跳台（连按累积，停手后跳转）"),
        Row(Context.PLAYING, "MENU", "打开右侧设置菜单"),
        Row(Context.PLAYING, "BACK", "先收起信息条/菜单；再按退出播放页"),
    )

    /** 频道列表：焦点在四列之间移动，列内上下移动。 */
    private val panel = listOf(
        Row(Context.PANEL, "↑ / ↓", "在焦点所在列内移动（分组 / 频道 / 节目单）"),
        Row(Context.PANEL, "← / →", "在不同列之间切换焦点（分组 → 频道 → 节目单 → ★收藏）"),
        Row(Context.PANEL, "OK", "播放选中的频道 / 对 ★收藏 按钮执行收藏或取消"),
        Row(Context.PANEL, "BACK", "关闭频道列表（不退出播放）"),
    )

    private val epg = listOf(
        Row(Context.EPG, "BACK / ←", "关闭节目单"),
        Row(Context.EPG, "↑ / ↓ / OK", "在节目单内移动与查看节目详情"),
    )

    private val menu = listOf(
        Row(Context.MENU, "↑ / ↓", "在菜单项之间移动"),
        Row(Context.MENU, "OK", "执行该菜单项"),
        Row(Context.MENU, "BACK", "关闭设置菜单"),
    )

    /** 全表（顺序即设置页里的展示顺序）。 */
    val rows: List<Row> = playing + panel + epg + menu

    fun forContext(context: Context): List<Row> = rows.filter { it.context == context }

    /**
     * 冲突检查：同一状态下同一个键被绑了多次。
     *
     * 空列表 = 没有冲突。设置页的键位表应该在启动调试时就能看出问题，
     * 所以这条也被单测钉住（[KeymapTest]）。
     */
    fun conflicts(): List<String> {
        val seen = HashMap<String, MutableList<String>>()
        rows.forEach { r ->
            val k = "${r.context.name}|${r.key}"
            seen.getOrPut(k) { mutableListOf() }.add(r.action)
        }
        return seen.filterValues { it.size > 1 }
            .map { (k, v) -> "$k → ${v.joinToString(" / ")}" }
            .sorted()
    }

    /**
     * 渲染成设置页可直接显示的文本。
     *
     * 纯字符串拼接（不依赖 Android），这样单测能断言"每个状态都在表里"。
     */
    fun asText(): String = buildString {
        Context.entries.forEach { ctx ->
            if (isNotEmpty()) append("\n\n")
            append("【").append(ctx.label).append("】\n")
            forContext(ctx).forEach { r ->
                append("  ").append(r.key).append("：").append(r.action).append('\n')
            }
        }
    }.trimEnd()
}
