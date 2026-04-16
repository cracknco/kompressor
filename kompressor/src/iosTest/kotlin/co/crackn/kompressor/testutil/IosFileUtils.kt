/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package co.crackn.kompressor.testutil

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.AVFAudio.AVAudioFile
import platform.AVFoundation.AVURLAsset
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

/**
 * Reads audio sample rate + channel count.
 *
 * Uses [AVAudioFile.processingFormat] rather than `AVAssetTrack.formatDescriptions` because the
 * latter returns a `List<*>` of CoreFoundation opaque pointers (`CMAudioFormatDescriptionRef`)
 * that Kotlin/Native refuses to cast with `as` — it raises `ClassCastException` at runtime since
 * the Obj-C bridge wraps the elements in a private holder, not the raw CF pointer. AVAudioFile
 * exposes the format via an `AVAudioFormat` Obj-C object with direct `sampleRate` / `channelCount`
 * properties, so no cast is needed.
 */
fun readAudioMetadata(path: String): AudioMetadata {
    // K/N binding for -[AVAudioFile initForReading:error:] types the return as
    // non-nullable, so we trust it and let an ObjC-side failure propagate as an
    // exception rather than gating with an unreachable elvis branch.
    val file = AVAudioFile(forReading = NSURL.fileURLWithPath(path), error = null)
    val format = file.processingFormat
    return AudioMetadata(
        sampleRate = format.sampleRate.toInt(),
        channels = format.channelCount.toInt(),
    )
}

fun readAudioDurationSec(path: String): Double {
    val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
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
