/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

import co.crackn.kompressor.audio.AudioCompressionError
import co.crackn.kompressor.image.ImageCompressionError
import co.crackn.kompressor.video.VideoCompressionError
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/**
 * Enforces the typed-error taxonomy contract for CRA-21.
 *
 * For every subclass of each `*CompressionError` sealed hierarchy:
 *  - the class is constructible with a `details` string,
 *  - the resulting `message` embeds those details (so the string survives `throw`/`printStackTrace`),
 *  - the optional `cause` is preserved.
 *
 * The `when` blocks over the sealed parent give us compile-time exhaustiveness: adding a new
 * subtype without updating this test becomes a compile error, not a runtime gap. The reference
 * side — that every throw site targets one of these subtypes — is enforced by
 * `scripts/audit-throws.sh` in CI.
 *
 * Reflection note: `KClass.sealedSubclasses` is not available on Kotlin/Native commonMain, so we
 * enumerate subclasses explicitly instead of walking the hierarchy at runtime.
 */
class ErrorTaxonomyCompletenessTest {

    private val root = RuntimeException("platform cause")

    @Test
    fun videoCompressionError_everySubtypeIsConstructibleAndPreservesCause() {
        val samples: List<VideoCompressionError> = listOf(
            VideoCompressionError.UnsupportedSourceFormat("hevc-main10", root),
            VideoCompressionError.DecodingFailed("corrupt-sample", root),
            VideoCompressionError.EncodingFailed("no-h264-encoder", root),
            VideoCompressionError.IoFailed("permission-denied", root),
            VideoCompressionError.Unknown("mystery", root),
        )
        samples.forEach { it.assertTypedErrorContract() }

        samples.forEach {
            @Suppress("UNUSED_EXPRESSION")
            when (it) {
                is VideoCompressionError.UnsupportedSourceFormat -> it.details shouldBe "hevc-main10"
                is VideoCompressionError.DecodingFailed -> it.details shouldBe "corrupt-sample"
                is VideoCompressionError.EncodingFailed -> it.details shouldBe "no-h264-encoder"
                is VideoCompressionError.IoFailed -> it.details shouldBe "permission-denied"
                is VideoCompressionError.Unknown -> it.details shouldBe "mystery"
            }
        }
    }

    @Test
    fun audioCompressionError_everySubtypeIsConstructibleAndPreservesCause() {
        val samples: List<AudioCompressionError> = listOf(
            AudioCompressionError.UnsupportedSourceFormat("opus", root),
            AudioCompressionError.DecodingFailed("truncated", root),
            AudioCompressionError.EncodingFailed("muxer-refused", root),
            AudioCompressionError.IoFailed("disk-full", root),
            AudioCompressionError.UnsupportedConfiguration("mono->stereo", root),
            AudioCompressionError.UnsupportedBitrate("8kbps-not-in-range", root),
            AudioCompressionError.Unknown("mystery", root),
        )
        samples.forEach { it.assertTypedErrorContract() }

        samples.forEach {
            @Suppress("UNUSED_EXPRESSION")
            when (it) {
                is AudioCompressionError.UnsupportedSourceFormat -> it.details shouldBe "opus"
                is AudioCompressionError.DecodingFailed -> it.details shouldBe "truncated"
                is AudioCompressionError.EncodingFailed -> it.details shouldBe "muxer-refused"
                is AudioCompressionError.IoFailed -> it.details shouldBe "disk-full"
                is AudioCompressionError.UnsupportedConfiguration -> it.details shouldBe "mono->stereo"
                is AudioCompressionError.UnsupportedBitrate -> it.details shouldBe "8kbps-not-in-range"
                is AudioCompressionError.Unknown -> it.details shouldBe "mystery"
            }
        }
    }

    @Test
    fun imageCompressionError_everySubtypeIsConstructibleAndPreservesCause() {
        val samples: List<ImageCompressionError> = listOf(
            ImageCompressionError.UnsupportedSourceFormat("random-bytes", root),
            ImageCompressionError.DecodingFailed("truncated-jpeg", root),
            ImageCompressionError.EncodingFailed("bitmap-compress-false", root),
            ImageCompressionError.IoFailed("content-uri-revoked", root),
            ImageCompressionError.Unknown("mystery", root),
        )
        samples.forEach { it.assertTypedErrorContract() }

        samples.forEach {
            @Suppress("UNUSED_EXPRESSION")
            when (it) {
                is ImageCompressionError.UnsupportedSourceFormat -> it.details shouldBe "random-bytes"
                is ImageCompressionError.DecodingFailed -> it.details shouldBe "truncated-jpeg"
                is ImageCompressionError.EncodingFailed -> it.details shouldBe "bitmap-compress-false"
                is ImageCompressionError.IoFailed -> it.details shouldBe "content-uri-revoked"
                is ImageCompressionError.Unknown -> it.details shouldBe "mystery"
            }
        }
    }

    @Test
    fun rootClasses_areThrowable() {
        // All three hierarchies must be throwable — VideoCompressionError/AudioCompressionError
        // extend Exception, ImageCompressionError extends RuntimeException. Downstream callers rely
        // on this to `when`-branch inside a catch block without unchecked-cast warnings.
        VideoCompressionError.Unknown("v").shouldBeInstanceOf<Throwable>()
        AudioCompressionError.Unknown("a").shouldBeInstanceOf<Throwable>()
        ImageCompressionError.Unknown("i").shouldBeInstanceOf<Throwable>()
    }

    private fun Throwable.assertTypedErrorContract() {
        val details = when (this) {
            is VideoCompressionError.UnsupportedSourceFormat -> details
            is VideoCompressionError.DecodingFailed -> details
            is VideoCompressionError.EncodingFailed -> details
            is VideoCompressionError.IoFailed -> details
            is VideoCompressionError.Unknown -> details
            is AudioCompressionError.UnsupportedSourceFormat -> details
            is AudioCompressionError.DecodingFailed -> details
            is AudioCompressionError.EncodingFailed -> details
            is AudioCompressionError.IoFailed -> details
            is AudioCompressionError.UnsupportedConfiguration -> details
            is AudioCompressionError.UnsupportedBitrate -> details
            is AudioCompressionError.Unknown -> details
            is ImageCompressionError.UnsupportedSourceFormat -> details
            is ImageCompressionError.DecodingFailed -> details
            is ImageCompressionError.EncodingFailed -> details
            is ImageCompressionError.IoFailed -> details
            is ImageCompressionError.Unknown -> details
            else -> error("Not a typed kompressor error: ${this::class.simpleName}")
        }
        (message ?: "") shouldContain details
        cause shouldBe root
    }
}
