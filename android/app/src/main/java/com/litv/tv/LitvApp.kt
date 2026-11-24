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
                    Log.i(TAG, "应用退到后台，暂停播放")
                    runCatching { player.pause() }
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

    /**
     * 全局唯一的播放器。
     *
     * 放在 Application 而不是 Activity：Activity 重建（旋屏、返回再进）不该
     * 重建播放器；配合 [com.litv.tv.player.PlaybackService] 常驻，
     * 播放器和它的缓冲可以一直活着，再次打开时只接 Surface 就行。
     */
    val player: com.litv.tv.player.PlayerManager by lazy {
        com.litv.tv.player.PlayerManager(this, prefs)
    }

    companion object {
        private const val TAG = "LitvApp"
    }
}
