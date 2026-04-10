package co.crackn.kompressor

import co.crackn.kompressor.audio.AudioCompressor
import co.crackn.kompressor.image.ImageCompressor
import co.crackn.kompressor.video.VideoCompressor

/**
 * Main entry point for compression operations.
 *
 * Obtain an instance via [createKompressor]. Each compressor is lazily initialised —
 * only the ones you access are created.
 */
interface Kompressor {
    /** Image compressor for JPEG (and future format) compression. */
    val image: ImageCompressor

    /** Video compressor for H.264 (and future codec) compression. */
    val video: VideoCompressor

    /** Audio compressor for AAC (and future codec) compression. */
    val audio: AudioCompressor
}

/**
 * Create a platform-specific [Kompressor] instance.
 *
 * On Android the [android.content.Context] is obtained automatically via AndroidX App Startup.
 */
expect fun createKompressor(): Kompressor
