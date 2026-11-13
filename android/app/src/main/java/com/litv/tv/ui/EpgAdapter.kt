package com.litv.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.litv.tv.R
import com.litv.tv.data.EpgProgram
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 节目单列表。
 *
 * 对齐参考实现的样子：
 *   [日期] │ [时间段] [节目名] [回看 / 预约 / 正在播]
 *
 * 三处关键差异（之前是"只有开始时间 + 一个回看角标"）：
 *   1. 日期列：今天/明天/后天优先，其余用周几。同一天的连续行只在第一行写日期，
 *      避免每行重复（参考实现就是这个做法）。
 *   2. 时间段：`00:51-01:19`，一眼知道这档多长。
 *   3. 动作：已播完 → 回看；未播 → 预约；正在播 → 正在播（强调色）。
 */
class EpgAdapter : RecyclerView.Adapter<EpgAdapter.VH>() {

    private val items = ArrayList<EpgProgram>()
    private var currentIndex = -1
    private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())

    /** 点节目：已播完的回看，未播的预约 */
    var onProgramClick: ((EpgProgram) -> Unit)? = null

    /** 已预约的节目起点，用来把「预约」显示成「已预约」。 */
    private var reserved: Set<Long> = emptySet()

    fun setReserved(starts: Set<Long>) {
        reserved = starts.toSet()
        notifyDataSetChanged()
    }

    fun submit(list: List<EpgProgram>) {
        items.clear()
        items.addAll(list.sortedBy { it.startAt })
        val now = System.currentTimeMillis() / 1000
        currentIndex = items.indexOfFirst { it.startAt <= now && it.endAt > now }
        notifyDataSetChanged()
    }

    fun currentPosition(): Int = if (currentIndex >= 0) currentIndex else 0

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_epg, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = items[position]
        val now = System.currentTimeMillis() / 1000
        val ctx = holder.itemView.context

        // ---- 时间段
        holder.time.text =
            hm.format(Date(p.startAt * 1000)) + "-" + hm.format(Date(p.endAt * 1000))

        // ---- 节目名
        holder.title.text = p.title.ifBlank { "（无标题）" }

        val isNow = position == currentIndex
        val isPast = p.endAt in 1 until now
        holder.title.setTextColor(ctx.getColor(if (isNow) R.color.accent else R.color.text_primary))
        holder.title.alpha = if (isNow) 1f else 0.88f

        // ---- 动作：正在播 / 回看 / 预约
        holder.action.visibility = View.VISIBLE
        when {
            isNow -> {
                holder.action.text = "正在播"
                holder.action.setTextColor(ctx.getColor(R.color.accent))
            }
            isPast -> {
                holder.action.text = "回看"
                holder.action.setTextColor(ctx.getColor(R.color.text_primary))
            }
            reserved.contains(p.startAt) -> {
                holder.action.text = "已预约"
                holder.action.setTextColor(ctx.getColor(R.color.text_primary))
            }
            else -> {
                holder.action.text = "预约"
                holder.action.setTextColor(ctx.getColor(R.color.text_secondary))
            }
        }

        holder.itemView.setOnClickListener { onProgramClick?.invoke(p) }
        holder.rowBody.setOnFocusChangeListener { v, hasFocus ->
            v.animate().scaleX(if (hasFocus) 1.02f else 1f)
                .scaleY(if (hasFocus) 1.02f else 1f)
                .setDuration(100).start()
        }
    }



    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val rowBody: View = v.findViewById(R.id.rowBody)
        val time: TextView = v.findViewById(R.id.time)
        val title: TextView = v.findViewById(R.id.title)
        val action: TextView = v.findViewById(R.id.action)
    }
}
