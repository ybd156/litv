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
    private var hls: HlsMediaSource.Factory = HlsMediaSource.Factory(dsFactory).apply {
        if (av3aEnabled) setExtractorFactory(Av3aHlsExtractorFactory())
        // 允许"无分片准备"：起播时不必先把每个变体的 playlist 全拉一遍，
        // 少一个网络往返。IPTV 只有一个变体，这个优化是纯赚。
        setAllowChunklessPreparation(true)
    }

    private var progressive = ProgressiveMediaSource.Factory(dsFactory)

    // 下面两个 setter 以前直接 `= this` 把参数丢掉，等于 ExoPlayer 下发的
    // 错误处理策略永远到不了底层工厂——表现为 setLoadErrorHandlingPolicy
    // 写了却完全不起作用。必须真的转发。
    override fun setDrmSessionManagerProvider(
        drmSessionManagerProvider: DrmSessionManagerProvider,
    ): MediaSource.Factory {
        hls = hls.setDrmSessionManagerProvider(drmSessionManagerProvider)
        progressive = progressive.setDrmSessionManagerProvider(drmSessionManagerProvider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(
        loadErrorHandlingPolicy: LoadErrorHandlingPolicy,
    ): MediaSource.Factory {
        hls = hls.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        progressive = progressive.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        return this
    }

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
