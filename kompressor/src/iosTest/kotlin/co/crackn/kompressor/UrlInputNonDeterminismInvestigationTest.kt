/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package co.crackn.kompressor

import co.crackn.kompressor.audio.AudioCompressionConfig
import co.crackn.kompressor.audio.IosAudioCompressor
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource
import co.crackn.kompressor.io.of
import co.crackn.kompressor.testutil.AV_TIMESTAMP_BYTE_TOLERANCE
import co.crackn.kompressor.testutil.Mp4Generator
import co.crackn.kompressor.testutil.WavGenerator
import co.crackn.kompressor.testutil.assertAvOutputStructurallyEquivalent
import co.crackn.kompressor.testutil.readBytes
import co.crackn.kompressor.testutil.writeBytes
import co.crackn.kompressor.video.IosVideoCompressor
import co.crackn.kompressor.video.VideoCompressionConfig
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.posix.usleep

/**
 * CRA-98 investigation tests for the legacy-vs-novel AVFoundation byte divergence observed in
 * [UrlInputEndToEndTest.audio_nsurlInput_matchesFilePathCompressionOutcome] /
 * [UrlInputEndToEndTest.video_nsurlInput_matchesFilePathCompressionOutcome], where PR #142 shipped
 * bitwise-identical then PR #143 relaxed to a 1024-byte size tolerance. The class KDoc of
 * [UrlInputEndToEndTest] blamed second-resolution `mvhd`/`mdhd` stamping but at the time the
 * legacy-twin determinism test (`audio_twoConsecutiveCompresses_staysWithinTimestampBudget`,
 * formerly `audio_twoConsecutiveCompressesProduceIdenticalBytes` before PR #157's CodeRabbit
 * follow-up renamed it to reflect the structural-tolerance assertion) passed
 * with a strict bitwise assertion — neither confirming nor refuting the hypothesis since two
 * back-to-back calls of ~100 ms each usually land in the same wall-clock second. (That legacy
 * twin was later relaxed to the same structural tolerance Step 2 uses, after the same straddle
 * was observed on the NSURL twin under heavy CI load — see release run 24935723437 and the
 * test's own KDoc.)
 *
 * Observed results (iOS Simulator arm64, run on CRA-98 branch):
 *
 *  * **Step 1 loop (100 legacy compresses across >5 s):** 3 distinct byte-sets, all **identical in
 *    size** (27537 bytes). Divergent bytes clustered at offsets `26370, 26486, 26586` — i.e. the
 *    tail of the file where the `moov` box lives for non-network-optimised M4A output. This
 *    matches ISOBMFF `mvhd.creation_time` / `trak.tkhd.creation_time` / `mdhd.creation_time`
 *    locations exactly.
 *  * **Step 2 (novel-overload twice within ms):** audio AND video bitwise-identical when both
 *    calls land in the same wall-clock second → the NSURL dispatch path is deterministic, not
 *    a per-call divergence source. The release pipeline later observed the pair straddle a
 *    second tick on a slow `macos-latest` runner (run 24935723437), so the assertion is now
 *    structural (sizes equal + ≤[AV_TIMESTAMP_BYTE_TOLERANCE] differing bytes) rather than
 *    bitwise — see Step-2 method KDoc + the investigation doc's "Slow-runner caveat" section.
 *  * **Step 3 (legacy-vs-novel single pair):** in this run both landed in the same wall-clock
 *    second and produced bitwise-identical output.
 *
 * **Verdict:**
 *  * **H1 (wall-clock `mvhd`/`mdhd` stamping) — CONFIRMED** as the primary cause.
 *  * **H2 (NSURL dispatch overhead inflates straddle probability) — confirmed as the *mechanism***
 *    for when the divergence manifests on a legacy-vs-novel pair. Novel-vs-novel within the same
 *    wall-clock second is bitwise-identical (Step 2), so only cross-path timing jitter pushes a
 *    pair across a second boundary.
 *  * **H3 (NSURL path stamps a non-timestamp value) — REFUTED** by Step 2.
 *
 * Follow-up applied in [UrlInputEndToEndTest] and
 * [co.crackn.kompressor.io.StreamAndBytesEndToEndTest]: the size-delta tolerance
 * (`AV_SIZE_TOLERANCE_BYTES = 1024L`) is replaced with a structural check asserting sizes are
 * equal and the count of differing bytes is within the `mvhd` + `tkhd` + `mdhd` timestamp budget.
 * See `docs/investigations/avfoundation-nsurl-divergence.md` for full analysis.
 */
class UrlInputNonDeterminismInvestigationTest {

