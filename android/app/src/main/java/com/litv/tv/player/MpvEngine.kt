package com.litv.tv.player

import android.content.Context
import android.util.Log
import android.view.SurfaceView
import android.view.ViewGroup
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 内核 3/4：libmpv（含 AVS3 模式）。
 *
 * 定位：全格式兜底。ffmpeg 解得了的它就能播——异常 TS 封装、AC3/EAC3、
 * 以及 AVS3（前提是 .so 编译时开了 --enable-libuavs3d）。
 *
 * 播放参数全部来自对生产级 App 的逆向实测（mediaplayer v1.1.25 的 mpv 配置），
 * 不是拍脑袋：`video-latency-hacks=yes` + `probesize=1.5M` 是直播秒开的关键。
 */
class MpvEngine(private val avs3Mode: Boolean = false) : PlayerEngine {

    override val id = if (avs3Mode) EngineKind.AVS3.key else EngineKind.MPV.key
    override val displayName = if (avs3Mode) EngineKind.AVS3.label else EngineKind.MPV.label
    override var listener: PlayerEngine.Listener? = null

    private var created = false
    private var surface: SurfaceView? = null
    private var appContext: Context? = null
    private val eventThreadStarted = AtomicBoolean(false)
    private var videoW = 0
    private var videoH = 0

    override fun isAvailable(context: Context): Boolean = MpvNative.available

    override fun attach(context: Context, surfaceView: SurfaceView, container: ViewGroup?) {
        appContext = context.applicationContext
        surface = surfaceView
    }

    override fun prepare(url: String, headers: Map<String, String>, bufferMs: Int, decode: String) {
        if (!MpvNative.available) {
            listener?.onError("libmpv 未编译（native/build-libmpv.sh）")
            return
        }
        val ctx = appContext ?: surface?.context ?: run {
            listener?.onError("mpv 未初始化")
            return
        }

        ensureCreated(ctx)
        applyOptions(bufferMs, decode, headers)

        surface?.let { MpvNative.attachSurface(it.holder.surface) }
        MpvNative.command(arrayOf("loadfile", url, "replace"))
        MpvNative.setPropertyString("pause", "no")
    }

    /** 只创建一次 mpv 实例，换台只 loadfile，省掉重复初始化的 200~400ms。 */
    private fun ensureCreated(ctx: Context) {
        if (created) return
        MpvNative.create(ctx)
        MpvNative.init()
        created = true
        startEventLoop()
    }

    private fun startEventLoop() {
        if (!eventThreadStarted.compareAndSet(false, true)) return
        Thread({
            try {
                MpvNative.eventLoop()
            } catch (t: Throwable) {
                Log.w(TAG, "mpv 事件循环退出", t)
            }
        }, "mpv-event").apply { isDaemon = true }.start()
    }

    private fun applyOptions(bufferMs: Int, decode: String, headers: Map<String, String>) {
        fun opt(k: String, v: String) = MpvNative.setOptionString(k, v)

        // ---- 硬解策略 ----
        // 硬解：mediacodec 优先，失败自动回落到 mediacodec-copy（兼容性换画质）
        // 软解 / AVS3：AVS3 没有硬件解码器，必然软解
        val hwdec = when {
            avs3Mode -> "no"
            decode == "soft" -> "no"
            else -> "mediacodec,mediacodec-copy,auto"
        }
        opt("hwdec", hwdec)
        opt("hwdec-codecs", "all")

        // ---- 渲染 ----
        opt("vo", "gpu")
        opt("gpu-context", "android")
        opt("opengl-es", "yes")
        opt("ao", "audiotrack,opensles")
        opt("audio-set-media-role", "yes")

        // ---- 直播低延迟（关键三连）----
        opt("profile", "fast")
        opt("video-latency-hacks", "yes")
        opt("demuxer-lavf-o", "probesize=1500000,analyzeduration=1500000")

        // ---- 同步与掉帧 ----
        opt("vd-lavc-threads", "0")     // 0 = 按 CPU 核数自动
        opt("video-sync", "audio")      // 以音频为基准，直播最稳
        opt("framedrop", "decoder+vo")  // 卡顿时优先丢帧保流畅
        opt("deinterlace", "yes")       // 国内不少源是隔行扫描

        // ---- 网络 ----
        opt("cache", "yes")
        opt("cache-pause", "yes")
        opt("network-timeout", "15")
        opt("tls-verify", "no")         // 直播源证书普遍不规范，不关会一片连不上
        opt("rtsp-transport", "tcp")
        opt("user-agent", headers["User-Agent"] ?: "LITV/1.0 (Android TV)")

        // ---- 缓冲：秒数 → 字节（照抄实测映射表）----
        val secs = (bufferMs / 1000).coerceIn(1, 30)
        val maxBytesMB = when {
            secs <= 2 -> 32
            secs <= 4 -> 64
            secs <= 9 -> 128
            else -> 256
        }
        opt("demuxer-max-bytes", "${maxBytesMB}MiB")
        opt("demuxer-max-back-bytes", "${(maxBytesMB / 4).coerceAtLeast(4)}MiB")
        opt("demuxer-readahead-secs", secs.coerceAtLeast(1).toString())

        // ---- 自定义请求头 ----
        if (headers.isNotEmpty()) {
            val joined = headers.entries.joinToString(",") { "${it.key}: ${it.value}" }
            opt("http-header-fields", joined)
        }
    }

