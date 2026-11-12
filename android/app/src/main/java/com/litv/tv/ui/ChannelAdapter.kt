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
import com.litv.tv.data.UiScaleOptions
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

    /** 界面缩放档位 key（s/m/l）：影响台名字号与行高，见 [UiScaleOptions]。 */
    private var uiScaleKey: String = "m"

    fun setUiScale(key: String) {
        if (key == uiScaleKey) return
        uiScaleKey = key
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
        // 占位方块按频道名取色，让每行有点辨识度。
        //
        // 为什么需要：很多源的台标挂在只对特定网络开放的 CDN 上（实测
        // 某源用 wapvideo.cn，电视上 ping 直接 unknown host），图永远加载
        // 不出来。一片一样的灰块等于没有台标——按名字散列到固定色，
        // 至少能靠颜色记住"这个台在哪"。
        holder.logoText.setBackgroundColor(placeholderColor(ch.name))
        holder.logoText.visibility = View.VISIBLE
        if (ch.logo.isNotBlank()) loadLogo(holder.logo, ch.logo)
        // 正在播的那个台给实底高亮（bg_focusable 的 state_selected）。
        // 不能只依赖 holder.name.isSelected —— 那是给走马灯的，
        // 子 View 的 selected 不会往上传染给行容器。
        holder.itemView.isSelected = ch.id == playingId
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

        // 界面缩放：台名字号与行高按档位缩放（设置页「界面缩放」小/中/大）
        val scale = UiScaleOptions.from(uiScaleKey)
        holder.name.textSize = 17f * scale.fontScale
        holder.tvNo.textSize = 15f * scale.fontScale
        holder.now.textSize = 11f * scale.fontScale
        holder.itemView.minimumHeight =
            (scale.rowHeightDp * holder.itemView.resources.displayMetrics.density).toInt()

        // 「正在播」用左侧竖条 + accent 台名；
        // 「焦点」用背景描边 + 轻微放大（见 bg_channel_item.xml）。
        // 两者视觉必须不同 —— 以前都用同一个竖条，用户分不清"选中"和"在看"。
        val playing = ch.id == playingId
        holder.selBar.visibility = if (playing) View.VISIBLE else View.GONE
        holder.name.setTextColor(
            holder.itemView.context.getColor(
                if (playing) R.color.accent else R.color.text_primary
            )
        )

        holder.itemView.setOnLongClickListener {
            onLongClick?.invoke(ch)
            onLongClick != null
        }
        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            // 焦点放大：TV 上远距离也能分辨
            v.animate().scaleX(if (hasFocus) 1.02f else 1f)
                .scaleY(if (hasFocus) 1.02f else 1f)
                .setDuration(120).start()
            // 焦点不再复用竖条：竖条只表示"正在播"
            if (hasFocus) onFocus?.invoke(ch)
        }

        loadLogo(holder.logo, ch.logo)
    }

    /** 公开出来：三列布局右侧的台标复用同一份缓存 */
    fun loadLogoFor(target: ImageView, url: String) = loadLogo(target, url)

    /**
     * 占位方块的颜色：按频道名散列到一组低饱和色。
     *
     * 低饱和是刻意的——列表里几十行同时出现，高饱和会吵得没法看；
     * 这里只需要"相邻两行颜色不一样"，让人靠颜色记住位置。
     */
    private fun placeholderColor(name: String): Int {
        val palette = intArrayOf(
            0xFF2A3B4C.toInt(), 0xFF3B2A4C.toInt(), 0xFF2A4C3B.toInt(), 0xFF4C3B2A.toInt(),
            0xFF2A4C4C.toInt(), 0xFF4C2A3B.toInt(), 0xFF3B4C2A.toInt(), 0xFF2A2A4C.toInt(),
        )
        if (name.isEmpty()) return palette[0]
        var h = 0
        for (c in name) h = h * 31 + c.code
        return palette[(h and 0x7FFFFFFF) % palette.size]
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
                // 打域名而不是完整 URL：台标地址里偶尔带签名参数。
                // 有这条日志才能区分"图挂了"和"这个源根本没有 logo"——
                // 实测某源的台标全挂在电视解析不了的域名上（unknown host），
                // 之前完全没有日志，只能靠猜。
                val host = runCatching { URL(url).host }.getOrDefault("?")
                android.util.Log.i("LITV", "台标加载失败 $host：${t.javaClass.simpleName}")
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
