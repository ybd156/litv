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
