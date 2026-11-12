package com.litv.tv.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 播放地址缓存（多频道）。
 *
 * 为什么要它：起播前必须请求 /tv/play 拿带签名的地址，这是**串行**的网络往返。
 * 实测冷启动 9.5 秒里有近 3 秒花在这上面，换台同理。
 * 而签名地址本身有有效期，只要没过期就能直接用——网络请求挪到后台。
 *
 * 为什么是多条而不是一条：换台场景下用户经常来回切（比如在几个台之间比节目），
 * 只缓存最后一个台的话，切回去仍然要等网络。
 *
 * 存储上限 [MAX_ENTRIES]：预分配里存太多会让 SharedPreferences 变大、
 * 每次读写都变慢，得不偿失。
 */
object PlayCacheStore {

    private const val MAX_ENTRIES = 24

    private val gson = Gson()
    private val listType = object : TypeToken<MutableList<PlayCache>>() {}.type

    private fun read(prefs: Prefs): MutableList<PlayCache> = try {
        val raw = prefs.playCache
        if (raw.isBlank()) mutableListOf() else gson.fromJson(raw, listType) ?: mutableListOf()
    } catch (t: Throwable) {
        mutableListOf()
    }

    /**
     * 取这个频道还能用的缓存；没有或快过期就返回 null。
     *
     * 过期判定在 [PlayCache.isUsable] 里（纯函数，余量阈值的单一来源
     * 是 [PlayCache.MIN_REMAIN_SEC]，这里不再写字面量）。
     *
     * @param nowSec 当前 Unix 秒
     */
    fun get(prefs: Prefs, channelId: Long, identity: String, nowSec: Long): PlayCache? {
        val c = read(prefs).firstOrNull {
            it.channelId == channelId && it.identity == identity
        } ?: return null
        return if (c.isUsable(nowSec, PlayCache.MIN_REMAIN_SEC)) c else null
    }

    /**
     * 算缓存身份：服务端 origin + 会话代际 + 线路 + 模式。
     *
     * 任何一项变了就不该复用旧地址——切中转、换服务器、解绑重绑都属这一类。
     */
    fun identity(prefs: Prefs, streamId: Long, mode: String): String {
        val origin = prefs.serverBase.trim().trimEnd('/').lowercase()
        return "$origin|${prefs.playCacheEpoch}|$streamId|${mode.lowercase()}"
    }

    /** 存一条（同频道覆盖），并淘汰最旧的。 */
    fun put(prefs: Prefs, cache: PlayCache) {
        if (cache.channelId <= 0 || cache.url.isBlank()) return
        val list = read(prefs)
        // 同频道同身份覆盖；不同身份并存，互不串
        list.removeAll { it.channelId == cache.channelId && it.identity == cache.identity }
        list.add(0, cache)                     // 最近用过的排前面
        while (list.size > MAX_ENTRIES) {
            list.removeAt(list.size - 1)
        }
        runCatching { prefs.playCache = gson.toJson(list, listType) }
    }

    /** 清空（解绑 / 换服务器时调用）。 */
    fun clear(prefs: Prefs) {
        prefs.playCache = ""
        // 推进代际：解绑/换服务器之后，之前算出来的 identity 全部失效，
        // 旧请求就算晚回来也命中不了新缓存。
        prefs.playCacheEpoch = prefs.playCacheEpoch + 1
    }
}
