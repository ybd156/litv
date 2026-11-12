package androidx.media3.extractor.ts

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator

/**
 * 读取 AV3A（AVS3-P3 / Audio Vivid）基本流。
 *
 * media3 不认识这个编码，TsExtractor 默认会直接把这条流丢掉。
 * 这里按 media3 自己的音频读取器（Ac3Reader）的结构写一份：
 *
 *   createTracks  -> 注册一条音频轨道，声明 MIME = audio/av3a
 *   packetStarted -> 拿到 PES 的 PTS
 *   consume       -> 把 PES 负载整块作为一帧交给解码器
 *
 * 音频 ES 一般一个 PES 就是一帧，所以不需要像 AC3 那样自己找同步头。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Av3aReader : ElementaryStreamReader {

    private var output: TrackOutput? = null
    private var timeUs: Long = C.TIME_UNSET

    override fun seek() {
        timeUs = C.TIME_UNSET
    }

    override fun createTracks(extractorOutput: ExtractorOutput, idGenerator: TrackIdGenerator) {
        idGenerator.generateNewId()
        val out = extractorOutput.track(idGenerator.trackId, C.TRACK_TYPE_AUDIO)
        output = out
        out.format(
            Format.Builder()
                .setId(idGenerator.formatId)
                .setSampleMimeType(MIME_AV3A)
                // 实际采样率/声道数由解码器给出（getOutputFormat 会用解码器返回的值），
                // 这里先给常见值，保证 Format 合法、能被渲染器接住。
                .setChannelCount(2)
                .setSampleRate(48000)
                .build()
        )
    }

    override fun packetStarted(pesTimeUs: Long, flags: Int) {
        timeUs = pesTimeUs
    }

    /** 一个 PES 结束。音频不需要额外处理。 */
    override fun packetFinished(isLast: Boolean) = Unit

    override fun consume(data: ParsableByteArray) {
        val out = output ?: return
        val size = data.bytesLeft()
        if (size <= 0) return
        out.sampleData(data, size)
        if (timeUs != C.TIME_UNSET) {
            out.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, size, 0, null)
            timeUs = C.TIME_UNSET
        }
    }

    companion object {
        /** 我们自己定的 MIME。渲染器按它来认领这条轨道。 */
        const val MIME_AV3A = "audio/av3a"
    }
}
