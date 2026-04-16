/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSPOSIXErrorDomain

private const val POSIX_ENOENT = 2

/**
 * Returns the size in bytes of the file at [path], or throws [AVNSErrorException] carrying the
 * real [NSError] from Foundation (domain + code preserved so the iOS typed-error mappers can
 * distinguish `ENOENT`, `EACCES`, `EROFS`, etc.). Falls back to a synthetic POSIX `ENOENT`
 * [NSError] only when Foundation does not populate an error object.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun nsFileSize(path: String): Long = memScoped {
    val errorRef = alloc<ObjCObjectVar<NSError?>>()
    val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, errorRef.ptr)
        ?: throw AVNSErrorException(
            nsError = errorRef.value
                ?: NSError.errorWithDomain(NSPOSIXErrorDomain, code = POSIX_ENOENT.toLong(), userInfo = null),
            message = "File not found: $path",
        )
    (attrs[NSFileSize] as? Number)?.toLong()
        ?: error("Cannot read file size: $path")
}
