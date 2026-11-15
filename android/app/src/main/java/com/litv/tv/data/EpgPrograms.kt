package com.litv.tv.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 节目单的**共用判定**：日期分组、日期标签、这一档该显示什么动作。
 *
 * 为什么单独抽出来：电视端（EpgAdapter）和手机端（PhoneHomeActivity）
 * 各自实现过一遍「正在播 / 回看 / 预约」和「今天 / 明天 / 后天」，
 * 结果两边慢慢就不一致了（手机没有日期、没有预约回看）。
 * 外壳可以有两套，这些规则只能有一份。
 */
object EpgPrograms {

    /** 一档节目的动作。 */
    enum class Action { PLAYING, REPLAY, RESERVE }

    private val DAY = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    private val HHMM = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun actionOf(p: EpgProgram, nowSec: Long = System.currentTimeMillis() / 1000): Action = when {
        p.startAt <= nowSec && p.endAt > nowSec -> Action.PLAYING
        p.endAt <= nowSec -> Action.REPLAY
        else -> Action.RESERVE
    }

    fun dayKeyOf(sec: Long): String = DAY.format(Date(sec * 1000))

    fun timeLabel(sec: Long): String = HHMM.format(Date(sec * 1000))

    /** `00:51-01:19` */
    fun rangeLabel(p: EpgProgram): String =
        timeLabel(p.startAt) + "-" + timeLabel(p.endAt)

    /** 按天分组，保持节目原有顺序。 */
    fun groupByDay(list: List<EpgProgram>): LinkedHashMap<String, MutableList<EpgProgram>> {
        val out = LinkedHashMap<String, MutableList<EpgProgram>>()
        for (p in list) out.getOrPut(dayKeyOf(p.startAt)) { mutableListOf() }.add(p)
        return out
    }

    /** 日期条上的文字：今天 / 明天 / 后天，其余「M月d日」。 */
    fun dayLabel(dayKey: String): String {
        val cal = Calendar.getInstance()
        repeat(3) { offset ->
            val d = DAY.format(cal.time)
            if (d == dayKey) return when (offset) {
                0 -> "今天"
                1 -> "明天"
                else -> "后天"
            }
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return try {
            SimpleDateFormat("M月d日", Locale.getDefault()).format(DAY.parse(dayKey)!!)
        } catch (t: Throwable) {
            dayKey
        }
    }

    /** 今天在节目单里的下标；没有今天就是 0。 */
    fun todayIndex(list: List<EpgProgram>): Int {
        val today = dayKeyOf(System.currentTimeMillis() / 1000)
        val firstIndexOfDay = list.indexOfFirst { dayKeyOf(it.startAt) == today }
        return if (firstIndexOfDay >= 0) firstIndexOfDay else 0
    }
}
