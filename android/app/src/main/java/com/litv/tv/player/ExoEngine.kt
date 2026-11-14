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
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import com.litv.tv.data.DohDns

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

/**
 * 是否启用 tunneled 播放。**只在电视端开启**。
 *
 * 为什么：tunneled 是把解码后的画面交给独立硬件图层显示，
 * 某些 Amlogic 电视的硬解器只有这个模式能出画面（普通模式 wait timeout）。
 *
 * 但它在手机上有副作用：tunneled 图层和 Surface 强绑定，
 * 切 Tab / 去设置页回来时 Surface 是新建的，播放器会卡在旧 Surface 的
 * 缓冲队列上（日志刷 BufferQueueProducer dequeueBuffer），画面全黑。
 * 手机不需要 tunneled，关掉即可。
 */
@Volatile
var exoEnableTunneling: Boolean = true

class ExoEngine : PlayerEngine {


    /** ExoPlayer 有 onRenderedFirstFrame，能真正上报首帧。 */
    override val reportsFirstFrame = true

    override val id = EngineKind.EXO.key
    override val displayName = EngineKind.EXO.label
    override var listener: PlayerEngine.Listener? = null

    private var player: ExoPlayer? = null

    /**
     * 当前播放器的构建配置指纹。
     * 换台时如果它没变，就复用播放器而不是销毁重建（省约 0.7 秒）。
     */
    private var builtConfig: String? = null

    /** 当前频道的 UA / 请求头。复用的播放器每次建连接时现读这两个值。 */
    @Volatile private var curUa: String = UA
    @Volatile private var curHeaders: Map<String, String> = emptyMap()

    /**
     * 动态 DataSource 工厂。
     *
     * 播放器复用之后，工厂是在第一次 build 时创建的，而请求头每个频道都不同。
     * 所以不能在 build 时把 headers 写死——必须在每次 createDataSource()
     * 时读当前的 curUa / curHeaders。
     */
    private val dynamicHttpFactory: androidx.media3.datasource.HttpDataSource.Factory =
        object : androidx.media3.datasource.HttpDataSource.Factory {
            override fun createDataSource(): androidx.media3.datasource.HttpDataSource {
                val extra = curHeaders.filterKeys { !it.equals("User-Agent", ignoreCase = true) }

                // DoH 开启时改走 OkHttp 数据源：只有 OkHttp 的 Dns 是我们可以替换的
                // （Media3 自带的 DefaultHttpDataSource 直接用系统解析，插不进去）。
                // 关闭 DoH 时保持原路径，行为与旧版逐字节一致。
                if (DohDns.isEnabled()) {
                    val okF = OkHttpDataSource.Factory(DohDns.client())
                    // ★ 源配置里的 UA 必须走 setUserAgent：OkHttp 数据源会用它构造
                    //   Request，塞进 headers 会被 OkHttp 的 User-Agent 覆盖掉。
                    okF.setUserAgent(curUa)
                    if (extra.isNotEmpty()) okF.setDefaultRequestProperties(extra)
                    return okF.createDataSource()
                }

                // ★ 源配置里的 UA 必须走 setUserAgent，不能只塞进请求头。
                //   DefaultHttpDataSource 是「先应用 defaultRequestProperties、
                //   再单独 setRequestProperty("User-Agent", userAgent)」——
                //   写死的 UA 会把源 UA 覆盖掉，表现为源站校验 UA 失败。
                val f = DefaultHttpDataSource.Factory()
                    .setUserAgent(curUa)
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(8000)
                    .setReadTimeoutMs(15000)
                if (extra.isNotEmpty()) f.setDefaultRequestProperties(extra)
                return f.createDataSource()
            }

