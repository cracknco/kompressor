/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.testutil.Hdr10Mp4Generator
import co.crackn.kompressor.video.deviceSupportsHdr10Hevc
import java.io.File
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
            "Generated fixture ${target.length()} B exceeds the 1 MB LFS budget",
        )
    }

    private fun fixtureTargetFile(): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = ctx.getExternalFilesDir(null) ?: error("No external files dir available")
        return File(dir, FIXTURE_FILENAME)
    }

    private companion object {
        const val FIXTURE_FILENAME = "hdr10_p010.mp4"
        const val MAX_FIXTURE_BYTES = 1_048_576L
    }
}
