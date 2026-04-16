/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package co.crackn.kompressor

import co.crackn.kompressor.audio.AudioCompressionError
import co.crackn.kompressor.audio.IosAudioCompressor
import co.crackn.kompressor.image.ImageCompressionError
import co.crackn.kompressor.image.IosImageCompressor
import co.crackn.kompressor.testutil.MinimalPngFixtures
import co.crackn.kompressor.testutil.Mp4Generator
import co.crackn.kompressor.testutil.TestConstants.SAMPLE_RATE_44K
import co.crackn.kompressor.testutil.TestConstants.STEREO
import co.crackn.kompressor.testutil.WavGenerator
import co.crackn.kompressor.testutil.fileSize
import co.crackn.kompressor.testutil.readBytes
import co.crackn.kompressor.testutil.writeBytes
import co.crackn.kompressor.video.IosVideoCompressor
import co.crackn.kompressor.video.VideoCompressionError
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.UIKit.UIImage
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * iOS contract tests for the Unicode edge cases that historically trip AVFoundation and
 * Core Graphics when they appear in an output file path: emoji ZWJ sequences, Arabic
 * RTL text, a bare zero-width joiner, and an NFD-encoded combining mark.
 *
 * For every case the compressor must either succeed (producing a non-empty output file
 * that the platform can still recognise as valid media — `UIImage.imageWithContentsOfFile`
 * must return non-nil for JPEG, the MP4 / M4A must begin with the ISO BMFF "ftyp" box)
 * or fail with the typed `IoFailed` variant of the corresponding per-media sealed error
 * hierarchy, carrying a `details` string with a readable reason (≥ 10 non-whitespace
 * chars). Anything else — an opaque `Unknown`, an `NPE` from the ObjC bridge, a terse
 * one-letter reason, a header-less / zero-byte file, or a crash — is a regression.
 *
 * The CRA-10 DoD refers to the typed failure as `IoError.InvalidPath`; the equivalent
 * in this codebase is `ImageCompressionError.IoFailed`, `AudioCompressionError.IoFailed`,
 * and `VideoCompressionError.IoFailed` respectively. The contract (typed + readable) is
 * identical.
 *
 * This complements `PathEncodingTest` in the same source set, which already covers the
 * common cases (spaces, NFC-precomposed accents, CJK, single-code-point emoji).
 */
class IosPathEncodingTest {

    private lateinit var tempDir: String
    private val image = IosImageCompressor()
    private val audio = IosAudioCompressor()
    private val video = IosVideoCompressor()

    @BeforeTest
    fun setUp() {
        tempDir = NSTemporaryDirectory() + "kompressor-ios-path-encoding-${NSUUID().UUIDString}/"
        check(makeDirs(tempDir)) { "Failed to create base temp dir: $tempDir" }
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtPath(tempDir, null)
    }

    // ---- Image ----

    @Test
    fun imageCompress_outputWithEmojiZwjFamily_succeedsOrTypedIoFailed() = runTest {
        runImageCase(label = "emoji ZWJ family 👨‍👩‍👧", trickySegment = EMOJI_ZWJ_FAMILY)
    }

    @Test
    fun imageCompress_outputWithRtlArabic_succeedsOrTypedIoFailed() = runTest {
        runImageCase(label = "RTL Arabic مرحبا", trickySegment = RTL_ARABIC)
    }

    @Test
    fun imageCompress_outputWithBareZeroWidthJoiner_succeedsOrTypedIoFailed() = runTest {
        runImageCase(label = "bare zero-width joiner", trickySegment = ZWJ_BETWEEN_LETTERS)
    }

    @Test
    fun imageCompress_outputWithNfdCombiningMark_succeedsOrTypedIoFailed() = runTest {
        runImageCase(label = "NFD café (ca + U+0301)", trickySegment = NFD_CAFE)
    }

    // ---- Audio ----

    @Test
    fun audioCompress_outputWithEmojiZwjFamily_succeedsOrTypedIoFailed() = runTest {
        runAudioCase(label = "emoji ZWJ family 👨‍👩‍👧", trickySegment = EMOJI_ZWJ_FAMILY)
    }

    @Test
    fun audioCompress_outputWithRtlArabic_succeedsOrTypedIoFailed() = runTest {
        runAudioCase(label = "RTL Arabic مرحبا", trickySegment = RTL_ARABIC)
    }

    @Test
    fun audioCompress_outputWithBareZeroWidthJoiner_succeedsOrTypedIoFailed() = runTest {
        runAudioCase(label = "bare zero-width joiner", trickySegment = ZWJ_BETWEEN_LETTERS)
    }

