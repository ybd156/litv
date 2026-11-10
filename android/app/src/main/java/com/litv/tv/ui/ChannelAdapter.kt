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
    private val onLongClick: ((Channel) -> Unit)? = null,
    /** 焦点落到某个台上时回调——三列联动要靠它实时刷新右侧节目单 */
    private val onFocus: ((Channel) -> Unit)? = null,
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    private val items = ArrayList<Channel>()
    private val nowMap = HashMap<Long, com.litv.tv.data.NowPlaying>()
    private val logoCache = LruCache<String, Bitmap>(32)
    private var favorites: Set<Long> = emptySet()

    /**
     * 正在播放的频道 id。
     *
     * 为什么要单独存：`selBar` 原来只跟**焦点**走，焦点一移开就消失，
     * 于是"恢复上次频道"之后用户根本看不出自己在看哪个台。
     * 现在 selBar = 焦点在它 或 它就是正在播的那个台。
     */
    private var playingId: Long = 0

    fun setPlayingId(id: Long) {
        if (id == playingId) return
        playingId = id
        notifyDataSetChanged()
    }

    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun submit(list: List<Channel>, now: Map<Long, com.litv.tv.data.NowPlaying>, favs: Set<Long> = favorites) {
        items.clear()
        items.addAll(list)
        nowMap.clear()
        nowMap.putAll(now)
        favorites = favs
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
        // 没台号就用序号，别留一格空白
        holder.tvNo.text = if (ch.number > 0) ch.number.toString() else (position + 1).toString()
        // 台标：先显示首字方块兜底，图真的加载成功了再盖上去。
        // （源里的台标多是外链，加载失败很常见；只在 URL 为空时才兜底是不够的）
        holder.logo.setImageDrawable(null)
        holder.logoText.text = ch.name.take(2)
        holder.logoText.visibility = View.VISIBLE
        if (ch.logo.isNotBlank()) loadLogo(holder.logo, ch.logo)
        // 走马灯必须 isSelected=true 才会滚（TV 上长台名不会截断）
        holder.name.isSelected = true
        holder.now.isSelected = true

        val np = nowMap[ch.id]
        holder.now.text = np?.title.orEmpty()
        holder.now.visibility = if (np?.title.isNullOrBlank()) View.GONE else View.VISIBLE

        // 行内节目进度：mediaplayer 的做法，2dp 细条
        if (np != null && np.end > np.start) {
            val nowSec = System.currentTimeMillis() / 1000
            val pct = (((nowSec - np.start) * 100) / (np.end - np.start)).coerceIn(0, 100).toInt()
            holder.prog.progress = pct
            holder.prog.visibility = View.VISIBLE
        } else {
            holder.prog.visibility = View.GONE
        }

        // 徽标：收藏星标优先，其次是线路数
        val fav = favorites.contains(ch.id)
        when {
            fav && ch.streamCount > 1 -> {
                holder.badge.text = "★ ${ch.streamCount} 线路"
                holder.badge.visibility = View.VISIBLE
            }
            fav -> {
                holder.badge.text = "★ 收藏"
                holder.badge.visibility = View.VISIBLE
            }
            ch.streamCount > 1 -> {
                holder.badge.text = "${ch.streamCount} 线路"
                holder.badge.visibility = View.VISIBLE
            }
            else -> holder.badge.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { onClick(ch) }
        // 正在播的台：即使焦点不在它上面也保留竖条，用户一眼能看到自己的台在哪
        holder.selBar.visibility = if (ch.id == playingId) View.VISIBLE else View.GONE
        holder.itemView.setOnLongClickListener {
            onLongClick?.invoke(ch)
            onLongClick != null
        }
        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            // 焦点放大：TV 上远距离也能分辨
            v.animate().scaleX(if (hasFocus) 1.02f else 1f)
                .scaleY(if (hasFocus) 1.02f else 1f)
                .setDuration(120).start()
            holder.selBar.visibility =
                if (hasFocus || ch.id == playingId) View.VISIBLE else View.GONE
            // 三列联动：焦点到哪个台，右侧就显示哪个台的节目单
            if (hasFocus) onFocus?.invoke(ch)
        }

        loadLogo(holder.logo, ch.logo)
    }

    /** 公开出来：三列布局右侧的台标复用同一份缓存 */
    fun loadLogoFor(target: ImageView, url: String) = loadLogo(target, url)

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
                    if (target.tag == url || target.tag == null) {
                        target.setImageBitmap(bmp)
                        // 图真的有了，把首字兜底藏掉
                        (target.parent as? android.view.ViewGroup)
                            ?.findViewById<TextView>(R.id.logoText)
                            ?.visibility = View.GONE
                    }
                }
            }
        }
        target.tag = url
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val selBar: View = v.findViewById(R.id.selBar)
        val tvNo: TextView = v.findViewById(R.id.tvNo)
        val prog: android.widget.ProgressBar = v.findViewById(R.id.prog)
        val logo: ImageView = v.findViewById(R.id.logo)
        val logoText: TextView = v.findViewById(R.id.logoText)
        val name: TextView = v.findViewById(R.id.name)
        val now: TextView = v.findViewById(R.id.now)
        val badge: TextView = v.findViewById(R.id.badge)
    }
}
