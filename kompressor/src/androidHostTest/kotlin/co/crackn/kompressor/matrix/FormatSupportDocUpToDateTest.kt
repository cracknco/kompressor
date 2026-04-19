/*
 * Copyright 2026 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.matrix

import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Enforces the CRA-43 DoD clause:
 *
 * > Auto-generation : CI régénère la table depuis les tests à chaque push main — pas de
 * > divergence code/doc possible.
 *
 * This test reads the committed `docs/format-support.md`, extracts the auto-generated block
 * between [FORMAT_SUPPORT_MATRIX_BEGIN_MARKER] and [FORMAT_SUPPORT_MATRIX_END_MARKER], and
 * asserts it matches [renderFormatSupportMatrixTables]. Any drift (a new image format, an
 * updated gate constant, a fix to the renderer) fails the test with an actionable message —
 * "run `./scripts/regenerate-format-support-doc.sh`". CI runs this test on every PR through
 * the shared `testAndroidHostTest` job; a dedicated `format-support-check.yml` workflow runs
 * it again without the `docs` paths-ignore filter so doc-only PRs can't sneak a stale
 * matrix through.
 *
 * Regeneration mode: when `-PregenerateFormatSupportDoc=true` is passed to Gradle, this
 * test rewrites the file in place instead of asserting. The `regenerate-format-support-doc.sh`
 * wrapper script drives this.
 *
 * This test lives in `androidHostTest` (JVM host) rather than `commonTest` because reading
 * the project's working-copy markdown requires `java.io.File` — unavailable in K/N and not
 * part of the kotlinx-io floor in this project. The matrix data and renderer live in
 * `commonMain` so they stay accessible to both platforms.
 */
class FormatSupportDocUpToDateTest {

    @Test
    fun committedMatrixIsByteIdenticalToRendererOutput() {
        val docFile = locateDocFile()
        val fileText = docFile.readText()

        val begin = fileText.indexOf(FORMAT_SUPPORT_MATRIX_BEGIN_MARKER)
        val end = fileText.indexOf(FORMAT_SUPPORT_MATRIX_END_MARKER)
        check(begin >= 0) {
            "Expected marker `$FORMAT_SUPPORT_MATRIX_BEGIN_MARKER` in ${docFile.absolutePath} — cannot verify."
        }
        check(end >= begin) {
            "Expected marker `$FORMAT_SUPPORT_MATRIX_END_MARKER` after begin marker in ${docFile.absolutePath}."
        }
        val committedBlock = fileText.substring(begin, end + FORMAT_SUPPORT_MATRIX_END_MARKER.length)
        val expectedBlock = renderFormatSupportMatrixTables()

        if (regenerateRequested()) {
            val rewritten = fileText.substring(0, begin) +
                expectedBlock +
                fileText.substring(end + FORMAT_SUPPORT_MATRIX_END_MARKER.length)
            docFile.writeText(rewritten)
            return
        }

        if (committedBlock != expectedBlock) {
            fail(
                buildString {
                    appendLine("docs/format-support.md is out of date with `FormatSupportMatrix`.")
                    appendLine("Run `./scripts/regenerate-format-support-doc.sh` and commit the result.")
                    appendLine()
                    appendLine("--- committed ---")
                    appendLine(committedBlock)
                    appendLine("--- expected ---")
                    append(expectedBlock)
                },
            )
        }

        // Sanity assertion: the committed file must reference the matrix doc so regressions
        // that accidentally remove the generated block (rather than updating it) still fail.
        (FORMAT_SUPPORT_MATRIX_BEGIN_MARKER in fileText) shouldBe true
    }

    /**
     * Locate `docs/format-support.md` from the working directory Gradle hands the test. In
     * practice this is the module directory (`kompressor/`) when invoked via
     * `./gradlew :kompressor:testAndroidHostTest`, but developers sometimes run tests from
     * the repo root or from the IDE. Walk up from the CWD until we find a sibling `docs/`
     * directory containing `format-support.md`; fail fast with a clear error if we can't —
     * silently returning a missing-file path would make the test appear to "pass" by
     * asserting against empty content.
     */
    private fun locateDocFile(): File {
        val explicit = System.getProperty(DOCS_DIR_SYSTEM_PROPERTY)
        if (explicit != null) {
            val candidate = File(explicit, DOC_RELATIVE_PATH)
            check(candidate.exists()) {
                "System property $DOCS_DIR_SYSTEM_PROPERTY=$explicit does not contain $DOC_RELATIVE_PATH"
            }
            return candidate
        }
        val startDir = System.getProperty("user.dir")
            ?: error("JVM did not expose `user.dir`; cannot locate docs/$DOC_RELATIVE_PATH")
        var dir: File? = File(startDir)
        val visited = mutableListOf<String>()
        while (dir != null) {
            visited += dir.absolutePath
            val candidate = File(dir, "docs/$DOC_RELATIVE_PATH")
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        throw IllegalStateException(
            "Could not locate docs/$DOC_RELATIVE_PATH starting from $startDir. " +
                "Searched: $visited. Set -D$DOCS_DIR_SYSTEM_PROPERTY=<repo>/docs to override.",
        )
    }

    private fun regenerateRequested(): Boolean =
        System.getProperty(REGENERATE_SYSTEM_PROPERTY) == "true"

    private companion object {
        const val DOC_RELATIVE_PATH: String = "format-support.md"
        const val DOCS_DIR_SYSTEM_PROPERTY: String = "kompressor.docsDir"
        const val REGENERATE_SYSTEM_PROPERTY: String = "kompressor.regenerateFormatSupportDoc"
    }
}
