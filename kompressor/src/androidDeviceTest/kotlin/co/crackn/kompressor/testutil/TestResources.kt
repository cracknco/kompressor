package co.crackn.kompressor.testutil

import androidx.test.platform.app.InstrumentationRegistry

/** Loads a test resource from the Android instrumentation test APK assets. */
fun readTestResource(path: String): ByteArray {
    val context = InstrumentationRegistry.getInstrumentation().context
    return context.assets.open(path).use { it.readBytes() }
}
