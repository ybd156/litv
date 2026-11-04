package com.litv.tv.player

import android.content.Context
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.ViewGroup
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import tv.danmaku.ijk.media.player.IMediaPlayer

/**
 * 内核：ijkplayer（B 站基于 ffmpeg 的播放器）。
 *
 * 为什么加这个内核——拆了三个参考 APK 得到的结论：
 *
 * | APK                    | 内核        |
 * |------------------------|-------------|
 * | mediaplayer-v1.1.28    | mpv         |
 * | 酷9原版 2.2.21         | ijkplayer   |
 * | 配套_DIYP              | ijkplayer   |
 *
 * 三个都是 **ffmpeg 内核**，而且都用同一组参数把「起播前的流分析」压到 0.1 秒。
 * 这是它们能做到"打开就出画面"的根本原因，不是玄学优化。
 *
 * 对比我们原来的主力 ExoPlayer：HLS 必须「拉 m3u8 → 拉完一整个分片 → 解析」，
 * 而 ffmpeg 只要读到几百 KB 就能开始解码。结构上的差距，调参补不回来。
 *
 * 代价：包体 +约 12MB（3 个 ABI），且 ffmpeg 内核在低端盒子上软解更吃 CPU。
 * 所以它只进默认链的前排用于直播，出问题仍会降级到 Exo/VLC。
 */
class IjkEngine : PlayerEngine {

    override val id = EngineKind.IJK.key
    override val displayName = EngineKind.IJK.label
    override var listener: PlayerEngine.Listener? = null
    override var aspect: String = "auto"

    private var player: IjkMediaPlayer? = null
    private var videoSurface: Surface? = null
    private var appCtx: Context? = null
    private var videoW = 0
    private var videoH = 0
    private var prepared = false
    private var firstFrameReported = false

    companion object {
        private const val TAG = "IjkEngine"

        /**
         * 原生库只加载一次。
         *
         * 必须自己 try-catch：某些设备（尤其模拟器缺 x86 包时）加载会抛，
         * 抛了就不能再声称内核可用，否则上层会一直派活给它然后全失败。
         */
        @Volatile
        private var libsState = 0   // 0=未试 1=成功 -1=失败

        /** 给启动日志用：只探测原生库能不能加载，不创建播放器。 */
        fun probe(): Boolean = loadLibsOnce()

        @Synchronized
        private fun loadLibsOnce(): Boolean {
            if (libsState != 0) return libsState > 0
            libsState = try {
                IjkMediaPlayer.loadLibrariesOnce(null)
                IjkMediaPlayer.native_profileBegin("libijkplayer.so")
                1
            } catch (t: Throwable) {
                Log.w(TAG, "ijkplayer 原生库加载失败（这个 ABI 可能没打包）", t)
                -1
            }
            return libsState > 0
        }
    }

    override fun isAvailable(context: Context): Boolean = loadLibsOnce()


    override fun attach(context: Context, surface: Surface?, container: ViewGroup?) {
        appCtx = context.applicationContext
        videoSurface = surface
        // Surface 可能晚于 prepare 到达，到了就补挂
        player?.let { p ->
            if (surface != null) {
                runCatching { p.setSurface(surface) }
                    .onFailure { Log.w(TAG, "补挂 Surface 失败", it) }
            }
        }
    }

