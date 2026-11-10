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
    /** 上次成功起播的地址（JSON），用于跳过启动时的 /tv/play 往返 */
    var playCache: String
        get() = sp.getString(K_PLAY_CACHE, "") ?: ""
        set(v) = sp.edit().putString(K_PLAY_CACHE, v).apply()

    var lastChannelId: Long
        get() = sp.getLong(K_LAST_CH, 0)
        set(v) = sp.edit().putLong(K_LAST_CH, v).apply()

    /**
     * 上次看的频道名与 tvg_id。
     *
     * 为什么要多存两个：上游刷新会**重建**频道行（改名/改分组都会生成新 id），
     * 只按 id 恢复必然失效，然后就静默跳到第一个台——这就是用户反馈的
     * 「退出重进没有到上次看的频道」。恢复优先级见 [LastChannelResolver]。
     */
    var lastChannelTvgId: String
        get() = sp.getString(K_LAST_CH_TVG, "") ?: ""
        set(v) = sp.edit().putString(K_LAST_CH_TVG, v).apply()

    var lastChannelName: String
        get() = sp.getString(K_LAST_CH_NAME, "") ?: ""
        set(v) = sp.edit().putString(K_LAST_CH_NAME, v).apply()

    /**
     * 上次看的分组**名字**（不是索引）。
     *
     * 为什么不能存索引：分组列表会变——收藏行现在常显、上游会加/删分组，
     * 索引会整体错位，老用户升级后直接落到隔壁分组。
     * 只在"上次那个台已经彻底不在列表里"时用来兜底恢复分组。
     */
    var lastGroup: String
        get() = sp.getString(K_LAST_GROUP, "") ?: ""
        set(v) = sp.edit().putString(K_LAST_GROUP, v).apply()

    // ---- 加密 DNS 与直连策略（本轮新增；都是本地偏好，不走服务端契约） ----

    /**
     * 加密 DNS（DoH）：`auto` = DoH 优先、失败回落系统；`off` = 只用系统 DNS。
     *
     * 默认 `auto`：直连源站时运营商 DNS 污染/SNI 重置是最常见的"连不上"原因，
     * 而 DoH 失败会自动回落系统 DNS，开启的代价很低。
     */
    var dohMode: String
        get() = sp.getString(K_DOH_MODE, "auto") ?: "auto"
        set(v) = sp.edit().putString(K_DOH_MODE, v).apply()

    /** DoH 服务商：ali | tencent | cloudflare（默认阿里，国内可达性最好）。 */
    var dohProvider: String
        get() = sp.getString(K_DOH_PROVIDER, "ali") ?: "ali"
        set(v) = sp.edit().putString(K_DOH_PROVIDER, v).apply()

    /** DoH 是否生效（`auto` 才算开）。 */
    fun dohEnabled(): Boolean = dohMode != "off"

    /**
     * 直连失败时是否**自动**改用 NAS 中转。
     *
     * 默认关闭（用户口径：中转默认不用，只在手动点时用）。
     * 打开即回退成旧行为：一失败就 forceRelayOnce 重播，不再询问。
     */
    var autoRelay: Boolean
        get() = sp.getBoolean(K_AUTO_RELAY, false)
        set(v) = sp.edit().putBoolean(K_AUTO_RELAY, v).apply()

    /** 界面缩放档位 key：s | m | l（见 [UiScaleOptions]）。默认中档 = 设计稿基准。 */
    var uiScale: String
        get() = sp.getString(K_UI_SCALE, "m") ?: "m"
        set(v) = sp.edit().putString(K_UI_SCALE, v).apply()

    /**
     * OSD 是否显示"播放状态叠加"（线路 / 内核 / 缓冲）。
     *
     * 默认开，但这些是排障信息，不该和台名抢注意力：关掉之后 OSD 恒为第一层，
     * 只留「台号 + 台名 + 收藏态」。
     */
    var showPlayStatus: Boolean
        get() = sp.getBoolean(K_SHOW_PLAY_STATUS, true)
        set(v) = sp.edit().putBoolean(K_SHOW_PLAY_STATUS, v).apply()

    // ---- 本地播放偏好（服务端远程配置会覆盖它） ----
    var engine: String
        get() = sp.getString(K_ENGINE, "auto") ?: "auto"
        set(v) = sp.edit().putString(K_ENGINE, v).apply()

    var decode: String
        get() = sp.getString(K_DECODE, "auto") ?: "auto"
        set(v) = sp.edit().putString(K_DECODE, v).apply()

    var bufferMs: Int
        get() = sp.getInt(K_BUFFER, PlaybackDefaults.BUFFER_MS)
        set(v) = sp.edit().putInt(K_BUFFER, v).apply()

    /**
     * 起播超时（毫秒）。超过这个时间还没 READY 就换下一个内核。
     *
     * 默认值与可选项都在 [PlaybackDefaults] 里，**不要在别处再写字面量**——
     * 之前 Prefs 写 15000、设置页选项却没有 15000，用户点一下那行就掉到 5 秒。
     *
     * 为什么是 15 秒：电视盒子（尤其 32 位 Amlogic）上，HLS 要经历 TCP+TLS 握手、
     * 下载 m3u8、拉分片、初始化解码器，5 秒经常不够——表现为"Exo 起播超时"
     * 直接跳到 VLC，而 VLC 又可能因为别的原因失败，最后报"所有内核都无法播放"。
     * 手机（arm64）够快所以看不出问题。
     */
    var startTimeoutMs: Int
        get() = sp.getInt(K_START_TIMEOUT, PlaybackDefaults.START_TIMEOUT_MS)
        set(v) = sp.edit().putInt(K_START_TIMEOUT, v).apply()

    /** 超时后重试几次再换内核。网络抖动时多试几次比直接降级好。 */
    var retryCount: Int
        get() = sp.getInt(K_RETRY, PlaybackDefaults.RETRY_COUNT)
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
    /**
     * 是否启用 AV3A（AVS3-P3）音频解码。
     *
     * 默认关闭。解码库不随本包分发（没有合法的开源实现），
     * 需要用户自己把 .so 放进 Av3aLoader 指定的目录。
     */
    var av3aEnabled: Boolean
        get() = sp.getBoolean(K_AV3A, false)
        set(v) = sp.edit().putBoolean(K_AV3A, v).apply()

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
        const val K_PLAY_CACHE = "play_cache"
        const val K_LAST_CH = "last_channel"
        const val K_LAST_CH_TVG = "last_channel_tvg"
        const val K_LAST_CH_NAME = "last_channel_name"
        const val K_LAST_GROUP = "last_group"
        const val K_DOH_MODE = "doh_mode"
        const val K_DOH_PROVIDER = "doh_provider"
        const val K_AUTO_RELAY = "auto_relay"
        const val K_UI_SCALE = "ui_scale"
        const val K_SHOW_PLAY_STATUS = "show_play_status"
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
        const val K_AV3A = "av3a_enabled"
        const val K_DIRECT_MODE = "direct_mode"
        const val K_CACHE = "bootstrap_cache"
    }
}
