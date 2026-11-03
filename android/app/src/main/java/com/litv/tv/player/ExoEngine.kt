package com.litv.tv.player

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout

/**
 * 内核 1：Media3 (ExoPlayer)。
 *
 * 定位：默认内核。走 MediaCodec 硬解，起播最快、功耗最低。
 *
 * 缓冲参数来自对生产级 IPTV App 的逆向实测（mediaplayer v1.1.25）：
 *   直播 setBufferDurationsMs(1000, 60000, 500, 1000)
 *   起播阈值只给 500ms —— 这是「秒开」的关键，给大了用户就觉得卡。
 */
/**
 * 是否强制软解。由 PlayerManager 在检测到硬解出不了帧时置位。
 *
 * Amlogic 盒子上默认的 OMX.amlogic.avc.decoder.awesome 会 "wait timeout"：
 * 数据喂进去了却吐不出帧，画面全黑。置位后所有频道直接用
 * OMX.google.h264.decoder（软解），能出画面但更吃 CPU。
 *
 * 放在顶层而不是 companion：ExoEngine 的 companion 是 private 的，
 * 外部拿不到。
 */
@Volatile
var exoForceSoftwareDecode: Boolean = false

/**
 * 是否已经渲染出第一帧。
 *
 * 关键：不能用 "音频开始播" 当作起播成功——Amlogic 硬解器出不了画面时，
 * 音频照常播放、Exo 也报告 playing，但视频一帧都没有（屏幕全黑）。
 * 只有 onRenderedFirstFrame 才能真正说明"画面上有东西了"。
 */
@Volatile
var exoFirstFrameRendered: Boolean = false

class ExoEngine : PlayerEngine {


    override val id = EngineKind.EXO.key
    override val displayName = EngineKind.EXO.label
    override var listener: PlayerEngine.Listener? = null

    private var player: ExoPlayer? = null
    private var appContext: Context? = null
    private var videoSurface: android.view.Surface? = null
    private var videoW = 0
    private var videoH = 0

    override fun isAvailable(context: Context): Boolean = true

    /** 由 PlayerActivity 注入的 PlayerView（media3-ui），优先用它 */
    var playerView: androidx.media3.ui.PlayerView? = null

    override fun attach(context: Context, surface: android.view.Surface?, container: ViewGroup?) {
        appContext = context.applicationContext
        videoSurface = surface
        player?.let { p ->
            if (playerView != null) playerView!!.player = p else p.setVideoSurface(surface)
        }
    }

    override fun prepare(url: String, headers: Map<String, String>, bufferMs: Int, decode: String) {
        val ctx = appContext ?: run {
            listener?.onError("内核未初始化")
            return
        }
        releaseInternal()

        // 直播缓冲：起播 500ms / 重缓冲 1000ms；maxBufferMs 由用户「网络缓冲」设置映射
        val maxBufferMs = bufferMs.coerceIn(2000, 60000)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1000, maxBufferMs, 500, 1000)
            .setBackBuffer(0, true)                     // 直播不回退缓冲，省内存
            .setPrioritizeTimeOverSizeThresholds(false) // 优先按字节封顶，防低端盒子 OOM
            .build()

