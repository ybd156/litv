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
        DohDns.configure(prefs.dohEnabled(), prefs.dohProvider)

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
