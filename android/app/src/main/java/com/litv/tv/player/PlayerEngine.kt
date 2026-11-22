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
        fun onVideoSizeChanged(width: Int, height: Int, frameRate: Float = -1f)
        fun onEnded()

        /**
         * 解码器本身失败（不是网络/取流问题）。
         *
         * 为什么要和 onError 分开：两者的正确处置完全不同——
         * 网络问题该换线路或换内核，解码器问题该换解码方式（硬解→软解）。
         * 混在一起会出现「硬解失败就去试 VLC，结果 VLC 又用硬解再失败一遍」
         * 这种白绕一圈的情况。
         */
        fun onDecoderFailed(message: String) = onError(message)

        /**
         * Surface 被系统回收（切后台、Activity 重建）。
         *
         * 这不是播放内容的问题，换内核/换解码都没用——上层重接 Surface
         * 或按缓存重新拉流即可恢复。默认退回 onError，避免漏处理。
         */
        fun onSurfaceLost(message: String) = onError(message)

        /**
         * 第一帧真正画到屏幕上。
         *
         * 和 onPlaying 的区别：onPlaying 只表示「开始播了」，
         * 此时画面往往还是黑的。换台时的快照遮罩必须等这一帧再撤，
         * 否则会出现「遮罩消失 → 黑屏 → 画面出来」的突兀跳变。
         */
        fun onFirstFrame() = Unit
    }

    /**
     * 这个内核能否真正上报「第一帧已渲染」。
     *
     * 只有 ExoPlayer 有等价事件（onRenderedFirstFrame）。VLC / mpv 没有——
     * 它们可以正常 Prepared / Playing，但 onFirstFrame 永远不会被调用。
     *
     * 首帧看门狗必须看这个能力位：对不报首帧的内核开看门狗，
     * 等于把一个播得好好的兜底内核在 5 秒后误判成失败并降级掉。
     */
    val reportsFirstFrame: Boolean get() = false

    var listener: Listener?

    /** 把渲染目标挂上去。Exo 用 SurfaceView，VLC 需要额外 attachViews。 */
    fun attach(context: Context, surface: android.view.Surface?, container: ViewGroup?)

    /**
     * Surface 被销毁（SurfaceView 销毁、Activity 重建、切后台）。
     *
     * 必须调用：内核不能继续持有这个 Surface。
     *
     * 为什么这条是必须的（真机崩溃）：libVLC 内部用 Handler 把 Surface 挂到
     * AWindow 上，如果传给它一个**已经销毁**的 Surface，它会在自己的线程里抛
     *   IllegalStateException: surface is not attached and holder is null
     * 这个异常在 libVLC 的 Handler 里抛出，我们外层包 try/catch **抓不到**，
     * 结果是整个播放页崩掉——用户看到的就是"电视无法播放"。
     *
     * 默认空实现：Exo 自己管 PlayerView 的 Surface 生命周期，不需要它。
     */
    fun onSurfaceDestroyed() = Unit

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

    /**
     * 已缓冲到的播放位置（毫秒）。0 表示拿不到。
     *
     * 只用于**起播阶段的“有没有进展”判断**，不参与任何界面显示。
     *
     * 为什么需要它（真机事故 2026-11-21，某 Android 9 电视）：
     * 央视频/IPTV 的 HLS 单分片能到 1.9MB、下载 4-5 秒，首帧自然超过
     * [PlaybackDefaults.START_FRAME_MS]（8 秒）。旧的“有数据无画面”判据只看时间，
     * 到点就判死 → 先切软解（更慢）、再降 VLC，而 VLC 在该源上输出撕裂，
     * 用户看到的是“一卡一卡”。这个值在涨就说明还在正常下载，不该判死。
     */
    fun bufferedMs(): Long = 0L

    /** 内核能力自检：比如 libmpv.so 不存在时返回 false。 */
    fun isAvailable(context: Context): Boolean


    /** 运行时统计，OSD 上显示用。 */
    fun stats(): EngineStats = EngineStats()

    /** 截取当前画面（换台时盖住黑屏用）。返回 null 表示内核不支持。 */
    fun snapshot(): android.graphics.Bitmap? = null
}

