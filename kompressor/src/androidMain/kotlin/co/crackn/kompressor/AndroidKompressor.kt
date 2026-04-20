/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(ExperimentalKompressorApi::class)

package co.crackn.kompressor

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import co.crackn.kompressor.audio.AndroidAudioCompressor
import co.crackn.kompressor.audio.AudioCompressor
import co.crackn.kompressor.image.AndroidImageCompressor
import co.crackn.kompressor.image.ImageCompressor
import co.crackn.kompressor.logging.KompressorLogger
import co.crackn.kompressor.logging.LogTags
import co.crackn.kompressor.logging.PlatformLogger
import co.crackn.kompressor.logging.SafeLogger
import co.crackn.kompressor.video.AndroidVideoCompressor
import co.crackn.kompressor.video.VideoCompressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AndroidKompressor(logger: KompressorLogger) : Kompressor {
    private val safeLogger = SafeLogger(logger)

    override val image: ImageCompressor by lazy { AndroidImageCompressor(safeLogger) }
    override val video: VideoCompressor by lazy { AndroidVideoCompressor(safeLogger) }
    override val audio: AudioCompressor by lazy { AndroidAudioCompressor(safeLogger) }

    private val capabilities: DeviceCapabilities by lazy { queryDeviceCapabilities() }

    override suspend fun probe(inputPath: String): Result<SourceMediaInfo> = withContext(Dispatchers.IO) {
        safeLogger.debug(LogTags.PROBE) { "probe() inputPath=$inputPath" }
        val result = suspendRunCatching { probeAndroidSource(inputPath) }
        result.fold(
            onSuccess = { info ->
                safeLogger.info(LogTags.PROBE) {
                    "probe() ok — video=${info.videoCodec} ${info.width}x${info.height} " +
                        "audio=${info.audioCodec} durationMs=${info.durationMs}"
                }
            },
            onFailure = { throwable ->
                safeLogger.warn(LogTags.PROBE, throwable) { "probe() failed for $inputPath" }
            },
        )
        result
    }

    override fun canCompress(info: SourceMediaInfo): Supportability =
        evaluateSupport(
            info = info,
            capabilities = capabilities,
            requiredOutputVideoMime = MIME_VIDEO_H264,
            requiredOutputAudioMime = MIME_AUDIO_AAC,
        ).also { verdict ->
            safeLogger.debug(LogTags.PROBE) {
                "canCompress() verdict=${verdict::class.simpleName}"
            }
        }
}

/** Creates an Android [Kompressor] backed by MediaCodec and BitmapFactory. */
public actual fun createKompressor(): Kompressor = AndroidKompressor(PlatformLogger())

/** Creates an Android [Kompressor] that routes diagnostics through [logger]. */
public actual fun createKompressor(logger: KompressorLogger): Kompressor = AndroidKompressor(logger)

private fun probeAndroidSource(inputPath: String): SourceMediaInfo {
    val mmr = MediaMetadataRetriever()
    val extractor = MediaExtractor()
    try {
        mmr.setDataSource(inputPath)
        extractor.setDataSource(inputPath)
        return buildSourceInfo(mmr, extractor)
    } finally {
        runCatching { extractor.release() }
        runCatching { mmr.release() }
    }
}

// Long because SourceMediaInfo has 15+ fields; splitting further just moves the line
// count around without clarifying intent.
@Suppress("LongMethod")
private fun buildSourceInfo(mmr: MediaMetadataRetriever, extractor: MediaExtractor): SourceMediaInfo {
    val videoFormat = findTrack(extractor, "video/")
    val audioFormat = findTrack(extractor, "audio/")
    val audioTrackCount = countTracks(extractor, "audio/")
    val videoMime = videoFormat?.getString(MediaFormat.KEY_MIME)
    val videoProfile = videoFormat?.getIntOrNull(MediaFormat.KEY_PROFILE)
    val bitDepth = readProbeBitDepth(
        mime = videoMime,
        profile = videoProfile,
        colorFormat = videoFormat?.getIntOrNull(MediaFormat.KEY_COLOR_FORMAT),
        explicitBitDepth = videoFormat?.getIntOrNull("bit-depth"), // KEY_BIT_DEPTH, API 33+
    )
    val isHdr = isProbeHdrFormat(
        colorTransfer = videoFormat?.getIntOrNull(MediaFormat.KEY_COLOR_TRANSFER),
        colorStandard = videoFormat?.getIntOrNull(MediaFormat.KEY_COLOR_STANDARD),
        bitDepth = bitDepth,
    )
    return SourceMediaInfo(
        containerMimeType = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
        videoCodec = videoMime,
        videoProfile = readProbeProfileName(videoMime, videoProfile),
        videoLevel = videoFormat?.getIntOrNull(MediaFormat.KEY_LEVEL)?.let { "Level $it" },
        width = videoFormat?.getIntOrNull(MediaFormat.KEY_WIDTH),
        height = videoFormat?.getIntOrNull(MediaFormat.KEY_HEIGHT),
        bitDepth = bitDepth,
        rotationDegrees = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull(),
        frameRate = videoFormat?.getFloatOrNull(MediaFormat.KEY_FRAME_RATE),
        bitrate = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull(),
        durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
        isHdr = isHdr,
        audioCodec = audioFormat?.getString(MediaFormat.KEY_MIME),
        audioSampleRate = audioFormat?.getIntOrNull(MediaFormat.KEY_SAMPLE_RATE),
        audioChannels = audioFormat?.getIntOrNull(MediaFormat.KEY_CHANNEL_COUNT),
        audioTrackCount = audioTrackCount,
    )
}

private fun findTrack(extractor: MediaExtractor, mimePrefix: String): MediaFormat? {
    for (i in 0 until extractor.trackCount) {
        val fmt = extractor.getTrackFormat(i)
        val mime = fmt.getString(MediaFormat.KEY_MIME)
        if (mime != null && mime.startsWith(mimePrefix)) return fmt
    }
    return null
}

private fun countTracks(extractor: MediaExtractor, mimePrefix: String): Int {
    var count = 0
    for (i in 0 until extractor.trackCount) {
        val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
        if (mime != null && mime.startsWith(mimePrefix)) count++
    }
    return count
}

private fun MediaFormat.getIntOrNull(key: String): Int? =
    if (containsKey(key)) getInteger(key) else null

private fun MediaFormat.getFloatOrNull(key: String): Float? {
    if (!containsKey(key)) return null
    // MediaFormat sometimes stores KEY_FRAME_RATE as Integer, sometimes as Float,
    // depending on the source (MediaExtractor vs. encoder output). getFloat()
    // throws ClassCastException on an Integer value, so try both.
    return runCatching { getFloat(key) }
        .recoverCatching { getInteger(key).toFloat() }
        .getOrNull()
}
