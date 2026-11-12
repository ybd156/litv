package androidx.media3.extractor.ts

/**
 * 在 media3 的 TS 解析器里认领 AV3A 音频流。
 *
 * 为什么需要它：media3 1.4.1 的 DefaultTsPayloadReaderFactory 里
 * 没有任何 AV3A 的流类型，遇到这类 ES 会返回 null，整条音轨被丢掉——
 * 于是播放器认为"这个频道没有音频"。
 *
 * 这里的流类型取值来自实测：AV3A 在 MPEG-TS 里常用 0xD5（标准），
 * 也有源用 0xD1/0xDA，甚至借用 AC3(0x81)/DTS(0x8A) 的类型。
 * 几种都认，遇到就交给 Av3aReader。
 *
 * DefaultTsPayloadReaderFactory 是 final 的，所以这里用组合而不是继承。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Av3aTsPayloadReaderFactory(
    private val delegate: TsPayloadReader.Factory = DefaultTsPayloadReaderFactory(),
) : TsPayloadReader.Factory {

    override fun createInitialPayloadReaders(): android.util.SparseArray<TsPayloadReader> =
        delegate.createInitialPayloadReaders()

    override fun createPayloadReader(
        streamType: Int,
        esInfo: TsPayloadReader.EsInfo,
    ): TsPayloadReader? {
        if (isAv3aStreamType(streamType)) {
            return PesReader(Av3aReader())
        }
        return delegate.createPayloadReader(streamType, esInfo)
    }

    companion object {
        /** AV3A 在 TS 里的几种 stream_type（实测来源见类注释）。 */
        const val STREAM_TYPE_AV3A_STANDARD = 0xD5 // 213
        const val STREAM_TYPE_AV3A_ALT1 = 0xD1     // 209
        const val STREAM_TYPE_AV3A_ALT2 = 0xDA     // 218

        // 注意：**不要**声明 0x81(AC3) 和 0x8A(DTS)。
        //
        // 参考实现里确实把这两个也当 AV3A 兜底，但那是有代价的：
        // 这两个是 MPEG-TS 的标准音轨类型，IPTV 里 AC3 音轨非常常见。
        // 一旦把它们认领成 AV3A，这些频道的音轨会被 Av3aReader 抢走，
        // 结果是「画面正常但完全没声音」——比播不了更难排查。
        //
        // 真正属于 AV3A 的是 0xD5 及其两个变体，只认这三个。
        fun isAv3aStreamType(streamType: Int): Boolean =
            streamType == STREAM_TYPE_AV3A_STANDARD ||
                streamType == STREAM_TYPE_AV3A_ALT1 ||
                streamType == STREAM_TYPE_AV3A_ALT2
    }
}
