package com.litv.tv.player

/**
 * 播放参数的唯一来源（重构方案 S2）。
 *
 * 为什么要有这一层
 * ----------------
 * 同一个台、同一条源，两个内核的缓冲深度完全不同，而数字散落在四处：
 *   `ExoEngine.prepare`（effBufferMs 的 22 秒地板）、`ExoEngine.buildPlayer`（min/max）、
 *   `VlcEngine.ensureLib`（实例级 800ms）、`VlcEngine.startOnMain`（media 级 bufferMs）、
 *   `MpvEngine.applyOptions`（秒 → 字节映射表）。
 * 没有任何一处代码说得清「正确值是多少」，于是「调参数」只能改一个数、装一次、看十分钟——
 * 既不可比较，也不可回归。
 *
 * 现在所有缓冲数字只在这里出现一次，两条约束：
 *   1. 引擎里禁止再出现裸缓冲数字（要改值改这里，别在引擎里就地加减）；
 *   2. 每次 prepare 打一行「本次生效参数」（[ExoPolicy.describe] 等），真机日志能对上。
 *
 * 本类是**纯逻辑**（不依赖 Android），边界由 `PlaybackPolicyTest` 守住。
 * 数值本身来自真机实测（见 `docs/refactor/播放内核重构方案.md` 与各引擎里的历史注释），
 * 这一片只做「收敛到一处」，**不改变现有取值**——改取值属于 S3/S4，要有同台同源的前后数据。
 */

/** 源类型。决定「缓冲能囤多深」：内置源的 HLS 窗口比自建源短得多。 */
enum class SourceKind(val label: String) {
    /**
     * 内置源（央视频）：经 `LocalHlsProxy` 的 `/ch/` 路径取流。
     * HLS 窗口固定 6 片 × 约 6 秒 ≈ 36 秒，缓冲压不进窗口就会 `BEHIND_LIVE_WINDOW`。
     */
    BUILTIN("内置源"),

    /**
     * 自建源（用户导入）：直连，或经 `/raw/` 代理。
     * 窗口通常 60~100 秒（服务端 remux 窗口 60 秒），可以囤得更深。
     */
    CUSTOM("自建源"),
    ;

    companion object {
        /**
         * 从播放地址判源类型。
         *
         * 判据沿用 ExoEngine 里原来的 `isLocalProxy`：**回环地址且不是 `/raw/`** 才算内置源。
         * 只看 `127.0.0.1` 会把「自建源走本地代理」误判成内置源，用小缓冲去跑慢源——
         * 反而更容易卡（这个坑已踩过一次，见 ExoEngine.prepare 的注释）。
         */
        fun ofUrl(url: String): SourceKind {
            val u = url.lowercase()
            val loopback = u.contains("127.0.0.1") || u.contains("localhost") || u.contains("[::1]")
            return if (loopback && !u.contains("/raw/")) BUILTIN else CUSTOM
        }
    }
}

/**
 * ExoPlayer 的缓冲参数（`DefaultLoadControl.setBufferDurationsMs` 的四个数 + 直播落后量）。
 *
 * @property cfgBufferMs 参与「要不要重建播放器」判定的缓冲设置。自建源有 22 秒地板：
 *   分片间隔接近 10 秒的源用 3 秒缓冲起播就是「攒够即欠载」的循环。
 * @property minBufferMs 往前**保持**多少缓冲，决定抗抖动能力。
 * @property maxBufferMs 缓冲上限。
 * @property bufferForPlaybackMs 起播前至少要多少（决定多久出画面，IPTV 对出画敏感）。
 * @property afterRebufferMs 欠载恢复后要再攒多少。
 * @property targetOffsetMs 主动落后直播边缘多少（HLS live playlist 才生效）。
 */
data class ExoPolicy(
    val cfgBufferMs: Int,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val afterRebufferMs: Int,
    val targetOffsetMs: Long,
) {
    /** 一行「本次生效参数」，写 `play.log` 用。 */
    fun describe(source: SourceKind): String =
        "缓冲参数 min=${minBufferMs}ms max=${maxBufferMs}ms 起播=${bufferForPlaybackMs}ms " +
            "恢复=${afterRebufferMs}ms 落后=${targetOffsetMs}ms 档位=${source.label}"
}

/**
 * libVLC 的缓存参数（`:network-caching` / `:live-caching`）。
 *
 * ⚠ 历史上这两个值同时出现在两处：LibVLC 实例级的 `--network-caching=800` 与
 * media 级的 `:network-caching=<设置值>`。media 级优先级更高，所以实例级那两行
 * 实际一直没生效，只是让人误以为「生效的是 800」。S2 起实例级那两行已删除，
 * 缓存只由本类的值在 media 上设置一次——**唯一来源**。
 */
