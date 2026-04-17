/*
 * Copyright 2026 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.matrix

import co.crackn.kompressor.AudioCodec
import co.crackn.kompressor.MIME_AUDIO_AAC
import co.crackn.kompressor.MIME_VIDEO_H264
import co.crackn.kompressor.MIME_VIDEO_HEVC
import co.crackn.kompressor.image.AVIF_INPUT_MIN_API_ANDROID
import co.crackn.kompressor.image.AVIF_INPUT_MIN_IOS
import co.crackn.kompressor.image.HEIC_INPUT_MIN_API_ANDROID
import co.crackn.kompressor.video.VideoCodec
import io.kotest.matchers.collections.shouldNotContainAnyOf
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test

/**
 * Cross-references every cell in [FormatSupportMatrix] against the authoritative sources:
 * `ImageFormatGates` constants and the DoD-listed formats. If a gate constant changes (e.g. a
 * future Android version gains AVIF earlier than API 31), this test fails and forces the doc
 * to be regenerated — meeting the "source-of-truth: device tests confirm each cell"
 * requirement at the deterministic-host level. The on-device smoke tests
 * (`ImageFormatMatrixTest` / `AudioInputRobustnessTest`) provide the real-hardware evidence
 * that the numbers here actually work.
 */
class FormatSupportMatrixConsistencyTest {

    @Test
    fun imageMatrixRowsCoverEveryFormatCalledOutByTheDoD() {
        // CRA-43 DoD explicitly names these image formats as required rows. A missing entry
        // means the published doc can't answer the "does Kompressor support <format>?"
        // question the ticket exists to solve.
        val required = setOf("JPEG", "PNG", "WEBP", "HEIC", "AVIF")
        val present = FormatSupportMatrix.image.map { it.formatIn.uppercase().substringBefore(" ") }.toSet()
        (required - present) shouldBe emptySet()
    }

    @Test
    fun audioMatrixRowsCoverEveryFormatCalledOutByTheDoD() {
        val required = setOf("AAC", "MP3", "FLAC", "OGG", "OPUS")
        val present = FormatSupportMatrix.audio.map { it.formatIn.uppercase().substringBefore(" ") }.toSet()
        (required - present) shouldBe emptySet()
    }

    @Test
    fun videoMatrixRowsCoverEveryFormatCalledOutByTheDoD() {
        // "H.265 / HEVC" is a single codec; we normalise to the canonical form for the check.
        val required = setOf("H.264", "H.265", "VP9", "AV1")
        val present = FormatSupportMatrix.video.map { it.formatIn.substringBefore(" ") }.toSet()
        (required - present) shouldBe emptySet()
    }

    @Test
    fun heicImageRowCarriesTheSameAndroidGateAsImageFormatGates() {
        val heicRow = FormatSupportMatrix.image.single { it.formatIn == "HEIC" }
        heicRow.androidMinApi shouldBe HEIC_INPUT_MIN_API_ANDROID
        FormatSupportMatrix.HEIC_INPUT_MIN_API_ANDROID shouldBe HEIC_INPUT_MIN_API_ANDROID
    }

    @Test
    fun avifImageRowCarriesTheSameGatesAsImageFormatGates() {
        val avifRow = FormatSupportMatrix.image.single { it.formatIn == "AVIF" }
        avifRow.androidMinApi shouldBe AVIF_INPUT_MIN_API_ANDROID
        avifRow.iosMinVersion shouldBe AVIF_INPUT_MIN_IOS
        FormatSupportMatrix.AVIF_INPUT_MIN_API_ANDROID shouldBe AVIF_INPUT_MIN_API_ANDROID
        FormatSupportMatrix.AVIF_INPUT_MIN_IOS shouldBe AVIF_INPUT_MIN_IOS
    }

    @Test
    fun onlyExperimentalImageRowsCarryExperimentalAnnotationCallout() {
        // Guard against accidental drift in the notes: the `@ExperimentalKompressorApi`
        // callout tracks the public `ImageFormat` output-enum annotations — HEIC and AVIF
        // are the only two output entries annotated today. HEIF has the same Android decode
        // gate as HEIC but isn't a distinct output format, so it should NOT advertise the
        // callout.
        val experimentalMentioned = FormatSupportMatrix.image
            .filter { "@ExperimentalKompressorApi" in it.notes }
            .map { it.formatIn }
            .toSet()
        experimentalMentioned shouldBe setOf("HEIC", "AVIF")
    }

    @Test
    fun iosUnsupportedRowsAreNeverFastPathEligible() {
        // A fast path requires a working decoder on the target platform. Catch the trivial
        // contradiction of "unsupported on iOS but has fast-path = Yes" — would silently
        // surface wrong "yes" to platform callers reading the matrix.
        val contradictions = FormatSupportMatrix.audio.filter {
            it.iosMinVersion == FormatSupportMatrix.IOS_UNSUPPORTED && it.fastPathEligible
        } + FormatSupportMatrix.video.filter {
            it.iosMinVersion == FormatSupportMatrix.IOS_UNSUPPORTED && it.fastPathEligible
        }
        contradictions.map { it.formatIn } shouldBe emptyList()
    }

