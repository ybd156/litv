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

    /**
     * 上限。512KB 大约只够 3~4 小时（实测每分钟 5~10 条），
     * 要跑 24 小时观察就把上限放到 2MB，轮转时保留的行数同步放大。
     */
    private const val MAX_BYTES = 2 * 1024 * 1024L

    /** 轮转时保留的行数（约 2MB / 60 字节每行）。 */
    private const val KEEP_LINES = 24_000

    @Volatile private var file: File? = null

    /**
     * 时间格式器。
     *
     * `SimpleDateFormat` **不是线程安全的**，旧版在锁外调用 `fmt.format(Date())`，
     * 而写日志的线程有主线程、看门狗、Exo/VLC 回调、vlc-ops……并发下会串出
     * 乱码时间戳，极端情况下抛 `ArrayIndexOutOfBoundsException`。
     * 现在只在 [writeLine] 的 `synchronized(lock)` 里用（且只在 [io] 单线程上执行），
     * 两重保证下不再有并发访问。
     *
     * 没有换 `java.time`：minSdk = 21 且没有开 coreLibraryDesugaring，
     * `DateTimeFormatter` 在 Android 8.0 以下会直接 `NoClassDefFoundError`。
     */
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    /**
     * 写盘单线程。
     *
     * 为什么必须异步：2MB 轮转要 `readLines()` 整文件再 `writeText()`，
     * 旧版在**调用线程**做——而调用方经常是主线程（看门狗、Exo 的回调），
     * 一次轮转就是几百毫秒的卡顿。现在调用方只入队，落盘与轮转都在这里做，
     * 顺带把「同一把文件描述符上的顺序写」串行化。
     */
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "play-log").apply { isDaemon = true }
    }

    /** 待写条数；日志被刷爆时宁可丢行，也不能把调用方（可能是主线程）拖住。 */
    private val pending = java.util.concurrent.atomic.AtomicInteger(0)
    private const val MAX_PENDING = 512

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
        // 与写盘/轮转互斥：否则可能正好读到被 writeText() 截断一半的轮转中间态。
        val text = synchronized(lock) { runCatching { src.readText() }.getOrNull() } ?: return null
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

    /**
     * 追加一行。
     *
     * 调用方（可能是主线程）只做「取时间戳 + 入队」；格式化、轮转、落盘都在
     * [io] 单线程上，不阻塞调用方。
     */
    fun w(tag: String, msg: String) {
        val f = file ?: return
        // 时间戳必须在**调用线程**取：排队久了之后写盘时刻已经不是事件时刻。
        val ts = System.currentTimeMillis()
        if (pending.get() >= MAX_PENDING) return
        pending.incrementAndGet()
        try {
            io.execute {
                try {
                    writeLine(f, ts, tag, msg)
                } finally {
                    pending.decrementAndGet()
                }
            }
        } catch (t: Throwable) {
            // executor 已关闭（进程退出中）：丢弃这一行，不能抛到调用方
            pending.decrementAndGet()
        }
    }

    /** 轮转 + 追加，只在 [io] 单线程上执行；[lock] 同时挡住 exportTo 的读。 */
    private fun writeLine(f: File, ts: Long, tag: String, msg: String) {
        synchronized(lock) {
            runCatching {
                if (f.length() > MAX_BYTES) {
                    // 简单轮转：留后半段
                    val keep = f.readLines().takeLast(KEEP_LINES)
                    f.writeText(keep.joinToString("\n", postfix = "\n"))
                }
                // 格式化在锁内（SimpleDateFormat 非线程安全，见 fmt 的说明）
                f.appendText("${fmt.format(Date(ts))} $tag: $msg\n")
            }
        }
    }
}
