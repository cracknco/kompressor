/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.audio

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

class AudioCompressionErrorTest {

    @Test
    fun unsupportedSourceFormatCarriesDetailsAndCause() {
        val cause = RuntimeException("boom")
        val error = AudioCompressionError.UnsupportedSourceFormat("audio/flac 96kHz 2ch", cause)
        error.details shouldBe "audio/flac 96kHz 2ch"
        error.cause shouldBe cause
        checkNotNull(error.message) shouldContain "audio/flac 96kHz 2ch"
    }

    @Test
    fun unsupportedConfigurationCarriesDetailsAndCause() {
        val cause = RuntimeException("cannot upmix")
        val error = AudioCompressionError.UnsupportedConfiguration(
            "iOS cannot upmix a 1-channel source into 2-channel output",
            cause,
        )
        error.details shouldBe "iOS cannot upmix a 1-channel source into 2-channel output"
        error.cause shouldBe cause
        checkNotNull(error.message) shouldContain "Unsupported configuration"
        checkNotNull(error.message) shouldContain "upmix"
    }

    @Test
    fun unsupportedBitrateCarriesDetailsAndCause() {
        val cause = RuntimeException("over cap")
        val error = AudioCompressionError.UnsupportedBitrate(
            "128000 bps exceeds max 64000 bps at 22050 Hz x 1 channel(s)",
            cause,
        )
        error.details shouldBe "128000 bps exceeds max 64000 bps at 22050 Hz x 1 channel(s)"
        error.cause shouldBe cause
        checkNotNull(error.message) shouldContain "Unsupported bitrate"
    }

    @Test
    fun subtypesAreDistinguishableViaWhen() {
        val errors = listOf(
            AudioCompressionError.UnsupportedSourceFormat("a"),
            AudioCompressionError.DecodingFailed("b"),
            AudioCompressionError.EncodingFailed("c"),
            AudioCompressionError.IoFailed("d"),
            AudioCompressionError.UnsupportedConfiguration("e"),
            AudioCompressionError.UnsupportedBitrate("f"),
            AudioCompressionError.Unknown("g"),
        )
        errors[0].shouldBeInstanceOf<AudioCompressionError.UnsupportedSourceFormat>()
        errors[1].shouldBeInstanceOf<AudioCompressionError.DecodingFailed>()
        errors[2].shouldBeInstanceOf<AudioCompressionError.EncodingFailed>()
        errors[3].shouldBeInstanceOf<AudioCompressionError.IoFailed>()
        errors[4].shouldBeInstanceOf<AudioCompressionError.UnsupportedConfiguration>()
        errors[5].shouldBeInstanceOf<AudioCompressionError.UnsupportedBitrate>()
        errors[6].shouldBeInstanceOf<AudioCompressionError.Unknown>()
    }

    @Test
    fun everySubtypePrefixesItsMessageWithItsCategory() {
        checkNotNull(AudioCompressionError.UnsupportedSourceFormat("x").message) shouldContain "Unsupported"
        checkNotNull(AudioCompressionError.DecodingFailed("x").message) shouldContain "Decoding"
        checkNotNull(AudioCompressionError.EncodingFailed("x").message) shouldContain "Encoding"
        checkNotNull(AudioCompressionError.IoFailed("x").message) shouldContain "IO"
        checkNotNull(AudioCompressionError.UnsupportedConfiguration("x").message) shouldContain "Unsupported configuration"
        checkNotNull(AudioCompressionError.UnsupportedBitrate("x").message) shouldContain "Unsupported bitrate"
        checkNotNull(AudioCompressionError.Unknown("x").message) shouldContain "Compression failed"
    }

    // ── CRA-90 I/O scaffolding error variants ──────────────────────────────
    //
    // Four new `data class` variants added alongside the existing `class` variants. Shape:
    // `(details: String, override val cause: Throwable? = null)` — the data-class generated
    // `equals` / `hashCode` is what callers rely on. Each variant has three tests: carries
    // details+cause, extends the parent sealed class, default cause is null.

    @Test
    fun sourceNotFoundCarriesDetailsAndCause() {
        val cause = RuntimeException("boom")
        val err = AudioCompressionError.SourceNotFound("content://x invalid", cause)
        err.details shouldBe "content://x invalid"
        err.cause shouldBe cause
        err shouldBe AudioCompressionError.SourceNotFound("content://x invalid", cause)
        checkNotNull(err.message) shouldContain "Source not found"
    }

