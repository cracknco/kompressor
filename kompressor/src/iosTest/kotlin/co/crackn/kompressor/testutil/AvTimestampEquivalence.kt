/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.testutil

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe

/**
 * Max count of differing bytes between two iOS audio/video outputs produced by successive
 * AVFoundation exports of the same source that straddle a 1-second wall-clock boundary.
 * Derived from the ISOBMFF timestamp budget (per ISO/IEC 14496-12 §8.2.2 / §8.3.2 / §8.4.2):
 *
 *  * `mvhd` (1 box) × {creation, modification} × 4 B = 8 B
 *  * `tkhd` (1–2 tracks) × 2 timestamps × 4 B = 8–16 B
 *  * `mdhd` (1–2 tracks) × 2 timestamps × 4 B = 8–16 B
 *
 * Total worst-case ≈ 40 B when every byte of every timestamp field flips on a carry cascade;
 * in practice only LSBs flip on a 1-second rollover (observed 3 bytes on CRA-98 CI). 64 B is
 * a 1.6× safety margin over the theoretical bound that still catches any real compression
 * regression. Single source of truth — replaces the three private companion-object copies that
 * lived in `UrlInputEndToEndTest` / `io.StreamAndBytesEndToEndTest` /
 * `UrlInputNonDeterminismInvestigationTest` before PR #157's CodeRabbit follow-up.
 *
 * See `docs/investigations/avfoundation-nsurl-divergence.md` for the full derivation.
 */
internal const val AV_TIMESTAMP_BYTE_TOLERANCE: Int = 64

/**
 * Assert two AVFoundation outputs (legacy-overload vs novel-overload pair) are *structurally*
 * equivalent despite AVFoundation's wall-clock `mvhd`/`tkhd`/`mdhd` timestamp stamping (CRA-98
 * H1 root cause). Sizes MUST match exactly — timestamp drift never resizes the container, so
 * any size mismatch is a real regression. Differing-byte count MUST fit inside
 * [AV_TIMESTAMP_BYTE_TOLERANCE].
 *
 * Use this overload when comparing a *legacy*-overload (FilePath) output against a *novel*-
 * overload (NSURL / NSData / Stream) output — the role-named parameters carry that semantic
 * into the failure messages.
 */
internal fun assertAvOutputStructurallyEquivalent(novelPath: String, legacyPath: String) {
    assertAvOutputStructurallyEquivalent(
        novelBytes = readBytes(novelPath),
        legacyBytes = readBytes(legacyPath),
    )
}

/**
 * Bytes-based variant of [assertAvOutputStructurallyEquivalent]; same semantics, used when
 * the caller already holds the byte arrays (e.g. one side came from a `Stream`/`Bytes`
 * destination rather than a file path).
 */
internal fun assertAvOutputStructurallyEquivalent(novelBytes: ByteArray, legacyBytes: ByteArray) {
    withClue("Outputs must not be empty. novel=${novelBytes.size} legacy=${legacyBytes.size}") {
        (novelBytes.isNotEmpty() && legacyBytes.isNotEmpty()) shouldBe true
    }
    withClue(
        "Output sizes must be equal (AVFoundation timestamp drift does not resize the " +
            "container). novel=${novelBytes.size} legacy=${legacyBytes.size}",
    ) {
        novelBytes.size shouldBe legacyBytes.size
    }
    var differing = 0
    for (i in novelBytes.indices) if (novelBytes[i] != legacyBytes[i]) differing++
    val divergentOffsets = collectDivergentOffsets(novelBytes, legacyBytes, limit = DIVERGENT_OFFSET_DUMP_LIMIT)
    withClue(
        "Expected ≤$AV_TIMESTAMP_BYTE_TOLERANCE differing bytes (AVFoundation wall-clock " +
            "`mvhd`/`tkhd`/`mdhd` second rollover); actual=$differing " +
            "firstDivergentOffsets=$divergentOffsets",
    ) {
        (differing <= AV_TIMESTAMP_BYTE_TOLERANCE) shouldBe true
    }
}

/**
 * Symmetric variant of [assertAvOutputStructurallyEquivalent] for back-to-back same-path
 * comparisons (two consecutive legacy compresses, or two consecutive NSURL compresses). The
 * role-named overload would emit misleading `novel=… legacy=…` failure messages for these
 * cases — neutral `a` / `b` naming reflects the actual semantic. The required [label]
 * distinguishes the call site in failure messages so a single failing log line can identify
 * which test fired the assertion without relying on the runner's separate test-name
 * annotation, which can land on a different log line in CI output.
 */
internal fun assertAvOutputStructurallyEquivalent(label: String, a: ByteArray, b: ByteArray) {
    withClue("$label outputs must not be empty. a=${a.size} b=${b.size}") {
        (a.isNotEmpty() && b.isNotEmpty()) shouldBe true
    }
    withClue(
        "$label output sizes must be equal (timestamp drift does not resize the " +
            "container). a=${a.size} b=${b.size}",
    ) {
        a.size shouldBe b.size
    }
    var differing = 0
    for (i in a.indices) if (a[i] != b[i]) differing++
    val divergentOffsets = collectDivergentOffsets(a, b, limit = DIVERGENT_OFFSET_DUMP_LIMIT)
    withClue(
        "$label expected ≤$AV_TIMESTAMP_BYTE_TOLERANCE differing bytes (AVFoundation " +
            "wall-clock `mvhd`/`tkhd`/`mdhd` second rollover); actual=$differing " +
            "firstDivergentOffsets=$divergentOffsets",
    ) {
        (differing <= AV_TIMESTAMP_BYTE_TOLERANCE) shouldBe true
    }
}

/**
 * Internal-to-this-file helper: collect up to [limit] byte offsets where [a] and [b] disagree.
 * Used by the structural-equivalence assertions to inline the divergent-offset dump into the
 * failing `withClue` message rather than emitting a separate `println` (which CI runners can
 * reorder onto a different log line than the assertion failure).
 *
 * Not exposed as `internal` because the public-test-helper surface is the assertion functions;
 * the Investigation test class keeps its own `firstDivergentOffsets` for Step 3 diagnostic
 * dumps that exercise different formatting (full hex context + ISOBMFF box scan).
 */
private fun collectDivergentOffsets(a: ByteArray, b: ByteArray, limit: Int): List<Int> {
    val result = mutableListOf<Int>()
    val min = minOf(a.size, b.size)
    var i = 0
    while (i < min && result.size < limit) {
        if (a[i] != b[i]) result += i
        i++
    }
    if (a.size != b.size && result.size < limit) result += min
    return result
}

private const val DIVERGENT_OFFSET_DUMP_LIMIT = 32
