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
        val renderers = androidx.media3.exoplayer.DefaultRenderersFactory(ctx)
            .setEnableDecoderFallback(true)

        val p = ExoPlayer.Builder(ctx, renderers)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dsFactory))
            .build()

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
            // 画面比例：让设置里的选项真正生效。
            //
            // auto = 保持比例**裁切铺满**（zoom）：电视上最符合"铺满屏幕"的预期，
            //        代价是 4:3 之类的老片源会切掉上下一点点。
            // fill = 拉伸铺满（会变形，用户明确选了才用）
            // 16:9 / 4:3 = 强制该比例，完整显示（留黑边）
            when (aspect) {
                "fill" -> playerView!!.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                // 16:9 / 4:3：完整显示留黑边。
                // 当前 media3 版本的 PlayerView 没有 setAspectRatio(float)，
                // 无法强制比例，只能保证不裁切。
                "16:9", "4:3" -> playerView!!.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                else -> playerView!!.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
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
        const val UA = "LITV/1.0 (Android TV)"
    }

}
