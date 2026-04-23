/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.logging

import io.kotest.common.ExperimentalKotest
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.alphanumeric
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.katakana
import io.kotest.property.arbitrary.merge
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

/**
 * Golden + property coverage for [redactPath].
 *
 * The helper exists to keep user-identifying filesystem paths out of the pluggable-logger channel
 * (see [CRA-100](https://linear.app/crackn/issue/CRA-100)). These tests pin the four callouts from
 * the ticket's DoD plus a property sweep over Unicode code points to guarantee the long-name
 * truncation branch stays well-formed regardless of input alphabet.
 */
@OptIn(ExperimentalKotest::class)
class LogRedactionTest {

    @Test
    fun emptyPath_returnsPlaceholder() {
        redactPath("") shouldBe "<empty>"
    }

    @Test
    fun posixPath_returnsBasename() {
        redactPath("/usr/local/foo.jpg") shouldBe "foo.jpg"
    }

    @Test
    fun contentUri_returnsLastSegment() {
        // substringAfterLast('/') handles `content://…/123` naturally — no scheme-aware parser
        // needed. Asserting the exact output pins that behaviour; regressing to "full URI" would
        // re-introduce the leak on Android's `content://media/external/images/<user-id>` paths.
        redactPath("content://media/external/images/123") shouldBe "123"
    }

    @Test
    fun iosTempPath_stripsNsTemporaryDirectory() {
        val tempPath = "/private/var/folders/xy/kompressor-ABC/phasset-DEF.mp4"
        redactPath(tempPath) shouldBe "phasset-DEF.mp4"
    }

    @Test
    fun basenameAtBoundary_isNotTruncated() {
        // 64 chars: right at the boundary — must pass through unchanged. The truncation branch
        // fires only for strictly-greater-than 64.
        val basename = "a".repeat(64)
        redactPath("/dir/$basename") shouldBe basename
    }

    @Test
    fun basenameOverBoundary_isTruncatedWithSuffix() {
        // 100 chars: expect 48-char prefix + "…(52 more)".
        val basename = "a".repeat(100)
        val redacted = redactPath("/dir/$basename")

        redacted shouldStartWith "a".repeat(48)
        redacted shouldContain "…(52 more)"
    }

    @Test
    fun pathWithoutSeparator_isTreatedAsBasename() {
        // `substringAfterLast('/')` returns the whole string when no '/' is present — exercise
        // that branch explicitly so a future refactor to a different separator handler can't
        // silently regress it.
        redactPath("bare-filename.mp4") shouldBe "bare-filename.mp4"
    }

    @Test
    fun trailingSlash_redactsToEmptyPlaceholder() {
        // Edge case: paths ending in '/'. `substringAfterLast('/')` returns "" — we surface that
        // as "<empty>" to stay consistent with the top-level empty-path handling (see also
        // [emptyPath_returnsPlaceholder]). A blank field in a log line is strictly less useful
        // than an explicit placeholder.
        redactPath("/some/dir/") shouldBe "<empty>"
    }

    @Test
    fun truncation_propertyOverUnicode() = runTest {
        // Property: for any basename longer than 64 characters built from mixed Unicode code
        // points, the redacted string must exactly match `take(48) + "…(N more)"` where
        // N = length − 48. We mix alphanumeric + katakana codepoints to exercise both single-unit
        // BMP characters and multi-byte non-ASCII; that's enough to catch regressions that would
        // interpret the String length differently than `redactPath` does internally.
        val mixedAlphabet: Arb<Codepoint> = Codepoint.alphanumeric().merge(Codepoint.katakana())
        val basenames: Arb<String> = Arb.string(65..200, mixedAlphabet)

        checkAll(PropTestConfig(seed = SEED), basenames) { basename ->
            val expectedPrefix = basename.take(48)
            val expectedSuffix = "…(${basename.length - 48} more)"
            redactPath("/dir/$basename") shouldBe "$expectedPrefix$expectedSuffix"
        }
    }

    @Test
    fun truncation_lengthRelation() = runTest {
        // Complementary property: the redacted length is exactly 48 + "…(N more)".length for any
        // basename whose length > 64, regardless of which Chars make up the name. Guards against
        // an accidental off-by-one in the `take(48)` / `length - 48` pair.
        val basenameLengths = Arb.int(65..1_000)
        checkAll(PropTestConfig(seed = SEED), basenameLengths) { len ->
            val basename = "x".repeat(len)
            val redacted = redactPath("/dir/$basename")
            val expectedSuffix = "…(${len - 48} more)"
            redacted.length shouldBe 48 + expectedSuffix.length
        }
    }

    private companion object {
        // Echo the per-run seed so a failing property run is reproducible; mirrors the style
        // used in the kompressor/property/* tests.
        val SEED: Long = kotlin.random.Random.nextLong().also {
            @Suppress("ForbiddenMethodCall")
            println("[property-seed] LogRedactionTest: $it")
        }
    }
}
