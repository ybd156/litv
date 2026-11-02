package com.litv.tv.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 本地持久化：服务端地址、设备标识、device token、播放偏好。
 *
 * device_key 一旦生成就不再变——服务端用它认"同一台设备"。
 * 首次生成时优先用 ANDROID_ID 派生：它由系统按「设备+签名+用户」算出来，
 * 重装 App / 清数据都不会变，所以重装后服务端能认回原设备，不必重新授权。
 */
class Prefs(ctx: Context) {

    private val appCtx: Context = ctx.applicationContext

    private val sp: SharedPreferences =
        appCtx.getSharedPreferences("litv", Context.MODE_PRIVATE)
    private val tokenStore = SecureTokenStore(
        object : TokenStorage {
            override fun encrypted(): String? = sp.getString(K_TOKEN_ENCRYPTED, null)
            override fun legacyPlaintext(): String? = sp.getString(K_TOKEN, null)
            override fun replace(encrypted: String?): Boolean {
                val edit = sp.edit().remove(K_TOKEN)
                if (encrypted == null) edit.remove(K_TOKEN_ENCRYPTED)
                else edit.putString(K_TOKEN_ENCRYPTED, encrypted)
                return edit.commit()
            }
        },
        KeystoreTokenCipher(ctx),
    )

    var serverBase: String
        get() = sp.getString(K_SERVER, "") ?: ""
        set(v) {
            val normalized = v.trimEnd('/')
            if (serverBase != normalized) {
                // Never forward a token issued by one server to a different origin.
                clearSession()
                sp.edit().remove(K_CACHE).apply()
            }
            sp.edit().putString(K_SERVER, normalized).apply()
        }

    var deviceKey: String
        get() {
            val existing = sp.getString(K_DEVICE_KEY, "") ?: ""
            if (existing.isNotEmpty()) return existing

            // 用 ANDROID_ID 派生稳定标识。
            //
            // 原来每次都是随机 UUID 存 SharedPreferences：重装或清数据后
            // 就变成"新设备"，必须让管理员重新批准一次，非常烦。
            // ANDROID_ID 在同一设备 + 同一签名 + 同一用户下保持不变，
            // 重装后仍然拿到同一个 key，服务端据此认回原设备。
            val androidId = runCatching {
                android.provider.Settings.Secure.getString(
                    appCtx.contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID,
                )
            }.getOrNull()

            // 9774d56d682e549c 是早期一批设备固定的错误值，不能用
            val fresh = if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
                "and-" + androidId
            } else {
                java.util.UUID.randomUUID().toString().replace("-", "")
            }
            sp.edit().putString(K_DEVICE_KEY, fresh).apply()
            return fresh
        }
        set(v) = sp.edit().putString(K_DEVICE_KEY, v).apply()

    /**
     * 上次停留的底部 Tab（play / channel）。
     *
     * 必须持久化：从遥控器页/设置页返回时，首页可能已经被系统回收重建，
     * 若在 onCreate 里写死回到「播放」，用户就会觉得"点了设置又给我弹回播放"。
     */
    var lastTab: String
        get() = sp.getString(K_LAST_TAB, "play") ?: "play"
        set(v) = sp.edit().putString(K_LAST_TAB, v).apply()

    var token: String
        get() = tokenStore.read()
        set(v) = tokenStore.write(v)

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

    /** 起播超时（毫秒）。超过这个时间还没 READY 就换下一个内核。 */
    var startTimeoutMs: Int
        get() = sp.getInt(K_START_TIMEOUT, 5000)
        set(v) = sp.edit().putInt(K_START_TIMEOUT, v).apply()

    /** 超时后重试几次再换内核。网络抖动时多试几次比直接降级好。 */
    var retryCount: Int
        get() = sp.getInt(K_RETRY, 1)
        set(v) = sp.edit().putInt(K_RETRY, v).apply()

    /** 渲染方式：auto | texture | surface。auto = 优先 TextureView */
    var renderView: String
        get() = sp.getString(K_RENDER_VIEW, "auto") ?: "auto"
        set(v) = sp.edit().putString(K_RENDER_VIEW, v).apply()

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

    /**
     * 开机自动播放上次看的频道。
     *
     * 电视的使用习惯就是「打开就看」，不该先让人在列表里找台。
     */
    var autoPlayLast: Boolean
        get() = sp.getBoolean(K_AUTOPLAY, true)
        set(v) = sp.edit().putBoolean(K_AUTOPLAY, v).apply()

    /**
     * 播放方式：auto（默认，能直连就直连）/ direct（强制直连）/ relay（强制走服务端）。
     *
     * 直连 = 客户端自己连源站，省掉 NAS 的带宽和 CPU，起播更快；
     * 代价是客户端得能访问到源站，跨网或内网源可能连不上。
     */
    var directMode: String
        get() = sp.getString(K_DIRECT_MODE, "auto") ?: "auto"
        set(v) = sp.edit().putString(K_DIRECT_MODE, v).apply()

    /**
     * 界面模式：auto / tv / phone。
     *
     * 自动判定在少数国产手机/平板上会失灵（它们会同时上报 LEANBACK，
     * 甚至 UiModeManager 报 TELEVISION），结果手机进了横屏网格 UI。
     * 给用户一个手动开关，比反复猜设备特征靠谱。
     */
    var uiMode: String
        get() = sp.getString(K_UI_MODE, "auto") ?: "auto"
        set(v) = sp.edit().putString(K_UI_MODE, v).apply()

    /** 某个频道上次用哪个内核播成功过——记住它，下次直接命中，省一次失败重试。 */
    fun engineFor(channelId: Long): String = sp.getString("engine_$channelId", "") ?: ""
    fun setEngineFor(channelId: Long, engine: String) =
        sp.edit().putString("engine_$channelId", engine).apply()

    /** 缓存上次的频道列表，冷启动先渲染再刷新（体感快很多）。 */
    var cachedBootstrap: String
        get() = sp.getString(K_CACHE, "") ?: ""
        set(v) = sp.edit().putString(K_CACHE, v).apply()

    fun clearSession() {
        tokenStore.clear()
        sp.edit().remove(K_DEVICE_ID).remove(K_CACHE).apply()
    }

    private companion object {
        const val K_SERVER = "server_base"
        const val K_DEVICE_KEY = "device_key"
        const val K_LAST_TAB = "last_tab"
        const val K_TOKEN = "token"

        /** 加密后的 token（SecureTokenStore 用；老的明文 token 会迁移过来） */
        const val K_TOKEN_ENCRYPTED = "token_enc"
        const val K_DEVICE_ID = "device_id"
        const val K_DEVICE_NAME = "device_name"
        const val K_LAST_CH = "last_channel"
        const val K_ENGINE = "engine"
        const val K_DECODE = "decode"
        const val K_BUFFER = "buffer_ms"
        const val K_START_TIMEOUT = "start_timeout_ms"
        const val K_RETRY = "retry_count"
        const val K_ASPECT = "aspect"
        const val K_RENDER_VIEW = "render_view"
        const val K_REVERSE = "reverse_keys"
        const val K_NUM_TIMEOUT = "num_timeout"
        const val K_UI_MODE = "ui_mode"
        const val K_AUTOPLAY = "auto_play_last"
        const val K_DIRECT_MODE = "direct_mode"
        const val K_CACHE = "bootstrap_cache"
    }
}
