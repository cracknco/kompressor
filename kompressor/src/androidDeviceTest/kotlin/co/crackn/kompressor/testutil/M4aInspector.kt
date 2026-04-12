package co.crackn.kompressor.testutil

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File

data class AudioMetadata(val sampleRate: Int, val channels: Int)

data class AudioTrackInfo(val mime: String, val sampleRate: Int, val channels: Int, val bitrate: Int?)

fun readAudioTrackInfo(file: File): AudioTrackInfo {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(file.absolutePath)
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                return AudioTrackInfo(
                    mime = mime,
                    sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                    channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                    bitrate = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                        format.getInteger(MediaFormat.KEY_BIT_RATE)
                    } else {
                        null
                    },
                )
            }
        }
        error("No audio track in file")
    } finally {
        extractor.release()
    }
}

fun hasVideoTrack(file: File): Boolean {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(file.absolutePath)
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) return true
        }
        return false
    } finally {
        extractor.release()
    }
}

/** Read the container-level bitrate via MediaMetadataRetriever. Returns null if not reported. */
fun readContainerBitrate(file: File): Int? {
    val mmr = MediaMetadataRetriever()
    try {
        mmr.setDataSource(file.absolutePath)
        return mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()
    } finally {
        mmr.release()
    }
}

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
