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
 * 参考 TVBox 系（酷9）的 item_epglist：
 *   - 已播完的节目带「回看」角标，提示这个能回看
 *   - 节目名下面是简介走马灯（单行滚动），一屏塞得下更多信息
 * 正在播的那档高亮，并且默认滚到它。
 */
class EpgAdapter : RecyclerView.Adapter<EpgAdapter.VH>() {

    private val items = ArrayList<EpgProgram>()
    private var currentIndex = -1
    private val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    /** 点节目：已播完的回看，未播的提示（后续可接预约） */
    var onProgramClick: ((EpgProgram) -> Unit)? = null

    fun submit(list: List<EpgProgram>) {
        items.clear()
        items.addAll(list)
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

        holder.time.text = fmt.format(Date(p.startAt * 1000))
        holder.title.text = p.title.ifBlank { "（无标题）" }

        val isNow = position == currentIndex
        holder.title.setTextColor(
            holder.itemView.context.getColor(if (isNow) R.color.accent else R.color.text_primary)
        )
        holder.title.alpha = if (isNow) 1f else 0.86f

        // 已播完 -> 可回看
        val canReplay = p.endAt in 1 until now
        holder.replayBadge.visibility = if (canReplay) View.VISIBLE else View.GONE

        // 简介走马灯：有简介才显示，且必须 isSelected=true 才会滚
        if (p.description.isNotBlank()) {
            holder.desc.visibility = View.VISIBLE
            holder.desc.text = p.description
            holder.desc.isSelected = true
        } else {
            holder.desc.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { onProgramClick?.invoke(p) }
        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            v.animate().scaleX(if (hasFocus) 1.02f else 1f)
                .scaleY(if (hasFocus) 1.02f else 1f)
                .setDuration(100).start()
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val time: TextView = v.findViewById(R.id.time)
        val title: TextView = v.findViewById(R.id.title)
        val replayBadge: TextView = v.findViewById(R.id.replayBadge)
        val desc: TextView = v.findViewById(R.id.desc)
    }
}
