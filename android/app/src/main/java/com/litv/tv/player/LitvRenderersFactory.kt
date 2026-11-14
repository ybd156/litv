package com.litv.tv.player

import android.content.Context
import android.os.Handler
import androidx.media3.decoder.av3a.Av3aAudioRenderer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import java.util.ArrayList

/**
 * 在默认渲染器列表末尾补一个 AV3A 音频渲染器。
 *
 * 为什么放在末尾：AV3A 是可选增强，不能影响常规音频。
 * 只有当解码库真的加载成功（用户放了 .so 且开关打开）时才添加；
 * 没库时列表和官方默认完全一致。
 */
class LitvRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    /**
     * 加大音频输出缓冲。
     *
     * 实测模拟器上反复出现
     *   audio_hw_generic: Hardware backing HAL too slow, could only write 0 of 720 frames
     * 音频 HAL 喂不进数据就会欠载，听感是断续 + 电音。
     * ExoPlayer 默认给 AudioTrack 的缓冲偏小（几十毫秒级），
     * 这里把最小缓冲抬到 250ms，让 HAL 有更多余量吸收抖动。
     *
     * 代价：音画同步的容忍度略微变宽（首帧音频延迟几十毫秒），
     * 对直播可接受，换来的是不再欠载。
     */
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): androidx.media3.exoplayer.audio.AudioSink? {
        return runCatching {
            androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                .setAudioTrackBufferSizeProvider(
                    androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider.Builder()
                        .setMinPcmBufferDurationUs(250_000)
                        .setMaxPcmBufferDurationUs(750_000)
                        .setPassthroughBufferDurationUs(250_000)
                        .build()
                )
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .build()
        }.getOrNull()
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out,
        )
        if (Av3aLoader.available(context)) {
            out.add(Av3aAudioRenderer(eventHandler, eventListener, audioSink))
        }
    }
}
