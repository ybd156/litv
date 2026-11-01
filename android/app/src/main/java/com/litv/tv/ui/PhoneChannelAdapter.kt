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
 * 手机端频道列表适配器（单列行式）。
 *
 * 与 TV 端的网格适配器分开：手机上要的是信息密度和点击热区，
 * TV 上要的是焦点和远距离可读性，硬套一套反而两边都别扭。
 */
class PhoneChannelAdapter(
    private val onClick: (Channel) -> Unit,
    private val onStarClick: (Channel) -> Unit,
) : RecyclerView.Adapter<PhoneChannelAdapter.VH>() {

    private val items = ArrayList<Channel>()
    private val nowMap = HashMap<Long, String>()
    private val logoCache = LruCache<String, Bitmap>(48)
    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private var favorites: Set<Long> = emptySet()

    fun submit(list: List<Channel>, now: Map<Long, String>, favs: Set<Long>) {
        items.clear(); items.addAll(list)
        nowMap.clear(); nowMap.putAll(now)
        favorites = favs
        notifyDataSetChanged()
    }

    fun items(): List<Channel> = items

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel_phone, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = items[position]
        holder.name.text = ch.name
        val now = nowMap[ch.id]
        holder.now.text = now ?: ""
        holder.now.visibility = if (now.isNullOrBlank()) View.GONE else View.VISIBLE
        holder.star.text = if (favorites.contains(ch.id)) "★" else "☆"

        holder.itemView.setOnClickListener { onClick(ch) }
        holder.star.setOnClickListener { onStarClick(ch) }
        loadLogo(holder.logo, ch.logo)
    }

    /** 加载台标。公开出来是为了让首页的「正在热播」大卡片复用同一个缓存。 */
    fun loadLogo(target: ImageView, url: String) {
        target.setImageDrawable(null)
        if (url.isBlank()) return
        logoCache.get(url)?.let { target.setImageBitmap(it); return }
        target.tag = url
        executor.execute {
            val bmp = try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000; readTimeout = 5000
                }
                conn.inputStream.use { input ->
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(input, null, opts)
                    val scale = maxOf(1, minOf(opts.outWidth, opts.outHeight) / 96)
                    (URL(url).openConnection() as HttpURLConnection).inputStream.use { i2 ->
                        BitmapFactory.decodeStream(i2, null,
                            BitmapFactory.Options().apply { inSampleSize = scale })
                    }
                }
            } catch (t: Throwable) {
                null
            }
            if (bmp != null) {
                logoCache.put(url, bmp)
                main.post { if (target.tag == url) target.setImageBitmap(bmp) }
            }
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val logo: ImageView = v.findViewById(R.id.logo)
        val name: TextView = v.findViewById(R.id.name)
        val now: TextView = v.findViewById(R.id.now)
        val star: TextView = v.findViewById(R.id.star)
    }
}
