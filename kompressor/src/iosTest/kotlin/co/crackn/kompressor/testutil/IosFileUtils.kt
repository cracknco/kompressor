@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package co.crackn.kompressor.testutil

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.pointed
import kotlinx.cinterop.usePinned
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.formatDescriptions
import platform.AVFoundation.tracksWithMediaType
import platform.CoreMedia.CMAudioFormatDescriptionGetStreamBasicDescription
import platform.CoreMedia.CMAudioFormatDescriptionRef
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToURL
import platform.posix.memcpy

private fun NSData.toByteArray(): ByteArray {
    if (length == 0UL) return ByteArray(0)
    return ByteArray(length.toInt()).also { bytes ->
        bytes.usePinned { pinned ->
            memcpy(pinned.addressOf(0), this.bytes, length)
        }
    }
}

fun readBytes(path: String): ByteArray {
    val data = NSData.dataWithContentsOfFile(path)
        ?: error("Cannot read file: $path")
    return data.toByteArray()
}

data class AudioMetadata(val sampleRate: Int, val channels: Int)

fun readAudioMetadata(path: String): AudioMetadata {
    val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
    @Suppress("UNCHECKED_CAST")
    val tracks = asset.tracksWithMediaType(AVMediaTypeAudio)
    check(tracks.isNotEmpty()) { "No audio track found in output" }

    val track = tracks.first() as AVAssetTrack
    val formatDescriptions = track.formatDescriptions
    check(formatDescriptions.isNotEmpty()) { "No format descriptions found" }

    @Suppress("UNCHECKED_CAST")
    val basicDesc = CMAudioFormatDescriptionGetStreamBasicDescription(
        formatDescriptions.first() as CMAudioFormatDescriptionRef,
    )
    checkNotNull(basicDesc) { "Could not read audio format description" }

    return AudioMetadata(
        sampleRate = basicDesc.pointed.mSampleRate.toInt(),
        channels = basicDesc.pointed.mChannelsPerFrame.toInt(),
    )
}

fun readAudioDurationSec(path: String): Double {
    val asset = platform.AVFoundation.AVURLAsset(
        uRL = NSURL.fileURLWithPath(path), options = null,
    )
    return platform.CoreMedia.CMTimeGetSeconds(asset.duration)
}

fun fileSize(path: String): Long {
    val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, null)
        ?: error("File not found: $path")
    return (attrs[NSFileSize] as? Number)?.toLong()
        ?: error("Cannot read file size: $path")
}

fun writeBytes(path: String, bytes: ByteArray) {
    val data = bytes.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
    }
    val written = data.writeToURL(NSURL.fileURLWithPath(path), atomically = true)
    check(written) { "Failed to write file: $path" }
}
