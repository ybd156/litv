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

    /** true 时用极简数据源（只发 UA）。内置源/签名 URL 类源用得上。 */
    @Volatile private var rawHttpMode = false

    /**
     * 给所有 HTTP 请求加 `Cache-Control: no-cache`。
     *
     * 有些源的 m3u8 响应带 `max-age=86400`，但里面分片 URL 的签名只有几分钟有效：
     * CDN 会把**过期的那份 m3u8** 反复喂给播放器，分片就请求到 0 字节 ——
     * 表现是「播一下卡一下」（实测 mursor 源就是这个毛病）。
     * 直播列表本来就该每次拿新的，默认开；VOD 想省流量可以在设置里关掉。
     */
    @Volatile var noCache: Boolean = true

    /**
     * 动态 DataSource 工厂。
     *
     * 播放器复用之后，工厂是在第一次 build 时创建的，而请求头每个频道都不同。
     * 所以不能在 build 时把 headers 写死——必须在每次 createDataSource()
     * 时读当前的 curUa / curHeaders。
     */
    /**
     * 源站返回 HTML 错误页时不重试。
     *
     * 实测快速连按换台，源站会回一个 HTML 错误页：
     *   ProtocolException: Unexpected status line: <html>
     * ExoPlayer 默认按指数退避一直重试，日志里出现「加载慢 9857ms」，
     * 用户看到的就是最后那个台转圈十几秒。
     * 这类错误重试多少次结果都一样，立刻失败让用户换台更合适。
     */
    private val noRetryOnHtml =
        object : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
            override fun getRetryDelayMsFor(
                loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo,
            ): Long {
                var cause: Throwable? = loadErrorInfo.exception
                while (cause != null) {
                    val msg = cause.message ?: ""
                    if (cause is java.net.ProtocolException ||
                        msg.contains("Unexpected status line", true) ||
                        msg.contains("<html>", true)
                    ) {
                        Log.w(TAG, "源站返回非媒体内容（HTML/协议错误），不再重试")
                        com.litv.tv.data.PlayLog.w(TAG, "源站返回非媒体内容（HTML/协议错误），不再重试")
                        return androidx.media3.common.C.TIME_UNSET
                    }
                    cause = cause.cause
                }
                return super.getRetryDelayMsFor(loadErrorInfo)
            }
        }

    /** 加载失败回调：让 PlayerManager 立刻换内核，不必等满无数据看门狗。 */
    var onLoadFailure: ((String) -> Unit)? = null

    /**
     * 异常摘要：只留根因的类名与消息，并把 URL 的 query 段抹掉
     * （地址里带签名参数，不能进日志、也不该进上报）。
     */
    private fun briefError(t: Throwable): String {
        var c: Throwable? = t
        var guard = 0
        while (c?.cause != null && c.cause !== c && guard++ < 8) c = c.cause
        val root = c ?: t
        val msg = (root.message ?: "").replace(Regex("\\?[^\\s\"']*"), "?<redacted>")
        return root.javaClass.simpleName + (if (msg.isEmpty()) "" else ": $msg")
    }

    /**
     * 把 m3u8 请求的 `position` 强制归零的数据源包装。
     *
     * 为什么需要（真机实测 2026-11-21，央视频）：
     * 该 CDN 对带 `Range: bytes=N-` 的请求直接 403，而裸请求同 URL 是 200。
     * ExoPlayer 在拉 HLS 直播 playlist 时，只要 DataSpec.position 不为 0 就会带上 Range
     * （复用播放器、起播追边、部分 seek 之后都可能出现），于是整条链路间歇性 403，
     * 表现是「播几秒断流 + 弹所有内核均失败」。
     *
     * 只对 `.m3u8` 生效：直播 playlist 永远是整份重拉，置零是安全的；
     * 分片本身就是 position=0，普通点播源的 seek 完全不受影响。
     */
    private class PositionZeroingDataSource(
        private val delegate: androidx.media3.datasource.HttpDataSource,
    ) : androidx.media3.datasource.HttpDataSource {

        private fun sanitize(spec: androidx.media3.datasource.DataSpec): androidx.media3.datasource.DataSpec {
            val uri = spec.uri.toString()
            if (!uri.contains(".m3u8", ignoreCase = true)) return spec
            if (spec.position == 0L && spec.length == androidx.media3.common.C.LENGTH_UNSET.toLong()) return spec
            return spec.buildUpon()
                .setPosition(0L)
                .setLength(androidx.media3.common.C.LENGTH_UNSET.toLong())
                .build()
        }

        override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
            val s = sanitize(dataSpec)
            // 诊断：ExoPlayer 到底发了什么。只看 m3u8，且只在 debug 包打。
            if (com.litv.tv.BuildConfig.DEBUG &&
                dataSpec.uri.toString().contains(".m3u8", ignoreCase = true)
            ) {
                android.util.Log.w(
                    "ExoSpec",
                    "orig[method=" + dataSpec.httpMethod +
                        " pos=" + dataSpec.position +
                        " len=" + dataSpec.length +
                        " flags=" + dataSpec.flags +
                        " key=" + dataSpec.key +
                        "] send[method=" + s.httpMethod +
                        " pos=" + s.position +
                        " len=" + s.length + "]",
                )
            }
            return delegate.open(s)
        }

        override fun close() = delegate.close()

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            delegate.read(buffer, offset, length)

        override fun getUri(): android.net.Uri? = delegate.uri

        override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

        override fun getResponseCode(): Int = delegate.responseCode

        override fun setRequestProperty(name: String, value: String) =
            delegate.setRequestProperty(name, value)

        override fun clearRequestProperty(name: String) =
            delegate.clearRequestProperty(name)

        override fun clearAllRequestProperties() =
            delegate.clearAllRequestProperties()

        override fun addTransferListener(
            transferListener: androidx.media3.datasource.TransferListener,
        ) {
            delegate.addTransferListener(transferListener)
        }
    }

    private val dynamicHttpFactory: androidx.media3.datasource.HttpDataSource.Factory =
        object : androidx.media3.datasource.HttpDataSource.Factory {
            override fun createDataSource(): androidx.media3.datasource.HttpDataSource {
                val extra = curHeaders
            .filterKeys { !it.equals("User-Agent", ignoreCase = true) }
            .toMutableMap()
        // 读 prefs 而不是靠外部接线：createDataSource 可能在任意时刻被调用，
        // 保证「设置里改完下一次请求就生效」。
        val noCacheOn = appContext?.let { com.litv.tv.data.Prefs(it).hlsNoCache } ?: noCache
        if (noCacheOn) extra["Cache-Control"] = "no-cache"

                // DoH 开启时改走 OkHttp 数据源：只有 OkHttp 的 Dns 是我们可以替换的
                // （Media3 自带的 DefaultHttpDataSource 直接用系统解析，插不进去）。
                // 关闭 DoH 时保持原路径，行为与旧版逐字节一致。
                if (DohDns.isEnabled()) {
                    val okF = OkHttpDataSource.Factory(DohDns.client())
                    // ★ 源配置里的 UA 必须走 setUserAgent：OkHttp 数据源会用它构造
                    //   Request，塞进 headers 会被 OkHttp 的 User-Agent 覆盖掉。
                    okF.setUserAgent(curUa)
                    if (extra.isNotEmpty()) okF.setDefaultRequestProperties(extra)
                    return PositionZeroingDataSource(okF.createDataSource())
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
                return PositionZeroingDataSource(f.createDataSource())
            }

            override fun setDefaultRequestProperties(
                properties: Map<String, String>,
            ): androidx.media3.datasource.HttpDataSource.Factory = this
        }

    /** 已打过日志的解码器查询（mime|secure|tunnel），避免重复与空行。 */
    private val loggedCodecQueries = HashSet<String>()

    private var appContext: Context? = null
    private var videoSurface: android.view.Surface? = null
    private var videoW = 0
    private var videoH = 0

    override fun isAvailable(context: Context): Boolean = true

    override fun attach(context: Context, surface: android.view.Surface?, container: ViewGroup?) {
        appContext = context.applicationContext
        videoSurface = surface
        player?.let { p ->
            // 统一契约：Exo 只认 Activity 交过来的 Surface。
            // 不再有 PlayerView 分支——那个分支里「同一个播放器再绑一次」是空操作，
            // 新 Surface 永远应用不上，表现就是「退出去再进黑屏、声音还在」。
            // 传 null 也要设：否则旧 Surface 留在渲染器里，
            // 被系统销毁后会抛 "The surface has been released"。
            runCatching { p.setVideoSurface(surface) }
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
        // 央视频的 CDN 对 Media3 默认数据源发出的请求判 403（同 URL 裸连接是 200），
        // 这类签名 URL 源改走只发 UA 的极简数据源。
        rawHttpMode = url.contains("ysp.cctv.cn", ignoreCase = true)

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
                // ★ 必须把位置归零：复用实例时若 currentPosition 还停在上一台的位置，
                //   新数据源的首次请求就会带 `Range: bytes=N-`。
                //   央视频的 CDN 对带 Range 的请求直接 403（实测同 URL 裸拉 200、
                //   加 `Range: bytes=0-` 就 403），表现为「所有内核都播不了」。
                existing.seekTo(0L)
            }
            p = existing
        } else {
            releaseInternal()
            p = buildPlayer(ctx, decode, bufferMs)
            player = p
            builtConfig = cfg
        }

        exoFirstFrameRendered = false

        // 诊断：只打 URL 尾部（含 query），不打完整签名地址
        if (com.litv.tv.BuildConfig.DEBUG) {
            Log.i(TAG, "prepare url tail=…" + url.takeLast(90) + "  len=" + url.length)
        }

        player = p
        // 统一契约：视频输出永远是 Activity 交过来的 Surface。
        // 画面比例（auto/zoom/fill/16:9/4:3）由 Activity 侧的
        // AspectRatioFrameLayout 负责，引擎不再参与。
        p.setVideoSurface(videoSurface)
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
        // ★ 跟随设置里的「缓冲」（prefs.bufferMs），不再写死 10 秒。
        //
        // 10 秒对直播是错的：服务端 HLS 窗口只有 6 片 × 2 秒 = 12 秒，
        // 要保 10 秒缓冲，播放位置必然贴着窗口边缘，一抖就
        // ERROR_CODE_BEHIND_LIVE_WINDOW → seek 回边缘 → 重新缓冲，
        // 表现就是「越看越卡」。落盘日志里起播 6 秒就掉出去过一次。
        // 社区直播实践是 1.5~3 秒，这里跟随设置并夹在 1~8 秒。
        // 下限从 1 秒抬到 12 秒。实测这个源（mursor）的 HLS 分片间隔接近 10 秒：
        // minBuffer 如果小于一个分片长度，缓冲刚攒够就掉回阈值以下，
        // 表现是每 15 秒一次「重缓冲：播放中回到 BUFFERING」+「位置回落」。
        // 起播不受影响 —— 起播深度由 bufferForPlaybackMs(800ms) 控制，不是这个值。
        // ★ 2026-11-21 改：下限 12s → 20s。
        //   依据是电视 18.6 小时的播放日志：自建源（分片间隔接近 10 秒）
        //   在 04~16 时仍有 17~29 次/小时的重缓冲，而同期「加载慢」实测只有
        //   833~1205ms（属正常波动）——说明不是网络慢，是缓冲攒不住：
        //   12 秒在 10 秒分片下只剩 1 个分片余量，一抖就跌破阈值。
        //   提到 20 秒覆盖 2 个分片。代价是直播延迟增加约 8 秒（比卡顿划算）。
        // ★ 2026-11-21 修正：上一版把这个下限提到 20 秒是错的。
        //   真机日志立刻出现 ERROR_CODE_BEHIND_LIVE_WINDOW：
        //     「播放位置掉出直播窗口，seek 回直播边缘」→ 每 6~30 秒一次重缓冲。
        //   原因：直播的 HLS 窗口只有 6 个分片（日志 segs=6，每片约 6 秒 ≈ 36 秒），
        //   而 maxBufferMs 上限是 40 秒 —— 比整个窗口还大。
        //   播放器囤满 40 秒，播放位置就在窗口外，必然掉出去。
        //   这跟本文件上面那段注释里早写过的坑是同一个：缓冲不能贴窗口边缘。
        // 下限 14 秒：实测央视频分片约 6 秒，14 秒 = 2.3 个分片，才抗得住一次抖动；
        // 同时必须 < 直播窗口的一半（窗口 6 片 ≈ 36 秒，一半是 18 秒），
        // 否则播放位置贴边 → BEHIND_LIVE_WINDOW（这个坑上面刚踩过）。
        val minBufferMs = bufferMs.coerceIn(14_000, 16_000)
        // 上限原来只有 max(缓冲设置, 12s)。实测 cctv17 这类源：
        // 播放列表（1KB）每次要 880~1450ms、分片（2~3MB）要 870~1213ms，
        // 两分钟里"加载慢"出现 29 次、重缓冲 2 次 —— 12 秒的池子太薄，
        // 连续两次慢请求就见底。上限放到"缓冲设置 × 3"（下限 20s、上限 60s），
        // 让 ExoPlayer 有空间提前囤货。
        // 上限封在 20 秒：直播不需要囤 60 秒，那是纯浪费内存，
        // 低端盒子上还会把 GC 压力拉满。
        // 上限 40 秒：它是"最多囤多少"，不是目标深度，不会和 12 秒窗口冲突
        // （窗口冲突由 minBufferMs 决定）。20 秒太紧——实测 2.8 小时里
        // 重缓冲 79 次、判定卡死 10 次，上游拉 1KB 清单要 1.5 秒时没有余量。
        // ★ 上限必须**明显小于**直播窗口（这里窗口约 36 秒）。
        //   原来 max = min*4 夹到 40 秒，比窗口还大，必然掉出直播窗口。
        //   改成 min + 8 秒（10~16 秒的 min 对应 18~24 秒上限），留足余量。
        val maxBufferMs = (minBufferMs + 4_000).coerceIn(minBufferMs + 2_000, 20_000)
        // bufferForPlayback 从 300 提到 800：
        // 实测音频 HAL 的欠载告警全部集中在起播那 4 秒（19 条在 20:42:49~53，
        // 之后 55 秒安静）。300ms 就开播时管线还没填满，第一秒最容易破音。
        // 起播慢 0.5 秒，换掉开头那一段电音。
        val loadControl = DefaultLoadControl.Builder()
            // 第 4 个参数是"重缓冲后要再攒多少才恢复播放"。
            // 2000 → 1000：实测重缓冲 38 次/小时，每次多等 1 秒就是
            // 每小时多卡 38 秒；上游慢是常态，早点恢复比攒满更划算。
            .setBufferDurationsMs(minBufferMs, maxBufferMs, 800, 1000)
            .setBackBuffer(0, true)                     // 直播不回退缓冲，省内存
            .setPrioritizeTimeOverSizeThresholds(false) // 优先按字节封顶，防低端盒子 OOM
            .build()

        // 落一行到 play.log：事后能确认这台设备实际用的缓冲参数，
        // 不用再靠猜（之前排查卡顿时最缺的就是这个数）。
        com.litv.tv.data.PlayLog.w(
            TAG,
            "缓冲参数 min=${minBufferMs}ms max=${maxBufferMs}ms 起播=800ms 恢复=1000ms",
        )

        // ★ 源配置里的 UA 必须走 setUserAgent，不能只塞进请求头。
        //   ExoPlayer 的 DefaultHttpDataSource 是「先应用 defaultRequestProperties、
        //   再单独 setRequestProperty("User-Agent", userAgent)」——
        //   写死的 UA 会把请求头里的源 UA 覆盖掉，表现为"硬解时源站校验 UA 失败"。
        // 用动态工厂：播放器会被复用，而请求头每个频道都不一样，
        // 不能在 build 时写死。
        // rawHttpMode 时绕开 Media3 的默认数据源，改用只发 UA 的极简实现。
        // 见 RawHttpDataSource 的注释：签名 URL 型 CDN 对默认数据源的请求判 403。
        val dsFactory: androidx.media3.datasource.DataSource.Factory =
            if (rawHttpMode) RawHttpDataSource.Factory({ curUa }, { curHeaders })
            else DefaultDataSource.Factory(ctx, dynamicHttpFactory)

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
                // 只在**非空**且**首次**时打日志。
                // 以前每次都打：Media3 会为 secure / tunneled 变体各查一次，
                // 那些查询正常也会返回空列表，于是日志里全是
                // 「解码器候选(video/avc): 」这种空行，看着像"设备没有解码器"。
                if (all.isNotEmpty()) {
                    val key = mimeType + "|" + requiresSecureDecoder + "|" + requiresTunnelingDecoder
                    if (loggedCodecQueries.add(key)) {
                        Log.i(
                            TAG,
                            "解码器候选($mimeType secure=$requiresSecureDecoder tunnel=$requiresTunnelingDecoder): " +
                                all.joinToString(", ") { it.name },
                        )
                    }
                }
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
            .setMediaSourceFactory(
                // 统一走自己的工厂（AV3A 解析器只在该库存在时才挂）：
                // 只有走它，HLS 才能拿到 setAllowChunklessPreparation
                // 与自定义错误策略；DefaultMediaSourceFactory 没有这两个入口。
                LitvMediaSourceFactory(dsFactory, Av3aLoader.available(ctx))
                    .setLoadErrorHandlingPolicy(noRetryOnHtml)
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
                com.litv.tv.data.PlayLog.w(TAG, "视频首帧已渲染")
                com.litv.tv.data.PlayLog.w(TAG, "首帧已渲染")
                listener?.onFirstFrame()
            }

            // 下面四项是"随机卡顿"的唯一可量化证据。
            // 之前日志里只有首帧和报错，卡的时候什么线索都没有，只能靠猜。

            override fun onDroppedVideoFrames(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long,
            ) {
                Log.w(TAG, "丢帧 ${droppedFrames} 帧 / ${elapsedMs}ms")
            }

            override fun onAudioUnderrun(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                bufferSize: Int,
                bufferSizeMs: Long,
                elapsedSinceLastFeedMs: Long,
            ) {
                Log.w(TAG, "音频欠载：缓冲 ${bufferSizeMs}ms，距上次喂数据 ${elapsedSinceLastFeedMs}ms")
            }

            // 分片加载耗时：重缓冲的直接原因就是"这一片拉得比播得慢"。
            // 只记耗时和字节数，不记 URI —— 地址里带签名参数，不能进日志。
            override fun onLoadCompleted(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
                mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
            ) {
                val ms = loadEventInfo.loadDurationMs
                if (ms >= 800) {
                    com.litv.tv.data.PlayLog.w(TAG, "加载慢 ${ms}ms")
                    Log.w(
                        TAG,
                        "加载慢 ${ms}ms / ${loadEventInfo.bytesLoaded / 1024}KB（${mediaLoadData.trackType}）",
                    )
                }
            }

            override fun onLoadError(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
                mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
                error: java.io.IOException,
                wasCanceled: Boolean,
            ) {
                if (wasCanceled) return
                val brief = briefError(error)
                Log.w(TAG, "加载失败 $brief")
                com.litv.tv.data.PlayLog.w(TAG, "加载失败 $brief")
                // 告诉 PlayerManager：这个内核拿不到数据，别再等 12 秒看门狗了。
                //
                // ★ 必须把**真实原因**传上去。以前这里传的是常量「加载失败」，
                //   PlayerManager 里「网络类失败 → 重试同一内核 5×2s」那条分支
                //   因此永远不成立，DNS 抖动被当成内核失败，一路把内核链烧完
                //   —— 表现就是「息屏唤醒后网络还没起来，画面直接黑掉」。
                onLoadFailure?.invoke(brief)
            }

            override fun onBandwidthEstimate(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                totalLoadTimeMs: Int,
                totalBytesLoaded: Long,
                bitrateEstimate: Long,
            ) {
                if (bitrateEstimate > 0) {
                    Log.i(TAG, "带宽估算 ${bitrateEstimate / 1000} kbps")
                }
            }

            override fun onPlaybackStateChanged(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                playbackState: Int,
            ) {
                // 已经在播却退回 BUFFERING = 重缓冲（网络或解码跟不上）
                if (playbackState == Player.STATE_BUFFERING && exoFirstFrameRendered) {
                    Log.w(TAG, "重缓冲：播放中回到 BUFFERING")
                    com.litv.tv.data.PlayLog.w(TAG, "重缓冲：播放中回到 BUFFERING")
                }
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
                // 源帧率：区分"源本来 20fps（正常）"和"解码器只能出 20fps（卡顿）"。
                val fps = p.videoFormat?.frameRate ?: -1f
                Log.i(TAG, "视频规格 ${size.width}x${size.height} @ ${fps}fps")
                listener?.onVideoSizeChanged(size.width, size.height, fps)
            }

            override fun onPlayerError(error: PlaybackException) {
                val msg = buildString {
                    append(error.errorCodeName)
                    error.cause?.message?.let { append(": ").append(it) }
                }
                Log.w(TAG, "Exo 播放失败: $msg")
                com.litv.tv.data.PlayLog.w(TAG, "Exo 播放失败: $msg")

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
                    com.litv.tv.data.PlayLog.w(TAG, "播放位置掉出直播窗口，seek 回直播边缘继续播（不当作失败）")
                com.litv.tv.data.PlayLog.w(TAG, "掉出直播窗口，seek 回边缘")
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
                    com.litv.tv.data.PlayLog.w(TAG, "Surface 已释放，交由上层重接，不当作播放失败")
                    listener?.onSurfaceLost(msg)
                    return
                }

                // 区分「解码器失败」和「其它失败」。
                // 解码器失败要换解码方式（硬→软），换内核没用——
                // VLC/mpv 也是走同一批 MediaCodec，会再失败一遍。
                if (isDecoderFailure(error, msg)) {
                    Log.w(TAG, "判定为解码器失败，交由上层切换解码方式")
                    com.litv.tv.data.PlayLog.w(TAG, "判定为解码器失败，交由上层切换解码方式")
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
        // 注意：不要试图用 `Format(0,` 判断"格式无效"——那个 0 是 format 的 id，
        // 不是宽高；真正的宽高在后面的 [1920, 1080, ...] 里。
        // 我按它判断过一次，把真解码器失败误判成数据问题，结果不切软解、
        // 改去换内核，VLC 又拿不到数据，直接死循环。
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

    override fun play() {
        val p = player ?: return
        // stop() 之后播放器处于 IDLE，必须重新 prepare 才会重建解码器。
        // 只把 playWhenReady 置回 true 是不够的：挂起过的硬解码器经常
        // 再也不出帧（黑屏，要等 5 秒无帧看门狗换软解才恢复）。
        if (p.playbackState == androidx.media3.common.Player.STATE_IDLE) {
            runCatching { p.prepare() }
        }
        p.playWhenReady = true
    }

    /**
     * 暂停用 stop()，不是 playWhenReady=false。
     *
     * stop() 会释放解码器资源，回来时 [play] 重新 prepare，解码管线是新建的。
     * 这是参照成熟实现的做法（my-tv 的 PlayerFragment：onPause → stop()、
     * onStart → prepare() + play()）。用 pause() 保留解码器的话，退后台
     * 一会儿再回来会黑屏，必须等看门狗换软解才出画面。
     */
    override fun pause() {
        runCatching { player?.stop() }
    }
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

    /**
     * ExoPlayer 已缓冲到的位置。起播阶段它随分片下载持续增长，
     * 用来区分「卡死不动」和「分片大、正在慢慢下」——见 PlayerEngine.bufferedMs()。
     */
    override fun bufferedMs(): Long =
        runCatching { player?.bufferedPosition ?: 0L }.getOrDefault(0L)

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
