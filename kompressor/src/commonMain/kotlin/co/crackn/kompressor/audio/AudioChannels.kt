package co.crackn.kompressor.audio

/**
 * Audio channel layout for compression output.
 *
 * Mono and stereo are universally supported. Surround layouts ([FIVE_POINT_ONE],
 * [SEVEN_POINT_ONE]) require an AAC encoder that can emit multi-channel streams — this is the
 * case on every Android device exposing the standard `audio/mp4a-latm` encoder, and on every
 * iOS A10+ device. Upmix (e.g. mono source → stereo output, or stereo source → 5.1 output) is
 * **not supported** and surfaces a typed
 * [AudioCompressionError.UnsupportedConfiguration]; only same-count passthrough or downmix
 * (e.g. 5.1 → stereo) is allowed.
 */
enum class AudioChannels(
    /** Number of audio channels represented by this layout. */
    val count: Int,
) {
    /** Single channel. */
    MONO(1),

    /** Two channels (left + right). */
    STEREO(2),

    /**
     * 5.1 surround: front-left, front-right, front-center, LFE, back-left, back-right.
     * Channel order follows the ITU / ISO/IEC 23001-8 Mpeg5_1_D layout.
     */
    FIVE_POINT_ONE(6),

    /**
     * 7.1 surround: front-left, front-right, front-center, LFE, back-left, back-right,
     * side-left, side-right.
     */
    SEVEN_POINT_ONE(8),
}
