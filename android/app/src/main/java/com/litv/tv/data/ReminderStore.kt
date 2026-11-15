package com.litv.tv.data

import android.content.Context

/**
 * 预约记录的**本地**存储。
 *
 * 为什么放本地：预约本质是"到点了提醒我"，属于本机行为，
 * 没必要为它增加一个服务端接口、也没必要为此联网。
 * 存成一行 JSON 字符串数组：`startAt|channelId|title`。
 */
object ReminderStore {

    private const val KEY = "reminders"
    private const val SP = "litv"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<String> {
        val raw = sp(ctx).getString(KEY, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\u0001").filter { it.isNotBlank() }
    }

    /** 已预约的节目起点集合（判断"已预约"用）。 */
    fun reservedStarts(ctx: Context): Set<Long> =
        all(ctx).mapNotNull { it.substringBefore('|').toLongOrNull() }.toSet()

    fun isReserved(ctx: Context, startAt: Long): Boolean =
        reservedStarts(ctx).contains(startAt)

    fun add(ctx: Context, startAt: Long, channelId: Long, title: String) {
        val cur = all(ctx).filterNot { it.startsWith("$startAt|") }.toMutableList()
        cur.add("$startAt|$channelId|" + title.replace('\u0001', ' ').replace('|', ' '))
        sp(ctx).edit().putString(KEY, cur.joinToString("\u0001")).apply()
    }

    fun remove(ctx: Context, startAt: Long) {
        val cur = all(ctx).filterNot { it.startsWith("$startAt|") }
        sp(ctx).edit().putString(KEY, cur.joinToString("\u0001")).apply()
    }
}
