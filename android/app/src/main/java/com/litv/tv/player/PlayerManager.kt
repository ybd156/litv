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

        /** 第一帧真正画到屏幕上（换台快照要等这一帧再撤）。 */
        fun onFirstFrame() = Unit
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

    /** 上次设置「强制软解」时对应的频道，用来判断换台要不要重置 */
    private var lastChannelForSoftFlag = -1L
    private var pendingUrl = ""
    private var pendingHeaders: Map<String, String> = emptyMap()
    private var pendingChannelId = 0L

    /**
     * 当前（或最近一次）播放的频道 id。
     * 上层用它判断「播放器是不是已经在播这个台」——是的话只接 Surface 就行，
     * 不要再重新拉流（否则 Service 保活省下来的时间全白费）。
     */
    val playingChannelId: Long get() = pendingChannelId
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
        // 自适应：Exo 起播超时且还没试过软解 → 先用软解重试同一个内核。
        //
        // 这台 Amlogic 盒子的硬解器（OMX.amlogic.avc.decoder.awesome）
        // 在部分码流上会 wait timeout：数据喂进去了但不吐帧，画面全黑。
        // 换内核解决不了（VLC/mpv 同样起不来），但换成软解
        // （OMX.google.h264.decoder）就能正常出画面。
        // 所以先试软解，再考虑降级到别的内核。
        if (currentKind == EngineKind.EXO && !exoForceSoftwareDecode) {
            exoForceSoftwareDecode = true
            retried = 0
            Log.w(TAG, "exo 起播超时，改用软解重试")
            startCurrent()
            return@Runnable
        }

        retried = 0
        Log.w(TAG, "内核 ${currentKind?.key} 起播超时，降级")
        tryNext("起播超时")
    }

    /**
     * 首帧看门狗。
     *
     * 起播超时那个机制只看 "有没有 READY / 有没有开始播"，但 Amlogic 硬解器
     * 出不了画面时音频照常在放、Exo 照样报告 playing——超时永远不会触发，
     * 屏幕一直黑着。所以另开一个只看"有没有真的渲染出第一帧"的检查。
     */
    // ---------------------------------------------------------------- 卡顿看门狗
    //
    // 直播流会**静默卡住**：网络抖一下、服务端不再推数据、或者解码器停住，
    // ExoPlayer 都不会回调 onError，isPlaying() 依然是 true，画面却不动了。
    // 之前没有任何机制处理这种情况，用户看到的就是「卡住了不恢复，只能手动换台」。
    //
    // 唯一可靠的判断依据是播放位置：每隔几秒读一次，位置不前进就认为卡住了。
    private var stallTicks = 0
    private var lastPosMs = -1L
    private var stallRecovered = 0
    private var userPaused = false

    private val stallWatchdog = Runnable {
        val eng = current
        if (eng == null || !prepared) {
            main.postDelayed(stallWatchdog, STALL_CHECK_MS)
            return@Runnable
        }
        if (userPaused) {
            main.postDelayed(stallWatchdog, STALL_CHECK_MS)
            return@Runnable
        }
        val pos = runCatching { eng.stats().positionMs }.getOrDefault(-1L)

        // 这里**不能**用 isPlaying() 做前置条件。
        // 流真卡住时 ExoPlayer 会进入 BUFFERING，isPlaying() 返回 false，
        // 那样就永远检测不到了——这正是「卡住不恢复」的根因。
        // 判断依据只留「位置有没有前进」。
        if (pos >= 0) {
            if (lastPosMs >= 0 && pos <= lastPosMs) {
                stallTicks++
                Log.w(TAG, "播放位置没有前进（${lastPosMs}ms -> ${pos}ms），第 $stallTicks 次")
            } else {
                if (stallTicks > 0) Log.i(TAG, "播放已自行恢复，位置 $pos ms")
                stallTicks = 0
            }
            lastPosMs = pos
        }

        if (stallTicks >= STALL_TICKS_TO_RECOVER) {
            stallTicks = 0
            lastPosMs = -1L
            stallRecovered++
            if (stallRecovered > MAX_STALL_RECOVER) {
                // 反复卡死说明不是偶发抖动，重启也没用，别再无限循环
                Log.e(TAG, "已自动恢复 $MAX_STALL_RECOVER 次仍然卡死，停止重试")
                stopStallWatchdog()
                callback?.onError("播放反复卡死，请换台或检查网络")
                return@Runnable
            }
            Log.w(TAG, "判定卡死，第 $stallRecovered 次自动恢复")
            if (stallRecovered >= 2 && !forceRelayOnce) {
                // 同一个台连续卡两次，多半是这条直连线路有问题，改走中转
                forceRelayOnce = true
                Log.w(TAG, "连续卡死，这次改走服务端中转")
            }
            startCurrent()
        }
        main.postDelayed(stallWatchdog, STALL_CHECK_MS)
    }

    private fun startStallWatchdog() {
        main.removeCallbacks(stallWatchdog)
        stallTicks = 0
        lastPosMs = -1L
        main.postDelayed(stallWatchdog, STALL_CHECK_MS)
    }

    private fun stopStallWatchdog() {
        main.removeCallbacks(stallWatchdog)
        stallTicks = 0
        lastPosMs = -1L
    }

    private val frameWatchdog = Runnable {
        if (currentKind == EngineKind.EXO && !exoFirstFrameRendered && !exoForceSoftwareDecode) {
            Log.w(TAG, "exo 已起播但没有渲染出画面，改用软解")
            exoForceSoftwareDecode = true
            retried = 0
            startCurrent()
        }
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
        // 换台必须重置「强制软解」。
        // 否则在某个 4K 台上硬解失败一次之后，后面所有频道都会走软解——
        // 1080p 本来硬解好好的，却因为上一个台的问题被拖成软解，白白吃 CPU。
        if (channelId != lastChannelForSoftFlag) {
            exoForceSoftwareDecode = false
            lastChannelForSoftFlag = channelId
        }
        prepared = false
        stallRecovered = 0
        startCurrent()
    }

    private fun startCurrent() {
        main.removeCallbacks(startTimeout)

        // 换内核前给上一个解码器一点时间真正释放。
        //
        // ExoPlayer 的 release() 是**异步**的：它把 MediaCodec 的释放抛到内部
        // 播放线程。如果我们紧接着让下一个内核去申请解码器，上一个还占着
        // 硬件解码内存——4K 这种大码流就会直接报
        // "not enough codec memory for this file"，硬解失败回落软解。
        // 同设备上别的播放器只开一个解码器，所以不会遇到这个。
        if (chainIndex > 0 && current != null) {
            val next = chainIndex
            main.postDelayed({ startEngine(next) }, 600)
            return
        }
        startEngine(chainIndex)
    }

    private fun startEngine(index: Int) {
        if (index != chainIndex) return
        main.removeCallbacks(startTimeout)
        if (chainIndex >= chain.size) {
            callback?.onError(chainFailMessage())
            return
        }
        val kind = chain[chainIndex]

        // 换台时如果还是同一个内核，**复用引擎实例**。
        //
        // 为什么重要：每次 new ExoEngine() 都意味着底层 ExoPlayer 要销毁重建，
        // 实测这 0.7 秒纯属浪费（换台总耗时 2.65s 里占 1/4）。
        // 引擎内部已经做了「配置没变就复用播放器」，前提是引擎实例本身别换。
        val engine: PlayerEngine
        if (current != null && currentKind == kind) {
            engine = current!!
            Log.i(TAG, "复用 ${kind.key} 引擎实例")
        } else {
            val created = create(kind)
            if (created == null || !created.isAvailable(context)) {
                Log.i(TAG, "内核 ${kind.key} 不可用，跳过")
                failLog += "${kind.key}: 不可用"
                chainIndex++
                startCurrent()
                return
            }
            disposeEngine(current)
            current = created
            currentKind = kind
            callback?.onEngineChanged(kind)
            engine = created
        }

        // PlayerView 模式下不需要外部 Surface —— 它自己管 Surface 生命周期，
        // 所以只有两条都没有时才算「视图未就绪」。
        if (surface == null && playerView == null) {
            callback?.onError("播放视图未就绪")
            return
        }
        engine.attach(context, surface, container)
        stopStallWatchdog()
        main.removeCallbacks(frameWatchdog)
        if (kind == EngineKind.EXO) {
            main.postDelayed(frameWatchdog, 5000)
        }
        // 所有内核回调统一 post 到主线程。
        //
        // mpv 的日志/状态回调是从 native 线程发出来的，以前直接往下走，
        // 一路调到 PlayerActivity 里去 setVisibility，触发
        // "Only the original thread that created a view hierarchy can touch its views"
        // ——表现为切到 mpv 内核后 App 直接闪退。
        engine.listener = object : PlayerEngine.Listener {
            override fun onPrepared(durationMs: Long) {
                main.post {
                    prepared = true
                    main.removeCallbacks(startTimeout)
                    prefs.setEngineFor(pendingChannelId, kind.key)
                }
            }

            override fun onPlaying() {
                main.post {
                    prepared = true
                    main.removeCallbacks(startTimeout)
                    startStallWatchdog()
                    callback?.onPlaying()
                }
            }

            override fun onError(message: String) {
                main.post {
                    if (currentKind == kind) tryNext(message)
                }
            }

            override fun onFirstFrame() {
                callback?.onFirstFrame()
            }

            override fun onSurfaceLost(message: String) {
                Log.w(TAG, "Surface 丢失：$message")
                // 先把当前 Surface 重新挂上（Activity 那边可能已经有新的了）
                current?.attach(context, surface, container)
            }

            override fun onDecoderFailed(message: String) {
                main.post {
                    if (currentKind != kind) return@post
                    // 解码器失败：先换软解重试同一个内核。
                    // 换内核没用——VLC/mpv 用的是同一批 MediaCodec，
                    // 会拿同样的参数再失败一遍（实测 VLC 报
                    // "AMediaCodec.queueInputBuffer failed"），白等好几秒。
                    if (currentKind == EngineKind.EXO && !exoForceSoftwareDecode) {
                        Log.w(TAG, "解码器失败($message)，改用软解重试")
                        exoForceSoftwareDecode = true
                        retried = 0
                        startCurrent()
                        return@post
                    }
                    // 已经是软解还失败，说明这路码流本机真的解不了
                    if (currentKind == EngineKind.EXO && exoForceSoftwareDecode) {
                        Log.w(TAG, "软解也失败，该码流可能超出本机能力")
                        failLog += "exo软解: $message"
                    }
                    tryNext(message)
                }
            }

            override fun onVideoSizeChanged(width: Int, height: Int) {
                main.post { callback?.onVideoSizeChanged(width, height) }
            }

            override fun onEnded() = Unit
        }

        engine.aspect = prefs.aspect
        // 硬解已经被证明在这路码流上不行（exoForceSoftwareDecode 已置位）时，
        // 后面兜底的内核直接用软解——VLC/mpv 用的是同一批 MediaCodec，
        // 让它再试一遍硬解只会多黑屏几秒。
        val decodeMode = if (exoForceSoftwareDecode) "soft" else prefs.decode
        engine.prepare(pendingUrl, pendingHeaders, prefs.bufferMs, decodeMode)
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

    fun pause() {
        // 用户切走（Activity onPause）时暂停，这时位置当然不前进——
        // 看门狗必须停掉，否则会误判成卡死并自动重启播放。
        userPaused = true
        stopStallWatchdog()
        current?.pause()
    }

    fun resume() {
        userPaused = false
        current?.play()
        if (prepared) startStallWatchdog()
    }
    fun setVolume(v: Float) = current?.setVolume(v)
    fun stats(): EngineStats = current?.stats() ?: EngineStats()
    fun engineLabel(): String = currentKind?.label ?: ""
    fun snapshot() = current?.snapshot()

    /** 换台前抓一帧，盖住黑屏（TVBox 的做法，成本极低效果好）。 */
    fun stop() {
        main.removeCallbacks(startTimeout)
        stopStallWatchdog()
        current?.stop()
    }

    fun release() {
        main.removeCallbacks(frameWatchdog)
        main.removeCallbacks(startTimeout)
        stopStallWatchdog()
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

        /** 卡顿检测间隔。8 秒读一次播放位置，对 CPU 几乎没有开销。 */
        const val STALL_CHECK_MS = 8000L

        /**
         * 连续几次「位置没前进」才判定卡死。
         *
         * 2 次 = 大约 16 秒。留这个余量是因为网络抖动时位置可能短暂停住
         * 一两秒又继续，太快判定会导致好好的直播被反复重启。
         */
        const val STALL_TICKS_TO_RECOVER = 2

        /** 自动恢复的次数上限。超过就不再重启，直接报错。 */
        const val MAX_STALL_RECOVER = 3
    }
}
