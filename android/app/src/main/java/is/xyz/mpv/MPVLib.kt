// `is` 是 Kotlin 硬关键字，包声明和 import 都必须用反引号转义。
// JVM 侧生成的类名仍然是 is.xyz.mpv.MPVLib，正好对上 native 的 JNI 符号。
package `is`.xyz.mpv

import android.content.Context
import android.graphics.Bitmap
import android.view.Surface

/**
 * libmpv 的 JNI 绑定。
 *
 * ============================ 重要说明 ============================
 * 类名和包名 **必须** 是 `is.xyz.mpv.MPVLib`，不能改。
 *
 * 原因：native 侧用的是标准 JNI 命名约定（`Java_is_xyz_mpv_MPVLib_xxx`），
 * 我们当前用的是 mpv-android 官方 release 里预编译的 libplayer.so，
 * 它的符号就是按 `is.xyz.mpv.MPVLib` 导出的。改包名 → UnsatisfiedLinkError。
 *
 * 换自编 .so 时怎么办：
 *   自编的 libmpv 如果不带 JNI 胶水，就自己写 C 侧的 `Java_com_litv_...`
 *   或者干脆保留这个包名（推荐，零改动）。上层 MpvEngine 完全不感知。
 * =================================================================
 *
 * .so 依赖关系（都放 jniLibs/<abi>/，由系统 linker 自动按需加载）：
 *   libplayer.so  →  libmpv.so  →  libav*.so / libc++_shared.so
 */
object MPVLib {

    /** 事件回调。由 MpvEngine 实现。 */
    interface Listener {
        fun onPropertyChanged(name: String, value: Any?)
        fun onEvent(eventId: Int)
        fun onLog(prefix: String, level: Int, text: String)
    }

    @Volatile
    var listener: Listener? = null

    /** .so 是否加载成功（缺文件时为 false，上层自动降级）。 */
    var available: Boolean = false
        private set

    init {
        available = try {
            // 加载 libplayer.so；它会带着 libmpv.so 和 ffmpeg 一起进来
            System.loadLibrary("player")
            true
        } catch (t: Throwable) {
            false
        }
    }

    // ---------------- native 方法（签名必须与 C 侧一致，不要改） ----------------
    external fun create(appctx: Context)
    external fun init()
    external fun destroy()

    external fun command(cmd: Array<String>)

    external fun setOptionString(name: String, value: String)
    external fun setPropertyString(name: String, value: String)
    external fun setPropertyInt(name: String, value: Int)
    external fun setPropertyDouble(name: String, value: Double)
    external fun setPropertyBoolean(name: String, value: Boolean)

    external fun getPropertyString(name: String): String?
    external fun getPropertyInt(name: String): Int
    external fun getPropertyDouble(name: String): Double
    external fun getPropertyBoolean(name: String): Boolean

    external fun observeProperty(name: String, format: Int)

    external fun attachSurface(surface: Surface?)
    external fun detachSurface()
    external fun grabThumbnail(): Bitmap?

    // ---------------- native 反向调用（JNI 会直接找这些方法） ----------------
    // 注意：这些**不能**是 private，否则 JNI 找不到。

    @JvmStatic
    fun eventProperty(property: String) {
        listener?.onPropertyChanged(property, null)
    }

    @JvmStatic
    fun eventProperty(property: String, value: Boolean) {
        listener?.onPropertyChanged(property, value)
    }

    @JvmStatic
    fun eventProperty(property: String, value: Long) {
        listener?.onPropertyChanged(property, value)
    }

    @JvmStatic
    fun eventProperty(property: String, value: Double) {
        listener?.onPropertyChanged(property, value)
    }

    @JvmStatic
    fun eventProperty(property: String, value: String) {
        listener?.onPropertyChanged(property, value)
    }

    @JvmStatic
    fun event(eventId: Int) {
        listener?.onEvent(eventId)
    }

    @JvmStatic
    fun logMessage(prefix: String, level: Int, text: String) {
        listener?.onLog(prefix, level, text)
    }

    // ---------------- mpv 常量（与 client.h / event.h 对齐） ----------------
    const val FORMAT_NONE = 0
    const val FORMAT_STRING = 1
    const val FORMAT_OSD_STRING = 2
    const val FORMAT_FLAG = 3
    const val FORMAT_INT64 = 4
    const val FORMAT_DOUBLE = 5

    const val EVENT_NONE = 0
    const val EVENT_SHUTDOWN = 1
    const val EVENT_LOG_MESSAGE = 2
    const val EVENT_END_FILE = 7
    const val EVENT_FILE_LOADED = 8
    const val EVENT_VIDEO_RECONFIG = 17
}