            override fun setDefaultRequestProperties(
                properties: Map<String, String>,
            ): androidx.media3.datasource.HttpDataSource.Factory = this
        }

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
            // 无论走 playerView 还是裸 SurfaceView，都必须显式设置 surface。
            // 之前只有 playerView == null 时才 setVideoSurface，
            // 结果传 null 解绑时旧 Surface 一直留在渲染器里，
            // Surface 被系统销毁后就抛 "The surface has been released"。
            runCatching { p.setVideoSurface(surface) }
            playerView?.player = p
        }
    }

    override fun prepare(url: String, headers: Map<String, String>, bufferMs: Int, decode: String) {
        val ctx = appContext ?: run {
            listener?.onError("内核未初始化")
            return
        }
        // 更新当前频道的请求头。播放器复用后，DataSource 工厂会在每次
        // 建连接时现读这两个字段，所以必须先写进去。
        curUa = headers["User-Agent"]?.takeIf { it.isNotBlank() } ?: UA
        curHeaders = headers

        // 换台时复用播放器实例。
        //
        // 实测（x86 模拟器，缓存命中）换台总耗时 2.65s，拆开看：
        //   Release 旧实例 + Init 新实例   约 0.72s   ← 纯浪费
        //   拉 m3u8 + 分片 + 解码           约 1.7s
        // 只要解码方式 / 缓冲 / 隧道模式没变，就没有理由重建播放器。
        val cfg = "$decode|$bufferMs|$exoEnableTunneling|${Av3aLoader.available(ctx)}"
        val existing = player
        val p: ExoPlayer
        if (existing != null && builtConfig == cfg) {
            Log.i(TAG, "复用播放器换台（省掉 ~0.7s 重建开销）")
            runCatching {
                existing.stop()
                existing.clearMediaItems()
            }
            p = existing
        } else {
            releaseInternal()
            p = buildPlayer(ctx, decode, bufferMs)
            player = p
            builtConfig = cfg
        }

        exoFirstFrameRendered = false

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
        // 直播延迟：让播放器主动落后直播边缘（默认 20 秒）。
        //
        // 贴着边缘跑时，解码慢一点或网络抖一下就掉出 HLS 窗口
        // （ERROR_CODE_BEHIND_LIVE_WINDOW），表现是"看着看着卡一下"。
        // 主动落后等于把缓冲垫厚——代价是画面比真实直播晚几秒，
        // 换来的是不再频繁 seek 重建解码器。
        //
        // targetOffset 只在 HLS 的直播 playlist 上生效；VOD / 点播没有影响。
        // 服务器分片窗口不够长时会退化成"贴着边缘"，不会报错。
        val offsetMs = com.litv.tv.data.Prefs(ctx).liveOffsetMs.toLong()
        val liveCfg = androidx.media3.common.MediaItem.LiveConfiguration.Builder()
            .setTargetOffsetMs(offsetMs)
            // 不追帧：速度快了会主动丢帧，直播观感更差
            .setMinPlaybackSpeed(1.0f)
            .setMaxPlaybackSpeed(1.0f)
            .build()
        p.setMediaItem(
            MediaItem.Builder()
                .setUri(Uri.parse(url))
                .setLiveConfiguration(liveCfg)
                .build()
        )
        p.prepare()
        p.playWhenReady = true
    }

    /**
     * 建一个全新的播放器。只在配置（解码方式/缓冲/隧道）变化时调用。
     *
     * 监听器也在这里注册——它们挂在播放器实例上，
     * 如果放在 prepare 里，复用时会重复注册，同一个事件回调多次。
     */
    private fun buildPlayer(
        ctx: android.content.Context,
        decode: String,
        bufferMs: Int,
    ): ExoPlayer {
        // 直播缓冲。
        //
        // 参数含义（ExoPlayer 的 LoadControl）：
        //   minBufferMs  往前**保持**缓冲多少 -> 决定抗抖动能力
        //   maxBufferMs  缓冲上限
        //   bufferForPlaybackMs              起播前至少要有多少 -> 决定"多久出画面"
        //   afterRebufferMs                  欠载恢复后至少要有多少
        //
        // 原来 minBuffer=800ms：播放器只往前留 0.8 秒，CDN 稍微抖一下就欠载，
        // 表现就是"播一会儿卡一下"。起播速度由 bufferForPlaybackMs 决定，
        // 和 minBuffer 无关，所以这里把 minBuffer 放到 5 秒换取抗抖动，
        // 起播阈值仍保持 300ms（IPTV 对出画时间敏感）。
        // 往前保持 10 秒缓冲（用户指定）：CDN 抖一下不至于立刻欠载。
        // 上限至少要比它大，否则 ExoPlayer 会报参数非法。
        val minBufferMs = 10_000
        val maxBufferMs = maxOf(bufferMs.coerceIn(2000, 60_000), minBufferMs + 2_000)
        // bufferForPlayback 从 300 提到 800：
        // 实测音频 HAL 的欠载告警全部集中在起播那 4 秒（19 条在 20:42:49~53，
        // 之后 55 秒安静）。300ms 就开播时管线还没填满，第一秒最容易破音。
        // 起播慢 0.5 秒，换掉开头那一段电音。
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBufferMs, maxBufferMs, 800, 2000)
            .setBackBuffer(0, true)                     // 直播不回退缓冲，省内存
            .setPrioritizeTimeOverSizeThresholds(false) // 优先按字节封顶，防低端盒子 OOM
            .build()

        // ★ 源配置里的 UA 必须走 setUserAgent，不能只塞进请求头。
        //   ExoPlayer 的 DefaultHttpDataSource 是「先应用 defaultRequestProperties、
        //   再单独 setRequestProperty("User-Agent", userAgent)」——
        //   写死的 UA 会把请求头里的源 UA 覆盖掉，表现为"硬解时源站校验 UA 失败"。
        // 用动态工厂：播放器会被复用，而请求头每个频道都不一样，
        // 不能在 build 时写死。
        val dsFactory = DefaultDataSource.Factory(ctx, dynamicHttpFactory)

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
            .setTunnelingEnabled(exoEnableTunneling)
            .build()

        val p = ExoPlayer.Builder(ctx, renderers)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            // 媒体源工厂：
            // 只有真正加载到 AV3A 解码库时才换成我们的实现（它给 HLS 挂上
            // 自定义解析器）；**没有库时用官方 DefaultMediaSourceFactory**。
            //
            // 为什么不在没库时也用自己的：官方那个还负责 DASH / RTSP / DRM /
            // 错误重试策略等一堆东西，我的简化版只认 HLS 和渐进式。
            // 之前无条件替换，等于把所有非 HLS 频道和一堆边缘逻辑都关了，
            // 直连播放因此拿不到轨道——这类回归在 CI 里永远发现不了。
            .setMediaSourceFactory(
                if (Av3aLoader.available(ctx)) {
                    LitvMediaSourceFactory(dsFactory, true)
                } else {
                    DefaultMediaSourceFactory(dsFactory)
                }
            )
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
                listener?.onFirstFrame()
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

                // 播放位置掉到直播窗口之后：**这是可恢复的**，不是失败。
                //
                // ExoPlayer 官方语义：ERROR_CODE_BEHIND_LIVE_WINDOW 表示
                // 播放位置已经落到 HLS 直播窗口之外（播放器追不上、或切后台
                // 回来、或网络抖了一下缓冲没跟上）。正确处置是 seek 回直播边缘
                // 继续播，而不是当成错误。
                //
                // 真机现象（这就是"突然失败然后不播放"的真凶）：
                //   Exo 播放失败: ERROR_CODE_BEHIND_LIVE_WINDOW
                //   → 被当成"直连首个内核失败"
                //   → 跳过全部内核
                //   → 底部弹提示、画面停住
                // 用户看的好好的，突然就停了，而且换台才好。
                if (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    Log.w(TAG, "播放位置掉出直播窗口，seek 回直播边缘继续播（不当作失败）")
                    val exo = player
                    if (exo == null) {
                        listener?.onError(msg)
                        return
                    }
                    try {
                        // seekToDefaultPosition 对直播流就是"跳到当前直播边缘"。
                        //
                        // **不要无条件 prepare()**：BEHIND_LIVE_WINDOW 发生时
                        // 播放器还处在 READY，多调一次 prepare 会重建 MediaCodec
                        // —— 日志里那串「解码器候选(video/avc)」就是重建的证据，
                        // 每次白多黑 0.3~1 秒。只有真退回 IDLE 才需要 prepare。
                        exo.seekToDefaultPosition()
                        if (exo.playbackState == androidx.media3.common.Player.STATE_IDLE) {
                            exo.prepare()
                        }
                        exo.play()
                    } catch (t: Throwable) {
                        Log.w(TAG, "seek 回直播边缘失败，交给上层处理", t)
                        listener?.onError(msg)
                    }
                    return
                }

                // Surface 被系统回收导致的报错：不是内容/解码问题，
                // 换内核、换解码方式都没用，只会白折腾一轮。
                // 上层重接 Surface 后自然会恢复。
                if (msg.contains("surface has been released", ignoreCase = true) ||
                    msg.contains("surface", ignoreCase = true) && msg.contains("release", ignoreCase = true)
                ) {
                    Log.w(TAG, "Surface 已释放，交由上层重接，不当作播放失败")
                    listener?.onSurfaceLost(msg)
                    return
                }

                // 区分「解码器失败」和「其它失败」。
                // 解码器失败要换解码方式（硬→软），换内核没用——
                // VLC/mpv 也是走同一批 MediaCodec，会再失败一遍。
                if (isDecoderFailure(error, msg)) {
                    Log.w(TAG, "判定为解码器失败，交由上层切换解码方式")
                    listener?.onDecoderFailed(msg)
                } else {
                    listener?.onError(msg)
                }
            }
        })

        return p
    }

    override var aspect: String = "auto"

    /**
     * 判断这个错误是不是解码器层面的。
     *
     * 依据：Exo 的 errorCode 落在解码区间，或底层抛的是 MediaCodec 异常。
     * Amlogic 上 4K 码流会报 "not enough codec memory for this file"
     * 加 CodecException 0x80001000。
     */
    private fun isDecoderFailure(error: PlaybackException, msg: String): Boolean {
        if (error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
        ) return true

        var c: Throwable? = error.cause
        var depth = 0
        while (c != null && depth < 6) {
            if (c is android.media.MediaCodec.CodecException) return true
            val m = c.message ?: ""
            if (m.contains("codec memory", true) ||
                m.contains("0x80001000", true) ||
                m.contains("Decoder failed", true)
            ) return true
            c = c.cause
            depth++
        }
        return msg.contains("codec memory", true) || msg.contains("0x80001000", true)
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
        // 播放器没了，配置指纹也要清掉，否则下次 prepare 会以为还能复用
        builtConfig = null
    }

    override fun setVolume(volume: Float) {
        player?.volume = volume.coerceIn(0f, 1f)
    }

    override fun isPlaying(): Boolean = player?.isPlaying == true

    override fun stats(): EngineStats {
        val p = player ?: return EngineStats()
        val vf = p.videoFormat
        return EngineStats(
            positionMs = runCatching { p.currentPosition }.getOrDefault(-1L),
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
