@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package co.crackn.kompressor.testutil

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToURL

fun readBytes(path: String): ByteArray {
    val data = NSData.dataWithContentsOfFile(path)
        ?: error("Cannot read file: $path")
    if (data.length == 0UL) return ByteArray(0)
    return ByteArray(data.length.toInt()).also { bytes ->
        bytes.usePinned { pinned ->
            platform.posix.memcpy(pinned.addressOf(0), data.bytes, data.length)
        }
    }
}

data class AudioMetadata(val sampleRate: Int, val channels: Int)

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
