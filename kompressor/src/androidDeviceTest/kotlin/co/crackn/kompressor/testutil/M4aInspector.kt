package co.crackn.kompressor.testutil

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

data class AudioMetadata(val sampleRate: Int, val channels: Int)

fun readAudioMetadata(file: File): AudioMetadata {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(file.absolutePath)
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                return AudioMetadata(
                    sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                    channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                )
            }
        }
        error("No audio track in output")
    } finally {
        extractor.release()
    }
}

fun readAudioDurationMs(file: File): Long {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(file.absolutePath)
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                return format.getLong(MediaFormat.KEY_DURATION) / US_PER_MS
            }
        }
        error("No audio track in output")
    } finally {
        extractor.release()
    }
}

private const val US_PER_MS = 1_000L
