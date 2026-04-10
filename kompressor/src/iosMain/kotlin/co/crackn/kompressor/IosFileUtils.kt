package co.crackn.kompressor

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize

/** Returns the size in bytes of the file at [path], or throws if the file does not exist. */
@OptIn(ExperimentalForeignApi::class)
internal fun nsFileSize(path: String): Long {
    val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, null)
        ?: error("File not found: $path")
    return (attrs[NSFileSize] as? Number)?.toLong()
        ?: error("Cannot read file size: $path")
}
