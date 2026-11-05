package com.litv.tv.player

import android.content.Context
import android.util.Log
import android.view.SurfaceView
import android.view.ViewGroup
// 注意：`is` 是 Kotlin 关键字，导入这个包必须用反引号转义
import `is`.xyz.mpv.MPVLib

/**
 * 内核 3/4：libmpv（含 AVS3 模式）。
 *
 * 定位：全格式兜底。ffmpeg 解得了的它就能播——异常 TS 封装、AC3/EAC3、
 * 以及 AVS3（前提是 .so 编译时开了 --enable-libuavs3d）。
 *
 * 播放参数全部来自对生产级 App 的逆向实测（mediaplayer v1.1.25 的 mpv 配置），
 * 不是拍脑袋：`video-latency-hacks=yes` + `probesize=1.5M` 是直播秒开的关键。
 *
 * .so 来源：当前用 mpv-android 官方 release 的预编译库（过渡方案）。
 * 想换成自编的，只需要替换 jniLibs/<abi>/ 下的 .so，本文件不用改。
 */
class MpvEngine(private val avs3Mode: Boolean = false) : PlayerEngine, MPVLib.Listener {

    override val id = if (avs3Mode) EngineKind.AVS3.key else EngineKind.MPV.key
    override val displayName = if (avs3Mode) EngineKind.AVS3.label else EngineKind.MPV.label
    override var listener: PlayerEngine.Listener? = null

    private var created = false
    private var videoSurface: android.view.Surface? = null
    private var appContext: Context? = null
    private var videoW = 0
    private var videoH = 0
    private var fileLoaded = false

    override fun isAvailable(context: Context): Boolean = MPVLib.available


    override fun attach(context: Context, surface: android.view.Surface?, container: ViewGroup?) {
        appContext = context.applicationContext
        videoSurface = surface

        // Surface 可能晚于 prepare 就绪（TextureView 要等一次布局）。
        // prepare 里拿不到 Surface 就不会 attachSurface，画面永远出不来。
        // 只在 mpv 已经创建之后补挂，避免在未初始化时调 native。
        if (surface != null && created) {
            try {
                MPVLib.attachSurface(surface)
                Log.i(TAG, "Surface 晚到，已补挂 mpv 画面")
            } catch (t: Throwable) {
                Log.w(TAG, "补挂 mpv 画面出错", t)
            }
        }
    }

    override fun prepare(url: String, headers: Map<String, String>, bufferMs: Int, decode: String) {
        if (!MPVLib.available) {
            listener?.onError("libmpv 未打包进 APK")
            return
        }
        val ctx = appContext ?: run {
            listener?.onError("mpv 未初始化")
            return
        }

        ensureCreated(ctx)
        MPVLib.listener = this
        fileLoaded = false

        applyOptions(bufferMs, decode, headers)

        videoSurface?.let { MPVLib.attachSurface(it) }
        MPVLib.command(arrayOf("loadfile", url, "replace"))
        MPVLib.setPropertyString("pause", "no")
    }

    /** 只创建一次 mpv 实例，换台只 loadfile，省掉重复初始化的 200~400ms。 */
    private fun ensureCreated(ctx: Context) {
        if (created) return
        MPVLib.create(ctx)
        MPVLib.init()
        // 监听几个关键属性，用来判断起播和取分辨率
        MPVLib.observeProperty("video-params/w", MPVLib.FORMAT_INT64)
        MPVLib.observeProperty("video-params/h", MPVLib.FORMAT_INT64)
        MPVLib.observeProperty("pause", MPVLib.FORMAT_FLAG)
        created = true
    }

