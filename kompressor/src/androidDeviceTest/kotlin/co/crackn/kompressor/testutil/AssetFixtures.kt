package co.crackn.kompressor.testutil

import java.io.File

/**
 * Copy a binary fixture shipped under `kompressor/src/androidDeviceTest/resources/` out of the
 * test APK's classpath into a real file on disk so `AndroidAudioCompressor.compress()`
 * (which expects a filesystem path or a content:// URI) can read it.
 *
 * The KMP Android plugin's androidDeviceTest variant packages `resources/` content into the
 * test APK so they're accessible via `ClassLoader.getResourceAsStream`. We use that instead
 * of the conventional `assets/` directory because the KMP Android plugin's source-set wiring
 * for androidDeviceTest doesn't merge `src/androidDeviceTest/assets/` into the test APK's
 * Asset Manager.
 */
fun copyResourceToCache(resourceName: String, destDir: File): File {
    require((destDir.isDirectory) || destDir.mkdirs()) {
        "Destination is not a directory and could not be created: ${destDir.absolutePath}"
    }
    val target = File(destDir, resourceName)
    val destCanonical = destDir.canonicalFile
    val targetCanonical = target.canonicalFile
    require(
        targetCanonical.path == destCanonical.path ||
            targetCanonical.path.startsWith(destCanonical.path + File.separator),
    ) {
        "Resource path escapes destination directory: $resourceName"
    }
    target.parentFile?.let { parent ->
        require((parent.isDirectory) || parent.mkdirs()) {
            "Parent is not a directory and could not be created: ${parent.absolutePath}"
        }
    }
    val loader = Thread.currentThread().contextClassLoader ?: error("No context ClassLoader")
    val input = loader.getResourceAsStream(resourceName)
        ?: error("Resource not found on classpath: $resourceName")
    input.use { src ->
        target.outputStream().use { out -> src.copyTo(out) }
    }
    return target
}
