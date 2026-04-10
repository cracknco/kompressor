package co.crackn.kompressor.audio

/** Audio channel layout for compression output. */
enum class AudioChannels(
    /** Number of audio channels represented by this layout. */
    val count: Int,
) {
    /** Single channel. */
    MONO(1),

    /** Two channels (left + right). */
    STEREO(2),
}
