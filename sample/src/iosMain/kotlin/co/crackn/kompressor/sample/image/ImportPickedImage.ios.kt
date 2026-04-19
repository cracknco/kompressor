/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.sample.image

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.path
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL
import platform.Foundation.writeToURL
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation

private const val JPEG_QUALITY = 0.95

@OptIn(ExperimentalForeignApi::class)
actual suspend fun importPickedImage(source: PlatformFile, destination: PlatformFile) {
    val image = UIImage(contentsOfFile = source.path)
        ?: error("Failed to decode picked image: ${source.path}")
    val data = UIImageJPEGRepresentation(image, JPEG_QUALITY)
        ?: error("Failed to encode picked image as JPEG")
    val url = NSURL.fileURLWithPath(destination.path)
    check(data.writeToURL(url, atomically = true)) {
        "Failed to write imported image to: ${destination.path}"
    }
}
