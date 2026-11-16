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
        val ext = ctx.getExternalFilesDir(null)
        val dir = ext ?: ctx.filesDir
        file = File(dir, "play.log")
        // 打一次路径，方便排查"日志写到哪了"
        android.util.Log.i("PlayLog", "日志路径=" + file!!.absolutePath + " 外部可用=" + (ext != null))
    }

    /** 当前日志文件（可能为 null，表示还没写过）。 */
    fun currentFile(): File? = file

    /**
     * 导出到用户可访问的 Download 目录，返回写出的路径（失败返回 null）。
     *
     * 为什么需要：adb 在 Android 13 上读不到应用私有目录，
     * `getExternalFilesDir` 也可能返回 null 而回落到 filesDir，
     * 结果就是"App 在播但看不到日志"。导出到 Download 后 adb 直接能拉。
     */
    fun exportTo(ctx: Context): String? {
        val src = file ?: return null
        if (!src.isFile) return null
        val text = runCatching { src.readText() }.getOrNull() ?: return null
        val name = "litv-play.log"
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = ctx.contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                ctx.contentResolver.openOutputStream(uri)?.use {
                    it.write(text.toByteArray())
                } ?: return null
                "/sdcard/Download/$name"
            } else {
                val dir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS)
                val dst = File(dir, name)
                dst.writeText(text)
                dst.absolutePath
            }
        } catch (t: Throwable) {
            android.util.Log.w("PlayLog", "导出失败", t)
            null
        }
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
