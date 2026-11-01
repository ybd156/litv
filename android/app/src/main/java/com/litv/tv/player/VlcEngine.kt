package com.litv.tv.player

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.SurfaceView
import android.view.ViewGroup
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.util.ArrayList

/**
 * 内核 2：libVLC。
 *
 * 定位：兼容性兜底。运营商 TS 流、异常封装、非标音频，VLC 的容错比 Exo 强很多。
 * 代价：起播比硬解慢（约 0.3~0.8s），包体 +约 20MB。
 *
 * 用 Maven 上的预编译包（org.videolan.android:libvlc-all），
 * 因为自己编 libVLC 要 macOS + 一整套工具链，性价比太低。
 */
class VlcEngine : PlayerEngine {

    override val id = EngineKind.VLC.key
    override val displayName = EngineKind.VLC.label
    override var listener: PlayerEngine.Listener? = null

    private var libVlc: LibVLC? = null
    private var player: MediaPlayer? = null
    private var surface: SurfaceView? = null
    private var videoW = 0
    private var videoH = 0

    override fun isAvailable(context: Context): Boolean = try {
        Class.forName("org.videolan.libvlc.LibVLC")
        true
    } catch (t: Throwable) {
        false
    }

    override fun attach(context: Context, surfaceView: SurfaceView, container: ViewGroup?) {
        surface = surfaceView
    }

    private fun ensureLib(context: Context): LibVLC {
        libVlc?.let { return it }
        // 直播低延迟参数：不丢帧、不跳帧、网络缓冲给足
        val args = ArrayList<String>().apply {
            add("--no-drop-late-frames")
            add("--no-skip-frames")
            add("--avcodec-hw=any")          // 尽量硬解
            add("--network-caching=1500")    // 直播缓冲（毫秒）
            add("--live-caching=1500")
            add("--no-audio-time-stretch")
            add("-vvv")
        }
        return LibVLC(context.applicationContext, args).also { libVlc = it }
    }

    override fun prepare(url: String, headers: Map<String, String>, bufferMs: Int, decode: String) {
        val ctx = surface?.context ?: run {
            listener?.onError("VLC 未绑定 Surface")
            return
        }
        releaseInternal()

        val lib = ensureLib(ctx)
        val mp = MediaPlayer(lib)

        mp.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> listener?.onPlaying()
                MediaPlayer.Event.EndReached -> listener?.onEnded()
                MediaPlayer.Event.EncounteredError -> listener?.onError("VLC 解码/网络错误")
                // libVLC 3.5.1 的 MediaPlayer 没有公开 videoSize API，
                // 画面尺寸交给上层按 Surface 比例处理，这里不做上报。
                else -> Unit
            }
        }

        surface?.let {
            mp.vlcVout.setVideoView(it)
            mp.vlcVout.attachViews()
        }

        val media = Media(lib, Uri.parse(url)).apply {
            // VLC 的网络缓冲：把用户设置映射成毫秒
            setHWDecoderEnabled(decode != "soft", false)
            addOption(":network-caching=${bufferMs.coerceIn(500, 20000)}")
            addOption(":live-caching=${bufferMs.coerceIn(500, 20000)}")
            addOption(":clock-jitter=0")
            addOption(":clock-synchro=0")
            if (headers.isNotEmpty()) {
                val sb = StringBuilder()
                headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
                addOption(":http-referrer=${headers["Referer"] ?: ""}")
                addOption(":http-user-agent=${headers["User-Agent"] ?: "LITV/1.0"}")
            }
        }
        mp.media = media
        media.release()

        player = mp
        mp.play()
    }

    override fun play() { player?.play() }
    override fun pause() { player?.pause() }
    override fun stop() { player?.stop() }

    override fun release() = releaseInternal()

    private fun releaseInternal() {
        player?.let {
            try {
                it.stop()
                it.vlcVout.detachViews()
                it.release()
            } catch (t: Throwable) {
                Log.w(TAG, "释放 VLC 出错", t)
            }
        }
        player = null
    }

    override fun setVolume(volume: Float) {
        player?.volume = (volume.coerceIn(0f, 1f) * 100).toInt()
    }

    override fun isPlaying(): Boolean = player?.isPlaying == true

    override fun stats(): EngineStats = EngineStats(width = videoW, height = videoH)

    private companion object {
        const val TAG = "VlcEngine"
    }
}
