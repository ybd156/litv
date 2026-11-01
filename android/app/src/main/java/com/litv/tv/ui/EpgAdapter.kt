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
 * 只做「时间 + 标题」两列——TV 上信息过载反而看不懂。
 * 正在播的那档高亮，并且默认滚到它。
 */
class EpgAdapter : RecyclerView.Adapter<EpgAdapter.VH>() {

    private val items = ArrayList<EpgProgram>()
    private var currentIndex = -1
    private val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())

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
        holder.time.text = fmt.format(Date(p.startAt * 1000))
        holder.title.text = p.title.ifBlank { "（无标题）" }

        val isNow = position == currentIndex
        holder.title.setTextColor(
            holder.itemView.context.getColor(if (isNow) R.color.accent else R.color.text_primary)
        )
        holder.title.alpha = if (isNow) 1f else 0.86f

        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            v.animate().scaleX(if (hasFocus) 1.03f else 1f)
                .scaleY(if (hasFocus) 1.03f else 1f)
                .setDuration(100).start()
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val time: TextView = v.findViewById(R.id.time)
        val title: TextView = v.findViewById(R.id.title)
    }
}
