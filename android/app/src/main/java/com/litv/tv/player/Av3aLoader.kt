package com.litv.tv.player

import android.content.Context
import android.util.Log
import java.io.File

/**
 * AV3A（AVS3-P3 / Audio Vivid）音频解码库的加载器。
 *
 * 为什么要用户自己提供库文件：
 *
 *   AV3A 是国标里的沉浸式音频编码，**没有合法的开源解码器**。
 *   AVS 官方只发标准文档，参考软件按许可协议分发；
 *   公开能拿到的源码是泄露出来的专有代码，公开能拿到的二进制是厂商的专有库。
 *
 *   所以本项目**不内置、不下载、不随 Release 分发**这些库，
 *   只提供加载逻辑。用户自己把库放到下面的目录即可启用。
 *
 * 放置位置（无需 root，adb 可直接推）：
 *
 *   /sdcard/Android/data/<包名>/files/av3a/
 *       libav3aJNI.so
 *       libAVS3AudioDec.so
 *
 *   命令示例：
 *     adb push libav3aJNI.so        /sdcard/Android/data/com.litv.tv/files/av3a/
 *     adb push libAVS3AudioDec.so   /sdcard/Android/data/com.litv.tv/files/av3a/
 *
 * 加载顺序很重要：libav3aJNI 依赖 libAVS3AudioDec，必须先加载后者。
 */
object Av3aLoader {

    private const val TAG = "Av3aLoader"
    const val DIR_NAME = "av3a"

    /** 加载结果。UI 上直接显示这个，用户能自己判断"放了没、加载成功没"。 */
    data class Status(
        val dir: String,
        val jniFound: Boolean,
        val decFound: Boolean,
        val loaded: Boolean,
        val message: String,
    ) {
        val ok: Boolean get() = loaded
    }

    @Volatile
    private var cached: Status? = null

    /** 库所在目录：应用私有外部目录，adb 可写，不需要 root。 */
    fun libDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, DIR_NAME)

    /**
     * 尝试加载。已成功过就直接返回缓存。
     *
     * 失败不会抛异常——AV3A 只是可选增强，缺库时其它功能必须照常可用。
     */
    @Synchronized
    fun load(ctx: Context, force: Boolean = false): Status {
        cached?.let { if (!force && it.loaded) return it }

        // 用户在外部目录放库（adb 可直接推，免 root），
        // 但 Android 9+ 的 linker 不允许从 /sdcard 加载 .so
        // （permitted_paths 只含 /data/data/<pkg>），
        // 所以先复制到应用内部目录，再从那里 load。
        val extDir = libDir(ctx)
        val intDir = File(ctx.filesDir, DIR_NAME)
        copyInto(extDir, intDir)

        val dir = intDir
        val jni = File(dir, "libav3aJNI.so")
        val dec = File(dir, "libAVS3AudioDec.so")

        if (!jni.exists() || !dec.exists()) {
            val st = Status(
                dir = dir.absolutePath,
                jniFound = jni.exists(),
                decFound = dec.exists(),
                loaded = false,
                message = "未找到解码库（放到 " + dir.absolutePath + " 后重试）",
            )
            cached = st
            Log.i(TAG, st.message)
            return st
        }

        val st = try {
            // 依赖在前：libav3aJNI 会用到 libAVS3AudioDec 的符号
            System.load(dec.absolutePath)
            System.load(jni.absolutePath)
            // 加载成功还不够：再调一次 JNI 拿版本号，
            // 只有这一步通了才说明符号绑定真的对上（类名/签名一致）。
            val ver = androidx.media3.decoder.av3a.Av3aLibrary.getVersion()
            if (ver.isEmpty()) {
                Status(
                    dir = dir.absolutePath,
                    jniFound = true,
                    decFound = true,
                    loaded = false,
                    message = "库已加载但 JNI 绑定失败（类名或签名不匹配）",
                )
            } else {
                Status(
                    dir = dir.absolutePath,
                    jniFound = true,
                    decFound = true,
                    loaded = true,
                    message = "已加载 · 解码库版本 " + ver,
                )
            }
        } catch (t: Throwable) {
            // 常见原因：ABI 不匹配、文件损坏、SELinux 限制
            Status(
                dir = dir.absolutePath,
                jniFound = true,
                decFound = true,
                loaded = false,
                message = "加载失败：" + (t.message ?: t.javaClass.simpleName),
            )
        }
        cached = st
        Log.i(TAG, "AV3A " + st.message)
        return st
    }

    /**
     * 把外部目录里的库复制到内部目录。
     *
     * 只在大小不同或目标不存在时才复制，避免每次启用都重写一遍。
     * 复制失败不抛异常——后面加载会报出更具体的原因。
     */
    private fun copyInto(from: File, to: File) {
        if (!from.isDirectory) return
        if (!to.exists() && !to.mkdirs()) return
        val names = listOf("libAVS3AudioDec.so", "libav3aJNI.so")
        for (n in names) {
            val src = File(from, n)
            if (!src.isFile) continue
            val dst = File(to, n)
            if (dst.isFile && dst.length() == src.length()) continue
            try {
                src.inputStream().use { ins ->
                    dst.outputStream().use { outs -> ins.copyTo(outs) }
                }
                // 让 linker 能读；应用私有目录不需要可执行位
                dst.setReadable(true, true)
            } catch (t: Throwable) {
                Log.w(TAG, "复制 " + n + " 失败：" + (t.message ?: ""))
            }
        }
    }

    /** 是否可用（开关打开且库已成功加载）。 */
    fun available(ctx: Context): Boolean = cached?.loaded == true
}
