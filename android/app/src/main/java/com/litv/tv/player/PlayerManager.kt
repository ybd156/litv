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
    private var surface: SurfaceView? = null
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

    fun attach(surfaceView: SurfaceView, parent: ViewGroup?) {
        surface = surfaceView
        container = parent
        current?.attach(context, surfaceView, parent)
    }

    /**
     * 开始播放某个频道。
     * @param preferredEngine auto | exo | mpv | vlc
     */
    fun play(channelId: Long, url: String, headers: Map<String, String>, preferredEngine: String) {
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
            callback?.onError("所有播放内核都无法播放这个频道")
            return
        }
        val kind = chain[chainIndex]
        val engine = create(kind)
        if (engine == null || !engine.isAvailable(context)) {
            Log.i(TAG, "内核 ${kind.key} 不可用，跳过")
            chainIndex++
            startCurrent()
            return
        }

        current?.release()
        current = engine
        currentKind = kind
        callback?.onEngineChanged(kind)

        val sv = surface ?: run {
            callback?.onError("播放视图未就绪")
            return
        }
        engine.attach(context, sv, container)
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

        engine.prepare(pendingUrl, pendingHeaders, prefs.bufferMs, prefs.decode)
        // 超时和重试次数都从设置里读，不再写死
        main.postDelayed(startTimeout, prefs.startTimeoutMs.toLong())
    }

    private fun tryNext(reason: String) {
        main.removeCallbacks(startTimeout)
        Log.w(TAG, "内核 ${currentKind?.key} 失败($reason)，尝试下一个")
        chainIndex++
        if (chainIndex >= chain.size) {
            callback?.onError("播放失败：$reason")
            return
        }
        startCurrent()
    }

    private fun create(kind: EngineKind): PlayerEngine? = when (kind) {
        EngineKind.EXO -> ExoEngine()
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
        current?.release()
        current = null
        currentKind = null
    }

    private companion object {
        const val TAG = "PlayerManager"
        // 起不来的内核不该拖 6 秒——3 秒足够判断，超时就换下一个
        const val START_TIMEOUT_MS = 5000L
    }
}
