package co.crackn.kompressor

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSPOSIXErrorDomain

private const val POSIX_ENOENT = 2

/**
 * Returns the size in bytes of the file at [path], or throws [AVNSErrorException] wrapping a
 * POSIX `ENOENT` [NSError] so the iOS typed-error mappers classify a missing-input failure as
 * `IoFailed` rather than `Unknown`.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun nsFileSize(path: String): Long {
    val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, null)
        ?: throw AVNSErrorException(
            nsError = NSError.errorWithDomain(NSPOSIXErrorDomain, code = POSIX_ENOENT.toLong(), userInfo = null),
            message = "File not found: $path",
        )
    return (attrs[NSFileSize] as? Number)?.toLong()
        ?: error("Cannot read file size: $path")
}
