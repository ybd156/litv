package com.litv.tv.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.litv.tv.R
import com.litv.tv.data.Channel
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 频道网格适配器。
 *
 * 性能要点（低端盒子 1GB RAM 是下限）：
 *   - 台标走内存 LRU + 降采样解码（Tier.logoMaxPx 控制上限），不占大图
 *   - 台标加载是 2 线程池 + 弱并发，不抢播放线程的 CPU
 *   - ViewHolder 复用，不 inflate 复杂布局
 */
class ChannelAdapter(
    private val logoMaxPx: Int,
    private val onClick: (Channel) -> Unit,
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    private val items = ArrayList<Channel>()
    private val nowMap = HashMap<Long, String>()
    private val logoCache = LruCache<String, Bitmap>(32)

    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun submit(list: List<Channel>, now: Map<Long, String>) {
        items.clear()
        items.addAll(list)
        nowMap.clear()
        nowMap.putAll(now)
        notifyDataSetChanged()
    }

    fun items(): List<Channel> = items

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = items[position]
        holder.name.text = ch.name
        val now = nowMap[ch.id]
        holder.now.text = now ?: ""
        holder.now.visibility = if (now.isNullOrBlank()) View.GONE else View.VISIBLE

        // 多线路徽标：用户一眼能看出这个台有几个源可切
        if (ch.streamCount > 1) {
            holder.badge.text = "${ch.streamCount} 线路"
            holder.badge.visibility = View.VISIBLE
        } else {
            holder.badge.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { onClick(ch) }
        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            // 焦点放大：TV 上远距离也能分辨
            v.animate().scaleX(if (hasFocus) 1.06f else 1f)
                .scaleY(if (hasFocus) 1.06f else 1f)
                .setDuration(120).start()
        }

        loadLogo(holder.logo, ch.logo)
    }

    private fun loadLogo(target: ImageView, url: String) {
        target.setImageDrawable(null)
        if (url.isBlank()) return
        logoCache.get(url)?.let {
            target.setImageBitmap(it)
            return
        }
        executor.execute {
            val bmp = try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    instanceFollowRedirects = true
                }
                conn.inputStream.use { input ->
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(input, null, opts)
                    val scale = maxOf(1, minOf(opts.outWidth, opts.outHeight) / logoMaxPx)
                    BitmapFactory.decodeStream(
                        (URL(url).openConnection() as HttpURLConnection).inputStream,
                        null,
                        BitmapFactory.Options().apply { inSampleSize = scale },
                    )
                }
            } catch (t: Throwable) {
                null
            }
            if (bmp != null) {
                logoCache.put(url, bmp)
                main.post {
                    // 复用后可能已经滚走，做一次 URL 校验避免错图
                    if (target.tag == url || target.tag == null) target.setImageBitmap(bmp)
                }
            }
        }
        target.tag = url
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val logo: ImageView = v.findViewById(R.id.logo)
        val name: TextView = v.findViewById(R.id.name)
        val now: TextView = v.findViewById(R.id.now)
        val badge: TextView = v.findViewById(R.id.badge)
    }
}