    private lateinit var testDir: String
    private val audio = IosAudioCompressor()
    private val video = IosVideoCompressor()

    @BeforeTest
    fun setUp() {
        testDir = NSTemporaryDirectory() + "kompressor-cra98-${NSUUID().UUIDString}/"
        NSFileManager.defaultManager.createDirectoryAtPath(
            testDir, withIntermediateDirectories = true, attributes = null, error = null,
        )
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtPath(testDir, null)
    }

    /**
     * **Step 1 — H1: wall-clock timestamp straddling. CONFIRMED.**
     *
     * Runs [LOOP_ITERATIONS] consecutive legacy-overload audio compresses of the same WAV input,
     * with a real [STRADDLE_DELAY_MS] ms [usleep] (blocking-on-OS-thread, not
     * [kotlinx.coroutines.delay]) between iterations. Using a coroutine `delay(...)` inside
     * [kotlinx.coroutines.test.runTest] would be a no-op in wall-clock terms (the scheduler
     * advances virtual time but sleeps nothing on the dispatching thread), which would make the
     * assertion \[`distinctCount >= 2`] depend on AVFoundation's own per-compress wall-clock cost
     * — and that cost has been trending downward with every Apple Silicon refresh. A real
     * `usleep` pads the wall clock independently of compressor speed: 99 inter-iteration
     * delays × [STRADDLE_DELAY_MS] ms = [MIN_STRADDLE_SPAN_MS] ms of guaranteed real elapsed
     * time, so the 100-iteration loop is mathematically guaranteed to cross ≥4 wall-clock
     * second boundaries and expose the `mvhd` / `tkhd` / `mdhd` timestamp LSB flips regardless
     * of how fast AVFoundation gets.
     *
     * The method is documented in the CRA-98 investigation artefact as Option A of the
     * "runner-speed fragility" fix (see `docs/investigations/avfoundation-nsurl-divergence.md`).
     *
     * **Observed** (iOS Simulator arm64, CRA-98 branch): `distinctByteSets=3`, all sizes equal
     * at 27537 bytes, first divergent offsets `[26370, 26486, 26586]` — all inside the file's
     * `moov` tail (M4A is not network-optimised, so `moov` sits at the end). These offsets align
     * with `mvhd.creation_time`, `trak.tkhd.creation_time`, and `mdia.mdhd.creation_time` fields
     * flipping LSBs on a 1-second rollover. H1 is therefore the confirmed root cause of the
     * legacy-vs-novel divergence reported in PR #143.
     */
    @Test
    fun audio_legacyOverload_spansWallClockSecondBoundary() = runTest {
        val inputPath = createTestWav()
        val outputs = mutableListOf<ByteArray>()
        repeat(LOOP_ITERATIONS) { i ->
            val out = "$testDir" + "loop_$i.m4a"
            val result = audio.compress(
                MediaSource.Local.FilePath(inputPath),
                MediaDestination.Local.FilePath(out),
                AudioCompressionConfig(),
            )
            result.isSuccess shouldBe true
            outputs += readBytes(out)
            // Real wall-clock sleep — NOT `kotlinx.coroutines.delay`, which would advance
            // `runTest`'s virtual clock without spending any real time. `usleep` takes a
            // microsecond count; the `.toUInt()` cast is safe because `STRADDLE_DELAY_MS`
            // is a small positive compile-time constant.
            if (i < LOOP_ITERATIONS - 1) usleep((STRADDLE_DELAY_MS * MICROS_PER_MILLI).toUInt())
        }

        val hashBuckets = outputs.groupBy { it.contentHashCode() }
        val distinctCount = hashBuckets.size
        val sizeSet = outputs.map { it.size }.toSet()
        val firstDivergentOffsets: List<Int> = if (distinctCount > 1) {
            val a = outputs.first()
            val b = outputs.first { !it.contentEquals(a) }
            firstDivergentOffsets(a, b, limit = DUMP_OFFSET_LIMIT)
        } else {
            emptyList()
        }

        val diagnostic = buildString {
            append("[CRA-98 Step 1] LOOP_ITERATIONS=$LOOP_ITERATIONS ")
            append("STRADDLE_DELAY_MS=$STRADDLE_DELAY_MS ")
            append("distinctByteSets=$distinctCount sizes=$sizeSet ")
            append("firstDivergentOffsets=$firstDivergentOffsets")
        }
        println(diagnostic)

        withClue(
            "H1 = AVFoundation stamps wall-clock mvhd/mdhd. A 100-iteration loop with a real " +
                "usleep($STRADDLE_DELAY_MS) between iterations spans ≥${MIN_STRADDLE_SPAN_MS}ms " +
                "of wall-clock time (≥4 second-boundary crossings, independent of runner " +
                "speed), so if H1 holds the legacy path MUST produce ≥2 distinct byte-sets. " +
                "All outputs also MUST have identical size — H1 flips existing bytes in fixed " +
                "timestamp fields, never the file length. Observed: $diagnostic",
        ) {
            sizeSet.size shouldBe 1
            (distinctCount >= 2) shouldBe true
        }
    }

