package androidx.media3.decoder.av3a

import androidx.media3.decoder.DecoderException

/** AV3A 解码过程中的异常。 */
class Av3aDecoderException : DecoderException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable?) : super(message, cause)
}
