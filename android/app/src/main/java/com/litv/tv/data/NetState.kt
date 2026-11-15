package com.litv.tv.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 外部网络状态：**持续监听**，不是用时才问一次。
 *
 * 为什么需要持续检测：息屏唤醒 / Wi-Fi 重连时，链路可能已经 up 但还没验证
 * 能上网，这时发请求必然失败。与其拿无效的重试把额度耗光，不如等
 * 「已验证可上网」这件事真的发生，再立刻重试。
 *
 * 判据用 NET_CAPABILITY_VALIDATED（API 23+）：只 hasCapability(INTERNET)
 * 在「连上了但没网」的场合也会为 true，正是我们要排除的情况。
 */
object NetState {

    @Volatile
    private var online: Boolean = true

    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    @Volatile
    private var started = false

    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        online = check(cm)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) = publish(check(cm))
                override fun onLost(network: android.net.Network) = publish(check(cm))
                override fun onCapabilitiesChanged(
                    network: android.net.Network,
                    caps: NetworkCapabilities,
                ) = publish(check(cm))
            })
        }
    }

    fun isOnline(ctx: Context): Boolean = online

    /** 注册回调；立即用当前状态回调一次，避免调用方错过已经发生的变化。 */
    fun addListener(l: (Boolean) -> Unit) {
        listeners.add(l)
        runCatching { l(online) }
    }

    fun removeListener(l: (Boolean) -> Unit) {
        listeners.remove(l)
    }

    private fun publish(v: Boolean) {
        if (v == online) return
        online = v
        for (l in listeners) runCatching { l(v) }
    }

    private fun check(cm: ConnectivityManager): Boolean = try {
        val n = cm.activeNetwork
        val caps = n?.let { cm.getNetworkCapabilities(it) }
        when {
            caps == null -> false
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            else -> caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    } catch (t: Throwable) {
        true
    }
}
