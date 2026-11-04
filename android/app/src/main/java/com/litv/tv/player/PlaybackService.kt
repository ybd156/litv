package com.litv.tv.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.litv.tv.LitvApp

/**
 * 播放常驻服务。
 *
 * 为什么要有它：起播慢的大头不是解码，而是**每次打开都要重新建播放器、
 * 重新连源站**。把播放器放进前台 Service 之后：
 *   - 进程不会因为 Activity 退到后台被回收，播放器实例一直是热的
 *   - 再次打开 App 时只需把 Surface 接上去，不用重建 ExoPlayer / 重新握手
 *   - 音频可以在后台继续（像参考实现那样）
 *
 * 前台通知是必须的：Android 8+ 后台服务会被杀，只有前台服务能长期存活。
 */
class PlaybackService : Service() {

    companion object {
        private const val TAG = "PlaybackService"
        private const val CHANNEL_ID = "litv_playback"
        private const val NOTIF_ID = 1001

        const val ACTION_STOP = "com.litv.tv.action.STOP_PLAYBACK"

        @Volatile
        private var instance: PlaybackService? = null

        /** 服务在跑就返回它的播放器，否则 null。 */
        fun playerOrNull(): PlayerManager? = instance?.player

        /** 确保服务起来（幂等）。 */
        fun ensureRunning(context: Context) {
            val i = Intent(context, PlaybackService::class.java)
            runCatching { context.startForegroundService(i) }
        }
    }

    lateinit var player: PlayerManager
        private set

    private var currentTitle: String = ""

    inner class LocalBinder : Binder() {
        fun player(): PlayerManager = this@PlaybackService.player
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        instance = this
        val app = applicationContext as LitvApp
        // 用 Application 里的单例：Activity 重建不会重建播放器
        player = app.player

        // 一创建就把通知挂上——前台服务必须尽快 startForeground，
        // 否则系统会在 5 秒内抛 ANR。
        startForeground(NOTIF_ID, buildNotification("准备播放"))

        Log.i(TAG, "播放服务已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            player.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        Log.i(TAG, "播放服务销毁")
        instance = null
        runCatching { player.release() }
        super.onDestroy()
    }

    /** 播放内容变化时更新通知文案。 */
    fun updateNotification(title: String) {
        if (title == currentTitle) return
        currentTitle = title
        val nm = getSystemService(NotificationManager::class.java)
        runCatching { nm.notify(NOTIF_ID, buildNotification(title)) }
    }

    private fun buildNotification(title: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "播放",
                    // 低优先级：不出声、不弹横幅，只在通知栏留一条
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) }
            )
        }

        // 点通知回到 App：用启动 Activity 的隐式意图，避免直接依赖具体 Activity 类
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val contentPi = launch?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title.ifBlank { "LITV" })
            .setContentText("正在播放")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(contentPi)
            .addAction(
                Notification.Action.Builder(
                    null, "停止", stopPi
                ).build()
            )
            .build()
    }
}
