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
    private var videoSurface: android.view.Surface? = null
    private var appCtx: android.content.Context? = null
    /** 承载画面的容器，用来取真实窗口尺寸 */
    private var hostView: android.view.View? = null
    private var videoW = 0
    private var videoH = 0

    override fun isAvailable(context: Context): Boolean = try {
        Class.forName("org.videolan.libvlc.LibVLC")
        true
    } catch (t: Throwable) {
        false
    }

    override fun attach(context: Context, surface: android.view.Surface?, container: ViewGroup?) {
        videoSurface = surface
        appCtx = context.applicationContext

        // Surface 可能**晚于** prepare 就绪（TextureView 要等一次布局）。
        // 那种情况下 prepare 里拿不到 Surface，vlcVout 没挂上，
        // 画面永远出不来。Surface 到达后必须补挂一次。
        if (container != null) hostView = container
        val mp = player
        if (mp != null && surface != null) {
            try {
                mp.vlcVout.setVideoSurface(surface, null)
                mp.vlcVout.attachViews()
                Log.i(TAG, "Surface 晚到，已补挂 VLC 画面")
                syncWindowSize(mp)
            } catch (t: Throwable) {
                Log.w(TAG, "补挂 VLC 画面出错", t)
            }
        }
    }

    /**
     * 把真实窗口尺寸告诉 libVLC。
     *
     * 不告诉它会出事：用裸 Surface 挂载时，VLC 拿不到显示尺寸，
     * 就按**视频原始像素**1:1 画，而且贴在左下角。
     * 实测 1080p 屏幕上播 720p 频道，画面只占 1277x717（屏的 67%），
     * 四周一大片黑——看起来就像"画面没铺满"。
     *
     * setWindowSize 是 libVLC Android 的标准接口，这里用反射调，
     * 是因为不同小版本的方法签名有过变动，反射可以避免版本不匹配时直接崩。
     */
    private fun syncWindowSize(mp: MediaPlayer) {
        val v = hostView ?: return
        val apply = Runnable {
            val w = if (v.width > 0) v.width else 1920
            val h = if (v.height > 0) v.height else 1080
            try {
                val m = mp.vlcVout.javaClass.getMethod("setWindowSize", Int::class.java, Int::class.java)
                m.invoke(mp.vlcVout, w, h)
                Log.i(TAG, "已设置 VLC 窗口尺寸 ${w}x${h}")
            } catch (t: Throwable) {
                Log.w(TAG, "setWindowSize 不可用（忽略）：${t.message}")
            }
        }
        if (v.width > 0) apply.run() else v.post(apply)
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
        val ctx = appCtx ?: run {
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

        videoSurface?.let {
            mp.vlcVout.setVideoSurface(it, null)
            mp.vlcVout.attachViews()
            syncWindowSize(mp)
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
        // 画面比例：auto 交给 VLC 自己按视频宽高比铺；其余强制
        mp.aspectRatio = when (aspect) {
            "16:9" -> "16:9"
            "4:3" -> "4:3"
            "fill" -> "fill"
            else -> null
        }

        player = mp
        mp.play()
    }

    override var aspect: String = "auto"

    override fun play() { player?.play() }
    override fun pause() { player?.pause() }
    override fun stop() { player?.stop() }

    override fun release() = releaseInternal()

    /**
     * 释放播放器，但**保留 LibVLC 实例**。
     *
     * B04：LibVLC 是一个重对象（内含 FFmpeg 解码器、网络栈、音频输出）。
     * 以前 releaseInternal 只释放 MediaPlayer，libVlc 永不放；
     * 而 PlayerManager 每次切内核都 new 一个 VlcEngine，
     * 于是切几次台/内核就攒下几个 LibVLC，最终 OOM。
     *
     * 现在把它明确分成两级：
     *   - release()        ：只放播放器（换台时用），LibVLC 留着复用
     *   - releaseLibVLC()  ：连 LibVLC 一起放（引擎被丢弃时用）
     * 所有权因此清晰：谁创建谁销毁，且不在热路径上反复重建。
     */
    private fun releaseInternal() {
        player?.let {
            try {
                it.stop()
                it.vlcVout.detachViews()
                it.release()
            } catch (t: Throwable) {
                Log.w(TAG, "释放 VLC 播放器出错", t)
            }
        }
        player = null
    }

    /**
     * 彻底释放，包括 LibVLC。
     *
     * 由 PlayerManager 在丢弃本引擎时调用。必须幂等：
     * 重复调用不能崩，也不能把已经放掉的引用再放一次。
     */
    fun releaseLibVLC() {
        releaseInternal()
        libVlc?.let {
            try {
                it.release()
            } catch (t: Throwable) {
                Log.w(TAG, "释放 LibVLC 出错", t)
            }
        }
        libVlc = null
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
