// 包名是**故意**的，原因见 Av3aLibrary.kt 顶部说明：
// libav3aJNI.so 的导出符号固定为
//   Java_androidx_media3_decoder_av3a_Av3aDecoder_av3aXxx
// 所以类全名和方法签名都必须与之一致。
package androidx.media3.decoder.av3a

import java.nio.ByteBuffer

/**
 * AV3A（AVS3-P3 / Audio Vivid）解码器的 JNI 封装。
 *
 * 方法签名全部从原生库的导出符号反推得到，**不要随意改**：
 *
 *   av3aInit(II)J                                                 创建上下文，返回 native 句柄
 *   av3aDecode(J, ByteBuffer, I, ByteBuffer, I)I                  解一帧，返回写入的字节数（负数为错误码）
 *   av3aReset(J)Z                                                 重置
 *   av3aRelease(J)V                                               释放
 *   av3aGetChannelCount(J)I                                       声道数
 *   av3aGetSampleRate(J)I                                         采样率
 *
 * 解码库本身不随本项目分发（没有合法的开源实现），
 * 需要用户自行放入 Av3aLoader 指定的目录。
 */
internal class Av3aDecoder {

    companion object {
        /** 输出缓冲建议大小，与原实现的常量一致。 */
        const val OUTPUT_BUFFER_SIZE = 65536

        /** 通用错误码（原实现里 ERROR_OTHER = -2）。 */
        const val ERROR_OTHER = -2

        @JvmStatic
        private external fun av3aInit(a: Int, b: Int): Long

        @JvmStatic
        private external fun av3aDecode(
            context: Long,
            input: ByteBuffer,
            inputSize: Int,
            output: ByteBuffer,
            outputSize: Int,
        ): Int

        @JvmStatic
        private external fun av3aReset(context: Long): Boolean

        @JvmStatic
        private external fun av3aRelease(context: Long)

        @JvmStatic
        private external fun av3aGetChannelCount(context: Long): Int

        @JvmStatic
        private external fun av3aGetSampleRate(context: Long): Int
    }

    // ---- 下面这些是给 Kotlin 侧用的安全包装：把 UnsatisfiedLinkError 挡在库里 ----

    fun init(a: Int = 0, b: Int = 0): Long = try {
        av3aInit(a, b)
    } catch (t: Throwable) {
        0L
    }

    fun decode(ctx: Long, input: ByteBuffer, inSize: Int, output: ByteBuffer, outSize: Int): Int =
        try {
            av3aDecode(ctx, input, inSize, output, outSize)
        } catch (t: Throwable) {
            ERROR_OTHER
        }

    fun reset(ctx: Long): Boolean = try {
        av3aReset(ctx)
    } catch (t: Throwable) {
        false
    }

    fun release(ctx: Long) {
        try {
            av3aRelease(ctx)
        } catch (t: Throwable) {
            // 释放失败不致命
        }
    }

    fun channelCount(ctx: Long): Int = try {
        av3aGetChannelCount(ctx)
    } catch (t: Throwable) {
        0
    }

    fun sampleRate(ctx: Long): Int = try {
        av3aGetSampleRate(ctx)
    } catch (t: Throwable) {
        0
    }
}
