package com.litv.tv.player

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.Surface

/**
 * libmpv 的 JNI 绑定。
 *
 * 重要事实（调研已确认）：mpv-android 官方**不提供 AAR**，README 原文写着
 * "not a library/module (AAR) you can import into your app"。
 * 所以这里按官方指引，把 MPVLib 的 JNI 声明搬进我们自己的工程，
 * .so 由 native/build-libmpv.sh 交叉编译产出后放进 jniLibs。
 *
 * 没有 .so 时整个对象会进入 available=false 状态，MpvEngine 自报不可用，
 * 播放器自动降级到 Exo/VLC —— 保证 App 在任何情况下都能装能跑。
 */
object MpvNative {

    private const val TAG = "MpvNative"

    /** .so 是否加载成功。 */
    var available: Boolean = false
        private set

    init {
        available = try {
            System.loadLibrary("mpv")
            true
        } catch (t: Throwable) {
            Log.i(TAG, "libmpv.so 未提供，mpv 内核不可用（这是预期行为，除非已编译 native）")
            false
        }
    }

    // ---- 生命周期 ----
    external fun create(appctx: Context)
    external fun init()
    external fun destroy()

    // ---- 命令与属性 ----
    external fun command(cmd: Array<String>)
    external fun setOptionString(name: String, value: String)
    external fun setPropertyString(name: String, value: String)
    external fun getPropertyString(name: String): String?
    external fun getPropertyInt(name: String): Int
    external fun setPropertyInt(name: String, value: Int)
    external fun observeProperty(name: String, format: Int)

    // ---- 渲染 ----
    external fun attachSurface(surface: Surface?)
    external fun detachSurface()
    external fun grabThumbnail(): Bitmap?

    // ---- 事件循环（阻塞，需在独立线程跑）----
    external fun eventLoop()

    // ---- 以下由 native 回调（JNI 反向调用，签名必须与 C 侧一致）----
    @Suppress("UNUSED_PARAMETER")
    fun eventProperty(property: String) = MpvEngine.onProperty(property)

    @Suppress("UNUSED_PARAMETER")
    fun eventEvent(eventId: Int) = MpvEngine.onEvent(eventId)

    @Suppress("UNUSED_PARAMETER")
    fun eventLogMessage(prefix: String, level: String, text: String) {
        if (level == "error" || level == "fatal") Log.w(TAG, "$prefix$text")
    }

    // mpv 的格式常量（与 client.h 对齐）
    const val FORMAT_NONE = 0
    const val FORMAT_STRING = 1
    const val FORMAT_FLAG = 3
    const val FORMAT_INT64 = 4
    const val FORMAT_DOUBLE = 5

    // mpv 事件 ID（与 event.h 对齐）
    const val EVENT_NONE = 0
    const val EVENT_SHUTDOWN = 1
    const val EVENT_FILE_LOADED = 8
    const val EVENT_END_FILE = 7
    const val EVENT_VIDEO_RECONFIG = 17
}
