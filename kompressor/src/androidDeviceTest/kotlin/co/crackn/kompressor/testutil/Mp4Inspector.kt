package co.crackn.kompressor.testutil

import android.media.MediaExtractor
import android.media.MediaFormat
import co.crackn.kompressor.safeInt
import co.crackn.kompressor.safeLong
import java.io.File

/** Metadata extracted from an MP4 video file. */
data class VideoMetadata(
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val frameRate: Int,
)

/** Read video metadata from an MP4 file using [MediaExtractor]. */
fun readVideoMetadata(file: File): VideoMetadata {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(file.absolutePath)
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                return VideoMetadata(
                    width = format.getInteger(MediaFormat.KEY_WIDTH),
                    height = format.getInteger(MediaFormat.KEY_HEIGHT),
                    durationMs = format.safeLong(MediaFormat.KEY_DURATION) / US_PER_MS,
                    frameRate = format.safeInt(MediaFormat.KEY_FRAME_RATE),
                )
            }
        }
        error("No video track in output")
    } finally {
        extractor.release()
    }
}

private const val US_PER_MS = 1_000L
