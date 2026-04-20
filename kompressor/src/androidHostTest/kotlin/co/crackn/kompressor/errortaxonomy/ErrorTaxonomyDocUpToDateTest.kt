/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.errortaxonomy

import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Enforces the CRA-44 DoD clause:
 *
 * > Auto-generated from source. The committed doc must never drift from the three
 * > `…CompressionError.kt` sealed hierarchies.
 *
 * This test renders the doc via [ErrorTaxonomyRenderer] and asserts byte-identity with
 * the committed `docs/error-handling.md`. Drift (a new sealed subtype, a renamed one, a
 * reworded first-sentence KDoc summary, an editorial guidance update) fails the test with
 * an actionable message — "run `./scripts/regenerate-error-taxonomy.sh`".
 *
 * Regeneration mode: when `-PregenerateErrorTaxonomyDoc=true` is passed to Gradle, this
 * test rewrites the file in place instead of asserting. `scripts/regenerate-error-taxonomy.sh`
 * drives that invocation. This mirrors [co.crackn.kompressor.matrix.FormatSupportDocUpToDateTest]
 * (CRA-43) — same test-as-single-source-of-truth pattern, same repro flow for contributors.
 *
 * Lives in `androidHostTest` (JVM host) rather than `commonTest` because reading the
 * project's working-copy sources + markdown requires `java.io.File` — unavailable in K/N.
 */
class ErrorTaxonomyDocUpToDateTest {

    @Test
    fun committedDocIsByteIdenticalToRendererOutput() {
        val repoRoot = locateRepoRoot()
        val docFile = File(repoRoot, DOC_RELATIVE_PATH)
        check(docFile.exists()) {
            "Expected $DOC_RELATIVE_PATH under $repoRoot — cannot verify drift."
        }

        val expected = ErrorTaxonomyRenderer.render(repoRoot)

        if (regenerateRequested()) {
            docFile.writeText(expected)
            return
        }

        val committed = docFile.readText()
        if (committed != expected) {
            fail(
                buildString {
                    appendLine("$DOC_RELATIVE_PATH is out of date with the `…CompressionError.kt` sealed hierarchies.")
                    appendLine("Run `./scripts/regenerate-error-taxonomy.sh` and commit the result.")
                    appendLine()
                    appendLine("--- first diff excerpt ---")
                    appendLine(firstDiffContext(committed, expected))
                },
            )
        }
    }

    /**
     * Walk up from the JVM's working directory until we find a sibling `docs/` containing
     * `error-handling.md`. Mirrors the locator in `FormatSupportDocUpToDateTest`. Override
     * via the [DOCS_DIR_SYSTEM_PROPERTY] system property (set by `kompressor/build.gradle.kts`
     * for every test task so Gradle's CWD doesn't need to be the repo root).
     */
    private fun locateRepoRoot(): File {
        val explicit = System.getProperty(DOCS_DIR_SYSTEM_PROPERTY)
        if (explicit != null) {
            val docsDir = File(explicit)
            val parent = docsDir.parentFile
            check(parent != null && File(parent, DOC_RELATIVE_PATH).exists()) {
                "System property $DOCS_DIR_SYSTEM_PROPERTY=$explicit does not resolve to a " +
                    "repo with $DOC_RELATIVE_PATH"
            }
            return parent
        }
        val startDir = System.getProperty("user.dir")
            ?: error("JVM did not expose `user.dir`; cannot locate $DOC_RELATIVE_PATH")
        var dir: File? = File(startDir)
        val visited = mutableListOf<String>()
        while (dir != null) {
            visited += dir.absolutePath
            if (File(dir, DOC_RELATIVE_PATH).exists()) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException(
            "Could not locate $DOC_RELATIVE_PATH starting from $startDir. " +
                "Searched: $visited. Set -D$DOCS_DIR_SYSTEM_PROPERTY=<repo>/docs to override.",
        )
    }

    private fun regenerateRequested(): Boolean =
        System.getProperty(REGENERATE_SYSTEM_PROPERTY) == "true"

    /**
     * Emit a short diff excerpt centred on the first differing line. Keeps the failure
     * message usable without dumping both full documents into the test log.
     */
    private fun firstDiffContext(committed: String, expected: String): String {
        val committedLines = committed.lines()
        val expectedLines = expected.lines()
        val firstDiff = (0 until maxOf(committedLines.size, expectedLines.size)).firstOrNull { i ->
            committedLines.getOrNull(i) != expectedLines.getOrNull(i)
        } ?: return "(files differ in length only: committed=${committed.length}B expected=${expected.length}B)"
        val from = (firstDiff - CONTEXT_LINES).coerceAtLeast(0)
        val toCommitted = (firstDiff + CONTEXT_LINES).coerceAtMost(committedLines.lastIndex)
        val toExpected = (firstDiff + CONTEXT_LINES).coerceAtMost(expectedLines.lastIndex)
        return buildString {
            appendLine("first differing line: ${firstDiff + 1}")
            appendLine("--- committed (${committed.length}B) ---")
            for (i in from..toCommitted) appendLine("${i + 1}: ${committedLines[i]}")
            appendLine("--- expected (${expected.length}B) ---")
            for (i in from..toExpected) appendLine("${i + 1}: ${expectedLines[i]}")
        }
    }

    private companion object {
        const val DOC_RELATIVE_PATH: String = "docs/error-handling.md"
        const val DOCS_DIR_SYSTEM_PROPERTY: String = "kompressor.docsDir"
        const val REGENERATE_SYSTEM_PROPERTY: String = "kompressor.regenerateErrorTaxonomyDoc"
        const val CONTEXT_LINES: Int = 3
    }
}