    private fun applyOptions(bufferMs: Int, decode: String, headers: Map<String, String>) {
        fun opt(k: String, v: String) = MPVLib.setOptionString(k, v)

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

        // ---- 直播低延迟 ----
        //
        // 这组数值是照着 ijkplayer（酷9/DIYP 用的内核）的实测配置来的：
        //   probesize      1.5MB -> 400KB
        //   analyzeduration 1.5s -> 0.1s
        // ffmpeg 起播前会先"分析"一段流来确定编码参数，默认要好几秒。
        // ijk 把它压到 0.1 秒，代价是极少数畸形流可能识别不出编码——
        // 真遇到会走"分析失败→增大探测"的兜底，不影响正常源。
        opt("profile", "fast")
        opt("video-latency-hacks", "yes")
        opt("demuxer-lavf-o", "probesize=400000,analyzeduration=100000")

        // ---- 同步与掉帧 ----
        opt("vd-lavc-threads", "0")
        opt("video-sync", "audio")
        opt("framedrop", "decoder+vo")
        opt("deinterlace", "yes")

        // ---- 网络 ----
        opt("cache", "yes")
        // cache-pause=yes 会让 mpv 等缓存填到一定量才开始播（默认等 3 秒），
        // 直播场景这 3 秒纯属浪费。ijk 对应的是 packet-buffering=0。
        opt("cache-pause", "no")
        opt("network-timeout", "15")
        opt("rtsp-transport", "tcp")
        opt("user-agent", headers["User-Agent"] ?: "LITV/1.0 (Android)")
        // 画面比例：auto 用 no 让 mpv 按视频自身宽高比
        when (aspect) {
            "16:9" -> opt("video-aspect-override", "16:9")
            "4:3" -> opt("video-aspect-override", "4:3")
            "fill" -> opt("video-aspect-override", "-2")
            else -> opt("video-aspect-override", "no")
        }

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

    override var aspect: String = "auto"

    override fun play() {
        if (MPVLib.available) MPVLib.setPropertyString("pause", "no")
    }

    override fun pause() {
        if (MPVLib.available) MPVLib.setPropertyString("pause", "yes")
    }

    override fun stop() {
        if (MPVLib.available) runCatching { MPVLib.command(arrayOf("stop")) }
    }

    override fun release() {
        if (!MPVLib.available || !created) return
        runCatching {
            MPVLib.listener = null
            MPVLib.detachSurface()
            MPVLib.destroy()
        }
        created = false
    }

    override fun setVolume(volume: Float) {
        if (!MPVLib.available) return
        MPVLib.setPropertyString("volume", (volume.coerceIn(0f, 1f) * 100).toInt().toString())
    }

    override fun isPlaying(): Boolean {
        if (!MPVLib.available) return false
        return runCatching { !MPVLib.getPropertyBoolean("pause") }.getOrDefault(false)
    }

    override fun snapshot(): android.graphics.Bitmap? =
        if (MPVLib.available) runCatching { MPVLib.grabThumbnail() }.getOrNull() else null

    override fun stats(): EngineStats {
        if (!MPVLib.available) return EngineStats()
        return EngineStats(
            positionMs = runCatching { (MPVLib.getPropertyDouble("time-pos") * 1000).toLong() }
                .getOrDefault(-1L),
            videoCodec = runCatching { MPVLib.getPropertyString("video-codec") }.getOrNull() ?: "",
            audioCodec = runCatching { MPVLib.getPropertyString("audio-codec-name") }.getOrNull() ?: "",
            width = videoW,
            height = videoH,
            fps = runCatching { MPVLib.getPropertyDouble("estimated-vf-fps").toFloat() }
                .getOrDefault(0f),
            droppedFrames = runCatching { MPVLib.getPropertyInt("frame-drop-count") }
                .getOrDefault(0),
            bitrateKbps = runCatching { MPVLib.getPropertyInt("video-bitrate") / 1000 }
                .getOrDefault(0),
        )
    }

    // ---------------------------------------------------------------- MPVLib 回调

    override fun onPropertyChanged(name: String, value: Any?) {
        when (name) {
            "video-params/w" -> {
                videoW = (value as? Long)?.toInt() ?: videoW
                if (videoW > 0 && videoH > 0) listener?.onVideoSizeChanged(videoW, videoH)
            }
            "video-params/h" -> {
                videoH = (value as? Long)?.toInt() ?: videoH
                if (videoW > 0 && videoH > 0) listener?.onVideoSizeChanged(videoW, videoH)
            }
            "pause" -> {
                val paused = value as? Boolean ?: return
                if (!paused && fileLoaded) listener?.onPlaying()
            }
        }
    }

    override fun onEvent(eventId: Int) {
        when (eventId) {
            MPVLib.EVENT_FILE_LOADED -> {
                fileLoaded = true
                listener?.onPrepared(0)
            }
            MPVLib.EVENT_END_FILE -> {
                fileLoaded = false
                listener?.onEnded()
            }
        }
    }

    override fun onLog(prefix: String, level: Int, text: String) {
        // level: 0=trace 1=v 2=debug 3=info 4=warn 5=error 6=fatal
        if (level >= 5) Log.w(TAG, "$prefix$text")
        if (level >= 5 && text.contains("error", ignoreCase = true)) {
            listener?.onError(text.trim())
        }
    }

    private companion object {
        const val TAG = "MpvEngine"
    }
}
