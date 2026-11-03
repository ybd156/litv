// 包名是**故意**的，原因见 Av3aLibrary.kt：
// libav3aJNI.so 的导出符号固定为
//   Java_androidx_media3_decoder_av3a_Av3aDecoder_av3aXxx
// 类全名和方法签名必须与之一致，否则 JNI 查不到方法。
package androidx.media3.decoder.av3a

import androidx.media3.common.C
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.DecoderOutputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.SimpleDecoderOutputBuffer

/**
 * AV3A（AVS3-P3 / Audio Vivid）解码器，接入 media3 的 SimpleDecoder 体系。
 *
 * 原生方法签名全部从 libav3aJNI.so 的导出符号反推得到，不要随意改：
 *
 *   av3aInit(II)J                                          创建上下文，返回句柄
 *   av3aDecode(J,ByteBuffer,I,ByteBuffer,I)I               解一帧，返回写入字节数（负数为错误）
 *   av3aReset(J)Z / av3aRelease(J)V
 *   av3aGetChannelCount(J)I / av3aGetSampleRate(J)I
 *
 * 解码库不随本项目分发（没有合法开源实现），由用户自行放入
 * Av3aLoader 指定的目录；库不可用时这个类根本不会被创建。
 */
class Av3aDecoder(
    private val sampleRateHint: Int,
    private val channelCountHint: Int,
) : SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, Av3aDecoderException>(
    // media3 的这个构造在 Java 侧允许传「长度固定、元素为 null」的数组，
    // 实际缓冲由 createInputBuffer/createOutputBuffer 惰性填充。
    // Kotlin 把元素类型看作非空，所以要显式强转。
    @Suppress("UNCHECKED_CAST")
    arrayOfNulls<DecoderInputBuffer>(INPUT_BUFFER_COUNT) as Array<DecoderInputBuffer>,
    @Suppress("UNCHECKED_CAST")
    arrayOfNulls<SimpleDecoderOutputBuffer>(OUTPUT_BUFFER_COUNT) as Array<SimpleDecoderOutputBuffer>,
) {

    private var ctx: Long = 0

    /** 解码库实际输出的采样率（拿不到就退回 hint）。 */
    var outputSampleRate: Int = sampleRateHint
        private set

    /** 解码库实际输出的声道数。 */
    var outputChannelCount: Int = channelCountHint
        private set

    init {
        ctx = try {
            av3aInit(sampleRateHint, channelCountHint)
        } catch (t: Throwable) {
            0L
        }
        if (ctx != 0L) {
            val sr = try { av3aGetSampleRate(ctx) } catch (t: Throwable) { 0 }
            val ch = try { av3aGetChannelCount(ctx) } catch (t: Throwable) { 0 }
            if (sr > 0) outputSampleRate = sr
            if (ch > 0) outputChannelCount = ch
        }
    }

    /** 原生上下文是否创建成功。false 时上层应报不支持。 */
    fun isReady(): Boolean = ctx != 0L

    override fun getName(): String = "av3a"

    override fun createInputBuffer(): DecoderInputBuffer =
        DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED)

    override fun createOutputBuffer(): SimpleDecoderOutputBuffer =
        SimpleDecoderOutputBuffer(object : DecoderOutputBuffer.Owner<SimpleDecoderOutputBuffer> {
            override fun releaseOutputBuffer(outputBuffer: SimpleDecoderOutputBuffer) {
                this@Av3aDecoder.releaseOutputBuffer(outputBuffer)
            }
        })

    override fun createUnexpectedDecodeException(error: Throwable): Av3aDecoderException =
        Av3aDecoderException("AV3A 解码异常", error)

    override fun decode(
        inputBuffer: DecoderInputBuffer,
        outputBuffer: SimpleDecoderOutputBuffer,
        reset: Boolean,
    ): Av3aDecoderException? {
        if (reset) {
            try {
                av3aReset(ctx)
            } catch (t: Throwable) {
                // 重置失败不致命
            }
        }
        if (inputBuffer.isEndOfStream) {
            outputBuffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
            return null
        }
        if (ctx == 0L) {
            return Av3aDecoderException("AV3A 上下文未创建")
        }
        val input = inputBuffer.data ?: return null
        val inSize = input.remaining()
        if (inSize <= 0) return null

        val output = outputBuffer.init(inputBuffer.timeUs, OUTPUT_BUFFER_SIZE)
        val written = try {
            av3aDecode(ctx, input, inSize, output, OUTPUT_BUFFER_SIZE)
        } catch (t: Throwable) {
            return Av3aDecoderException("AV3A 解码失败", t)
        }

        if (written < 0) {
            // 负数：库内部错误码。丢这一帧继续，别整条流挂掉。
            return null
        }
        if (written == 0) {
            // 这一帧还没凑够，等更多数据
            return null
        }
        output.position(0)
        output.limit(written)
        return null
    }

    override fun release() {
        try {
            if (ctx != 0L) av3aRelease(ctx)
        } catch (t: Throwable) {
            // 释放失败不致命
        }
        ctx = 0
        super.release()
    }

    private companion object {
        const val INPUT_BUFFER_COUNT = 4
        const val OUTPUT_BUFFER_COUNT = 4

        /** 输出 PCM 缓冲大小，与参考实现的常量一致。 */
        const val OUTPUT_BUFFER_SIZE = 65536

        @JvmStatic
        private external fun av3aInit(a: Int, b: Int): Long

        @JvmStatic
        private external fun av3aDecode(
            context: Long,
            input: java.nio.ByteBuffer,
            inputSize: Int,
            output: java.nio.ByteBuffer,
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
}
