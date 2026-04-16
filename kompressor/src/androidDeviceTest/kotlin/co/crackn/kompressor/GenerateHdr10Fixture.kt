/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.testutil.Hdr10Mp4Generator
import co.crackn.kompressor.video.deviceSupportsHdr10Hevc
import java.io.File
import java.security.MessageDigest
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertTrue

/**
 * One-shot entry point for regenerating the `hdr10_p010.mp4` LFS fixture (CRA-6).
 *
 * Intended invocation is via `scripts/generate-hdr10-fixture.sh`, which installs the device
 * test APK, runs this single method through `am instrument -e class`, then `adb pull`s the
 * written file into `fixtures/hdr/video/`. Left in the main device-test suite on purpose:
 * when FTL or a local `connectedAndroidDeviceTest` run includes it, the only side effect is
 * a small file written to the app's external files dir, which is wiped between test runs.
 *
 * Skips (not fails) on devices that don't advertise a Main10 HDR10 encoder — the separate
 * `HdrVideoCompressionTest` already guards the typed-error contract on those devices.
 */
class GenerateHdr10Fixture {

    @Test
    fun writeHdr10P010FixtureToExternalFilesDir() {
        assumeTrue(
            "Device lacks HEVC Main10 HDR10 encoder — cannot regenerate fixture",
            deviceSupportsHdr10Hevc(),
        )
        val target = fixtureTargetFile()
        Hdr10Mp4Generator.generate(target)
        assertTrue(target.length() > 0, "Generated fixture is empty: ${target.absolutePath}")
        assertTrue(
            target.length() <= MAX_FIXTURE_BYTES,
            "Generated fixture ${target.length()} B exceeds the $MAX_FIXTURE_MB MB LFS budget",
        )
        // Print the size + sha so the harness script can read it back from logcat without
        // needing a second adb shasum round-trip. The `[FIXTURE]` tag is what the script greps.
        android.util.Log.i(
            LOG_TAG,
            "[FIXTURE] path=${target.absolutePath} size=${target.length()} sha256=${sha256(target)}",
        )
    }

    private fun fixtureTargetFile(): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = ctx.getExternalFilesDir(null) ?: error("No external files dir available")
        return File(dir, FIXTURE_FILENAME)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buf = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val n = stream.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val FIXTURE_FILENAME = "hdr10_p010.mp4"
        const val MAX_FIXTURE_MB = 1L
        const val MAX_FIXTURE_BYTES = MAX_FIXTURE_MB * 1_048_576L
        const val DEFAULT_BUFFER_SIZE = 8192
        const val LOG_TAG = "Hdr10FixtureGen"
    }
}