    override fun play() {
        if (!MpvNative.available) return
        MpvNative.setPropertyString("pause", "no")
    }

    override fun pause() {
        if (!MpvNative.available) return
        MpvNative.setPropertyString("pause", "yes")
    }

    override fun stop() {
        if (!MpvNative.available) return
        runCatching { MpvNative.command(arrayOf("stop")) }
    }

    override fun release() {
        if (!MpvNative.available || !created) return
        runCatching {
            MpvNative.detachSurface()
            MpvNative.destroy()
        }
        created = false
    }

    override fun setVolume(volume: Float) {
        if (!MpvNative.available) return
        MpvNative.setPropertyString("volume", (volume.coerceIn(0f, 1f) * 100).toInt().toString())
    }

    override fun isPlaying(): Boolean {
        if (!MpvNative.available) return false
        return runCatching { MpvNative.getPropertyString("pause") == "no" }.getOrDefault(false)
    }

    override fun snapshot(): android.graphics.Bitmap? =
        if (MpvNative.available) runCatching { MpvNative.grabThumbnail() }.getOrNull() else null

    override fun stats(): EngineStats {
        if (!MpvNative.available) return EngineStats()
        return EngineStats(
            videoCodec = runCatching { MpvNative.getPropertyString("video-codec") }.getOrNull() ?: "",
            audioCodec = runCatching { MpvNative.getPropertyString("audio-codec-name") }.getOrNull() ?: "",
            width = videoW,
            height = videoH,
            fps = runCatching { MpvNative.getPropertyString("estimated-vf-fps")?.toFloatOrNull() }
                .getOrNull() ?: 0f,
            droppedFrames = runCatching { MpvNative.getPropertyInt("frame-drop-count") }
                .getOrDefault(0),
            bitrateKbps = runCatching { MpvNative.getPropertyInt("video-bitrate") / 1000 }
                .getOrDefault(0),
        )
    }

    companion object {
        private const val TAG = "MpvEngine"

        /** 当前活跃实例，供 native 回调转发事件。 */
        @Volatile
        private var active: MpvEngine? = null

        fun register(e: MpvEngine) { active = e }

        fun onProperty(property: String) {
            val e = active ?: return
            when (property) {
                "video-params/w", "width" -> {
                    e.videoW = runCatching { MpvNative.getPropertyInt("width") }.getOrDefault(0)
                    e.videoH = runCatching { MpvNative.getPropertyInt("height") }.getOrDefault(0)
                    if (e.videoW > 0) e.listener?.onVideoSizeChanged(e.videoW, e.videoH)
                }
                "pause" -> if (e.isPlaying()) e.listener?.onPlaying()
            }
        }

        fun onEvent(eventId: Int) {
            val e = active ?: return
            when (eventId) {
                MpvNative.EVENT_FILE_LOADED -> e.listener?.onPrepared(0)
                MpvNative.EVENT_END_FILE -> e.listener?.onEnded()
            }
        }
    }

    init {
        register(this)
    }
}
