@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package co.crackn.kompressor.testutil

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

/**
 * Loads a test resource bundled with the iOS test target.
 *
 * Resources placed in `src/iosTest/resources/` (or propagated from `commonTest/resources/`)
 * are automatically bundled into the test framework by KGP 1.9.20+.
 */
@Suppress("unused") // Infrastructure for fixture-based golden tests
fun readTestResource(path: String): ByteArray {
    val lastSlash = path.lastIndexOf('/')
    val directory = if (lastSlash >= 0) path.substring(0, lastSlash) else null
    val filename = if (lastSlash >= 0) path.substring(lastSlash + 1) else path
    val dot = filename.lastIndexOf('.')
    val name = if (dot >= 0) filename.substring(0, dot) else filename
    val ext = if (dot >= 0) filename.substring(dot + 1) else null

    val bundle = NSBundle.mainBundle
    val resourcePath = if (directory != null) {
        bundle.pathForResource(name, ofType = ext, inDirectory = directory)
    } else {
        bundle.pathForResource(name, ofType = ext)
    } ?: error("Test resource not found in bundle: $path")

    val data = NSData.dataWithContentsOfFile(resourcePath)
        ?: error("Could not read test resource data: $resourcePath")

    return ByteArray(data.length.toInt()).also { bytes ->
        bytes.usePinned { pinned ->
            memcpy(pinned.addressOf(0), data.bytes, data.length)
        }
    }
}
