package com.litv.tv

import android.app.Application
import android.util.Log
import com.litv.tv.data.Api
import com.litv.tv.data.DohDns
import com.litv.tv.data.Prefs
import com.litv.tv.data.RemoteClient
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

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        api = Api(prefs)
        tier = Tier.detect(this)
        remote = RemoteClient(prefs)

        // 加密 DNS 的开关与端点来自本地偏好：进程一启动就同步给解析器，
        // 否则第一次取流会先用默认值（阿里）跑一轮，用户改了设置也要等下次启动才生效。
        DohDns.configure(prefs.dohEnabled(), prefs.dohProvider)

        Log.i(
            TAG,
            "LITV 启动 | 渲染档=${tier.name} | libmpv=${if (MPVLib.available) "可用" else "不可用(该 ABI 无 .so)"} " +
                "| DoH=${if (prefs.dohEnabled()) prefs.dohProvider else "off"} " +
                "| Android ${android.os.Build.VERSION.SDK_INT} | ${android.os.Build.MODEL}"
        )

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