    /**
     * **Step 2 — NSURL-overload determinism sentinel (audio).**
     *
     * Two back-to-back NSURL-overload compresses of the same WAV, assert structural
     * equivalence (sizes equal + differing bytes ≤ [AV_TIMESTAMP_BYTE_TOLERANCE]).
     *
     * **Historical role.** Originally written as an H2-vs-H3 discriminator with a strict
     * `contentEquals` assertion: passing meant the NSURL path is deterministic *within a
     * wall-clock second* (refuting H3), failing would have confirmed H3. The investigation
     * concluded H3 is refuted (initial runs were bitwise-identical) and H1 is the root
     * cause — see class KDoc + `docs/investigations/avfoundation-nsurl-divergence.md`.
     *
     * **Why the assertion is now structural, not bitwise.** The release pipeline observed
     * the pair straddle a second tick on a slow `macos-latest` runner (run 24935723437),
     * producing the same 3-byte H1 divergence at moov-tail timestamp offsets that Step 1
     * already proves. Asserting bitwise equality conflates "H3 confirmed" with "Step 1
     * also reproduces here" — the latter is just AVFoundation wall-clock stamping, not
     * new information. Switching to the production-grade
     * [assertAvOutputStructurallyEquivalent] tolerance keeps the test as an ongoing
     * sentinel for non-determinism *beyond* the timestamp budget (size mismatch or ≥
     * `AV_TIMESTAMP_BYTE_TOLERANCE + 1` differing bytes between two NSURL calls would
     * still fail) without re-flaking on the H1 mechanism the investigation is built to
     * exclude.
     */
    @Test
    fun audio_novelOverloadTwice_staysWithinTimestampBudget() = runTest {
        val inputPath = createTestWav()
        val firstOut = testDir + "novel_a.m4a"
        val secondOut = testDir + "novel_b.m4a"

        val first = audio.compress(
            input = MediaSource.of(NSURL.fileURLWithPath(inputPath)),
            output = MediaDestination.of(NSURL.fileURLWithPath(firstOut)),
            config = AudioCompressionConfig(),
        )
        val second = audio.compress(
            input = MediaSource.of(NSURL.fileURLWithPath(inputPath)),
            output = MediaDestination.of(NSURL.fileURLWithPath(secondOut)),
            config = AudioCompressionConfig(),
        )
        first.isSuccess shouldBe true
        second.isSuccess shouldBe true

        val a = readBytes(firstOut)
        val b = readBytes(secondOut)
        assertAvOutputStructurallyEquivalent("Step 2 audio NSURL-twice", a, b)
    }

    /**
     * **Step 2 — NSURL-overload determinism sentinel (video).** See audio twin above for
     * the historical role and the rationale for using [assertAvOutputStructurallyEquivalent]
     * instead of strict bitwise equality.
     */
    @Test
    fun video_novelOverloadTwice_staysWithinTimestampBudget() = runTest {
        val inputPath = Mp4Generator.generateMp4(testDir + "input.mp4", frameCount = VIDEO_FRAME_COUNT)
        val firstOut = testDir + "novel_a.mp4"
        val secondOut = testDir + "novel_b.mp4"

        val first = video.compress(
            input = MediaSource.of(NSURL.fileURLWithPath(inputPath)),
            output = MediaDestination.of(NSURL.fileURLWithPath(firstOut)),
            config = VideoCompressionConfig(),
        )
        val second = video.compress(
            input = MediaSource.of(NSURL.fileURLWithPath(inputPath)),
            output = MediaDestination.of(NSURL.fileURLWithPath(secondOut)),
            config = VideoCompressionConfig(),
        )
        first.isSuccess shouldBe true
        second.isSuccess shouldBe true

        val a = readBytes(firstOut)
        val b = readBytes(secondOut)
        assertAvOutputStructurallyEquivalent("Step 2 video NSURL-twice", a, b)
    }

