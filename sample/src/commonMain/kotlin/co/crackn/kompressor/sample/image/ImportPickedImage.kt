package co.crackn.kompressor.sample.image

import io.github.vinceglb.filekit.PlatformFile

/**
 * Imports a picked image into a Skia-compatible format (JPEG/PNG).
 *
 * On iOS, the photo library may return images in HEIC format which Skia
 * (used by Compose Multiplatform) cannot decode. This function re-encodes
 * such images as JPEG via platform-native APIs.
 */
expect suspend fun importPickedImage(source: PlatformFile, destination: PlatformFile)
