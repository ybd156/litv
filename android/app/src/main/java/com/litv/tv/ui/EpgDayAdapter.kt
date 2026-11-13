package com.litv.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.litv.tv.R
import java.util.Calendar

/**
 * 节目单左侧的**日期选择器**。
 *
 * 对齐参考实现：左边一列周几（今天/明天/后天优先），选哪天右边就显示哪天的节目。
 * 之前把日期当"行标签"塞进节目行里，滚到当前节目后整列日期全空——
 * 那是标签，不是选择器，用错了模型。
 */
class EpgDayAdapter : RecyclerView.Adapter<EpgDayAdapter.VH>() {

    /** @param epochDay 当天 00:00 的毫秒时间戳，用来过滤节目 */
    data class Day(val label: String, val epochDay: Long, val isToday: Boolean)

    private val items = ArrayList<Day>()
    private var selected = 0

    var onPick: ((Day) -> Unit)? = null

    /** 生成日期列表：前 4 天（回看用）+ 今天 + 明天 + 后天。 */
    fun buildDays(): List<Day> {
        val out = ArrayList<Day>()
        val todayCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val weekNames = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
        for (delta in -4..2) {
            val cal = Calendar.getInstance().apply {
                timeInMillis = todayCal.timeInMillis
                add(Calendar.DAY_OF_MONTH, delta)
            }
            val label = when (delta) {
                0 -> "今天"
                1 -> "明天"
                2 -> "后天"
                -1 -> "昨天"
                else -> weekNames[cal.get(Calendar.DAY_OF_WEEK) - 1]
            }
            out.add(Day(label, cal.timeInMillis, delta == 0))
        }
        return out
    }

    fun submit(days: List<Day>, selectToday: Boolean = true) {
        items.clear()
        items.addAll(days)
        selected = if (selectToday) items.indexOfFirst { it.isToday }.coerceAtLeast(0) else 0
        notifyDataSetChanged()
    }

    fun selectedDay(): Day? = items.getOrNull(selected)
    fun selectedPosition(): Int = selected
    fun positionOfToday(): Int = items.indexOfFirst { it.isToday }.coerceAtLeast(0)

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_epg_day, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val d = items[position]
        holder.name.text = d.label
        holder.itemView.isSelected = position == selected
        holder.name.setTextColor(
            holder.itemView.context.getColor(
                if (position == selected) R.color.accent else R.color.text_primary
            )
        )
        holder.itemView.setOnClickListener {
            val prev = selected
            selected = position
            // 只刷这两项：notifyDataSetChanged 会重建整表，焦点被清掉后
            // 系统会把焦点丢给"第一个可聚焦项"（频道页第一个分类"全部"），
            // 用户看到的就是"点日期焦点闪一下到全部"。
            if (prev in items.indices) notifyItemChanged(prev)
            notifyItemChanged(position)
            onPick?.invoke(d)
        }
        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            v.animate().scaleX(if (hasFocus) 1.02f else 1f)
                .scaleY(if (hasFocus) 1.02f else 1f)
                .setDuration(100).start()
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.dayName)
    }
}