    @Test
    fun sourceNotFoundExtendsAudioCompressionError() {
        val err: AudioCompressionError = AudioCompressionError.SourceNotFound("x")
        err.shouldBeInstanceOf<AudioCompressionError>()
    }

    @Test
    fun sourceNotFoundCauseDefaultsToNull() {
        AudioCompressionError.SourceNotFound("x").cause shouldBe null
    }

    @Test
    fun sourceReadFailedCarriesDetailsAndCause() {
        val cause = RuntimeException("io")
        val err = AudioCompressionError.SourceReadFailed("offset 512", cause)
        err.details shouldBe "offset 512"
        err.cause shouldBe cause
        err shouldBe AudioCompressionError.SourceReadFailed("offset 512", cause)
        checkNotNull(err.message) shouldContain "Source read failed"
    }

    @Test
    fun sourceReadFailedExtendsAudioCompressionError() {
        val err: AudioCompressionError = AudioCompressionError.SourceReadFailed("x")
        err.shouldBeInstanceOf<AudioCompressionError>()
    }

    @Test
    fun sourceReadFailedCauseDefaultsToNull() {
        AudioCompressionError.SourceReadFailed("x").cause shouldBe null
    }

    @Test
    fun destinationWriteFailedCarriesDetailsAndCause() {
        val cause = RuntimeException("ENOSPC")
        val err = AudioCompressionError.DestinationWriteFailed("disk full", cause)
        err.details shouldBe "disk full"
        err.cause shouldBe cause
        err shouldBe AudioCompressionError.DestinationWriteFailed("disk full", cause)
        checkNotNull(err.message) shouldContain "Destination write failed"
    }

    @Test
    fun destinationWriteFailedExtendsAudioCompressionError() {
        val err: AudioCompressionError = AudioCompressionError.DestinationWriteFailed("x")
        err.shouldBeInstanceOf<AudioCompressionError>()
    }

    @Test
    fun destinationWriteFailedCauseDefaultsToNull() {
        AudioCompressionError.DestinationWriteFailed("x").cause shouldBe null
    }

    @Test
    fun tempFileFailedCarriesDetailsAndCause() {
        val cause = RuntimeException("cache unavailable")
        val err = AudioCompressionError.TempFileFailed("/cache/tmp", cause)
        err.details shouldBe "/cache/tmp"
        err.cause shouldBe cause
        err shouldBe AudioCompressionError.TempFileFailed("/cache/tmp", cause)
        checkNotNull(err.message) shouldContain "Temp file failed"
    }

    @Test
    fun tempFileFailedExtendsAudioCompressionError() {
        val err: AudioCompressionError = AudioCompressionError.TempFileFailed("x")
        err.shouldBeInstanceOf<AudioCompressionError>()
    }

    @Test
    fun tempFileFailedCauseDefaultsToNull() {
        AudioCompressionError.TempFileFailed("x").cause shouldBe null
    }

    // ── CRA-110 waveform error variant ─────────────────────────────────────
    //
    // `NoAudioTrack` surfaces when a source container (e.g. a video-only MP4 or an image file)
    // is passed to `AudioCompressor.waveform`. Mirrors the shape of the other `data class`
    // I/O variants (CRA-90).

    @Test
    fun noAudioTrackCarriesDetailsAndCause() {
        val cause = RuntimeException("image passthrough")
        val err = AudioCompressionError.NoAudioTrack("photo.jpg has 0 audio tracks", cause)
        err.details shouldBe "photo.jpg has 0 audio tracks"
        err.cause shouldBe cause
        err shouldBe AudioCompressionError.NoAudioTrack("photo.jpg has 0 audio tracks", cause)
        checkNotNull(err.message) shouldContain "No audio track"
    }

    @Test
    fun noAudioTrackExtendsAudioCompressionError() {
        val err: AudioCompressionError = AudioCompressionError.NoAudioTrack("x")
        err.shouldBeInstanceOf<AudioCompressionError>()
    }

    @Test
    fun noAudioTrackCauseDefaultsToNull() {
        AudioCompressionError.NoAudioTrack("x").cause shouldBe null
    }
}
