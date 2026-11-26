package com.litv.tv

import android.app.Application
import android.util.Log
import com.litv.tv.data.Api
import com.litv.tv.data.DohDns
import com.litv.tv.data.Prefs
import com.litv.tv.data.RemoteClient
import com.litv.tv.data.XmltvEpg
// 注意：`is` 是 Kotlin 关键字，导入这个包必须用反引号转义
import `is`.xyz.mpv.MPVLib
import com.litv.tv.ui.Tier

/**
 * 全局单例。TV App 结构简单，不引 DI 框架（省体积、省启动时间）。
 */
class LitvApp : Application() {

    lateinit var prefs: Prefs
        private set
    lateinit var api: Api
        private set
    lateinit var tier: Tier
        private set

    /**
     * 遥控通道（电视端待命用）。
     *
     * 放在 Application 而不是 Activity：电视从首页进播放页时，
     * 如果两个 Activity 各自连一条 WS，服务端会把旧连接踢掉，来回打架。
     * 全局只连一条，当前 Activity 用 setCommandHandler 接管指令即可。
     */
    lateinit var remote: RemoteClient
        private set

    /**
     * 节目单（客户端自己下载解析 xmltv）。
     *
     * 放 Application 而不是 Activity：解析一次要几秒、常驻约 10 MB，
     * 电视端从首页进播放页会重建 Activity，放 Activity 里等于每次换页重解析。
     * 服务端只下发地址（bootstrap 的 `epg_url`），不存节目数据。
     */
    val epg: XmltvEpg by lazy { XmltvEpg(this) }

    /**
     * 轮询看门狗上一次看到的屏幕状态；null = 还没看过（第一次只记基线，不动作）。
     *
     * 为什么要有它：`ACTION_SCREEN_OFF` 不是所有关屏路径都会投递（2026-11-26 电视现场，
     * 面板已经黑了、`dumpsys power` 是 Asleep，接收器一行日志都没有）。只靠广播时，
     * 这种关屏不会停播，而 VLC 的「没送帧就轻推 pause/play」还会把已经停掉的播放拉起来。
     */
    private var screenWatchLast: Boolean? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        api = Api(prefs)
        tier = Tier.detect(this)
        remote = RemoteClient(prefs)

        // 加密 DNS 的开关与端点来自本地偏好：进程一启动就同步给解析器，
        // 否则第一次取流会先用默认值（阿里）跑一轮，用户改了设置也要等下次启动才生效。
        com.litv.tv.data.PlayLog.init(this)
        // ★ 构建身份：这是「设备上装的是哪一次构建」的唯一可靠判据。
        //   以前靠比 APK 与源码的 mtime，构建中改源码就会给出假绿灯（AGENTS §6 1b）。
        //   真机验证时对照：这里打印的 sha 必须等于要验证的那个 commit。
        Log.i(TAG, "BUILD sha=${BuildConfig.BUILD_SHA} v=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")
        com.litv.tv.data.PlayLog.w(TAG, "BUILD sha=${BuildConfig.BUILD_SHA} v=${BuildConfig.VERSION_NAME}")
        com.litv.tv.data.NetState.start(this)
        DohDns.attach(this)
        DohDns.configure(prefs.dohEnabled(), prefs.dohProvider)

        // 内置源（央视频）的频道表支持远程更新：sid/pid 会变，不该为几个数字发版。
        // 先吃本地缓存（立刻生效），再后台拉远程；失败就用编译进包里的默认表。
        // 注意必须在后台线程：这里有两次网络往返。
        Thread({
            runCatching { com.litv.tv.data.YspClient.refreshFromRemoteAsync(this) }
        }, "ysp-config").apply { isDaemon = true }.start()

        // 本地 HLS 代理要用 Cronet 拉回看（该 CDN 对 TLS 栈敏感），先给上下文。
        com.litv.tv.data.LocalHlsProxy.attach(this)

