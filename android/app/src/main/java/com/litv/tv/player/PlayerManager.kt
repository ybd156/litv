package com.litv.tv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceView
import android.view.ViewGroup
import com.litv.tv.data.PlaybackDefaults
import com.litv.tv.data.Prefs

/**
 * 多内核调度器：负责"选哪个内核、失败了换哪个、成功了记住它"。
 *
 * 这是整个播放链路里最容易写烂的部分。要点：
 *  1. 起播超时（读 `prefs.startTimeoutMs`，默认 15s）就当失败——直播不能无限等，
 *     用户早就换台了。注意别把这个默认值当成"常量"：设置页可改（3000~15000）；
 *  2. 成功后把"这个频道用哪个内核"记到本地，下次直接命中，不再试错；
 *  3. 换台时不重建内核实例（Exo 复用 player、mpv 复用实例），省 200~400ms。
 */
class PlayerManager(
    private val context: Context,
    private val prefs: Prefs,
) {

    interface Callback {
        /** 内核切换了（OSD 上可以提示"正在用 mpv 播放"）。 */
        fun onEngineChanged(kind: EngineKind)
        fun onPrepared(width: Int, height: Int)
        fun onPlaying()
        fun onError(message: String)
        fun onVideoSizeChanged(width: Int, height: Int)

        /** 第一帧真正画到屏幕上（换台快照要等这一帧再撤）。 */
        fun onFirstFrame() = Unit

        /**
         * 检测到"画面完全不动"并即将自动恢复。
         *
         * 用于 QoE 上报（`stall` 事件）：只有客户端知道"用户看到的画面停了多久"。
         * 有默认空实现，避免所有实现类被迫加方法。
         *
         * @param frozenMs 画面冻结时长（毫秒）
         */
        fun onStall(frozenMs: Int) = Unit
    }

    var callback: Callback? = null

    private val main = Handler(Looper.getMainLooper())
    private var surface: android.view.Surface? = null
    /** media3-ui 的 PlayerView，交给 ExoEngine 管 Surface */
    var playerView: androidx.media3.ui.PlayerView? = null
    private var container: ViewGroup? = null
    private var current: PlayerEngine? = null
    private var currentKind: EngineKind? = null
    private var chain: List<EngineKind> = emptyList()
    private var chainIndex = 0

    /** 上次设置「强制软解」时对应的频道，用来判断换台要不要重置 */
    private var lastChannelForSoftFlag = -1L
    private var pendingUrl = ""
    private var pendingHeaders: Map<String, String> = emptyMap()
    private var pendingChannelId = 0L

    /**
     * 当前（或最近一次）播放的频道 id。
     * 上层用它判断「播放器是不是已经在播这个台」——是的话只接 Surface 就行，
     * 不要再重新拉流（否则 Service 保活省下来的时间全白费）。
     */
    val playingChannelId: Long get() = pendingChannelId
    private var prepared = false

    /** 本次内核已经重试过几次 */
    private var retried = 0

    private val startTimeout = Runnable {
        if (prepared) return@Runnable
        // 还没到重试上限就再来一次：网络抖动/慢设备上，重试比直接降级管用
        if (retried < prefs.retryCount) {
            retried++
            Log.w(TAG, "内核 ${currentKind?.key} 起播超时，重试第 $retried 次（上限 ${prefs.retryCount}）")
            startCurrent()
            return@Runnable
        }
        // 自适应：Exo 起播超时且还没试过软解 → 先用软解重试同一个内核。
        //
        // 这台 Amlogic 盒子的硬解器（OMX.amlogic.avc.decoder.awesome）
        // 在部分码流上会 wait timeout：数据喂进去了但不吐帧，画面全黑。
        // 换内核解决不了（VLC/mpv 同样起不来），但换成软解
        // （OMX.google.h264.decoder）就能正常出画面。
        // 所以先试软解，再考虑降级到别的内核。
        if (currentKind == EngineKind.EXO && !exoForceSoftwareDecode) {
            exoForceSoftwareDecode = true
            retried = 0
            Log.w(TAG, "exo 起播超时，改用软解重试")
            startCurrent()
            return@Runnable
        }

        retried = 0
        Log.w(TAG, "内核 ${currentKind?.key} 起播超时，降级")
        tryNext("起播超时")
    }

    /**
     * 首帧看门狗。
     *
     * 起播超时那个机制只看 "有没有 READY / 有没有开始播"，但 Amlogic 硬解器
     * 出不了画面时音频照常在放、Exo 照样报告 playing——超时永远不会触发，
     * 屏幕一直黑着。所以另开一个只看"有没有真的渲染出第一帧"的检查。
     */
    // ---------------------------------------------------------------- 卡顿看门狗
    //
    // 直播流会**静默卡住**：网络抖一下、服务端不再推数据、或者解码器停住，
    // ExoPlayer 都不会回调 onError，isPlaying() 依然是 true，画面却不动了。
    // 之前没有任何机制处理这种情况，用户看到的就是「卡住了不恢复，只能手动换台」。
    //
    // 唯一可靠的判断依据是播放位置：每隔几秒读一次，位置不前进就认为卡住了。
    private var lastPosMs = -1L
    /**
     * 最后一次「播放位置发生变化」的时刻（SystemClock.elapsedRealtime）。
     *
     * 为什么不用「连续 N 次没前进」的计数：真机实测发现 HLS 直播的位置
     * 会**周期性回落**（2760 -> 1761、11765 -> 2767），因为播放器重新
     * 加载播放列表并按分片边界重算时间轴。用 `pos <= lastPosMs` 判断
     * 会把这种正常回落误判成卡顿，日志里一堆「没有前进第 1 次」。
     *
     * 改成看时间戳：位置只要**变化**（前进或回落都算）就刷新时间戳，
     * 超过阈值完全不变才判定卡死。这样既不误判，也能抓住真卡死。
     */
    private var lastPosChangeAt = 0L
    private var stallRecovered = 0
    private var userPaused = false

    /** 上一次判定卡死的时刻（elapsedRealtime），用于"稳定播放后把恢复计数归零"。 */
    private var lastStallAt = 0L

    private val stallWatchdog: Runnable = Runnable {
        val eng = current
        if (eng == null || !prepared) {
            main.postDelayed(stallWatchdog, STALL_CHECK_MS)
            return@Runnable
        }
        // 息屏时位置当然不前进，不能判成卡死（否则关屏一会儿就"反复卡死"报错）
        if (!screenInteractive()) {
            main.postDelayed(stallWatchdog, STALL_CHECK_MS)
            return@Runnable
        }
        if (userPaused) {
            main.postDelayed(stallWatchdog, STALL_CHECK_MS)
            return@Runnable
        }
        val pos = runCatching { eng.stats().positionMs }.getOrDefault(-1L)

        // 这里**不能**用 isPlaying() 做前置条件。
        // 流真卡住时 ExoPlayer 会进入 BUFFERING，isPlaying() 返回 false，
        // 那样就永远检测不到了——这正是「卡住不恢复」的根因。
        if (pos >= 0) {
            val now = android.os.SystemClock.elapsedRealtime()
            // 位置只要变了（前进或回落都算变化）就刷新时间戳
            if (pos != lastPosMs) {
                if (lastPosMs >= 0 && pos < lastPosMs) {
                    Log.i(TAG, "位置回落 ${lastPosMs}ms -> ${pos}ms（HLS 重载，正常）")
                }
                lastPosMs = pos
                lastPosChangeAt = now
            }
        }

        // 完全没变化超过阈值才算卡死。
        //
        // pos < 0 分两种情况，不能一律跳过：
        //   · 从没拿到过位置（lastPosMs < 0）→ 这个内核本来就不给位置，
        //     没法判断，跳过（否则时间戳不刷新，16 秒后必然误判）
        //   · 拿到过、现在变成 -1 → 播放器掉出了正常状态（直播流卡死后
        //     位置就会变成 -1）。这时必须继续用 lastPosChangeAt 计时，
        //     不然看门狗永远不判定 —— 真机上「播一会就卡、日志里一条错误都没有」
        //     就有这一份原因。
        if (pos < 0 && lastPosMs < 0) {
            main.postDelayed(stallWatchdog, STALL_CHECK_MS)
            return@Runnable
        }
        val frozenMs = android.os.SystemClock.elapsedRealtime() - lastPosChangeAt

        // 稳定播放满 5 分钟就把"已自动恢复次数"归零。
        //
        // 为什么必须有：stallRecovered 以前只在 play() 复位，长看同一个台时
        // 一天里累计 4 次网络抖动就会永久报错「播放反复卡死」——用户只能换台。
        // 连续稳定 5 分钟说明链路已经恢复，累计计数不该继续记账。
        val clock = android.os.SystemClock.elapsedRealtime()
        if (stallRecovered > 0 && lastStallAt > 0 && clock - lastStallAt >= STABLE_PLAY_RESET_MS) {
            Log.i(TAG, "稳定播放 ${STABLE_PLAY_RESET_MS / 60_000} 分钟，卡顿恢复计数归零")
            stallRecovered = 0
        }

        if (lastPosChangeAt > 0 && isStalled(pos, lastPosMs, frozenMs, STALL_FROZEN_MS)) {
            lastPosChangeAt = android.os.SystemClock.elapsedRealtime()
            lastStallAt = clock
            stallRecovered++
            if (stallRecovered > MAX_STALL_RECOVER) {
                // 反复卡死说明不是偶发抖动，重启也没用，别再无限循环
                Log.e(TAG, "已自动恢复 $MAX_STALL_RECOVER 次仍然卡死，停止重试")
                stopStallWatchdog()
                callback?.onError("播放反复卡死，请换台或检查网络")
                return@Runnable
            }
            Log.w(TAG, "判定卡死，第 $stallRecovered 次自动恢复")
            // QoE：把"这次到底冻了多久"报上去。服务端的 StallRate / rebuffer_ratio
            // 在此之前**恒为 0**（客户端只报过 start），面板会显示"卡顿率 0%"主动误导。
            callback?.onStall(frozenMs.toInt())
            if (stallRecovered >= 2 && directMode) {
                // 同一个台连续卡两次，多半是这条直连线路有问题。
                // 交给上层改走服务端中转（和「直连首个内核失败」走同一条路）。
                Log.w(TAG, "连续卡死，改走服务端中转")
                directMode = false
                onDirectFailed?.invoke("反复卡死")
                return@Runnable
            }
            startCurrent()
        }
        main.postDelayed(stallWatchdog, STALL_CHECK_MS)
    }

    private fun startStallWatchdog() {
        main.removeCallbacks(stallWatchdog)
        lastPosMs = -1L
        lastPosChangeAt = android.os.SystemClock.elapsedRealtime()
        main.postDelayed(stallWatchdog, STALL_CHECK_MS)
    }

    private fun stopStallWatchdog() {
        main.removeCallbacks(stallWatchdog)
        lastPosMs = -1L
        lastPosChangeAt = 0L
    }

    // ---------------------------------------------------------------- 起播两阶段 + 总预算
    //
    // 老机制只有一个 `startTimeout`（prefs.startTimeoutMs，默认 15s）+ 每内核重试，
    // 3 个内核轮一圈最坏 ≈105s，用户早就换台了——这是"起播很慢"的一半成因
    // （另一半在服务端 ring，见 B1）。
    //
    // 现在拆成三段，互不重叠，所有时长都来自 PlaybackDefaults（不再有裸数字）：
    //   阶段一 [noDataWatchdog]  4s 还没有字节     → 重试一次，还不行就换内核（快）
    //   阶段二 [noFrameWatchdog] 有字节 8s 没出帧  → 硬解换软解（Amlogic 黑屏）
    //   总预算 [budgetWatchdog]  28s               → 无论如何给用户一个结论
    // START_TIMEOUT_MS 保持 15s 不动：慢盒子那是实测下限，不能为了"看起来快"压它。

    /** 本次整段起播的硬截止（elapsedRealtime）。0 表示当前不在起播流程里。 */
    private var budgetDeadlineAt = 0L

    /** 本次内核尝试是否已经出过第一帧（渲染成功才算真的起播）。 */
    private var firstFrame = false

    /**
     * 当前内核是否会上报首帧。false 时**不排首帧看门狗**——
     * VLC / mpv 永远不发 onFirstFrame，开了只会把正常播放误判成失败。
     */
    private var engineReportsFirstFrame = false

    /** 起播总预算已耗尽。置位后内核的晚到事件一律忽略（终态）。 */
    private var budgetExpired = false

    /** 已释放（终态）。置位后不再启动引擎，排队的回调也一律丢弃。 */
    private var released = false

    /** 换内核前那 600ms 的延迟启动。必须持有引用，否则 release 取消不掉。 */
    private var pendingEngineStart: Runnable? = null

    private val noDataWatchdog = Runnable {
        if (prepared) return@Runnable
        if (retried < prefs.retryCount) {
            retried++
            Log.w(TAG, "内核 ${currentKind?.key} ${PlaybackDefaults.START_NOBYTE_MS / 1000} 秒无数据，重试第 $retried 次（上限 ${prefs.retryCount}）")
            startCurrent()
            return@Runnable
        }
        retried = 0
        if (!screenInteractive()) {
            Log.i(TAG, "屏幕已熄灭，跳过无数据判定")
            return@Runnable
        }
        failLog += "${currentKind?.key ?: "?"}: ${PlaybackDefaults.START_NOBYTE_MS / 1000} 秒无数据"
        Log.w(TAG, "内核 ${currentKind?.key} ${PlaybackDefaults.START_NOBYTE_MS / 1000} 秒无数据，换下一个内核")
        tryNext("${PlaybackDefaults.START_NOBYTE_MS / 1000} 秒无数据")
    }

    /**
     * 屏幕是否处于交互状态（亮着且解锁）。
     *
     * 为什么看门狗必须问这一句（真机事故）：
     * 电视息屏/待机时显示管线停了，Surface 不会出帧，播放器一个帧都渲染不出来。
     * 这时看门狗会判定"有数据无画面" → 换内核 → 三个内核全"失败" → 停播并提示。
     * 用户回来看到的就是「突然失败，然后不播放」。
     *
     * 息屏不是故障，是用户主动关的屏。此时**不判、不换、不报错**。
     */
    private fun screenInteractive(): Boolean {
        val pm = context.getSystemService(android.content.Context.POWER_SERVICE)
            as? android.os.PowerManager ?: return true
        return pm.isInteractive
    }

    private val noFrameWatchdog = Runnable {
        if (!prepared || firstFrame) return@Runnable
        // 保险：内核本来就不报首帧，这个判定没有依据
        if (!engineReportsFirstFrame) return@Runnable
        // 息屏时不出帧是正常的，不能当成失败（否则息屏一会儿就"播不了"）
        if (!screenInteractive()) {
            Log.i(TAG, "屏幕已熄灭，跳过首帧判定")
            return@Runnable
        }
        if (currentKind == EngineKind.EXO && !exoForceSoftwareDecode) {
            Log.w(TAG, "已起播但 5 秒没有画面，改用软解")
            exoForceSoftwareDecode = true
            retried = 0
            startCurrent()
            return@Runnable
        }
        failLog += "${currentKind?.key ?: "?"}: 有数据无画面"
        Log.w(TAG, "已起播但 5 秒没有画面，换下一个内核")
        tryNext("有数据无画面")
    }

    private val budgetWatchdog = Runnable {
        if (prepared && firstFrame) return@Runnable
        if (budgetExpired) return@Runnable
        // 置终态：之后内核再报 Prepared/Playing/首帧 都不再改变状态
        budgetExpired = true
        removeStartTimers()
        stopStallWatchdog()
        // 真的停掉内核。只报错不停止的话，它会在后台继续缓冲/重连。
        runCatching { current?.stop() }
        Log.e(TAG, "起播总预算 ${PlaybackDefaults.START_BUDGET_MS}ms 用尽，放弃")
        callback?.onError(
            "起播超时（${PlaybackDefaults.START_BUDGET_MS / 1000} 秒）：" +
                "源站无响应或线路不可用，请换台或换线路"
        )
    }

    /** 起播阶段的三个计时器一起撤（换台/失败/离开页面都要调，否则会互相打架）。 */
    private fun removeStartTimers() {
        main.removeCallbacks(startTimeout)
        main.removeCallbacks(noDataWatchdog)
        main.removeCallbacks(noFrameWatchdog)
        main.removeCallbacks(budgetWatchdog)
        // 故意**不清零** budgetDeadlineAt。
        // 清零会让 remainingBudgetMs() 返回 MAX_VALUE，之后每次重试/换内核
        // 都等于重新获得一份完整预算——总等待可以无限越过初次选台的截止时间。
        // 只有新的播放（play()）才重新设绝对 deadline。
    }

    /** 用「还能花多少时间」给下一次等待封顶，避免单次等待越过总预算。 */
    private fun remainingBudgetMs(): Long {
        if (budgetDeadlineAt <= 0L) return Long.MAX_VALUE
        return (budgetDeadlineAt - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    }

    /** 当前内核是否已经起播（用于 Surface 晚就绪时决定要不要补播） */
    val isPlaying: Boolean get() = prepared

    fun attach(surface: android.view.Surface?, parent: ViewGroup?) {
        this.surface = surface
        container = parent
        current?.attach(context, surface, parent)
    }

    /**
     * Surface 被销毁：立刻通知当前内核松手。
     *
     * 真机崩溃：SurfaceView 销毁后我们仍把旧 Surface 留在内核里，
     * libVLC 会在自己的 Handler 线程里抛
     * IllegalStateException: surface is not attached and holder is null，
     * 外层 try/catch 抓不到，整个播放页直接崩。
     */
    fun onSurfaceDestroyed() {
        surface = null
        current?.onSurfaceDestroyed()
    }

    /**
     * 开始播放某个频道。
     * @param preferredEngine auto | exo | mpv | vlc
     */
    /**
     * 每个内核各自的失败原因。
     *
     * 原来所有失败被合并成一句"所有播放内核都无法播放这个频道"，
     * 用户和排查者都不知道到底是编码不支持、地址失效还是网络不通。
     * 现在逐个记录下来，失败时一起展示。
     */
    private val failLog = mutableListOf<String>()

    /**
     * 本次播放是否用的直连。
     *
     * 直连失败几乎都是网络问题（源站在这个网络里不可达），
     * 换内核不可能修好——所以第一个内核失败就立刻通知上层改走中转，
     * 不要傻等 exo 超时 + vlc + mpv 轮一圈（那要 20 秒）。
     */
    var directMode: Boolean = false

    /** 直连第一个内核就失败时回调，上层据此改用中转重播 */
    var onDirectFailed: ((String) -> Unit)? = null

    /** 把各内核的失败原因拼成一句话（过长时截断，电视上放不下）。 */
    private fun chainFailMessage(): String {
        if (failLog.isEmpty()) return "所有播放内核都无法播放这个频道"
        val detail = failLog.joinToString("；")
        val msg = "所有内核均失败：$detail"
        return if (msg.length > 120) msg.take(117) + "…" else msg
    }

    fun play(channelId: Long, url: String, headers: Map<String, String>, preferredEngine: String) {
        failLog.clear()
        pendingChannelId = channelId
        pendingUrl = url
        pendingHeaders = headers

        val remembered = prefs.engineFor(channelId).ifBlank { null }
        chain = EngineChain.chainFor(preferredEngine, remembered)
        chainIndex = 0
        // 换台必须重置「强制软解」。
        // 否则在某个 4K 台上硬解失败一次之后，后面所有频道都会走软解——
        // 1080p 本来硬解好好的，却因为上一个台的问题被拖成软解，白白吃 CPU。
        if (channelId != lastChannelForSoftFlag) {
            exoForceSoftwareDecode = false
            lastChannelForSoftFlag = channelId
        }
        prepared = false
        stallRecovered = 0
        // 重试额度也必须按次重置。
        // 以前只重置上面两个：如果上一个台是"重试过一次才成功"的，
        // retried 会以 1 的残值留给下一个台，于是换台后的第一次超时
        // 直接跳过了重试（设置里的"重试 1 次"时有时无）。
        retried = 0
        // 起播阶段：清掉上一次的所有计时器，重置第一帧标志，并设一个硬预算。
        removeStartTimers()
        firstFrame = false
        budgetExpired = false
        released = false
        budgetDeadlineAt = android.os.SystemClock.elapsedRealtime() + PlaybackDefaults.START_BUDGET_MS
        main.postDelayed(budgetWatchdog, PlaybackDefaults.START_BUDGET_MS.toLong())
        startCurrent()
    }

    /**
     * 换台一开始就停掉上一次的起播计时器。
     *
     * 必须由 Activity 在**发起换台的那一刻**调，不能等 play() 里再清：
     * playChannel 要先向服务端要签名地址（实测 0.8~1.4 秒），
     * 这段时间里上一次的 noDataWatchdog 还在跑，会对着还没开始的新台触发
     * "4 秒无数据 -> 换内核"，把新台直接判死。
     * 实测：playChannel 之后 179ms 就打出「内核 vlc 4 秒无数据」。
     */
    /**
     * 内核报告加载失败（源站返回 HTML 错误页 / 协议错误等）。
     *
     * 这类错误重试也不会好，而且没必要等满 12 秒的"无数据"看门狗 ——
     * 实测快速连按换台时源站会回 HTML，日志里能看到 10 秒的空转。
     * 收到就立刻换下一个内核。
     */
    fun notifyLoadFailed(reason: String) {
        if (prepared || budgetExpired || released) return
        main.removeCallbacks(noDataWatchdog)
        failLog += "${currentKind?.key ?: "?"}: $reason"
        Log.w(TAG, "内核 ${currentKind?.key} 加载失败（$reason），立即换下一个")
        retried = 0
        tryNext(reason)
    }

    fun cancelPendingStart() {
        removeStartTimers()
        budgetExpired = true
    }

    private fun startCurrent() {
        main.removeCallbacks(startTimeout)

        // 换内核前给上一个解码器一点时间真正释放。
        //
        // ExoPlayer 的 release() 是**异步**的：它把 MediaCodec 的释放抛到内部
        // 播放线程。如果我们紧接着让下一个内核去申请解码器，上一个还占着
        // 硬件解码内存——4K 这种大码流就会直接报
        // "not enough codec memory for this file"，硬解失败回落软解。
        // 同设备上别的播放器只开一个解码器，所以不会遇到这个。
        if (chainIndex > 0 && current != null) {
            val next = chainIndex
            // 存成字段而不是匿名 Runnable：release() 要能取消它。
            // 以前是匿名对象，释放后这 600ms 一到仍然会 startEngine——
            // 等于给一个已经销毁的播放器重新拉起内核。
            cancelPendingEngineStart()
            val r = Runnable { startEngine(next) }
            pendingEngineStart = r
            main.postDelayed(r, 600)
            return
        }
        startEngine(chainIndex)
    }

    /** 取消「换内核前等 600ms」的那次延迟启动。 */
    private fun cancelPendingEngineStart() {
        pendingEngineStart?.let { main.removeCallbacks(it) }
        pendingEngineStart = null
    }

    private fun startEngine(index: Int) {
        if (released) return
        if (index != chainIndex) return
        main.removeCallbacks(startTimeout)
        if (chainIndex >= chain.size) {
            callback?.onError(chainFailMessage())
            return
        }
        val kind = chain[chainIndex]

        // 换台时如果还是同一个内核，**复用引擎实例**。
        //
        // 为什么重要：每次 new ExoEngine() 都意味着底层 ExoPlayer 要销毁重建，
        // 实测这 0.7 秒纯属浪费（换台总耗时 2.65s 里占 1/4）。
        // 引擎内部已经做了「配置没变就复用播放器」，前提是引擎实例本身别换。
        val engine: PlayerEngine
        if (current != null && currentKind == kind) {
            engine = current!!
            Log.i(TAG, "复用 ${kind.key} 引擎实例")
        } else {
            val created = create(kind)
            if (created == null || !created.isAvailable(context)) {
                Log.i(TAG, "内核 ${kind.key} 不可用，跳过")
                failLog += "${kind.key}: 不可用"
                chainIndex++
                startCurrent()
                return
            }
            disposeEngine(current)
            current = created
            currentKind = kind
            callback?.onEngineChanged(kind)
            engine = created
        }

        // 记录这个内核能不能上报首帧：不报的不开首帧看门狗
        engineReportsFirstFrame = engine.reportsFirstFrame

        // PlayerView 模式下不需要外部 Surface —— 它自己管 Surface 生命周期，
        // 所以只有两条都没有时才算「视图未就绪」。
        if (surface == null && playerView == null) {
            callback?.onError("播放视图未就绪")
            return
        }
        engine.attach(context, surface, container)
        stopStallWatchdog()
        // 息屏前那次起播留下的 15 秒 startTimeout 必须清掉：
        // 不清的话它会在唤醒后的新会话里突然触发（实测 0.4 秒后就报超时）。
        main.removeCallbacks(startTimeout)
        // ★ 重新 attach 等于重新起播，必须清掉「上一次已经播过」的状态。
        //
        // 不清会怎么样（真机实测踩到的坑）：换台/息屏唤醒时 Surface 会先销毁再重建，
        // 这里 attach() 不重置 prepared / firstFrame，于是
        //   · budgetWatchdog 28 秒后检查到 `prepared && firstFrame`（上一轮的残留）
        //     → 直接 return，**不报起播超时**
        //   · 而 stallWatchdog 要等 onPlaying 才启动，这条路径一直走不到 onPlaying
        // 两条看门狗同时不管 → 画面永远卡着，日志里一条错误都没有，用户只能换台。
        prepared = false
        firstFrame = false
        // 阶段一：4 秒还没有"字节/READY"就直接重试或换内核。
        // （原来的 frameWatchdog 只盯 EXO 的首帧，且只在 5 秒后看一次；
        //  现在由阶段二 noFrameWatchdog 统一覆盖三个内核，这里不再重复排。）
        main.removeCallbacks(noFrameWatchdog)
        main.removeCallbacks(noDataWatchdog)
        main.postDelayed(noDataWatchdog, PlaybackDefaults.START_NOBYTE_MS.toLong())
        // 所有内核回调统一 post 到主线程。
        //
        // mpv 的日志/状态回调是从 native 线程发出来的，以前直接往下走，
        // 一路调到 PlayerActivity 里去 setVisibility，触发
        // "Only the original thread that created a view hierarchy can touch its views"
        // ——表现为切到 mpv 内核后 App 直接闪退。
        engine.listener = object : PlayerEngine.Listener {
            override fun onPrepared(durationMs: Long) {
                main.post {
                    // 起播预算已耗尽：内核晚到的事件不再改变状态（终态）
                    if (budgetExpired || released) return@post
                    prepared = true
                    main.removeCallbacks(startTimeout)
                    main.removeCallbacks(noDataWatchdog)
                    // 已经有字节了：转入阶段二，只看"有没有真的画出第一帧"。
                    if (!firstFrame && engineReportsFirstFrame) {
                        main.removeCallbacks(noFrameWatchdog)
                        main.postDelayed(
                            noFrameWatchdog,
                            minOf(
                                PlaybackDefaults.START_FRAME_MS.toLong(),
                                remainingBudgetMs(),
                            ),
                        )
                    }
                    prefs.setEngineFor(pendingChannelId, kind.key)
                }
            }

            override fun onPlaying() {
                main.post {
                    // 起播预算已耗尽：内核晚到的事件不再改变状态（终态）
                    if (budgetExpired || released) return@post
                    prepared = true
                    main.removeCallbacks(startTimeout)
                    main.removeCallbacks(noDataWatchdog)
                    if (!firstFrame && engineReportsFirstFrame) {
                        main.removeCallbacks(noFrameWatchdog)
                        main.postDelayed(
                            noFrameWatchdog,
                            minOf(
                                PlaybackDefaults.START_FRAME_MS.toLong(),
                                remainingBudgetMs(),
                            ),
                        )
                    }
                    startStallWatchdog()
                    callback?.onPlaying()
                }
            }

            override fun onError(message: String) {
                main.post {
                    // 起播预算已耗尽：内核晚到的事件不再改变状态（终态）
                    if (budgetExpired || released) return@post
                    if (currentKind == kind) tryNext(message)
                }
            }

            override fun onFirstFrame() {
                main.post {
                    // 起播预算已耗尽：内核晚到的事件不再改变状态（终态）
                    if (budgetExpired || released) return@post
                    firstFrame = true
                    main.removeCallbacks(noFrameWatchdog)
                    callback?.onFirstFrame()
                }
            }

            override fun onSurfaceLost(message: String) {
                main.post {
                    Log.w(TAG, "Surface 丢失：$message")
                    // 先把当前 Surface 重新挂上（Activity 那边可能已经有新的了）
                    current?.attach(context, surface, container)
                }
            }

            override fun onDecoderFailed(message: String) {
                main.post {
                    if (currentKind != kind) return@post
                    // 解码器失败：先换软解重试同一个内核。
                    // 换内核没用——VLC/mpv 用的是同一批 MediaCodec，
                    // 会拿同样的参数再失败一遍（实测 VLC 报
                    // "AMediaCodec.queueInputBuffer failed"），白等好几秒。
                    if (currentKind == EngineKind.EXO && !exoForceSoftwareDecode) {
                        Log.w(TAG, "解码器失败($message)，改用软解重试")
                        exoForceSoftwareDecode = true
                        retried = 0
                        startCurrent()
                        return@post
                    }
                    // 已经是软解还失败，说明这路码流本机真的解不了
                    if (currentKind == EngineKind.EXO && exoForceSoftwareDecode) {
                        Log.w(TAG, "软解也失败，该码流可能超出本机能力")
                        failLog += "exo软解: $message"
                    }
                    tryNext(message)
                }
            }

            override fun onVideoSizeChanged(width: Int, height: Int) {
                main.post { callback?.onVideoSizeChanged(width, height) }
            }

            override fun onEnded() = Unit
        }

        engine.aspect = prefs.aspect
        // 硬解已经被证明在这路码流上不行（exoForceSoftwareDecode 已置位）时，
        // 后面兜底的内核直接用软解——VLC/mpv 用的是同一批 MediaCodec，
        // 让它再试一遍硬解只会多黑屏几秒。
        val decodeMode = if (exoForceSoftwareDecode) "soft" else prefs.decode
        engine.prepare(pendingUrl, pendingHeaders, prefs.bufferMs, decodeMode)
        // ★ 新一轮起播：预算必须重新计时。
        //
        // attach() 也走完整起播（prepare + 超时），但它不走 startCurrent()，
        // 而 budgetDeadlineAt 只在 startCurrent() 里设置。于是这里拿到的是
        // **上一轮**（很可能早就过期）的 deadline → remainingBudgetMs() 返回 0
        // → startTimeout 的 wait 变成 0 → 立刻判超时。
        //
        // 真机表现：息屏唤醒后 0.4 秒就报「起播超时」，然后跑一遍重试流程。
        // 结果虽然能恢复，但完全是靠这个误报推动的，不是设计意图。
        budgetExpired = false
        released = false
        budgetDeadlineAt = android.os.SystemClock.elapsedRealtime() + PlaybackDefaults.START_BUDGET_MS
        main.removeCallbacks(budgetWatchdog)
        main.postDelayed(budgetWatchdog, PlaybackDefaults.START_BUDGET_MS.toLong())
        // 起播超时（prefs.startTimeoutMs，默认 15s）保留：慢盒子上的"有字节但很慢"
        // 仍然值得等，不能为了看起来快把它压小。它和 4 秒无字节、25 秒总预算三层互不冲突：
        // 谁先到谁生效，且都不超过总预算。
        val wait = minOf(prefs.startTimeoutMs.toLong(), remainingBudgetMs())
        main.removeCallbacks(startTimeout)
        main.postDelayed(startTimeout, wait)
    }

    private fun tryNext(reason: String) {
        main.removeCallbacks(startTimeout)
        Log.w(TAG, "内核 ${currentKind?.key} 失败($reason)，尝试下一个")
        failLog += "${currentKind?.key ?: "?"}: $reason"

        // 直连模式下第一个内核就失败：只有**网络层**失败才值得跳过其余内核。
        //
        // 真机事故（"突然失败，然后不播放"）：
        //   Exo 起播后 5 秒没出画面 → 报「有数据无画面」
        //   → 这里无条件当成"直连失败" → 跳过全部内核 → 直接停播
        // 但「有数据无画面」是解码/渲染问题，换内核本来可能就好了；
        // 「BEHIND_LIVE_WINDOW」是播放器状态问题，seek 回去就能继续。
        // 把它们当成网络失败，等于把"能救回来的"直接判死。
        if (directMode && chainIndex == 0 && isNetworkFailure(reason)) {
            Log.w(TAG, "直连首个内核失败（网络层：$reason），跳过其余内核")
            directMode = false
            onDirectFailed?.invoke(reason)
            return
        }
        chainIndex++
        if (chainIndex >= chain.size) {
            callback?.onError(chainFailMessage())
            return
        }
        startCurrent()
    }

    /**
     * 失败原因是不是网络/连接层的？
     *
     * 只有这类才值得"跳过其余内核"——因为直连时几个内核走的是同一条网络路径，
     * 换内核修不好。而解码、渲染、播放器状态类的失败，换内核/换解码恰恰有用。
     *
     * 判定保守：认不出来的一律当作**非**网络失败（让它继续试下一个内核），
     * 宁可多试一次，也不要把能救回来的台判死。
     */
    private fun isNetworkFailure(reason: String): Boolean {
        val r = reason.lowercase()
        val marks = listOf(
            "io_network", "unable to connect", "connection", "connect", "refused",
            "reset", "handshake", "ssl", "timeout", "超时", "unreachable",
            "unknownhost", "no address", "解析", "连不上", "网络",
        )
        return marks.any { r.contains(it) }
    }

    private fun create(kind: EngineKind): PlayerEngine? = when (kind) {
        EngineKind.EXO -> ExoEngine().also {
            it.playerView = playerView
            // 加载失败（源站回 HTML / 协议错误）立刻换内核，不等 12 秒看门狗
            it.onLoadFailure = { main.post { notifyLoadFailed("加载失败") } }
        }
        EngineKind.VLC -> VlcEngine()
        EngineKind.MPV -> MpvEngine(avs3Mode = false)
        EngineKind.AVS3 -> MpvEngine(avs3Mode = true)
    }

    fun pause() {
        // 用户切走（Activity onPause）时暂停，这时位置当然不前进——
        // 看门狗必须停掉，否则会误判成卡死并自动重启播放。
        userPaused = true
        stopStallWatchdog()
        current?.pause()
    }

    fun resume() {
        userPaused = false
        current?.play()
        if (prepared) startStallWatchdog()
    }
    fun setVolume(v: Float) = current?.setVolume(v)
    fun stats(): EngineStats = current?.stats() ?: EngineStats()
    fun engineLabel(): String = currentKind?.label ?: ""

    /** 内核 key（exo | vlc | mpv | avs3），QoE 上报用。 */
    fun engineKey(): String = currentKind?.key ?: ""
    fun snapshot() = current?.snapshot()

    /** 换台前抓一帧，盖住黑屏（TVBox 的做法，成本极低效果好）。 */
    fun stop() {
        removeStartTimers()
        stopStallWatchdog()
        current?.stop()
    }

    fun release() {
        // 先置终态：之后任何回调、任何延迟启动都不再执行
        released = true
        cancelPendingEngineStart()
        removeStartTimers()
        main.removeCallbacks(budgetWatchdog)
        stopStallWatchdog()
        disposeEngine(current)
        current = null
        currentKind = null
    }

    /**
     * 丢弃一个引擎时，把它的重资源一并释放。
     *
     * B04：VlcEngine 的 LibVLC 是重对象（内含 FFmpeg、网络栈、音频输出）。
     * 只调 PlayerEngine.release() 只放掉 MediaPlayer，LibVLC 会一直留着；
     * 而每次换内核都会 new 一个 VlcEngine，切几次就攒几个 LibVLC，最终 OOM。
     *
     * 这里按类型做最后一步清理。用 when 而不是接口方法，
     * 是因为只有 VLC 有这种"比播放器活得更久"的资源；
     * 给所有引擎加一个空接口方法反而会让所有权更模糊。
     */
    private fun disposeEngine(engine: PlayerEngine?) {
        if (engine == null) return
        engine.release()
        if (engine is VlcEngine) {
            try {
                engine.releaseLibVLC()
            } catch (t: Throwable) {
                Log.w(TAG, "释放 LibVLC 出错", t)
            }
        }
    }

    private companion object {
        const val TAG = "PlayerManager"

        /** 卡顿检测间隔。4 秒读一次播放位置，对 CPU 几乎没有开销。 */
        const val STALL_CHECK_MS = 4000L

        /**
         * 播放位置完全不变多久算卡死。
         *
         * 16 秒：网络抖动时位置可能短暂停几秒又继续，太快判定会把
         * 好好的直播反复重启；太慢用户又会觉得「卡了半天没反应」。
         */
        const val STALL_FROZEN_MS = 16000L

        /** 自动恢复的次数上限。超过就不再重启，直接报错。 */
        const val MAX_STALL_RECOVER = 3

        /**
         * 连续稳定播放这么久之后，把 [MAX_STALL_RECOVER] 的累计计数归零。
         *
         * 5 分钟的依据：一次网络抖动/服务端重推通常几十秒内恢复；能连续放满 5 分钟
         * 说明链路已经正常，再不让计数回落，长看同一个台迟早被"累计 4 次"判死。
         */
        const val STABLE_PLAY_RESET_MS = 5 * 60_000L
    }
}

/**
 * 卡死判定（纯逻辑，抽出来是为了能单测——时序接线仍然只能靠真机验证）。
 *
 * @param pos         当前播放位置，拿不到时内核返回负值
 * @param lastPosMs   上一次成功读到的位置（< 0 表示这个内核从来没给过位置）
 * @param frozenMs    位置保持不变已经过去多久
 * @param thresholdMs 判定阈值
 *
 * 为什么 `pos < 0` 不能一律跳过：直播流卡死后播放器会掉出正常状态，位置变成 -1。
 * 如果这时直接跳过判定，看门狗就永远不触发——真机表现就是「画面卡住、
 * 日志里一条错误都没有、只能换台」。只有当**从来没拿到过位置**时才跳过，
 * 那种情况是真的无从判断（有些内核本来就不给位置）。
 */
internal fun isStalled(pos: Long, lastPosMs: Long, frozenMs: Long, thresholdMs: Long): Boolean {
    if (thresholdMs <= 0L) return false
    if (pos < 0 && lastPosMs < 0) return false
    return frozenMs >= thresholdMs
}