        // ★ 源配置里的 UA 必须走 setUserAgent，不能只塞进请求头。
        //   ExoPlayer 的 DefaultHttpDataSource 是「先应用 defaultRequestProperties、
        //   再单独 setRequestProperty("User-Agent", userAgent)」——
        //   写死的 UA 会把请求头里的源 UA 覆盖掉，表现为"硬解时源站校验 UA 失败"。
        val ua = headers["User-Agent"]?.takeIf { it.isNotBlank() } ?: UA
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8000)
            .setReadTimeoutMs(15000)
        // 其余头（Referer 之类）照旧；User-Agent 已经在上面设过了
        val extraHeaders = headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) }
        if (extraHeaders.isNotEmpty()) httpFactory.setDefaultRequestProperties(extraHeaders)

        val dsFactory = DefaultDataSource.Factory(ctx, httpFactory)

        // 关键：硬解失败必须能自动回退到下一个解码器（最终是软解）。
        // 这台电视的 Amlogic AVC 硬解会抛
        //   MediaCodecVideoDecoderException: Decoder failed
        // 不开这个开关 Exo 就直接报错退出，表现为黑屏/闪退。
        // 显式挑解码器。
        //
        // 这台 Amlogic 盒子上，默认选中的 OMX.amlogic.avc.decoder.awesome
        // 会一直报 "wait timeout"，喂了数据也不吐帧（画面全黑），
        // 而网络/取流都正常（代理实测分片 200）。
        // 手机用同一份代码能播，差别只剩"选了哪个 MediaCodec"。
        //
        // 策略：把软解（c2.android / OMX.google）排到前面先试，
        // 硬件解码器排在后面兜底。软解更慢，但至少能出画面。
        val selector = object : androidx.media3.exoplayer.mediacodec.MediaCodecSelector {
            override fun getDecoderInfos(
                mimeType: String,
                requiresSecureDecoder: Boolean,
                requiresTunnelingDecoder: Boolean,
            ): List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> {
                val all = androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT
                    .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                if (!mimeType.startsWith("video/")) return all
                Log.i(TAG, "解码器候选($mimeType): " + all.joinToString(", ") { it.name })
                val soft = all.filter {
                    it.name.contains("c2.android") || it.name.contains("OMX.google") ||
                        it.name.contains(".sw.")
                }
                val hard = all.filterNot { it in soft }
                // 自适应：默认硬解优先（省 CPU、支持 4K）；
                // 一旦检测到硬解出不了帧（PlayerManager 置位），后续全部走软解。
                return if (exoForceSoftwareDecode) soft + hard else hard + soft
            }
        }

        // 用我们自己的工厂：在默认音频渲染器之后补一个 AV3A（有库时才加）
        val renderers = LitvRenderersFactory(ctx)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(selector)

        // 开启 tunneled 播放。
        //
        // Amlogic 的硬件解码器有两条输出路径：普通模式和 tunneled 模式。
        // 实测普通模式下这个解码器会 wait timeout（数据喂进去不吐帧），
        // 但很多盒子的 tunneled 路径是好的——系统播放器走的正是这条路。
        // 解码器不支持 tunneled 时 Media3 会自动回落到普通模式，所以开了不亏。
        val trackSelector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(ctx)
        trackSelector.parameters = trackSelector.buildUponParameters()
            .setTunnelingEnabled(true)
            .build()

        val p = ExoPlayer.Builder(ctx, renderers)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dsFactory))
            .build()

        exoFirstFrameRendered = false
        p.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onRenderedFirstFrame(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                output: Any,
                renderTimeMs: Long,
            ) {
                exoFirstFrameRendered = true
                Log.i(TAG, "视频首帧已渲染")
            }
        })

        player?.setVideoSurface(videoSurface)

        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        listener?.onPrepared(p.duration.coerceAtLeast(0))
                        if (p.playWhenReady) listener?.onPlaying()
                    }
                    Player.STATE_ENDED -> listener?.onEnded()
                    else -> Unit
                }
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                videoW = size.width
                videoH = size.height
                listener?.onVideoSizeChanged(size.width, size.height)
            }

            override fun onPlayerError(error: PlaybackException) {
                val msg = buildString {
                    append(error.errorCodeName)
                    error.cause?.message?.let { append(": ").append(it) }
                }
                Log.w(TAG, "Exo 播放失败: $msg")
                listener?.onError(msg)
            }
        })

        player = p
        // 有 PlayerView 就交给它管 Surface（参考实现就是这么做的），
        // 否则退回直接 setVideoSurface
        if (playerView != null) {
            playerView!!.player = p
            playerView!!.useController = false
            // tunneled 模式下视频走独立硬件图层，PlayerView 默认的黑色
            // 遮罩（shutter）会把它整块盖住——表现为"解码器在解、首帧也
            // 触发了，但屏幕全黑"。必须把遮罩设成透明才能看到画面。
            playerView!!.setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
            // 画面比例：让设置里的选项真正生效。
            //
            // auto = 保持比例**裁切铺满**（zoom）：电视上最符合"铺满屏幕"的预期，
            //        代价是 4:3 之类的老片源会切掉上下一点点。
            // fill = 拉伸铺满（会变形，用户明确选了才用）
            // 16:9 / 4:3 = 强制该比例，完整显示（留黑边）
            when (aspect) {
                // 拉伸铺满：会变形，用户明确选了才用
                "fill" -> playerView!!.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                // 裁切铺满：用户主动选才用。
                // 默认(auto)不能用它——视频尺寸未知时 ZOOM 会把子视图算成 0 尺寸，
                // 表现为"转圈结束后黑屏"。
                "zoom" -> playerView!!.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                // 16:9 / 4:3 / auto：完整显示留黑边。
                // 当前 media3 版本的 PlayerView 没有 setAspectRatio(float)，
                // 无法强制比例，只能保证不裁切。
                else -> playerView!!.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        } else {
            p.setVideoSurface(videoSurface)
        }
        p.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
        p.prepare()
        p.playWhenReady = true
    }

    override var aspect: String = "auto"

    override fun play() { player?.playWhenReady = true }
    override fun pause() { player?.playWhenReady = false }
    override fun stop() { player?.stop() }
    override fun release() = releaseInternal()

    private fun releaseInternal() {
        player?.let {
            it.setVideoSurfaceView(null)
            it.release()
        }
        player = null
    }

    override fun setVolume(volume: Float) {
        player?.volume = volume.coerceIn(0f, 1f)
    }

    override fun isPlaying(): Boolean = player?.isPlaying == true

    override fun stats(): EngineStats {
        val p = player ?: return EngineStats()
        val vf = p.videoFormat
        return EngineStats(
            videoCodec = vf?.codecs ?: "",
            audioCodec = p.audioFormat?.codecs ?: "",
            width = videoW,
            height = videoH,
            fps = vf?.frameRate ?: 0f,
            bitrateKbps = ((vf?.bitrate ?: 0) / 1000),
        )
    }

    private companion object {
        const val TAG = "ExoEngine"
        const val UA = "LITV/1.0 (Android)"
    }

}
