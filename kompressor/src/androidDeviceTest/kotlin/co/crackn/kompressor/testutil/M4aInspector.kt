/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.testutil

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File

data class AudioMetadata(val sampleRate: Int, val channels: Int)

data class AudioTrackInfo(val mime: String, val sampleRate: Int, val channels: Int, val bitrate: Int?)

fun readAudioTrackInfo(file: File): AudioTrackInfo {
    val format = findFirstTrackByMimePrefix(file, AUDIO_PREFIX)
        ?: error("No audio track in file")
    val mime = format.getString(MediaFormat.KEY_MIME) ?: error("Audio track missing MIME")
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

fun hasVideoTrack(file: File): Boolean =
    findFirstTrackByMimePrefix(file, VIDEO_PREFIX) != null

fun hasAudioTrack(file: File): Boolean =
    findFirstTrackByMimePrefix(file, AUDIO_PREFIX) != null

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
    val format = findFirstTrackByMimePrefix(file, AUDIO_PREFIX)
        ?: error("No audio track in output")
    return AudioMetadata(
        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
    )
}

fun readAudioDurationMs(file: File): Long {
    val format = findFirstTrackByMimePrefix(file, AUDIO_PREFIX)
        ?: error("No audio track in output")
    return format.getLong(MediaFormat.KEY_DURATION) / US_PER_MS
}

/**
 * Extract the first [MediaFormat] whose MIME starts with [mimePrefix]. Centralises extractor
 * lifecycle (setDataSource / release) so the individual helpers stay consistent.
 */
private fun findFirstTrackByMimePrefix(file: File, mimePrefix: String): MediaFormat? {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(file.absolutePath)
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) return format
        }
        return null
    } finally {
        extractor.release()
    }
}

private const val AUDIO_PREFIX = "audio/"
private const val VIDEO_PREFIX = "video/"
private const val US_PER_MS = 1_000L