    /**
     * **Step 3 — byte-level diff capture (audio).**
     *
     * Run a legacy-overload compress and a novel-overload compress, then collect every divergent
     * byte offset. The output is the raw data fed to
     * `docs/investigations/avfoundation-nsurl-divergence.md`. To keep the test green regardless
     * of whether a particular run happens to produce identical bytes, we soft-assert via
     * `println` + `withClue` rather than hard-failing on the content.
     */
    @Test
    fun audio_legacyVsNovel_captureDivergentByteOffsets() = runTest {
        val inputPath = createTestWav()
        val legacyPath = testDir + "step3_legacy.m4a"
        val novelPath = testDir + "step3_novel.m4a"

        val legacy = audio.compress(
            MediaSource.Local.FilePath(inputPath),
            MediaDestination.Local.FilePath(legacyPath),
            AudioCompressionConfig(),
        )
        val novel = audio.compress(
            input = MediaSource.of(NSURL.fileURLWithPath(inputPath)),
            output = MediaDestination.of(NSURL.fileURLWithPath(novelPath)),
            config = AudioCompressionConfig(),
        )
        legacy.isSuccess shouldBe true
        novel.isSuccess shouldBe true

        val legacyBytes = readBytes(legacyPath)
        val novelBytes = readBytes(novelPath)
        val dump = describeDivergence("audio", legacyBytes, novelBytes)
        println(dump)

        // Always-green: this is a diagnostic. The hard assertions live in
        // [UrlInputEndToEndTest] (with its tolerance). Fail only if pipeline broke.
        withClue(dump) {
            legacyBytes.isNotEmpty() shouldBe true
            novelBytes.isNotEmpty() shouldBe true
        }
    }

    /**
     * **Step 3 — byte-level diff capture (video).**
     */
    @Test
    fun video_legacyVsNovel_captureDivergentByteOffsets() = runTest {
        val inputPath = Mp4Generator.generateMp4(testDir + "input.mp4", frameCount = VIDEO_FRAME_COUNT)
        val legacyPath = testDir + "step3_legacy.mp4"
        val novelPath = testDir + "step3_novel.mp4"

        val legacy = video.compress(
            MediaSource.Local.FilePath(inputPath),
            MediaDestination.Local.FilePath(legacyPath),
            VideoCompressionConfig(),
        )
        val novel = video.compress(
            input = MediaSource.of(NSURL.fileURLWithPath(inputPath)),
            output = MediaDestination.of(NSURL.fileURLWithPath(novelPath)),
            config = VideoCompressionConfig(),
        )
        legacy.isSuccess shouldBe true
        novel.isSuccess shouldBe true

        val legacyBytes = readBytes(legacyPath)
        val novelBytes = readBytes(novelPath)
        val dump = describeDivergence("video", legacyBytes, novelBytes)
        println(dump)

        withClue(dump) {
            legacyBytes.isNotEmpty() shouldBe true
            novelBytes.isNotEmpty() shouldBe true
        }
    }

    // ---------- helpers ----------

    private fun createTestWav(): String {
        val bytes = WavGenerator.generateWavBytes(AUDIO_DURATION_S, WAV_SAMPLE_RATE, WAV_CHANNELS)
        val path = testDir + "input.wav"
        writeBytes(path, bytes)
        return path
    }

    /**
     * Collect up to [limit] byte offsets where [a] and [b] disagree. Covers the common-prefix
     * case and size-mismatch tails. Used by Step 1 for the `firstDivergentOffsets` diagnostic
     * field of the loop summary, and by Step 3's [describeDivergence] for full hex-context
     * dumps. The structural-equivalence assertion used by Step 2 lives in the shared testutil
     * `co.crackn.kompressor.testutil.assertAvOutputStructurallyEquivalent` and computes its
     * own divergent-offset list internally.
     */
    private fun firstDivergentOffsets(a: ByteArray, b: ByteArray, limit: Int): List<Int> {
        val result = mutableListOf<Int>()
        val min = minOf(a.size, b.size)
        var i = 0
        while (i < min && result.size < limit) {
            if (a[i] != b[i]) result += i
            i++
        }
        // Record the tail mismatch offset once — further tail bytes are trivially divergent.
        if (a.size != b.size && result.size < limit) result += min
        return result
    }

    /**
     * Build a human-readable diff summary: sizes, delta, divergent offset count, first N offsets
     * with hex context, and a rough ISOBMFF-box scan (top-level boxes encountered in the legacy
     * output, so the doc artefact can map offsets to box ranges).
     */
    private fun describeDivergence(kind: String, legacy: ByteArray, novel: ByteArray): String {
        val offsets = firstDivergentOffsets(legacy, novel, limit = FULL_DUMP_LIMIT)
        val hexContext = offsets.take(DUMP_OFFSET_LIMIT).joinToString(" | ") { off ->
            "@$off " +
                "L=${hexByteAt(legacy, off)} " +
                "N=${hexByteAt(novel, off)}"
        }
        val topBoxes = scanTopLevelIsoBmffBoxes(legacy)
        return buildString {
            appendLine("[CRA-98 Step 3 $kind] legacy.size=${legacy.size} novel.size=${novel.size} " +
                "delta=${novel.size - legacy.size} divergentOffsetsCount=${offsets.size}")
            appendLine("  divergentSample: $hexContext")
            appendLine("  legacyTopBoxes: ${topBoxes.joinToString(" ")}")
        }
    }

