package com.litv.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.litv.tv.R

/**
 * 左栏的频道分组。
 *
 * 第一项固定是「全部」，其余按后端返回的分组顺序。
 */
class GroupAdapter : RecyclerView.Adapter<GroupAdapter.VH>() {

    data class Row(val name: String, val count: Int)

    private val items = ArrayList<Row>()
    private var selected = 0

    var onPick: ((String?) -> Unit)? = null

    fun submit(rows: List<Row>, selectedName: String?) {
        items.clear()
        items.addAll(rows)
        selected = items.indexOfFirst { it.name == (selectedName ?: "全部") }.coerceAtLeast(0)
        notifyDataSetChanged()
    }

    fun selectedPosition(): Int = selected

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_group, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = items[position]
        holder.name.text = row.name
        holder.count.text = if (row.count > 0) row.count.toString() else ""
        holder.name.setTextColor(
            holder.itemView.context.getColor(
                if (position == selected) R.color.accent else R.color.text_primary
            )
        )
        holder.itemView.setOnClickListener {
            selected = position
            notifyDataSetChanged()
            onPick?.invoke(if (row.name == "全部") null else row.name)
        }
        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            v.animate().scaleX(if (hasFocus) 1.02f else 1f)
                .scaleY(if (hasFocus) 1.02f else 1f)
                .setDuration(100).start()
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.name)
        val count: TextView = v.findViewById(R.id.count)
    }
}
