package co.crackn.kompressor.sample.image

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.copyTo

actual suspend fun importPickedImage(source: PlatformFile, destination: PlatformFile) {
    source.copyTo(destination)
}
