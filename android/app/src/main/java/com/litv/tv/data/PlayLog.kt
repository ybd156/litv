package com.litv.tv.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 播放日志落盘。
 *
 * 为什么需要：logcat 的环形缓冲很小（128 KiB），播久了之后
 * 「判定卡死 / 重缓冲 / 换内核」这些关键证据早被系统日志冲掉了，
 * 只能等下次复现。这里把关键事件单独写一份到文件，关掉 App 也还在。
 *
 * 落盘位置：外部私有目录 Android/data/<包名>/files/play.log
 * （电视是 Android 9，adb 可以直接 pull；Android 11+ 的 adb 读不到，
 *   届时用 App 内的「导出日志」）。
 *
 * 只记事件与数字，不记 URL / token / 签名参数。
 */
object PlayLog {

    private const val MAX_BYTES = 512 * 1024L

    @Volatile private var file: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    fun init(ctx: Context) {
        if (file != null) return
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        file = File(dir, "play.log")
    }

    fun w(tag: String, msg: String) {
        val f = file ?: return
        val line = "${fmt.format(Date())} $tag: $msg\n"
        synchronized(lock) {
            runCatching {
                if (f.length() > MAX_BYTES) {
                    // 简单轮转：留后半段
                    val keep = f.readLines().takeLast(2000)
                    f.writeText(keep.joinToString("\n", postfix = "\n"))
                }
                f.appendText(line)
            }
        }
    }
}
