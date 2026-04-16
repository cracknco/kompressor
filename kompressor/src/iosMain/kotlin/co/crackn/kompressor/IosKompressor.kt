/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

import co.crackn.kompressor.audio.AudioCompressor
import co.crackn.kompressor.audio.IosAudioCompressor
import co.crackn.kompressor.image.ImageCompressor
import co.crackn.kompressor.image.IosImageCompressor
import co.crackn.kompressor.video.IosVideoCompressor
import co.crackn.kompressor.video.VideoCompressor
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.estimatedDataRate
import platform.AVFoundation.naturalSize
import platform.AVFoundation.nominalFrameRate
import platform.AVFoundation.preferredTransform
import platform.AVFoundation.tracksWithMediaType
import platform.CoreMedia.CMTimeGetSeconds
import platform.Foundation.NSURL

internal class IosKompressor : Kompressor {
    override val image: ImageCompressor by lazy { IosImageCompressor() }
    override val video: VideoCompressor by lazy { IosVideoCompressor() }
    override val audio: AudioCompressor by lazy { IosAudioCompressor() }

    private val capabilities: DeviceCapabilities by lazy { queryDeviceCapabilities() }

    override suspend fun probe(inputPath: String): Result<SourceMediaInfo> = withContext(Dispatchers.Default) {
        suspendRunCatching { probeIosSource(inputPath) }
    }

    override fun canCompress(info: SourceMediaInfo): Supportability =
        evaluateSupport(
            info = info,
            capabilities = capabilities,
            requiredOutputVideoMime = MIME_VIDEO_H264,
            requiredOutputAudioMime = MIME_AUDIO_AAC,
        )
}

/** Creates an iOS [Kompressor] backed by AVFoundation and VideoToolbox. */
public actual fun createKompressor(): Kompressor = IosKompressor()

@OptIn(ExperimentalForeignApi::class)
@Suppress("UNCHECKED_CAST")
private fun probeIosSource(inputPath: String): SourceMediaInfo {
    val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(inputPath), options = null)
    val videoTrack = asset.tracksWithMediaType(AVMediaTypeVideo).firstOrNull() as? AVAssetTrack
    val audioTracks = asset.tracksWithMediaType(AVMediaTypeAudio)
    val (width, height) = videoTrack?.naturalSize?.useContents { width.toInt() to height.toInt() }
        ?: (null to null)
    val durationMs = (CMTimeGetSeconds(asset.duration) * MILLIS_PER_SEC).toLong().takeIf { it > 0 }
    // We leave codec MIMEs null on iOS (no stable K/N cinterop for
    // CMFormatDescriptionGetMediaSubType), which makes canCompress skip
    // per-codec checks. AVFoundation itself is the ultimate gate — the real
    // verdict comes when the transcode starts and is surfaced as a typed
    // VideoCompressionError if the file turns out to be unreadable.
    return SourceMediaInfo(
        videoCodec = null,
        width = width,
        height = height,
        rotationDegrees = videoTrack?.let(::readRotation),
        frameRate = videoTrack?.nominalFrameRate,
        bitrate = videoTrack?.estimatedDataRate?.toInt(),
        durationMs = durationMs,
        audioCodec = null,
        audioTrackCount = audioTracks.size,
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun readRotation(track: AVAssetTrack): Int =
    track.preferredTransform.useContents { computeRotationDegrees(a, b) }

private const val MILLIS_PER_SEC = 1000.0