/** 播放统计，给 OSD 和"卡顿上报"用。 */
data class EngineStats(
    /**
     * 当前播放位置（毫秒）。-1 表示这个内核拿不到。
     *
     * 用途：卡顿看门狗。直播流静默卡住时不会有任何 error 回调，
     * 只能靠「位置不再前进」来判断。
     */
    val positionMs: Long = -1,
    val videoCodec: String = "",
    val audioCodec: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val fps: Float = 0f,
    val droppedFrames: Int = 0,
    val bitrateKbps: Int = 0,
    /**
     * 是否处于「缓冲中」（不是真卡死）。
     *
     * 用途：[PlayerManager] 的卡顿看门狗。
     * 真机日志里「加载慢 990ms」之后 82 秒被判定卡死 —— 那时候网络是好的，
     * 只是播放器在 BUFFERING、位置暂时不动。把缓冲也当成卡死会导致
     * 「重缓冲 → 判死 → 重建播放器 → 更久才出画」的放大循环。
     */
    val isBuffering: Boolean = false,
)

/** 内核类型。 */
enum class EngineKind(val key: String, val label: String) {
    EXO("exo", "系统硬解 (Media3)"),
    /** ffmpeg 内核。用的是我们自己编的 ffmpeg（--enable-openssl），支持 https。 */
    MPV("mpv", "libmpv 软解"),
    VLC("vlc", "libVLC"),
    // 注意：这个内核只是 mpv 强制软解，并没有单独集成 libuavs3d。
    // 之前标签写 "AVS3 专用 (libuavs3d)" 是错的，会让人以为 AVS3 视频能解。
    AVS3("avs3", "AVS3 软解 (mpv)");

    companion object {
        fun from(key: String?): EngineKind? = entries.firstOrNull { it.key == key }

        /**
         * 设置页「内核」下拉的顺序（TV）：auto 在首位，其余按**降级链**顺序。
         *
         * 为什么不用 `entries` 直接推导：枚举声明顺序是 EXO/MPV/VLC/AVS3，
         * 而界面上一直是 EXO→VLC→MPV（先快后稳），两者不是一回事。
         * 这里显式写一次，替换掉 SettingsActivity 与 PlayerActivity 里各自
         * 重复的 `listOf("auto","exo","vlc","mpv","avs3")`。
         */
        val SELECTABLE: List<String> = listOf("auto", EXO.key, VLC.key, MPV.key, AVS3.key)

        /**
         * 手机端的可选内核：不含 avs3。
         *
         * avs3 那条要用户自己准备解码库，手机界面不暴露这个选项
         * （手机端一直只有 auto/exo/vlc/mpv 四项）。
         */
        val SELECTABLE_PHONE: List<String> = listOf("auto", EXO.key, VLC.key, MPV.key)

        /**
         * 界面上的紧凑名字。
         *
         * 与 [label] 的区别：label 是给人看的长描述（"系统硬解 (Media3)"），
         * 手机播放页那一行放不下，一直用短名。集中在这里，顺带修掉
         * 之前手写映射里的笔误（"AV3S"）。
         */
        fun shortLabel(key: String): String = when (from(key)) {
            EXO -> "Exo"
            VLC -> "VLC"
            MPV -> "mpv"
            AVS3 -> "AVS3"
            null -> "自动"
        }
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
    /**
     * 默认降级链。
     *
     * 顺序变了：以前是 Exo 打头（赌硬解快），现在把 ijk 放前面。
     *
     * 依据是实测 + 拆参考包：IPTV 直播场景下 ffmpeg 内核
     * （ijk / mpv）起播明显快于 ExoPlayer——因为 Exo 播 HLS 必须
     * 等一整个分片下载完，而 ffmpeg 读到几百 KB 就能出画面。
     * 酷9、DIYP 都用 ijk 打头，mediaplayer 直接只用 mpv。
     *
     * 仍然保留 Exo 在链上：ijk 遇到畸形流或解码失败时能立刻降级，
     * 而 Exo 的硬解在部分 Amlogic 盒子上画质更好。
     */
    val DEFAULT = listOf(EngineKind.EXO, EngineKind.VLC, EngineKind.MPV)

    /** 用户强制指定内核时，仍然允许它失败后回退到通用链。 */
    fun chainFor(preferred: String?, remembered: String?): List<EngineKind> {
        // ★ 用户显式选了内核（设置里"播放内核"不是 auto）时，**以它为准**。
        //
        // 以前是 `remembered ?: preferred` —— 按频道记忆优先，
        // 而每个台播过一次就会记下当时的内核，于是设置里怎么改都没用
        // （用户反馈"切换播放器没有作用"就是这个）。
        // 记忆只在 auto 时才有意义：那是"这个台哪个内核能播"的经验值。
        val pref = EngineKind.from(preferred)
        val forced = if (pref != null) pref else EngineKind.from(remembered)
        val base = when (forced) {
            null, EngineKind.EXO -> DEFAULT
            else -> listOf(forced) + DEFAULT.filter { it != forced }
        }
        return base.distinct()
    }
}