        // 整个应用退到后台就停播。
        //
        // 为什么不能只靠 Activity 的 onPause：打开设置/遥控器时 PhoneHomeActivity
        // 会**故意**跳过暂停（否则点设置就断流）；但用户从设置页直接按 HOME，
        // PhoneHomeActivity 不会再收到一次 onPause，播放器就一直响——
        // 「退后台声音还在播」就是这么来的。
        //
        // 这里按「所有 Activity 都不在前台」判定，并延迟 600ms 执行：
        // 应用内切页（首页→设置）会在窗口内重新 onResume，把这次暂停取消掉。
        registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            private var resumed = 0
            /** 是否已经真的进过一次前台。启动瞬间 LauncherActivity 起来又 finish，
             *  计数会短暂归零——那不算「退到后台」，不能因此停播。 */
            private var everResumed = false
            private val h = android.os.Handler(android.os.Looper.getMainLooper())
            private val pauseAll = Runnable {
                if (everResumed && resumed <= 0) {
                    // ★ 2026-11-25：改成暂停「所有还活着的播放器」。
                    //   原来是 player.pause()，而 player 是一个从未起播的 Application 实例：
                    //   真机实测（手机首页→设置页→按 HOME）日志打了这句、AudioTrack 仍 state:started，
                    //   也就是「退后台声音继续」这个老问题一直没被兜住。命中数进日志，0 就说明又打空了。
                    val n = com.litv.tv.player.LivePlayers.pauseAll()
                    Log.i(TAG, "应用退到后台，暂停播放（命中 $n 个）")
                }
            }

            override fun onActivityResumed(a: android.app.Activity) {
                resumed++
                everResumed = true
                h.removeCallbacks(pauseAll)
            }

            // 应用内切页时，新 Activity 的 onStart 会先于 onResume 到来。
            // 这里也取消一次，避免「首页 finish → 播放页 onStart」之间那段空档
            // 被误判成退到后台（电视上就是启动即停播、Surface 被释放）。
            override fun onActivityStarted(a: android.app.Activity) {
                h.removeCallbacks(pauseAll)
            }

