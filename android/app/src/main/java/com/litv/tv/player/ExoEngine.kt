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
    private var surface: SurfaceView? = null
    private var videoW = 0
    private var videoH = 0

    override fun isAvailable(context: Context): Boolean = true

    override fun attach(context: Context, surfaceView: SurfaceView, container: ViewGroup?) {
        appContext = context.applicationContext
        surface = surfaceView
        player?.setVideoSurfaceView(surfaceView)
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

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8000)
            .setReadTimeoutMs(15000)
        if (headers.isNotEmpty()) httpFactory.setDefaultRequestProperties(headers)

        val dsFactory = DefaultDataSource.Factory(ctx, httpFactory)

        val p = ExoPlayer.Builder(ctx)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dsFactory))
            .build()

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
        surface?.let { p.setVideoSurfaceView(it) }
        p.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
        p.prepare()
        p.playWhenReady = true
    }

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
