package com.litv.tv.player

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.SurfaceView
import android.view.ViewGroup
import com.litv.tv.BuildConfig
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
    /** 已经挂给 libVLC 的 Surface，用来避免重复 attach。 */
    private var attachedSurface: android.view.Surface? = null
    /** 原生库可用性只探测一次：loadLibrary 失败要缓存，别每次换台都重试。 */
    private var probeResult: Boolean? = null

    private var hostView: android.view.View? = null
    private var videoW = 0
    private var videoH = 0

    override fun isAvailable(context: Context): Boolean {
        // ★ 必须真的去加载 so，不能只 Class.forName。
        //
        // LibVLC 的静态初始化块在原生库加载失败时会自己调 System.exit(1)：
        //   E/VLC/LibVLC: Can't load vlcjni library: UnsatisfiedLinkError:
        //                 cannot locate symbol "__sfp_handle_exceptions"
        //   I/com.litv.tv: System.exit called, status: 1
        // 那是**进程级退出**，catch 不到、也没有堆栈，用户看到的就是"点一下闪退"。
        //
        // 先自己 loadLibrary：失败只会抛 UnsatisfiedLinkError（可以捕获），
        // 于是我们永远不会走到 LibVLC 的构造，进程也就不会被带走。
        if (probeResult != null) return probeResult!!
        val ok = try {
            System.loadLibrary("vlcjni")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "VLC 原生库不可用：" + t.javaClass.simpleName + " / " + t.message, t)
            false
        }
        probeResult = ok
        return ok
    }

    /** SurfaceView 的 holder：libVLC 只给 Surface 不给 holder 时能挂上但不解码（黑屏） */
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** 最近一次收到 VLC TimeChanged 的时刻（elapsedRealtime） */
    @Volatile private var lastTimeEventAt = 0L

    /** VLC 自己的看门狗：只在本引擎内部生效，不改 PlayerManager 的位置语义 */
    private var nudgedOnce = false
    private val vlcWatchdog = object : Runnable {
        override fun run() {
            val p = player ?: return
            val now = android.os.SystemClock.elapsedRealtime()
            val idle = if (lastTimeEventAt > 0) now - lastTimeEventAt else 0
            if (lastTimeEventAt > 0 && idle > 12_000) {
                Log.w(TAG, "VLC 12 秒没送帧（idle=${idle}ms），轻推一次")
                // 必须走 VlcOps：看门狗跑在主线程，直接 pause/play 会和 release 抢 libvlc 的锁
                VlcOps.exec.execute { runCatching { p.pause(); p.play() } }
                if (nudgedOnce) {
                    com.litv.tv.data.PlayLog.w(TAG, "VLC 停止送帧")
                    listener?.onError("VLC 停止送帧")
                    return
                }
                nudgedOnce = true
            } else if (idle in 1..3_000) {
                nudgedOnce = false
            }
            main.postDelayed(this, 5_000)
        }
    }

    var surfaceHolder: android.view.SurfaceHolder? = null
        private set

    private var videoLayout: org.videolan.libvlc.util.VLCVideoLayout? = null
    fun attachVideoLayout(l: org.videolan.libvlc.util.VLCVideoLayout?) { videoLayout = l }

    fun setHolder(h: android.view.SurfaceHolder?) { surfaceHolder = h }

    override fun attach(context: Context, surface: android.view.Surface?, container: ViewGroup?) {
        videoSurface = surface
        appCtx = context.applicationContext

        // Surface 可能**晚于** prepare 就绪（TextureView 要等一次布局）。
        // 那种情况下 prepare 里拿不到 Surface，vlcVout 没挂上，
        // 画面永远出不来。Surface 到达后必须补挂一次。
        if (container != null) hostView = container
        val mp = player
        // isValid 必须查：SurfaceView 销毁后 holder.surface 对象还在，
        // 但已经无效。传给 libVLC 会在它的 Handler 线程里抛异常并崩溃。
        if (mp != null && surface != null && surface.isValid) {
            // 已经挂过就别再 setVideoSurface：libVLC 会抛
            // IllegalStateException: Can't set view when already attached，
            // 把 vout 状态搞坏后画面会撕裂/冻住（实测真机就是这样）。
            // prepare 里已经挂过一次，这里必须靠 areViewsAttached() 判断，
            // 光比 attachedSurface 不够 —— 首次挂载走的是另一条路径。
            if (mp.vlcVout.areViewsAttached()) {
                if (surface === attachedSurface) return
                runCatching { mp.vlcVout.detachViews() }
            }
            try {
                mp.vlcVout.setVideoSurface(surface, null)
                mp.vlcVout.attachViews()
                attachedSurface = surface
                Log.i(TAG, "Surface 晚到，已补挂 VLC 画面")
                syncWindowSize(mp)
            } catch (t: Throwable) {
                Log.w(TAG, "补挂 VLC 画面出错", t)
            }
        }
    }

    /**
     * Surface 销毁：立刻断开 VLC 与它的关系，别让 libVLC 继续拿着死 Surface。
     *
     * 真机崩溃就出在这里：SurfaceView 销毁后我们仍把旧 Surface 挂在 vout 上，
     * libVLC 的 AWindow 在自己的 Handler 里 setSurface 时抛
     * IllegalStateException，直接崩掉播放页。
     */
    override fun onSurfaceDestroyed() {
        videoSurface = null
        player?.let {
            try {
                it.vlcVout.detachViews()
            } catch (t: Throwable) {
                Log.w(TAG, "Surface 销毁时 detachViews 出错（忽略）", t)
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
    /**
     * **同步**把窗口尺寸设成布局此刻的尺寸，必须在 [MediaPlayer.attachViews] **之前**调用。
     *
     * 真机实测（手机首页预览框，2026-11-17）：
     *   View 树里 VLCVideoLayout / player_surface_frame / surface_video 都是 1220×686，
     *   但画面只画出 686×386、居中 —— 正好是容器的 9/16（56.25%）。
     *   原因：`syncWindowSize()` 是 `post` 出去的，执行时布局只量出了高度，
     *   于是 `setWindowSize(686, 686)` 被 vout 当成"方形窗口"，16:9 内容缩成 686×386 再居中。
     *   而 vout 一旦挂上，**事后改窗口尺寸不会重新布局**（画面定格在旧尺寸），
     *   所以只在 attach 之后补设置是没用的 —— 必须在 attach 之前就设对。
     */
    private fun applyWindowSizeNow(mp: MediaPlayer, v: android.view.View) {
        if (v.width <= 0 || v.height <= 0) return
        // 布局可能"只量完了高度、宽度还是旧值/等于高度"（真机看到 686x686）。
        // 这种情况下借父容器的宽度：手机首页那个 16:9 框，父容器是 1220 宽。
        var w = v.width
        val h = v.height
        val pv = v.parent as? android.view.View
        if (pv != null && pv.width > w) {
            w = pv.width
            Log.i(TAG, "setWindowSize：vlay 宽未量完(${v.width})，借用父容器宽 $w")
        }
        runCatching {
            val m = mp.vlcVout.javaClass.getMethod("setWindowSize", Int::class.java, Int::class.java)
            m.invoke(mp.vlcVout, w, h)
            Log.i(TAG, "setWindowSize(同步) ${w}x$h")
        }.onFailure { Log.w(TAG, "setWindowSize(同步) 失败：${it.message}") }
    }

    private fun syncWindowSize(mp: MediaPlayer) {
        // ★ 优先用 VLCVideoLayout 的尺寸，而不是 hostView。
        //   hostView 是 Activity 传进来的 `android.R.id.content`（整屏），
        //   拿它设窗口尺寸，VLC 会按整屏去算内层渲染区域 ——
        //   实测手机首页预览框：容器 1220×686，它算成 686×383（宽度取了容器高度的值），
        //   画面只占 56%，看起来就是"没按比例"。
        val v = videoLayout ?: hostView ?: return
        val apply = Runnable {
            val w = if (v.width > 0) v.width else 1920
            val h = if (v.height > 0) v.height else 1080
            try {
                val m = mp.vlcVout.javaClass.getMethod("setWindowSize", Int::class.java, Int::class.java)
                m.invoke(mp.vlcVout, w, h)
                Log.i(TAG, "已设置 VLC 窗口尺寸 ${w}x${h}")
                com.litv.tv.data.PlayLog.w(TAG, "已设置 VLC 窗口尺寸 ${w}x${h}")
            } catch (t: Throwable) {
                Log.w(TAG, "setWindowSize 不可用（忽略）：${t.message}")
            }
        }
        // 一律延后到布局完成再设尺寸：直接 run 时布局还没量好，
        // 拿到的可能是整屏高度（实测 1220x2268 而不是 16:9 的 686），
        // VLC 会按整屏渲染并反复重建 vout。
        v.post(apply)
    }

    /**
     * 取（或创建）LibVLC 实例。
     *
     * ★ 必须同步：LibVLC 构造要上百毫秒，两个线程同时进来都会通过
     *   `libVlc?.let` 检查、各建一个实例，后一个覆盖字段，第一个成为
     *   永不释放的孤儿。实测 logcat 里同时出现两个 libvlc 地址，
     *   音频时钟互相打架（playback too early / inserting zeroes）。
     *   触发条件是 prepare() 被并发/重复调用。
     */
    @Synchronized
    private fun ensureLib(context: Context): LibVLC {
        libVlc?.let { return it }
        // 直播低延迟参数：不丢帧、不跳帧、网络缓冲给足
        val args = ArrayList<String>().apply {
            // ★ 直播必须允许丢帧/跳帧。
            // 以前这里写的是 --no-drop-late-frames + --no-skip-frames
            // （注释还写着"直播低延迟参数：不丢帧、不跳帧"），理解反了：
            // 解码跟不上时禁止丢帧，延迟会无限积压，积到一定程度 VLC
            // 就彻底不吐帧。实测播 20 秒后 idle 从 14 秒涨到 20 秒，
            // 然后「VLC 停止送帧」→ 换内核。
            // 直播要的是"跟不上就丢，保住实时性"。
            add("--drop-late-frames")
            add("--skip-frames")
            // ★ 不要整个关掉时钟同步。
            //
            // 曾经为修「播 20 秒停止吐帧」加过 --clock-jitter=0
            // --clock-synchro=0。那样确实不再卡死，但音视频各自跑、慢慢漂开，
            // VLC 到阈值做一次硬重同步——表现为「卡一下 + 电音，一会又好」，
            // 而且初始播放就会周期出现。
            //
            // 现在用更温和的组合处理直播漂移：
            //   --drop-late-frames / --skip-frames  跟不上就丢帧，保住实时性
            //   --audio-time-stretch                音频重采样对齐，不丢样本
            // 时钟同步保持默认，让 VLC 自己平滑校正。
            add("--avcodec-hw=any")          // 尽量硬解
            // 直播缓冲从 1500ms 降到 800ms。
            // VLC 的 network-caching 同时决定「起播前等多久」，1500ms 意味着
            // 至少白等 1.5 秒——参考的 ijkplayer 用 packet-buffering=0 直接跳过这段。
            // 800ms 在局域网/中转场景够用，再低容易花屏。
            add("--network-caching=800")
            add("--live-caching=800")
            // ★ 不要关音频时间伸缩。
            // 关掉之后（原来的 --no-audio-time-stretch），音视频时钟漂移
            // 只能靠丢样本/插零来凑，听感就是"电音"、断续，过一会再同步、
            // 然后又漂——表现为周期性卡一下 + 电音。
            // 允许伸缩时 VLC 会重采样音频去对齐，代价极小，听感正常。
            add("--audio-time-stretch")
            // ★ 详细日志只在 debug 包开。
            //
            // `-vvv` 会让 libVLC 自己把完整 input MRL（含 `?exp=…&sig=…`）
            // 和 HTTP 请求头打进 logcat。release 包也这么打，等于把签名地址
            // 交给任何能 adb / root 的人 —— AGENTS.md §6 明令禁止。
            // 排查"某个台播不了"时用 debug 包复现即可。
            if (BuildConfig.DEBUG) add("-vvv")
        }
        return LibVLC(context.applicationContext, args).also { libVlc = it }
    }

    override fun prepare(url: String, headers: Map<String, String>, bufferMs: Int, decode: String) {
        // 打一行比例映射，便于真机核对「设置里的比例到底生效成什么」
        android.util.Log.i(TAG, "prepare: aspect=$aspect → ${AspectPolicy.of(aspect)}")
        val ctx = appCtx ?: run {
            listener?.onError("VLC 未绑定 Surface")
            return
        }
        releaseInternal()

        // ★ LibVLC 的初始化必须放到后台线程。
        //   首次 dlopen 42MB 的 libvlc.so + 扫描插件要好几秒，在主线程做就是 ANR
        //   （真机日志：Going to pause when pause is already pending，
        //   之后整个 App 冻住、连 logcat 都没有输出）。
        Thread {
            val lib = try {
                ensureLib(ctx)
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "LibVLC 初始化失败: " + t.message, t)
                null
            }
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            if (lib == null) {
                main.post { listener?.onError("VLC 初始化失败") }
                return@Thread
            }
            // ★ MediaPlayer 的构造同样是重活（要建 vout/音频输出线程），
            //   必须留在这个后台线程里。只把"绑 Surface + play"放主线程。
            val mp = try {
                MediaPlayer(lib)
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "MediaPlayer 创建失败: " + t.message, t)
                main.post { listener?.onError("VLC 播放器创建失败") }
                return@Thread
            }
            main.post { startOnMain(mp, url, headers, bufferMs, decode) }
        }.start()
    }

    /** 初始化完成后的实际起播（主线程）。 */
    private fun startOnMain(
        mp: MediaPlayer,
        url: String,
        headers: Map<String, String>,
        bufferMs: Int,
        decode: String,
    ) {
        val lib = libVlc ?: run {
            listener?.onError("VLC 未初始化")
            return
        }

        mp.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    listener?.onPlaying()
                    com.litv.tv.data.PlayLog.w(TAG, "VLC 已开始播放")
                    lastTimeEventAt = android.os.SystemClock.elapsedRealtime()
                    nudgedOnce = false
                    main.removeCallbacks(vlcWatchdog)
                    main.postDelayed(vlcWatchdog, 5_000)
                }
                MediaPlayer.Event.TimeChanged -> lastTimeEventAt = android.os.SystemClock.elapsedRealtime()
                MediaPlayer.Event.EndReached -> listener?.onEnded()
                MediaPlayer.Event.EncounteredError -> {
                    com.litv.tv.data.PlayLog.w(TAG, "VLC 解码/网络错误")
                    listener?.onError("VLC 解码/网络错误")
                }
                // libVLC 3.5.1 的 MediaPlayer 没有公开 videoSize API，
                // 画面尺寸交给上层按 Surface 比例处理，这里不做上报。
                else -> Unit
            }
        }

        // 官方做法（见 VLC for Android 的 VideoPlayerActivity）：
        // attachViews(layout, displayManager, subtitles=true, useTextureView=false)
        // 且调用时 layout 必须可见，否则 helper 算出 0x0 → 画面小/不动。
        val vlay = videoLayout
        if (vlay != null) {
            if (vlay.visibility != android.view.View.VISIBLE) vlay.visibility = android.view.View.VISIBLE
            // 官方时序：attachViews 必须在布局测量完成之后。
            // 尺寸为 0 时（刚切内核/刚创建视图）helper 会算出 0x0 → 画面小且不动。
            val doAttach = {
                if (vlay.width > 0 && vlay.height > 0) {
                    runCatching {
                        // ★ 必须在 attachViews 之前设窗口尺寸（挂上后再改不生效）
                        applyWindowSizeNow(mp, vlay)
                        mp.attachViews(vlay, null, true, false)
                        // 画面比例交给官方的 videoScale（对应「比例」设置），
                        // 之前用 BEST_FIT 会缩成小窗。
                        // 与「比例」设置一一对应：
                        //   自动 → 保持原始比例（必要时留黑边）
                        //   16:9 / 4:3 → 强制该比例
                        //   铺满 → 裁切填满
                        mp.videoScale = scaleFor(aspect)
                        android.util.Log.i(TAG, "attachViews ok ${vlay.width}x${vlay.height}")
                        stretchInnerSurface(vlay)
                        scheduleRefit(vlay)
                    }.onFailure { android.util.Log.w(TAG, "attachViews 失败：${it.message}") }
                } else {
                    android.util.Log.w(TAG, "layout 还没测量（${vlay.width}x${vlay.height}），等布局完成再挂")
                    vlay.addOnLayoutChangeListener(object : android.view.View.OnLayoutChangeListener {
                        override fun onLayoutChange(
                            v: android.view.View, l: Int, t: Int, r: Int, b: Int,
                            ol: Int, ot: Int, or_: Int, ob: Int,
                        ) {
                            if (v.width > 0 && v.height > 0) {
                                v.removeOnLayoutChangeListener(this)
                                runCatching {
                                    applyWindowSizeNow(mp, v)
                                    mp.attachViews(vlay, null, true, false)
                                    mp.videoScale = scaleFor(aspect)
                                    android.util.Log.i(TAG, "attachViews ok(延迟) ${v.width}x${v.height}")
                                    stretchInnerSurface(vlay)
                                    scheduleRefit(vlay)
                                }
                            }
                        }
                    })
                }
            }
            doAttach()
        } else
        // 同 attach：只挂**有效**的 Surface。
        videoSurface?.takeIf { it.isValid }?.let {
            // 有 holder 必须一起传：只传 Surface 时 libVLC 拿不到缓冲区格式，会黑屏不报错
            mp.vlcVout.setVideoSurface(it, surfaceHolder)
            mp.vlcVout.attachViews()
            syncWindowSize(mp)
        }

        val media = Media(lib, Uri.parse(url)).apply {
            // VLC 的网络缓冲：把用户设置映射成毫秒
            setHWDecoderEnabled(decode != "soft", false)
            addOption(":network-caching=${bufferMs.coerceIn(500, 20000)}")
            addOption(":live-caching=${bufferMs.coerceIn(500, 20000)}")
            // ★ 绝不在这里加 :clock-jitter=0 / :clock-synchro=0。
            //
            // 这两行曾经存在，而且被删过一次又漏回来过（commit 7456a77 只删了
            // 实例级 args，漏了这里），结果是"注释说已修、实际没修"。
            // media 级 option 的优先级**高于** LibVLC 实例参数，所以只要这两行
            // 还在，ensureLib() 里"时钟同步保持默认"的决定就等于没写：
            // 音视频各自跑、慢慢漂开，VLC 到阈值做一次硬重同步
            // ——表现就是「卡一下 + 电音，一会又好」，且初始播放就周期出现。
            //
            // 直播漂移由实例级参数处理（--drop-late-frames / --skip-frames /
            // --audio-time-stretch），见 ensureLib()。删这两行前请先看
            // ensureLib() 里关于「播 20 秒停止吐帧」的那段注释：
            // 那次故障的真根因是 --no-drop-late-frames/-no-skip-frames + 关时间伸缩，
            // 不是时钟同步。
            if (headers.isNotEmpty()) {
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
        android.util.Log.i(TAG, "VLC play() 已调用 url=" + Redact.url(url) + " holder=" + (surfaceHolder != null))
    }

    /** 布局变化（进出全屏/旋转）后重新同步 VLC 窗口尺寸 */
    fun resyncWindow() {
        val p = player
        val v = hostView
        android.util.Log.i(
            TAG,
            "resync: player=${p != null} view=${v != null} w=${v?.width ?: -1} h=${v?.height ?: -1}",
        )
        if (p == null) return
        val surf = videoSurface
        val w = if (v != null && v.width > 0) v.width else 1920
        val h = if (v != null && v.height > 0) v.height else 1080
        // 正确顺序：先 detach，再改尺寸，再重新给 surface，最后 attach。
        // 之前是先 setWindowSize 再 detach —— detach 被忽略，attach 报
        // "already attached"，只能靠重建播放器绕过（那就是全屏卡一下的根因）。
        runCatching { p.vlcVout.detachViews() }
        try {
            val m = p.vlcVout.javaClass.getMethod("setWindowSize", Int::class.java, Int::class.java)
            m.invoke(p.vlcVout, w, h)
        } catch (t: Throwable) {
            Log.w(TAG, "setWindowSize 不可用：${t.message}")
        }
        if (surf != null && surf.isValid) {
            runCatching { p.vlcVout.setVideoSurface(surf, surfaceHolder) }
        }
        val wasPlaying = runCatching { p.isPlaying }.getOrDefault(false)
        val ok = runCatching { p.vlcVout.attachViews() }.isSuccess
        Log.i(TAG, "resync: ${w}x${h} attach=$ok wasPlaying=$wasPlaying")
        // attach 成功了，但 vout 重挂后 VLC 不一定会继续送帧（真机表现：画面定格）。
        // 轻推一下播放状态即可唤醒，不用重建播放器（重建会断流、黑一下）。
        if (ok && wasPlaying) {
            main.postDelayed({
                runCatching {
                    VlcOps.exec.execute { runCatching { p.pause(); p.play() } }
                }
                android.util.Log.i(TAG, "resync: 已轻推 pause/play 唤醒画面")
            }, 120)
        }
    }


    /**
     * 多次把 VLC 的内层渲染区域压回容器大小。
     *
     * 为什么需要"多次"：libvlc 的 `updateVideoSurfaces()` 会按它自己算的尺寸
     * 回写 `player_surface_frame` / `surface_video` 的 LayoutParams。
     * 真机实测（手机首页预览框，容器 1220×686）：它算成 **686×383**，
     * 即宽度取了容器**高度**的值 → 画面看起来"只有一半、没按比例"。
     * 压一次不够：它可能在 300ms、1s 后又写回去。
     *
     * 所以 attach 之后按 0/250/700/1500ms 各压一次，且每次都重新同步窗口尺寸。
     */
    private fun scheduleRefit(vlay: org.videolan.libvlc.util.VLCVideoLayout) {
        for (delay in longArrayOf(0L, 250L, 700L, 1500L)) {
            vlay.postDelayed({
                if (vlay.width <= 0 || vlay.height <= 0) return@postDelayed
                runCatching {
                    player?.let { syncWindowSize(it) }
                    stretchInnerSurface(vlay)
                }
                android.util.Log.i(TAG, "refit@${delay}ms：${vlay.width}x${vlay.height}")
            }, delay)
        }
    }

    /**
     * 布局尺寸变了之后，把**内层渲染区域**重新对齐到容器。
     *
     * 真机现象（手机首页预览框）：`videoBox` 是 1220×686，但画面只画在左上角
     * 一小块，看起来"没有按比例 / 画面没铺满"。
     *
     * 原因是 libvlc 的 `updateVideoSurfaces()` 会按它自己算出的尺寸写回
     * `player_surface_frame` / `surface_video` 的 LayoutParams，
     * 直播源拿不到正确宽高时就会算成容器的一小部分（代码里早就记录过 680x360）。
     * `stretchInnerSurface()` 只在 attach 时调过一次（+300ms 一次），
     * 之后布局再变（改比例、进出全屏）就没人管了。
     *
     * 所以这里提供一个可重复调用的对齐入口，由 `PlayerManager.resyncWindow()` 调。
     */
    fun refitVideoLayout() {
        val vlay = videoLayout ?: return
        val p = player ?: return
        if (vlay.width <= 0 || vlay.height <= 0) {
            android.util.Log.w(TAG, "refit: 布局还没测量（${vlay.width}x${vlay.height}），跳过")
            return
        }
        runCatching {
            p.videoScale = scaleFor(aspect)
            syncWindowSize(p)
            stretchInnerSurface(vlay)
        }.onFailure { android.util.Log.w(TAG, "refit 失败：${it.message}") }
        // libvlc 可能在我们之后又把尺寸写回去，按几个时间点各压一次
        scheduleRefit(vlay)
        android.util.Log.i(TAG, "refit: ${vlay.width}x${vlay.height} scale=${AspectPolicy.of(aspect)}")
    }

    /**
     * 当前渲染目标的真实像素尺寸。
     *
     * 优先用 `SurfaceHolder.surfaceFrame`（SurfaceView 的真实尺寸，旋转后立即更新），
     * 退而用 hostView / videoLayout。
     */
    private fun currentRenderSize(): Pair<Int, Int>? {
        runCatching {
            val f = surfaceHolder?.surfaceFrame
            if (f != null && f.width() > 0 && f.height() > 0) return f.width() to f.height()
        }
        val v = videoLayout ?: hostView ?: return null
        if (v.width <= 0 || v.height <= 0) return null
        return v.width to v.height
    }

    /**
     * 重挂**之前**把窗口尺寸设成当前尺寸。
     *
     * 真机教训（2026-11-17 04:41）：切横屏时窗口被设成 2624×686，
     * 切回竖屏时若只重挂不改窗口，libVLC 仍按 2624×686 往 1220×1476 上画
     * → 画面缩成 42%、钉在右下角。而 `ok=true` 只表示调用没抛异常，不代表几何正确。
     */
    private fun applyRenderSizeBeforeAttach(mp: MediaPlayer): Boolean {
        val size = currentRenderSize() ?: return false
        return runCatching {
            val m = mp.vlcVout.javaClass.getMethod("setWindowSize", Int::class.java, Int::class.java)
            m.invoke(mp.vlcVout, size.first, size.second)
            Log.i(TAG, "setWindowSize(重挂前) ${size.first}x${size.second}")
            true
        }.getOrDefault(false)
    }

    /**
     * 息屏/开屏后**强制**把视频输出重新绑一遍。
     *
     * 为什么不能复用 [attach]：那里有一句
     * `if (surface === attachedSurface) return`，专防"重复 setVideoSurface 把 vout 搞坏"。
     * 但息屏唤醒时 SurfaceView 的 Surface **对象还是同一个**、`isValid` 也还是 true，
     * 而底层缓冲区已经被系统回收 —— 短路成立、什么都不做，结果就是
     * **音频照常在放、画面永远黑**（真机现象）。
     *
     * 所以这里刻意**不去重**：detach → setVideoSurface → attachViews 整套重来，
     * 再轻推一次 pause/play 让 VLC 重新出帧。不重建播放器，不断流。
     *
     * @return false 表示当前没法重绑（没有播放器 / Surface 无效），调用方应退化为重建
     */
    fun rebindVideo(): Boolean {
        val p = player
        if (p == null) {
            android.util.Log.w(TAG, "rebindVideo: 跳过，没有播放器实例")
            return false
        }

        // ★ 有 VLCVideoLayout 时，视频输出挂在**它内层自己的 SurfaceView** 上
        //   （prepare() 里是 mp.attachViews(vlcLayout, ...)），
        //   跟 Activity 那个 surface 字段**没有关系**。
        //
        // 真机踩过（03:42）：这里去等 Activity 的 surface，而 VLC 根本不看它 →
        // 一直"挂起等待"，black 了 54 秒，日志里 1279 条
        // `libvlc: video output creation failed`。判据用错了对象。
        val vlay = videoLayout
        if (vlay != null) {
            if (vlay.visibility != android.view.View.VISIBLE) vlay.visibility = android.view.View.VISIBLE
            val wasPlaying = runCatching { p.isPlaying }.getOrDefault(false)
            runCatching { p.vlcVout.detachViews() }
            val bound = runCatching {
                p.attachViews(vlay, null, true, false)
                p.videoScale = scaleFor(aspect)
            }.isSuccess
            android.util.Log.i(TAG, "rebindVideo(layout): bound=$bound wasPlaying=$wasPlaying ${vlay.width}x${vlay.height}")
            if (!bound) return false
            syncWindowSize(p)
            runCatching { stretchInnerSurface(vlay) }
            vlay.postDelayed({ runCatching { stretchInnerSurface(vlay) } }, 300)
            if (wasPlaying) {
                main.postDelayed({
                    runCatching {
                        VlcOps.exec.execute { runCatching { p.pause(); p.play() } }
                    }
                    android.util.Log.i(TAG, "rebindVideo(layout): 已轻推 pause/play 唤醒画面")
                }, 120)
            }
            return true
        }

        // 没有 VLCVideoLayout 时（老布局）才走 Surface 那条路
        val s = videoSurface
        if (s == null) {
            // 最常见的一种：Surface 已被 force-disconnect/abandoned，
            // 调用方只能退化为重建（这里没法重绑）。
            android.util.Log.w(TAG, "rebindVideo: 跳过，videoSurface 已为 null（Surface 被销毁）")
            return false
        }
        if (!s.isValid) {
            android.util.Log.w(TAG, "rebindVideo: 跳过，Surface 无效")
            return false
        }

        val wasPlaying = runCatching { p.isPlaying }.getOrDefault(false)
        // detach 失败不致命（可能本来就没挂），继续往下走
        runCatching { p.vlcVout.detachViews() }

        val bound = runCatching {
            p.vlcVout.setVideoSurface(s, surfaceHolder)
            p.vlcVout.attachViews()
            p.videoScale = scaleFor(aspect)
        }.isSuccess

        android.util.Log.i(TAG, "rebindVideo: bound=$bound wasPlaying=$wasPlaying")
        if (!bound) return false

        attachedSurface = s
        syncWindowSize(p)
        // attachViews 成功后 VLC 不一定会继续送帧（真机：画面定格）。
        // 轻推一次播放状态即可唤醒，不用重建播放器。
        if (wasPlaying) {
            main.postDelayed({
                runCatching {
                    VlcOps.exec.execute { runCatching { p.pause(); p.play() } }
                }
                android.util.Log.i(TAG, "rebindVideo: 已轻推 pause/play 唤醒画面")
            }, 120)
        }
        return true
    }

    /**
     * 比例设置 → VLC 的 ScaleType。
     *
     * ★ 这里原来有一段注释与代码**相反**的 bug：
     * 注释写「自动 → 保持原始比例（必要时留黑边）」，但 `else` 分支是
     * `SURFACE_FILL`，也就是设置里选「自动」时画面被拉伸/裁切填满 ——
     * 手机上放非 16:9 的台会明显变形。
     * 现在映射规则收敛到 [AspectPolicy]（带单测）：
     * **只有显式「铺满」才 FILL，其余一律保持原始比例。**
     */
    private fun scaleFor(a: String): MediaPlayer.ScaleType = when (AspectPolicy.of(a)) {
        AspectMode.RATIO_16_9 -> MediaPlayer.ScaleType.SURFACE_16_9
        AspectMode.RATIO_4_3 -> MediaPlayer.ScaleType.SURFACE_4_3
        AspectMode.FILL -> MediaPlayer.ScaleType.SURFACE_FILL
        AspectMode.BEST_FIT -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
    }

    /**
     * VLCVideoLayout 内层是 player_surface_frame → surface_video，
     * 尺寸由 libvlc 的 updateVideoSurfaces() 计算后写回 LayoutParams。
     * 直播源常常拿不到正确的视频宽高，算出来是容器的一半（实测 680x360），
     * 所以在挂载后把这些内层视图强制设成 MATCH_PARENT，让 VLC 自己往里缩放。
     */
    /** 打印视图树：拿到 VLCVideoLayout 内层真实类名/资源名/尺寸 */
    private fun dumpTree(v: android.view.View, depth: Int = 0) {
        val name = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull() ?: "?"
        android.util.Log.i(
            TAG,
            "tree " + "  ".repeat(depth) + v.javaClass.simpleName + " id=" + v.id + "(" + name + ") " + v.width + "x" + v.height,
        )
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) dumpTree(v.getChildAt(i), depth + 1)
        }
    }

    /**
     * 内层 View 的"防缩回"守卫。
     *
     * 真机证据（SurfaceFlinger，2026-11-17）：
     *   SurfaceView 那层 `activeBuffer=[1920x1088]`、`sourceCrop=[0,0,1920,1080]`（完整画面），
     *   但 `displayFrame=[267 394 953 780]` = **686×386**，缩放 0.357。
     *   也就是说 VLC 画的是完整帧，是**承载它的那个 SurfaceView 只有 686×386**。
     *
     * 而 `stretchInnerSurface()` 明明把它设成过 MATCH_PARENT（日志里 `tree surface_video 1220x686`），
     * 之后 libVLC 又按自己算的尺寸（容器高度当边长，得到 686×386）写回去了。
     *
     * 所以这里挂一个布局监听：只要内层尺寸和容器不一致，就再压回 MATCH_PARENT。
     * 加计数上限，防止和 libVLC 互相打架变成死循环。
     */
    private val innerFillCount = mutableMapOf<Int, Int>()

    /** 每个内层 View 最多压几次，防止与 libVLC 互相改写导致布局死循环 */
    private val MAX_INNER_FILL = 20

    private fun guardInnerFill(vlay: org.videolan.libvlc.util.VLCVideoLayout, v: android.view.View) {
        if (innerFillCount.containsKey(v.id)) return
        innerFillCount[v.id] = 0
        v.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            if (vlay.width <= 0 || vlay.height <= 0) return@addOnLayoutChangeListener
            if (view.width == vlay.width && view.height == vlay.height) return@addOnLayoutChangeListener
            val n = innerFillCount[v.id] ?: 0
            if (n >= MAX_INNER_FILL) return@addOnLayoutChangeListener
            innerFillCount[v.id] = n + 1
            runCatching {
                view.layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
            if (n == 0 || n == MAX_INNER_FILL - 1) {
                android.util.Log.i(
                    TAG,
                    "内层 ${view.id} 被缩到 ${view.width}x${view.height}，" +
                        "压回 ${vlay.width}x${vlay.height}（第 ${n + 1}/$MAX_INNER_FILL 次）",
                )
            }
        }
    }

    /**
     * 找出 VLCVideoLayout 内层的几个 View（player_surface_frame / surface_video / ...）。
     *
     * ★ 必须遍历视图树按**资源条目名**匹配，不能再用
     *   `res.getIdentifier(name, "id", "org.videolan.libvlc")`。
     *   本项目开了 `nonTransitiveRClass=true`，VLC 的 id 被合并进**应用自己的包**，
     *   用库包名去查恒为 0 → 循环体直接 `continue` → 「把内层压成 MATCH_PARENT」
     *   从来没生效过（真机日志里一条「内层 … 已设为铺满」都没有）。
     *   后果：libVLC 把 surface_video 算成 686×386 后没人纠正，画面只有容器的 56%。
     */
    private fun findVlcInnerViews(vlay: android.view.View): List<android.view.View> {
        val want = setOf("player_surface_frame", "surface_video", "surface_subtitles", "texture_video")
        val out = ArrayList<android.view.View>(4)
        fun walk(v: android.view.View) {
            val name = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()
            if (name != null && name in want) out.add(v)
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(vlay)
        return out
    }

    private fun stretchInnerSurface(vlay: org.videolan.libvlc.util.VLCVideoLayout) {
        runCatching { dumpTree(vlay) }
        runCatching {
            val inner = findVlcInnerViews(vlay)
            android.util.Log.i(TAG, "内层命中 ${inner.size} 个：${inner.map { it.id }}")
            for (v in inner) {
                v.layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                )
                guardInnerFill(vlay, v)
                android.util.Log.i(TAG, "内层 ${v.id} 已设为铺满")
            }
        }.onFailure { android.util.Log.w(TAG, "内层铺满失败：${it.message}") }
    }

    override var aspect: String = "auto"

    /**
     * 所有 libvlc 播放器操作（stop / release / detachViews / pause / play）都排到
     * **同一条专用线程**串行执行，主线程只投递、不等待。
     *
     * 真机 ANR 证据（2026-11-17 21:58，`/data/anr` 5 份 trace 同一形态）：
     *
     * ```
     * "main" state=S
     *   pthread_mutex_lock
     *   libvlc_media_player_stop+40
     *   MediaPlayer.nativeStop / MediaPlayer.stop
     *   …VlcEngine 释放路径… → Handler.run        ← 主线程
     * Thread-16（e3.b0.run）
     *   libvlc_media_player_stop+48 → input_Close → vlc_join → vlc_sem_wait
     * ```
     *
     * 即**两个线程同时调 stop** → libvlc 内部互锁，主线程被挡 ≥5 秒，系统判
     * `Waited 5005ms for MotionEvent(action=DOWN)` → ANR（2 天内 7 条）。
     * 两处来源就是本类：`stop()` 里 `Thread { p.stop() }`，以及
     * `releaseInternal()` 在调用方线程（通常是主线程）直接 `it.stop()`。
     */
    private object VlcOps {
        val exec: java.util.concurrent.ExecutorService =
            java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "vlc-ops").apply { isDaemon = true }
            }
    }

    override fun play() {
        val p = player ?: return
        VlcOps.exec.execute { runCatching { p.play() } }
    }

    override fun pause() {
        val p = player ?: return
        VlcOps.exec.execute { runCatching { p.pause() } }
    }

    // libvlc 的 stop/release 要等 vout/音频线程收尾，主线程做会卡死（ANR）。
    // 统一走 VlcOps：与 release 串行，绝不再出现两个线程同时 stop。
    override fun stop() {
        main.removeCallbacks(vlcWatchdog)
        val p = player
        if (p == null) return
        VlcOps.exec.execute { runCatching { p.stop() } }
    }

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
        // 先把引用摘掉：之后任何 stop/play 都拿不到这个播放器，避免和这里的
        // release 抢同一把 libvlc 锁（ANR 就是这么来的，见 VlcOps 的注释）。
        val p = player
        player = null
        if (p == null) return
        VlcOps.exec.execute {
            try {
                p.stop()
                p.vlcVout.detachViews()
                p.release()
            } catch (t: Throwable) {
                Log.w(TAG, "释放 VLC 播放器出错", t)
            }
        }
    }

    /**
     * 彻底释放，包括 LibVLC。
     *
     * 由 PlayerManager 在丢弃本引擎时调用。必须幂等：
     * 重复调用不能崩，也不能把已经放掉的引用再放一次。
     *
     * 注意：`releaseInternal()` 的任务先投递、这个后投递，
     * 单线程执行器保证**先放播放器再放 LibVLC** 的顺序。
     */
    fun releaseLibVLC() {
        releaseInternal()
        val lv = libVlc
        libVlc = null
        if (lv == null) return
        VlcOps.exec.execute {
            try {
                lv.release()
            } catch (t: Throwable) {
                Log.w(TAG, "释放 LibVLC 出错", t)
            }
        }
    }

    override fun setVolume(volume: Float) {
        player?.volume = (volume.coerceIn(0f, 1f) * 100).toInt()
    }

    /**
     * VLC 实际**已显示**的画面帧数（`MediaStats.displayedPictures`）。
     *
     * 这是判断"vout 到底有没有在出画"的唯一可靠信号：
     *   - `MediaPlayer.time` / `Event.TimeChanged` 在 vout 死掉时**照样在走**
     *     （demux 没停），真机上因此出现「日志说有帧、屏幕定住」的假绿灯；
     *   - `attachViews` 的返回值只表示没抛异常（同一个坑，11-17 已踩过）。
     *
     * 用反射读，避免为 libvlc 的内部类引入编译期依赖；取不到返回 -1，
     * 调用方需退化到旧判据。
     */
    fun displayedPictures(): Long {
        val mp = player ?: return -1
        return runCatching {
            val media = mp.media ?: return -1
            val stats = media.javaClass.getMethod("getStats").invoke(media) ?: return -1
            stats.javaClass.getField("displayedPictures").getLong(stats)
        }.getOrDefault(-1)
    }

    /**
     * 只把窗口尺寸同步成当前渲染目标的尺寸，不做任何重建。
     *
     * 这是 VLC 官方 Android SDK 的做法：`VideoHelper.updateVideoSurfaces()`
     * 在布局变化的回调里**只调 `vlcVout.setWindowSize(w,h)` 并改 LayoutParams**，
     * 从不 detach/attach，也不重建 vout 或 MediaPlayer
     * （`setVideoTrackEnabled(false/true)` 只在 attach/detach 时切换）。
     *
     * 我们之前试过的三种做法都会打断视频轨道/解码，转场那 1~5 秒停顿就是这么来的：
     *   ① reattachVideo（detach→attach）—— vout 不重连，画面定格
     *   ② recreateVout（setVideoTrackEnabled 切换）—— 等于重启视频轨道，1~5s
     *   ③ retryNow()（重建 MediaPlayer）—— 整条重连，2~10s
     */
    fun syncWindowToCurrentSize(): Boolean {
        val p = player ?: return false
        return applyRenderSizeBeforeAttach(p)
    }

    override fun isPlaying(): Boolean = player?.isPlaying == true

    override fun stats(): EngineStats = EngineStats(
        positionMs = runCatching { player?.time ?: -1L }.getOrDefault(-1L),
        width = videoW,
        height = videoH,
    )

    private companion object {
        const val TAG = "VlcEngine"
    }
}
