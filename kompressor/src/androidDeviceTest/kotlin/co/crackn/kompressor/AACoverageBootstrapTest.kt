/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import kotlin.test.assertTrue

/**
 * Tiny no-op test whose side effect is to ensure `/sdcard/Android/data/<pkg>/files/` exists on
 * device before [InstrumentationCoverageReporter][androidx.test.internal.runner.coverage.InstrumentationCoverageReporter]
 * runs at `instrumentationRunFinished`.
 *
 * Why this exists (see `.github/workflows/pr.yml`): FTL invokes the test APK with
 * `-e coverage=true -e coverageFile=/sdcard/Android/data/<pkg>/files/coverage.ec`. On the
 * first-install of an instrumentation-only APK, the system does NOT auto-create the
 * app-scoped external files directory, so the coverage dumper's `FileOutputStream` fails
 * with `ENOENT` and `coverage.ec` never materialises. Calling `getExternalFilesDir(null)`
 * has the side effect of creating the directory, which is all we need.
 *
 * Tried simpler options first and they all dead-ended:
 *  - Custom `AndroidJUnitRunner` subclass: AGP's KMP Android library plugin overwrites any
 *    `<instrumentation>` declaration in the test manifest with its auto-generated default.
 *    `tools:replace="android:name"` is stripped during merge.
 *  - `/sdcard/coverage.ec`: blocked by scoped storage on target SDK 36 (EACCES).
 *  - `/data/local/tmp/coverage.ec`: shell-user only (EACCES).
 *
 * The class name is prefixed `AA` so JUnit's alphabetical class ordering runs it first; it
 * doesn't strictly matter (any test running before `instrumentationRunFinished` satisfies the
 * precondition) but gives a belt-and-suspenders guarantee against reordering.
 */
class AACoverageBootstrapTest {

    @Test
    fun ensuresAppScopedExternalFilesDirExists() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        assertTrue(
            dir != null && dir.exists(),
            "getExternalFilesDir(null) must return a real, on-disk directory (got $dir) — " +
                "otherwise the JaCoCo coverage dump at end-of-run will fail with ENOENT",
        )
    }
}
