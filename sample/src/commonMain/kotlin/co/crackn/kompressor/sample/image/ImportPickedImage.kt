package co.crackn.kompressor.sample.image

import io.github.vinceglb.filekit.PlatformFile

/**
 * Imports a picked image into a Skia-compatible format.
 *
 * On iOS, this function normalizes images to ensure Skia compatibility by re-encoding
 * them to JPEG format when they are not reliably Skia-decodable. This includes HEIC images
 * (which Skia cannot decode) as well as other formats that may not be consistently supported
 * by Skia across platforms. The re-encoding is performed via UIKit's native image APIs,
 * producing JPEG output at 95% quality.
 *
 * On Android, the picked image is copied directly without modification.
 *
 * @param source The picked image file from the platform file picker
 * @param destination The target file where the Skia-compatible image will be written
 */
expect suspend fun importPickedImage(source: PlatformFile, destination: PlatformFile)