package com.litv.tv

import android.app.Application
import android.util.Log
import com.litv.tv.data.Api
import com.litv.tv.data.Prefs
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

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        api = Api(prefs)
        tier = Tier.detect(this)

        Log.i(
            TAG,
            "LITV 启动 | 渲染档=${tier.name} | libmpv=${if (MPVLib.available) "可用" else "未打包"} " +
                "| Android ${android.os.Build.VERSION.SDK_INT} | ${android.os.Build.MODEL}"
        )

        // 未捕获异常落日志，方便用户反馈"某某台一播就崩"
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Log.e(TAG, "未捕获异常", e)
            prev?.uncaughtException(t, e)
        }
    }

    companion object {
        private const val TAG = "LitvApp"
    }
}
