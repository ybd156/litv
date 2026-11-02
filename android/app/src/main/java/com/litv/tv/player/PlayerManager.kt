package com.litv.tv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceView
import android.view.ViewGroup
import com.litv.tv.data.Prefs

/**
 * 多内核调度器：负责"选哪个内核、失败了换哪个、成功了记住它"。
 *
 * 这是整个播放链路里最容易写烂的部分。要点：
 *  1. 起播超时（默认 6s）就当失败——直播不能无限等，用户早就换台了；
 *  2. 成功后把"这个频道用哪个内核"记到本地，下次直接命中，不再试错；
 *  3. 换台时不重建内核实例（Exo 复用 player、mpv 复用实例），省 200~400ms。
 */
class PlayerManager(
    private val context: Context,
    private val prefs: Prefs,
) {

    interface Callback {
        /** 内核切换了（OSD 上可以提示"正在用 mpv 播放"）。 */
        fun onEngineChanged(kind: EngineKind)
        fun onPrepared(width: Int, height: Int)
        fun onPlaying()
        fun onError(message: String)
        fun onVideoSizeChanged(width: Int, height: Int)
    }

    var callback: Callback? = null

    private val main = Handler(Looper.getMainLooper())
    private var surface: android.view.Surface? = null
    /** media3-ui 的 PlayerView，交给 ExoEngine 管 Surface */
    var playerView: androidx.media3.ui.PlayerView? = null
    private var container: ViewGroup? = null
    private var current: PlayerEngine? = null
    private var currentKind: EngineKind? = null
    private var chain: List<EngineKind> = emptyList()
    private var chainIndex = 0
    private var pendingUrl = ""
    private var pendingHeaders: Map<String, String> = emptyMap()
    private var pendingChannelId = 0L
    private var prepared = false

    /** 本次内核已经重试过几次 */
    private var retried = 0

    private val startTimeout = Runnable {
        if (prepared) return@Runnable
        // 还没到重试上限就再来一次：网络抖动/慢设备上，重试比直接降级管用
        if (retried < prefs.retryCount) {
            retried++
            Log.w(TAG, "内核 ${currentKind?.key} 起播超时，重试第 $retried 次（上限 ${prefs.retryCount}）")
            startCurrent()
            return@Runnable
        }
        retried = 0
        Log.w(TAG, "内核 ${currentKind?.key} 起播超时，降级")
        tryNext("起播超时")
    }

    /** 当前内核是否已经起播（用于 Surface 晚就绪时决定要不要补播） */
    val isPlaying: Boolean get() = prepared

    fun attach(surface: android.view.Surface?, parent: ViewGroup?) {
        this.surface = surface
        container = parent
        current?.attach(context, surface, parent)
    }

    /**
     * 开始播放某个频道。
     * @param preferredEngine auto | exo | mpv | vlc
     */
    /**
     * 每个内核各自的失败原因。
     *
     * 原来所有失败被合并成一句"所有播放内核都无法播放这个频道"，
     * 用户和排查者都不知道到底是编码不支持、地址失效还是网络不通。
     * 现在逐个记录下来，失败时一起展示。
     */
    private val failLog = mutableListOf<String>()

    /**
     * 本次播放是否用的直连。
     *
     * 直连失败几乎都是网络问题（源站在这个网络里不可达），
     * 换内核不可能修好——所以第一个内核失败就立刻通知上层改走中转，
     * 不要傻等 exo 超时 + vlc + mpv 轮一圈（那要 20 秒）。
     */
    var directMode: Boolean = false

    /** 直连第一个内核就失败时回调，上层据此改用中转重播 */
    var onDirectFailed: ((String) -> Unit)? = null

    /** 把各内核的失败原因拼成一句话（过长时截断，电视上放不下）。 */
    private fun chainFailMessage(): String {
        if (failLog.isEmpty()) return "所有播放内核都无法播放这个频道"
        val detail = failLog.joinToString("；")
        val msg = "所有内核均失败：$detail"
        return if (msg.length > 120) msg.take(117) + "…" else msg
    }

    fun play(channelId: Long, url: String, headers: Map<String, String>, preferredEngine: String) {
        failLog.clear()
        pendingChannelId = channelId
        pendingUrl = url
        pendingHeaders = headers

        val remembered = prefs.engineFor(channelId).ifBlank { null }
        chain = EngineChain.chainFor(preferredEngine, remembered)
        chainIndex = 0
        prepared = false
        startCurrent()
    }

    private fun startCurrent() {
        main.removeCallbacks(startTimeout)
        if (chainIndex >= chain.size) {
            callback?.onError(chainFailMessage())
            return
        }
        val kind = chain[chainIndex]
        val engine = create(kind)
        if (engine == null || !engine.isAvailable(context)) {
            Log.i(TAG, "内核 ${kind.key} 不可用，跳过")
            failLog += "${kind.key}: 不可用"
            chainIndex++
            startCurrent()
            return
        }

        disposeEngine(current)
        current = engine
        currentKind = kind
        callback?.onEngineChanged(kind)

        // PlayerView 模式下不需要外部 Surface —— 它自己管 Surface 生命周期，
        // 所以只有两条都没有时才算「视图未就绪」。
        if (surface == null && playerView == null) {
            callback?.onError("播放视图未就绪")
            return
        }
        engine.attach(context, surface, container)
        engine.listener = object : PlayerEngine.Listener {
            override fun onPrepared(durationMs: Long) {
                prepared = true
                main.removeCallbacks(startTimeout)
                prefs.setEngineFor(pendingChannelId, kind.key)
            }

            override fun onPlaying() {
                prepared = true
                main.removeCallbacks(startTimeout)
                callback?.onPlaying()
            }

            override fun onError(message: String) {
                if (currentKind == kind) tryNext(message)
            }

            override fun onVideoSizeChanged(width: Int, height: Int) {
                callback?.onVideoSizeChanged(width, height)
            }

            override fun onEnded() = Unit
        }

        engine.aspect = prefs.aspect
        engine.prepare(pendingUrl, pendingHeaders, prefs.bufferMs, prefs.decode)
        // 超时和重试次数都从设置里读，不再写死
        main.postDelayed(startTimeout, prefs.startTimeoutMs.toLong())
    }

    private fun tryNext(reason: String) {
        main.removeCallbacks(startTimeout)
        Log.w(TAG, "内核 ${currentKind?.key} 失败($reason)，尝试下一个")
        failLog += "${currentKind?.key ?: "?"}: $reason"

        // 直连模式下第一个内核就失败：别轮了，直接让上层改走中转
        if (directMode && chainIndex == 0) {
            Log.w(TAG, "直连首个内核失败，跳过其余内核，改走中转")
            directMode = false
            onDirectFailed?.invoke(reason)
            return
        }
        chainIndex++
        if (chainIndex >= chain.size) {
            callback?.onError(chainFailMessage())
            return
        }
        startCurrent()
    }

    private fun create(kind: EngineKind): PlayerEngine? = when (kind) {
        EngineKind.EXO -> ExoEngine().also { it.playerView = playerView }
        EngineKind.VLC -> VlcEngine()
        EngineKind.MPV -> MpvEngine(avs3Mode = false)
        EngineKind.AVS3 -> MpvEngine(avs3Mode = true)
    }

    fun pause() = current?.pause()
    fun resume() = current?.play()
    fun setVolume(v: Float) = current?.setVolume(v)
    fun stats(): EngineStats = current?.stats() ?: EngineStats()
    fun engineLabel(): String = currentKind?.label ?: ""
    fun snapshot() = current?.snapshot()

    /** 换台前抓一帧，盖住黑屏（TVBox 的做法，成本极低效果好）。 */
    fun stop() {
        main.removeCallbacks(startTimeout)
        current?.stop()
    }

    fun release() {
        main.removeCallbacks(startTimeout)
        disposeEngine(current)
        current = null
        currentKind = null
    }

    /**
     * 丢弃一个引擎时，把它的重资源一并释放。
     *
     * B04：VlcEngine 的 LibVLC 是重对象（内含 FFmpeg、网络栈、音频输出）。
     * 只调 PlayerEngine.release() 只放掉 MediaPlayer，LibVLC 会一直留着；
     * 而每次换内核都会 new 一个 VlcEngine，切几次就攒几个 LibVLC，最终 OOM。
     *
     * 这里按类型做最后一步清理。用 when 而不是接口方法，
     * 是因为只有 VLC 有这种"比播放器活得更久"的资源；
     * 给所有引擎加一个空接口方法反而会让所有权更模糊。
     */
    private fun disposeEngine(engine: PlayerEngine?) {
        if (engine == null) return
        engine.release()
        if (engine is VlcEngine) {
            try {
                engine.releaseLibVLC()
            } catch (t: Throwable) {
                Log.w(TAG, "释放 LibVLC 出错", t)
            }
        }
    }

    private companion object {
        const val TAG = "PlayerManager"
        // 起不来的内核不该拖 6 秒——3 秒足够判断，超时就换下一个
        const val START_TIMEOUT_MS = 5000L
    }
}