    @Test
    fun audioMatrixOutputIsAlwaysAac() {
        // In this release `AudioCodec` is a single-entry enum (AAC). If that changes, this
        // test is a reminder to add rows for the new output codec.
        val outputs = FormatSupportMatrix.audio.map { it.formatOut }.toSet()
        outputs shouldBe setOf("AAC")
    }

    @Test
    fun audioMatrixOutputSetMatchesAudioCodecEnumAndSupportabilityMime() {
        // Cross-reference with the public `AudioCodec` enum and the internal `MIME_AUDIO_AAC`
        // constant consumed by `evaluateSupport`. If a new AudioCodec entry is introduced (say
        // `OPUS`) or the AAC MIME string is re-written, this assertion forces the matrix to be
        // updated in lockstep — honouring the DoD clause "cross-ref with
        // Supportability.evaluateSupport".
        val enumNames = AudioCodec.entries.map { it.name }.toSet()
        enumNames shouldBe setOf("AAC")
        MIME_AUDIO_AAC shouldBe "audio/mp4a-latm"
        val matrixOutputs = FormatSupportMatrix.audio.map { it.formatOut }.toSet()
        matrixOutputs shouldBe enumNames
    }

    @Test
    fun videoMatrixOutputIsH264OrHevc() {
        val outputs = FormatSupportMatrix.video.map { it.formatOut }.toSet()
        outputs shouldBe setOf("H.264 / HEVC")
    }

    @Test
    fun videoMatrixOutputSetCoversVideoCodecEnumAndSupportabilityMimes() {
        // Pin the matrix's video output label to the `VideoCodec` enum + the MIME constants
        // consumed by `evaluateSupport`. The matrix publishes the pair as a single "H.264 /
        // HEVC" cell because both are always co-offered, but the underlying promise is the
        // VideoCodec enum set — any new codec (e.g. AV1 as output) must either extend the
        // label or fail this assertion.
        val enumNames = VideoCodec.entries.map { it.name }.toSet()
        enumNames shouldBe setOf("H264", "HEVC")
        MIME_VIDEO_H264 shouldBe "video/avc"
        MIME_VIDEO_HEVC shouldBe "video/hevc"
        // Every video row exposes both codecs as output — the label is the literal "H.264 /
        // HEVC". If a future VideoCodec extension reaches encoder parity we'll split per-row.
        val matrixLabels = FormatSupportMatrix.video.map { it.formatOut }.toSet()
        matrixLabels shouldBe setOf("H.264 / HEVC")
    }

    @Test
    fun imageRowsHaveUniqueFormatIn() {
        val duplicates = FormatSupportMatrix.image
            .groupBy { it.formatIn }
            .filter { it.value.size > 1 }
            .keys
        duplicates shouldBe emptySet()
    }

    @Test
    fun audioRowsHaveUniqueFormatIn() {
        val duplicates = FormatSupportMatrix.audio
            .groupBy { it.formatIn }
            .filter { it.value.size > 1 }
            .keys
        duplicates shouldBe emptySet()
    }

    @Test
    fun videoRowsHaveUniqueFormatIn() {
        val duplicates = FormatSupportMatrix.video
            .groupBy { it.formatIn }
            .filter { it.value.size > 1 }
            .keys
        duplicates shouldBe emptySet()
    }

    @Test
    fun notesCellsNeverContainPipeCharactersThatWouldBreakTheMarkdownTable() {
        val allRows = FormatSupportMatrix.image.map { it.notes } +
            FormatSupportMatrix.audio.map { it.notes } +
            FormatSupportMatrix.video.map { it.notes }
        allRows.shouldNotContainAnyOf(allRows.filter { "|" in it })
    }

    @Test
    fun renderedMatrixContainsEveryFormatInRow() {
        // Render-level smoke: the markdown output must contain every format-in cell as literal
        // text. Catches renderer regressions that would silently drop rows.
        val rendered = renderFormatSupportMatrixTables()
        FormatSupportMatrix.image.forEach { rendered shouldContain it.formatIn }
        FormatSupportMatrix.audio.forEach { rendered shouldContain it.formatIn }
        FormatSupportMatrix.video.forEach { rendered shouldContain it.formatIn }
    }

    @Test
    fun renderedMatrixIsFramedByBeginAndEndMarkers() {
        val rendered = renderFormatSupportMatrixTables()
        rendered.startsWith(FORMAT_SUPPORT_MATRIX_BEGIN_MARKER) shouldBe true
        rendered.endsWith(FORMAT_SUPPORT_MATRIX_END_MARKER) shouldBe true
    }

    @Test
    fun iosUnsupportedCellRendersAsEmDash() {
        val rendered = renderFormatSupportMatrixTables()
        // Scope the assertion to the MP3 row so an unrelated "—" somewhere else in the doc
        // can't accidentally pass the test.
        val mp3Line = rendered.lines().single { it.startsWith("| MP3 |") }
        mp3Line shouldContain " — "
    }
}
