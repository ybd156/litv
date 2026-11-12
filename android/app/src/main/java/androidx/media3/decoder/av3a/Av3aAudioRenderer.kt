package androidx.media3.decoder.av3a

import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.CryptoConfig
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DecoderAudioRenderer

/**
 * AV3A 音频渲染器：把 [Av3aDecoder] 接到 media3 的音频管线。
 *
 * 继承 `DecoderAudioRenderer` 就不用自己管 AudioSink、时钟、丢帧这些，
 * 只要回答三件事：这个格式支持吗、怎么建解码器、输出什么格式。
 *
 * 注意：只有当解码库加载成功时，上层（LitvRenderersFactory）才会把它
 * 加进渲染器列表——没库时列表里没有它，其它音频照常走系统解码。
 */
class Av3aAudioRenderer(
    eventHandler: Handler,
    eventListener: AudioRendererEventListener,
    audioSink: AudioSink,
) : DecoderAudioRenderer<Av3aDecoder>(eventHandler, eventListener, audioSink) {

    override fun getName(): String = "Av3aAudioRenderer"

    override fun supportsFormatInternal(format: Format): Int {
        // 只接 AV3A。media3 目前不认这个编码，
        // 所以真实流大概率进不到这里——见 README 里「已知限制」的说明。
        val mime = format.sampleMimeType ?: return C.FORMAT_UNSUPPORTED_TYPE
        if (!mime.contains("av3a", ignoreCase = true) &&
            !mime.contains("avs3", ignoreCase = true)
        ) {
            return C.FORMAT_UNSUPPORTED_TYPE
        }
        return if (format.sampleRate > 0) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE
    }

    override fun createDecoder(format: Format, cryptoConfig: CryptoConfig?): Av3aDecoder =
        Av3aDecoder(format.sampleRate, format.channelCount)

    override fun getOutputFormat(decoder: Av3aDecoder): Format {
        val sr = if (decoder.outputSampleRate > 0) decoder.outputSampleRate else 48000
        val ch = if (decoder.outputChannelCount > 0) decoder.outputChannelCount else 2
        return Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setSampleRate(sr)
            .setChannelCount(ch)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .build()
    }
}
