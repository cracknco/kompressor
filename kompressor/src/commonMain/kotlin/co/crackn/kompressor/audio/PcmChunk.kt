package co.crackn.kompressor.audio

/**
 * A chunk of raw PCM audio data copied from a decoder output buffer.
 *
 * The [data] array is a snapshot — it is safe to use after the decoder
 * buffer has been released. This enables streaming through a
 * [kotlinx.coroutines.channels.Channel] between concurrent decode/encode coroutines.
 *
 * @property data Raw PCM bytes (copied from the decoder's output buffer).
 * @property size Number of valid bytes in [data].
 * @property presentationTimeUs Presentation timestamp in microseconds.
 * @property isEndOfStream `true` when this is the final chunk (may have size == 0).
 */
internal class PcmChunk(
    val data: ByteArray,
    val size: Int,
    val presentationTimeUs: Long,
    val isEndOfStream: Boolean,
)