    private fun hexByteAt(a: ByteArray, off: Int): String =
        if (off in a.indices) a[off].toInt().and(0xFF).toString(HEX_RADIX).padStart(2, '0') else "--"

    /**
     * Minimal ISOBMFF top-level box scanner — reads the 4-byte size + 4-char type at the file
     * start and recursively walks siblings (not children). Produces strings like
     * "ftyp@0-32 moov@32-4096 mdat@4096-...". Tail-lenient: stops on malformed sizes.
     *
     * NOTE: This does not implement ISO/IEC 14496-12 §4.2 edge cases — `size == 1` (64-bit
     * `largesize` follows the type) and `size == 0` (box extends to EOF) — because the
     * AVFoundation outputs observed here use neither. If a future iOS SDK emits largesize boxes
     * (e.g. for long videos) this scanner would stop early; it is diagnostic-only and callers
     * should not depend on its completeness for correctness.
     */
    private fun scanTopLevelIsoBmffBoxes(bytes: ByteArray): List<String> {
        val result = mutableListOf<String>()
        var pos = 0
        val max = MAX_BOX_SCAN
        while (pos + ISOBMFF_HEADER_SIZE <= bytes.size && result.size < max) {
            val size = readUInt32BE(bytes, pos)
            // `readUInt32BE` is unsigned (masked via `and 0xFF`) so `size >= 0`; the header-size
            // guard rejects the `size == 0` "extends to EOF" sentinel + the `size == 1`
            // largesize sentinel, since both fall below 8. A malformed oversize that would walk
            // past EOF is caught by the next iteration's `pos + ISOBMFF_HEADER_SIZE <= bytes.size`
            // guard — no extra check needed here.
            if (size < ISOBMFF_HEADER_SIZE.toLong()) break
            val type = bytes.decodeToString(
                startIndex = pos + ISOBMFF_SIZE_BYTES,
                endIndex = pos + ISOBMFF_HEADER_SIZE,
            )
            val end = (pos + size).coerceAtMost(bytes.size.toLong()).toInt()
            result += "$type@$pos-$end"
            pos = (pos + size).toInt()
        }
        return result
    }

    private fun readUInt32BE(bytes: ByteArray, off: Int): Long {
        val b0 = bytes[off].toLong() and 0xFF
        val b1 = bytes[off + 1].toLong() and 0xFF
        val b2 = bytes[off + 2].toLong() and 0xFF
        val b3 = bytes[off + 3].toLong() and 0xFF
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    private companion object {
        const val LOOP_ITERATIONS = 100

        /**
         * Real inter-iteration wall-clock pad (milliseconds), consumed by
         * [platform.posix.usleep]. Keeps [LOOP_ITERATIONS]×[STRADDLE_DELAY_MS] guaranteed wall-
         * clock runtime independent of AVFoundation per-compress cost — see
         * [audio_legacyOverload_spansWallClockSecondBoundary] KDoc for the rationale.
         */
        const val STRADDLE_DELAY_MS = 50L

        /** Microseconds per millisecond — [platform.posix.usleep] takes microseconds. */
        const val MICROS_PER_MILLI = 1_000L

        /**
         * Lower bound on the total wall-clock span of the Step-1 loop, computed as
         * `(LOOP_ITERATIONS - 1) * STRADDLE_DELAY_MS`. Purely derivable (`= 99 × 50 = 4_950 ms`)
         * but kept as a named constant so the assertion message and the KDoc stay in sync.
         */
        const val MIN_STRADDLE_SPAN_MS = (LOOP_ITERATIONS - 1) * STRADDLE_DELAY_MS

        const val DUMP_OFFSET_LIMIT = 32
        const val FULL_DUMP_LIMIT = 512
        const val AUDIO_DURATION_S = 2
        const val VIDEO_FRAME_COUNT = 30
        const val WAV_SAMPLE_RATE = 44_100
        const val WAV_CHANNELS = 2
        const val HEX_RADIX = 16
        const val MAX_BOX_SCAN = 16
        const val ISOBMFF_HEADER_SIZE = 8
        const val ISOBMFF_SIZE_BYTES = 4
    }
}
