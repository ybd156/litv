package com.litv.tv.player

import android.content.Context
import android.view.SurfaceView
import android.view.ViewGroup

/**
 * 播放内核统一抽象。
 *
 * 为什么要有这一层：5 个参考 APK 没有一个只用单内核（mediaplayer 是 mpv+Exo、
 * TVBox 是 ijk+Exo2、酷9 是 IJK+EXO+MPV+VJ 四内核八档）。IPTV 源的编码/封装
 * 千奇百怪，单内核必然有一批台播不了。抽象出来才能"哪个能播用哪个"。
 */
interface PlayerEngine {

    /** 内核标识，写日志和记偏好用。 */
    val id: String

    /** 人类可读名字，给设置页显示。 */
    val displayName: String

    interface Listener {
        fun onPrepared(durationMs: Long)
        fun onPlaying()
        fun onError(message: String)
        fun onVideoSizeChanged(width: Int, height: Int)
        fun onEnded()
    }

    var listener: Listener?

    /** 把渲染目标挂上去。Exo 用 SurfaceView，VLC 需要额外 attachViews。 */
    fun attach(context: Context, surface: android.view.Surface?, container: ViewGroup?)

    /**
     * 准备播放。
     * @param decode auto | hard | soft
     */
    /** auto | 16:9 | 4:3 | fill。由 PlayerManager 在 prepare 前赋值。 */
    var aspect: String

    fun prepare(url: String, headers: Map<String, String>, bufferMs: Int, decode: String)

    fun play()
    fun pause()
    fun stop()
    fun release()

    fun setVolume(volume: Float)

    /** 当前是否已经可以出画面（用于判断"起播成功"）。 */
    fun isPlaying(): Boolean

    /** 内核能力自检：比如 libmpv.so 不存在时返回 false。 */
    fun isAvailable(context: Context): Boolean

    /** 运行时统计，OSD 上显示用。 */
    fun stats(): EngineStats = EngineStats()

    /** 截取当前画面（换台时盖住黑屏用）。返回 null 表示内核不支持。 */
    fun snapshot(): android.graphics.Bitmap? = null
}

/** 播放统计，给 OSD 和"卡顿上报"用。 */
data class EngineStats(
    val videoCodec: String = "",
    val audioCodec: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val fps: Float = 0f,
    val droppedFrames: Int = 0,
    val bitrateKbps: Int = 0,
)

/** 内核类型。 */
enum class EngineKind(val key: String, val label: String) {
    EXO("exo", "系统硬解 (Media3)"),
    MPV("mpv", "libmpv 软解"),
    VLC("vlc", "libVLC"),
    // 注意：这个内核只是 mpv 强制软解，并没有单独集成 libuavs3d。
    // 之前标签写 "AVS3 专用 (libuavs3d)" 是错的，会让人以为 AVS3 视频能解。
    AVS3("avs3", "AVS3 软解 (mpv)");

    companion object {
        fun from(key: String?): EngineKind? = entries.firstOrNull { it.key == key }
    }
}

/**
 * 自动降级链：按"先快后稳"排序。
 *
 * 起播速度：Exo(硬解) > VLC > mpv(软解)
 * 兼容性  ：mpv > VLC > Exo
 *
 * 所以默认顺序是 Exo → VLC → mpv：先赌硬解（快），不行再上兼容性最好的。
 * AVS3 不放进默认链——它只在探测到 avs3 编码时才主动选。
 */
object EngineChain {
    val DEFAULT = listOf(EngineKind.EXO, EngineKind.VLC, EngineKind.MPV)

    /** 用户强制指定内核时，仍然允许它失败后回退到通用链。 */
    fun chainFor(preferred: String?, remembered: String?): List<EngineKind> {
        val forced = EngineKind.from(remembered) ?: EngineKind.from(preferred)
        val base = when (forced) {
            null, EngineKind.EXO -> DEFAULT
            else -> listOf(forced) + DEFAULT.filter { it != forced }
        }
        return base.distinct()
    }
}