            override fun onActivityPaused(a: android.app.Activity) {
                resumed--
                if (resumed <= 0) {
                    resumed = 0
                    h.removeCallbacks(pauseAll)
                    h.postDelayed(pauseAll, 1500)
                }
            }

            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityStopped(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })

        // 屏幕熄灭就静音 —— 电视上按电源键**只关面板**，系统不会把前台 Activity
        // 推到后台（2026-11-25 实测：`mWakefulness=Asleep`、`Display Power: state=OFF`，
        // 而 `ResumedActivity` 仍是 `PlayerActivity`），所以 onPause 与上面那条
        // 「所有 Activity 都不在前台」的兜底都不会触发，表现就是「屏幕黑了声音还在」。
        // 2026-11-26 按用户要求改成只静音、不停播（直播继续往前走，亮屏即现场）。
        // 亮屏只恢复这次因息屏静音的那些（用户自己调过音量、息屏后才起播的都不动）。
        val screenReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                when (i?.action) {
                    android.content.Intent.ACTION_SCREEN_OFF -> {
                        // 先记基线：否则下面那条 5 秒轮询会把它当成「刚发现熄灭」，再静音一遍。
                        screenWatchLast = false
                        // 默认静音（播放继续）；偏好设成「无动作」的人才继续放声音。
                        val action = com.litv.tv.player.ScreenOffPlan.of(prefs.screenOffAction)
                        if (action == com.litv.tv.player.ScreenOffAction.None) {
                            Log.i(TAG, "屏幕熄灭，按设置不处理（息屏处理=无动作）")
                            com.litv.tv.data.PlayLog.w(TAG, "广播：屏幕熄灭，按设置不处理（无动作）")
                            return
                        }
                        val n = com.litv.tv.player.LivePlayers.muteAllForScreenOff()
                        Log.i(TAG, "屏幕熄灭，静音（命中 $n 个）")
                        com.litv.tv.data.PlayLog.w(TAG, "广播：屏幕熄灭，静音（命中 $n 个）")
                    }
                    android.content.Intent.ACTION_SCREEN_ON -> {
                        screenWatchLast = true
                        val n = com.litv.tv.player.LivePlayers.unmuteAfterScreenOn()
                        if (n > 0) {
                            Log.i(TAG, "屏幕点亮，恢复音量（$n 个）")
                            com.litv.tv.data.PlayLog.w(TAG, "广播：屏幕点亮，恢复音量（$n 个）")
                        }
                    }
                }
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            screenReceiver,
            android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_SCREEN_OFF)
                addAction(android.content.Intent.ACTION_SCREEN_ON)
            },
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // 广播没来时的兜底：每 5 秒看一次 PowerManager.isInteractive。
        // 判断逻辑抽成纯函数 ScreenPowerWatch（可单测），事件同时写 PlayLog——
        // 电视上的 logcat 环形缓冲经常被滚掉，出问题时只剩 play.log 可查。
        val powerWatch = object : Runnable {
            override fun run() {
                val pm = getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
                val now = runCatching { pm?.isInteractive ?: true }.getOrDefault(true)
                when (com.litv.tv.player.ScreenPowerWatch.decide(screenWatchLast, now)) {
                    com.litv.tv.player.ScreenPowerEvent.Pause -> {
                        val action = com.litv.tv.player.ScreenOffPlan.of(prefs.screenOffAction)
                        if (action == com.litv.tv.player.ScreenOffAction.None) {
                            Log.i(TAG, "轮询发现屏幕已熄灭，按设置不处理")
                            com.litv.tv.data.PlayLog.w(TAG, "轮询发现屏幕熄灭，按设置不处理（无动作）")
                        } else {
                            val n = com.litv.tv.player.LivePlayers.muteAllForScreenOff()
                            Log.i(TAG, "轮询发现屏幕已熄灭，静音（命中 $n 个）")
                            com.litv.tv.data.PlayLog.w(TAG, "轮询发现屏幕熄灭，静音（命中 $n 个）")
                        }
                    }
                    com.litv.tv.player.ScreenPowerEvent.Resume -> {
                        val n = com.litv.tv.player.LivePlayers.unmuteAfterScreenOn()
                        if (n > 0) {
                            Log.i(TAG, "轮询发现屏幕已点亮，恢复音量（$n 个）")
                            com.litv.tv.data.PlayLog.w(TAG, "轮询发现屏幕点亮，恢复音量（$n 个）")
                        }
                    }
                    com.litv.tv.player.ScreenPowerEvent.None -> Unit
                }
                screenWatchLast = now
                mainHandler.postDelayed(this, 5_000)
            }
        }
        mainHandler.postDelayed(powerWatch, 5_000)

        // libmpv 的可用性放到后台再查：
        // MPVLib.available 会触发 System.loadLibrary("mpv")，在 onCreate 里同步做
        // 会拖慢冷启动（该 ABI 没有 .so 时还要抛一次异常）。启动日志晚一行无所谓。
        Log.i(
            TAG,
            "LITV 启动 | 渲染档=${tier.name} | DoH=${if (prefs.dohEnabled()) prefs.dohProvider else "off"} " +
                "| Android ${android.os.Build.VERSION.SDK_INT} | ${android.os.Build.MODEL}"
        )
        Thread({
            runCatching {
                Log.i(TAG, "libmpv=${if (MPVLib.available) "可用" else "不可用(该 ABI 无 .so)"}")
            }
        }, "litv-mpv-probe").start()

        // 未捕获异常落日志，方便用户反馈"某某台一播就崩"
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Log.e(TAG, "未捕获异常", e)
            prev?.uncaughtException(t, e)
        }
    }

    // ★ 2026-11-25 删除了 `val player: PlayerManager by lazy { ... }`。
    //
    //   它从来没有接管过正在播的播放器（只有退后台兜底读它），却因为 `by lazy`
    //   在第一次 `pause()` 时**创建了第 4 个 PlayerManager 实例**，还顺手注册了
    //   一个 ACTION_SCREEN_OFF 接收器。真机实测证明它暂停的是空对象：
    //   手机首页→设置页→按 HOME，日志打了「暂停播放」，AudioTrack 仍然 state:started。
    //   兜底改走 `LivePlayers.pauseAll()`（见上），这条假路径就没人需要了。

    companion object {
        private const val TAG = "LitvApp"
    }
}