    @Test
    fun audioCompress_outputWithNfdCombiningMark_succeedsOrTypedIoFailed() = runTest {
        runAudioCase(label = "NFD café (ca + U+0301)", trickySegment = NFD_CAFE)
    }

    // ---- Video ----

    @Test
    fun videoCompress_outputWithEmojiZwjFamily_succeedsOrTypedIoFailed() = runTest {
        runVideoCase(label = "emoji ZWJ family 👨‍👩‍👧", trickySegment = EMOJI_ZWJ_FAMILY)
    }

    @Test
    fun videoCompress_outputWithRtlArabic_succeedsOrTypedIoFailed() = runTest {
        runVideoCase(label = "RTL Arabic مرحبا", trickySegment = RTL_ARABIC)
    }

    @Test
    fun videoCompress_outputWithBareZeroWidthJoiner_succeedsOrTypedIoFailed() = runTest {
        runVideoCase(label = "bare zero-width joiner", trickySegment = ZWJ_BETWEEN_LETTERS)
    }

    @Test
    fun videoCompress_outputWithNfdCombiningMark_succeedsOrTypedIoFailed() = runTest {
        runVideoCase(label = "NFD café (ca + U+0301)", trickySegment = NFD_CAFE)
    }

    // ---- helpers ----

    private suspend fun runImageCase(label: String, trickySegment: String) {
        val inputPath = tempDir + "input.png"
        writeBytes(inputPath, MinimalPngFixtures.indexed4x4())
        val outputPath = prepareTrickyOutputPath(label, trickySegment, extension = "jpg")

        val result = image.compress(inputPath, outputPath)

        assertSucceedsOrReturnsTypedIoFailed(
            label = label,
            outputPath = outputPath,
            result = result,
            typedFailureCheck = { it is ImageCompressionError.IoFailed && it.details.isReadableReason() },
            typedFailureName = "ImageCompressionError.IoFailed",
            verifyDecodable = ::canDecodeJpeg,
        )
    }

    private suspend fun runAudioCase(label: String, trickySegment: String) {
        val inputPath = tempDir + "input.wav"
        writeBytes(
            inputPath,
            WavGenerator.generateWavBytes(
                durationSeconds = 1,
                sampleRate = SAMPLE_RATE_44K,
                channels = STEREO,
            ),
        )
        val outputPath = prepareTrickyOutputPath(label, trickySegment, extension = "m4a")

        val result = audio.compress(inputPath, outputPath)

        assertSucceedsOrReturnsTypedIoFailed(
            label = label,
            outputPath = outputPath,
            result = result,
            typedFailureCheck = { it is AudioCompressionError.IoFailed && it.details.isReadableReason() },
            typedFailureName = "AudioCompressionError.IoFailed",
            verifyDecodable = ::hasMp4FtypSignature,
        )
    }

    private suspend fun runVideoCase(label: String, trickySegment: String) {
        val inputPath = tempDir + "input.mp4"
        Mp4Generator.generateMp4(inputPath, frameCount = VIDEO_FRAME_COUNT)
        val outputPath = prepareTrickyOutputPath(label, trickySegment, extension = "mp4")

        val result = video.compress(inputPath, outputPath)

        assertSucceedsOrReturnsTypedIoFailed(
            label = label,
            outputPath = outputPath,
            result = result,
            typedFailureCheck = { it is VideoCompressionError.IoFailed && it.details.isReadableReason() },
            typedFailureName = "VideoCompressionError.IoFailed",
            verifyDecodable = ::hasMp4FtypSignature,
        )
    }

    private fun prepareTrickyOutputPath(label: String, trickySegment: String, extension: String): String {
        val outDir = tempDir + "$trickySegment-out/"
        check(makeDirs(outDir)) {
            "Platform rejected mkdir for case '$label' (segment='$trickySegment'): $outDir"
        }
        return outDir + "$trickySegment.$extension"
    }

