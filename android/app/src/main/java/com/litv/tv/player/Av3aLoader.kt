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

        val dir = libDir(ctx)
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
            Status(
                dir = dir.absolutePath,
                jniFound = true,
                decFound = true,
                loaded = true,
                message = "已加载",
            )
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

    /** 是否可用（开关打开且库已成功加载）。 */
    fun available(ctx: Context): Boolean = cached?.loaded == true
}