data class VlcPolicy(val networkCachingMs: Int, val liveCachingMs: Int) {
    fun describe(source: SourceKind): String =
        "缓存参数 network=${networkCachingMs}ms live=${liveCachingMs}ms 档位=${source.label}"
}

/**
 * mpv 的缓冲参数（demuxer 预读）。
 *
 * mpv 的 `demuxer-max-bytes` 是字节上限，不是时长，所以这里保留「秒 → 字节」的映射表。
 */
data class MpvPolicy(
    val readaheadSecs: Int,
    val maxBytesMiB: Int,
    val maxBackBytesMiB: Int,
) {
    fun describe(source: SourceKind): String =
        "缓冲参数 readahead=${readaheadSecs}s max=${maxBytesMiB}MiB back=${maxBackBytesMiB}MiB 档位=${source.label}"
}

object PlaybackPolicy {

    /** 内置源实测窗口：6 片 × 约 6 秒。minBuffer 必须明显小于它的一半。 */
    const val BUILTIN_WINDOW_MS = 36_000

    /** 起播门槛。800ms：300ms 时音频 HAL 在起播 4 秒内破音（真机日志 19 条）。 */
    private const val START_MS = 800

    /** 欠载恢复门槛。1000ms：上游慢是常态，早点恢复比攒满划算（38 次/小时 × 1s）。 */
    private const val AFTER_REBUFFER_MS = 1_000

    /** 自建源的缓冲设置地板：分片间隔接近 10 秒，低于两片就是「攒够即欠载」。 */
    private const val CUSTOM_FLOOR_MS = 22_000

    /**
     * ExoPlayer 的取值。
     *
     * @param bufferMs 设置页的「缓冲」（毫秒），来自 `prefs.bufferMs`。
     * @param liveOffsetMs 设置页的「直播延迟」（毫秒），来自 `prefs.liveOffsetMs`。
     */
    fun exo(source: SourceKind, bufferMs: Int, liveOffsetMs: Int): ExoPolicy {
        val cfg = if (source == SourceKind.BUILTIN) bufferMs else maxOf(bufferMs, CUSTOM_FLOOR_MS)
        // 内置源：14~16 秒 = 2~2.7 个 6 秒分片，抗一次抖动；同时 < 窗口一半（18 秒）。
        // 自建源：20~30 秒覆盖 2~3 个 10 秒分片。
        val min = if (source == SourceKind.BUILTIN) {
            cfg.coerceIn(14_000, 16_000)
        } else {
            cfg.coerceIn(20_000, 30_000)
        }
        // 上限必须**明显小于**直播窗口：内置源窗口 36 秒，上限压到 20 秒；
        // 自建源窗口 60 秒以上，上限 40 秒（再大纯浪费内存，低端盒子还会被 GC 拖住）。
        val max = if (source == SourceKind.BUILTIN) {
            (min + 4_000).coerceIn(min + 2_000, 20_000)
        } else {
            (min + 10_000).coerceIn(min + 2_000, 40_000)
        }
        // 落后量 = targetOffset + minBuffer 是「离直播边缘的真实距离」。
        // 内置源窗口 36 秒：10 + 16 = 26 秒，留 10 秒余量（原来 20 + 14 = 34 秒只剩 2 秒）。
        val offset = liveOffsetMs.coerceIn(4_000, 10_000).toLong()
        return ExoPolicy(cfg, min, max, START_MS, AFTER_REBUFFER_MS, offset)
    }

    /**
     * libVLC 的取值。
     *
     * 夹在 500~20000ms：低于 500 起播就花屏，高于 20 秒对直播没有意义（只是延迟）。
     */
    fun vlc(source: SourceKind, bufferMs: Int): VlcPolicy {
        val c = bufferMs.coerceIn(500, 20_000)
        return VlcPolicy(c, c)
    }

    /**
     * mpv 的取值。秒 → 字节的映射照抄实测表（不动取值）。
     */
    fun mpv(source: SourceKind, bufferMs: Int): MpvPolicy {
        val secs = (bufferMs / 1000).coerceIn(1, 30)
        val maxMb = when {
            secs <= 2 -> 32
            secs <= 4 -> 64
            secs <= 9 -> 128
            else -> 256
        }
        return MpvPolicy(secs, maxMb, (maxMb / 4).coerceAtLeast(4))
    }
}
