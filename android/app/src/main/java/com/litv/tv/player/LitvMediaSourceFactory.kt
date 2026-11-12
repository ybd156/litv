package com.litv.tv.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ts.Av3aHlsExtractorFactory

/**
 * 媒体源工厂：HLS 走带 AV3A 支持的解析器，其它走渐进式。
 *
 * media3 1.4.1 的 DefaultMediaSourceFactory 没有注入自定义 HlsExtractorFactory 的入口，
 * 所以这里自己按 URL 分派。判据就是 .m3u8 后缀——IPTV 场景够用。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LitvMediaSourceFactory(
    dsFactory: DataSource.Factory,
    private val av3aEnabled: Boolean = false,
) : MediaSource.Factory {

    /**
     * HLS 解析器。
     *
     * 只有**装了 AV3A 解码库**时才替换成自定义解析器——
     * 没库的时候必须用 media3 默认实现。
     *
     * 这里踩过坑：之前无条件套用 Av3aHlsExtractorFactory，
     * 结果所有 HLS 频道都被迫走自定义 TsExtractor 路径，
     * 直连播放时 Exo 拿不到任何轨道，15 秒超时后黑屏。
     * 用户机器上根本没放 AV3A 库，却因为这个改动影响了全部频道。
     */
    private val hls: HlsMediaSource.Factory = HlsMediaSource.Factory(dsFactory).apply {
        if (av3aEnabled) setExtractorFactory(Av3aHlsExtractorFactory())
    }

    private val progressive = ProgressiveMediaSource.Factory(dsFactory)

    override fun setDrmSessionManagerProvider(
        drmSessionManagerProvider: DrmSessionManagerProvider,
    ): MediaSource.Factory = this

    override fun setLoadErrorHandlingPolicy(
        loadErrorHandlingPolicy: LoadErrorHandlingPolicy,
    ): MediaSource.Factory = this

    override fun getSupportedTypes(): IntArray =
        intArrayOf(C.CONTENT_TYPE_HLS, C.CONTENT_TYPE_OTHER)

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val uri = mediaItem.localConfiguration?.uri
        val path = uri?.lastPathSegment ?: ""
        return if (path.endsWith(".m3u8", ignoreCase = true)) {
            hls.createMediaSource(mediaItem)
        } else {
            progressive.createMediaSource(mediaItem)
        }
    }
}