    private fun assertSucceedsOrReturnsTypedIoFailed(
        label: String,
        outputPath: String,
        result: Result<*>,
        typedFailureCheck: (Throwable) -> Boolean,
        typedFailureName: String,
        verifyDecodable: (String) -> Boolean,
    ) {
        if (result.isSuccess) {
            assertTrue(
                fileSize(outputPath) > 0,
                "Case '$label' reported success but wrote empty file at $outputPath",
            )
            assertTrue(
                verifyDecodable(outputPath),
                "Case '$label' reported success and wrote bytes but the output at " +
                    "$outputPath is not decodable by the platform — AVFoundation/UIKit " +
                    "wrote a corrupt file on a tricky path",
            )
            return
        }
        val error = result.exceptionOrNull()
            ?: fail("Case '$label': result.isFailure but exception is null — contract violation")
        if (!typedFailureCheck(error)) {
            fail(
                "Case '$label' failed with an opaque or untyped error — expected " +
                    "$typedFailureName with a readable reason (>= $MIN_READABLE_REASON_LEN " +
                    "non-whitespace chars), got ${error::class.simpleName}: ${error.message}",
            )
        }
    }

    private fun String.isReadableReason(): Boolean = trim().length >= MIN_READABLE_REASON_LEN

    private fun canDecodeJpeg(path: String): Boolean = UIImage.imageWithContentsOfFile(path) != null

    /**
     * Verifies [path] starts with a valid ISO BMFF "ftyp" box — the mandatory first
     * box of an MP4 / M4A container (ISO/IEC 14496-12 §4.3). A file the compressor
     * "succeeded" writing but which lacks this signature is a zero-byte or header-only
     * corruption; catching that is the value the CRA-10 contract adds over
     * `fileSize > 0`. A stronger decode (AVURLAsset.duration) is deliberately avoided
     * because AVFoundation's asset loader is async-lazy for duration and has known
     * issues dereferencing `NSURL`s over paths containing a bare U+200D on the
     * Xcode 16 iOS simulator — issues orthogonal to the contract this test defends.
     */
    private fun hasMp4FtypSignature(path: String): Boolean {
        // Intentionally propagates any read failure instead of collapsing it into a
        // `false` return — otherwise a bug in the test harness's own path handling
        // (the very surface this test exercises) would be misreported as "AVFoundation/
        // UIKit wrote a corrupt file" and pin the blame on the compressor.
        val bytes = readBytes(path)
        if (bytes.size < FTYP_PROBE_OFFSET + FTYP_LEN) return false
        return bytes[FTYP_PROBE_OFFSET] == 'f'.code.toByte() &&
            bytes[FTYP_PROBE_OFFSET + 1] == 't'.code.toByte() &&
            bytes[FTYP_PROBE_OFFSET + 2] == 'y'.code.toByte() &&
            bytes[FTYP_PROBE_OFFSET + 3] == 'p'.code.toByte()
    }

    private fun makeDirs(path: String): Boolean =
        NSFileManager.defaultManager.createDirectoryAtPath(
            path,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )

    private companion object {
        const val VIDEO_FRAME_COUNT = 15

        // Minimum non-whitespace length for a typed-error `details` string to qualify as a
        // "readable reason" (ticket DoD: "raison lisible"). Floors out degenerate messages like
        // "x" or "io" that would satisfy `isNotBlank()` but defeat the contract. AVFoundation's
        // own format `"${domain}#${code} — ${localizedDescription}"` is always well above this.
        const val MIN_READABLE_REASON_LEN = 10

        // ISO BMFF "ftyp" box. The box-size prefix occupies bytes 0–3, the 4-char type
        // occupies bytes 4–7. Every well-formed MP4 / M4A starts exactly this way.
        const val FTYP_PROBE_OFFSET = 4
        const val FTYP_LEN = 4

        // MAN + ZWJ + WOMAN + ZWJ + GIRL — renders as 👨‍👩‍👧 on fonts that support the sequence,
        // degrades to three separate emoji elsewhere. The bytes on disk are identical either way.
        const val EMOJI_ZWJ_FAMILY = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"

        // Arabic "hello". Stored as UTF-8 bytes; the RTL rendering is a display concern but the
        // bidi-class codepoints have historically confused AVAssetWriter path parsing.
        const val RTL_ARABIC = "مرحبا"

        // Bare ZWJ (U+200D) between two ASCII letters — not part of a valid grapheme cluster.
        // Legal in an APFS file name; platforms must either honour it or reject with IoFailed.
        const val ZWJ_BETWEEN_LETTERS = "a\u200Db"

        // "café" in NFD form: c, a, f, e, U+0301 COMBINING ACUTE ACCENT. The 5-codepoint form
        // historically tripped AVAssetWriter and CoreGraphics output-URL parsing; the contract
        // is that compressors must handle it without crashing or returning an opaque error.
        // (Byte-exact APFS path preservation is a separate invariant, not asserted by this test.)
        const val NFD_CAFE = "cafe\u0301"
    }
}