    override fun prepare(url: String, headers: Map<String, String>, bufferMs: Int, decode: String) {
        val ctx = appCtx ?: run {
            listener?.onError("内核未初始化")
            return
        }
        if (!loadLibsOnce()) {
            listener?.onError("ijkplayer 原生库不可用")
            return
        }
        releaseInternal()

        val p = IjkMediaPlayer()
        player = p
        prepared = false
        firstFrameReported = false

        // ---- 关键：起播低延迟参数（照抄 ijk 官方推荐 + 参考 APK 的取值）----
        //
        // analyzeduration 默认 5 秒、probesize 默认 5MB——ffmpeg 起播前要
        // "分析"这么久的流才知道编码参数。压到 0.1 秒 / 400KB 后基本瞬间开始解码。
        // 代价：极少数畸形流可能识别不出编码，那就走降级换内核。
        setOpt(p, IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 100000L)
        setOpt(p, IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 409600L)
        setOpt(p, IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fflags", "nobuffer")
        setOpt(p, IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1L)
        // 直播无限缓冲，避免 ffmpeg 因为"读完了"而停
        setOpt(p, IjkMediaPlayer.OPT_CATEGORY_PLAYER, "infbuf", 1L)
        // 不等缓冲填满就开播（对应 mpv 的 cache-pause=no）
        setOpt(p, IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 0L)
        setOpt(p, IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1L)
        setOpt(p, IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 1L)

        // 缓冲设置：用户「网络缓冲」映射成毫秒
        val bufMs = bufferMs.coerceIn(300, 20000)
        setOpt(p, IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size", (bufMs.toLong() * 256))
        setOpt(p, IjkMediaPlayer.OPT_CATEGORY_FORMAT, "buffer_size", bufMs.toLong())

        // 硬解：auto/hard 开 mediacodec，soft 关掉
        val useHw = decode != "soft"
        setOpt(p, IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", if (useHw) 1L else 0L)
        setOpt(p, IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1L)
        setOpt(p, IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1L)

        // 请求头：ijk 用 format 分类下的 headers，格式是 "K: V\r\n"
        if (headers.isNotEmpty()) {
            val sb = StringBuilder()
            headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
            setOpt(p, IjkMediaPlayer.OPT_CATEGORY_FORMAT, "headers", sb.toString())
        }

        p.setOnPreparedListener(object : IMediaPlayer.OnPreparedListener {
            override fun onPrepared(mp: IMediaPlayer) {
                prepared = true
                videoW = mp.videoWidth
                videoH = mp.videoHeight
                listener?.onPrepared(0)
                listener?.onVideoSizeChanged(mp.videoWidth, mp.videoHeight)
                mp.start()
            }
        })

        p.setOnInfoListener(object : IMediaPlayer.OnInfoListener {
            override fun onInfo(mp: IMediaPlayer, what: Int, extra: Int): Boolean {
                when (what) {
                    // 首帧真正渲染出来了——换台遮罩要等这一刻再撤
                    IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> {
                        if (!firstFrameReported) {
                            firstFrameReported = true
                            Log.i(TAG, "视频首帧已渲染")
                            listener?.onPlaying()
                            listener?.onFirstFrame()
                        }
                    }
                    IMediaPlayer.MEDIA_INFO_BUFFERING_END -> listener?.onPlaying()
                }
                return false
            }
        })

        // ijk 这个回调有 5 个参数：(mp, width, height, sarNum, sarDen)
        p.setOnVideoSizeChangedListener { _, w, h, _, _ ->
            videoW = w
            videoH = h
            listener?.onVideoSizeChanged(w, h)
        }

        p.setOnErrorListener { _, what, extra ->
            Log.w(TAG, "ijk 播放失败 what=$what extra=$extra")
            prepared = false
            listener?.onError("ijkplayer 错误 what=$what extra=$extra")
            true
        }

        p.setOnCompletionListener {
            prepared = false
            listener?.onEnded()
        }

        videoSurface?.let { runCatching { p.setSurface(it) } }

        runCatching {
            p.setDataSource(url)
            p.prepareAsync()
        }.onFailure {
            Log.w(TAG, "ijk prepare 失败", it)
            listener?.onError("ijkplayer 起播失败：${it.message}")
        }
    }

    private fun setOpt(p: IjkMediaPlayer, cat: Int, name: String, v: Long) {
        runCatching { p.setOption(cat, name, v) }
    }

    private fun setOpt(p: IjkMediaPlayer, cat: Int, name: String, v: String) {
        runCatching { p.setOption(cat, name, v) }
    }

    override fun play() {
        runCatching { player?.start() }
    }

    override fun pause() {
        runCatching { player?.pause() }
    }

    override fun stop() {
        prepared = false
        runCatching { player?.stop() }
    }

    override fun release() {
        releaseInternal()
    }

    private fun releaseInternal() {
        val p = player ?: return
        player = null
        prepared = false
        runCatching {
            p.setOnPreparedListener(null)
            p.setOnInfoListener(null)
            p.setOnErrorListener(null)
            p.setOnCompletionListener(null)
            p.setOnVideoSizeChangedListener(null)
            p.reset()
            p.release()
        }
    }

    override fun setVolume(volume: Float) {
        runCatching { player?.setVolume(volume, volume) }
    }

    override fun isPlaying(): Boolean = prepared

    override fun stats(): EngineStats = EngineStats(
        videoCodec = "",
        audioCodec = "",
        width = videoW,
        height = videoH,
    )
}
