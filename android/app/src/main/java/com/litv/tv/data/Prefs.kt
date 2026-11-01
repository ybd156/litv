package com.litv.tv.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 本地持久化：服务端地址、设备标识、device token、播放偏好。
 *
 * device_key 一旦生成就不再变——服务端用它认"同一台电视"，
 * 重装 App 会被当成新设备（这也是为什么要有绑定码流程）。
 */
class Prefs(ctx: Context) {

    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("litv", Context.MODE_PRIVATE)

    var serverBase: String
        get() = sp.getString(K_SERVER, "") ?: ""
        set(v) = sp.edit().putString(K_SERVER, v.trimEnd('/')).apply()

    var deviceKey: String
        get() {
            val existing = sp.getString(K_DEVICE_KEY, "") ?: ""
            if (existing.isNotEmpty()) return existing
            val fresh = java.util.UUID.randomUUID().toString().replace("-", "")
            sp.edit().putString(K_DEVICE_KEY, fresh).apply()
            return fresh
        }
        set(v) = sp.edit().putString(K_DEVICE_KEY, v).apply()

    var token: String
        get() = sp.getString(K_TOKEN, "") ?: ""
        set(v) = sp.edit().putString(K_TOKEN, v).apply()

    var deviceId: Long
        get() = sp.getLong(K_DEVICE_ID, 0)
        set(v) = sp.edit().putLong(K_DEVICE_ID, v).apply()

    var deviceName: String
        get() = sp.getString(K_DEVICE_NAME, "") ?: ""
        set(v) = sp.edit().putString(K_DEVICE_NAME, v).apply()

    /** 上次看的频道，开机自动续播。 */
    var lastChannelId: Long
        get() = sp.getLong(K_LAST_CH, 0)
        set(v) = sp.edit().putLong(K_LAST_CH, v).apply()

    // ---- 本地播放偏好（服务端远程配置会覆盖它） ----
    var engine: String
        get() = sp.getString(K_ENGINE, "auto") ?: "auto"
        set(v) = sp.edit().putString(K_ENGINE, v).apply()

    var decode: String
        get() = sp.getString(K_DECODE, "auto") ?: "auto"
        set(v) = sp.edit().putString(K_DECODE, v).apply()

    var bufferMs: Int
        get() = sp.getInt(K_BUFFER, 3000)
        set(v) = sp.edit().putInt(K_BUFFER, v).apply()

    var aspect: String
        get() = sp.getString(K_ASPECT, "auto") ?: "auto"
        set(v) = sp.edit().putString(K_ASPECT, v).apply()

    /** 换台方向反转（老人用遥控器时经常觉得上下反了）。 */
    var reverseChannelKeys: Boolean
        get() = sp.getBoolean(K_REVERSE, false)
        set(v) = sp.edit().putBoolean(K_REVERSE, v).apply()

    /** 数字键跳台的等待窗口，单位毫秒（逆向实测：TVBox 用 2500ms）。 */
    var numberJumpTimeoutMs: Int
        get() = sp.getInt(K_NUM_TIMEOUT, 2500)
        set(v) = sp.edit().putInt(K_NUM_TIMEOUT, v).apply()

    /** 某个频道上次用哪个内核播成功过——记住它，下次直接命中，省一次失败重试。 */
    fun engineFor(channelId: Long): String = sp.getString("engine_$channelId", "") ?: ""
    fun setEngineFor(channelId: Long, engine: String) =
        sp.edit().putString("engine_$channelId", engine).apply()

    /** 缓存上次的频道列表，冷启动先渲染再刷新（体感快很多）。 */
    var cachedBootstrap: String
        get() = sp.getString(K_CACHE, "") ?: ""
        set(v) = sp.edit().putString(K_CACHE, v).apply()

    fun clearSession() = sp.edit().remove(K_TOKEN).remove(K_DEVICE_ID).apply()

    private companion object {
        const val K_SERVER = "server_base"
        const val K_DEVICE_KEY = "device_key"
        const val K_TOKEN = "token"
        const val K_DEVICE_ID = "device_id"
        const val K_DEVICE_NAME = "device_name"
        const val K_LAST_CH = "last_channel"
        const val K_ENGINE = "engine"
        const val K_DECODE = "decode"
        const val K_BUFFER = "buffer_ms"
        const val K_ASPECT = "aspect"
        const val K_REVERSE = "reverse_keys"
        const val K_NUM_TIMEOUT = "num_timeout"
        const val K_CACHE = "bootstrap_cache"
    }
}
