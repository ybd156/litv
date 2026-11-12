package androidx.media3.extractor.ts;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.util.TimestampAdjuster;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.hls.BundledHlsMediaChunkExtractor;
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory;
import androidx.media3.exoplayer.hls.HlsExtractorFactory;
import androidx.media3.exoplayer.hls.HlsMediaChunkExtractor;
import androidx.media3.extractor.ExtractorInput;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * HLS 分片解析器工厂：TS 分片改用带 AV3A 支持的 TsExtractor。
 *
 * 用 Java 写而不是 Kotlin：这个接口的参数类型全是 Java 集合，
 * Kotlin 侧对 java.util.List/Map 的平台类型匹配一直报 "overrides nothing"，
 * 换 Java 直接对上，省掉互相迁就。
 *
 * 只对真正的 TS 分片（首字节 0x47 同步字）替换，
 * 其它格式（fMP4 等）原样交给 media3 默认实现，避免把能播的流搞坏。
 */
@UnstableApi
// media3 1.4.1 里 BundledHlsMediaChunkExtractor 的三参构造被标了 deprecated，
// 但它是把自定义 TsExtractor 接进 HLS 分片解析的唯一公开入口——
// 换别的写法就得自己实现 HlsMediaChunkExtractor，反而更脆。
// 等 media3 升级到提供替代 API 时再改。
@SuppressWarnings("deprecation")
public final class Av3aHlsExtractorFactory implements HlsExtractorFactory {

    private final HlsExtractorFactory delegate = new DefaultHlsExtractorFactory();

    @Override
    public HlsMediaChunkExtractor createExtractor(
            Uri uri,
            Format format,
            @Nullable List<Format> muxedCaptionFormats,
            TimestampAdjuster timestampAdjuster,
            Map<String, List<String>> responseHeaders,
            ExtractorInput extractorInput,
            PlayerId playerId)
            throws IOException {
        if (looksLikeTs(extractorInput)) {
            TsExtractor ts = new TsExtractor(
                    TsExtractor.MODE_MULTI_PMT,
                    timestampAdjuster,
                    new Av3aTsPayloadReaderFactory());
            return new BundledHlsMediaChunkExtractor(ts, format, timestampAdjuster);
        }
        return delegate.createExtractor(
                uri, format, muxedCaptionFormats, timestampAdjuster,
                responseHeaders, extractorInput, playerId);
    }

    /** 用 peek 看首字节，不消费数据。 */
    private static boolean looksLikeTs(ExtractorInput input) {
        try {
            byte[] b = new byte[1];
            input.peekFully(b, 0, 1);
            return b[0] == 0x47;
        } catch (IOException e) {
            return false;
        }
    }
}
