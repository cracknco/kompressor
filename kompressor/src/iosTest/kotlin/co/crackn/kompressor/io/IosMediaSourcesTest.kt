/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package co.crackn.kompressor.io

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import okio.Buffer
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.create

/**
 * iOS sibling of `AndroidMediaSourcesTest` (androidHostTest). Pins the scheme-validation and
 * wrapper-equality branches of the iOS builders introduced in CRA-94. The E2E path (a real
 * `file://` NSURL flowing through the compressor) lives in `UrlInputEndToEndTest`.
 *
 * PHAsset tests are intentionally absent from this file — constructing a real [PHAsset] requires
 * the Photos framework permission + a PhotoKit library with at least one asset, neither of
 * which is available in the iOS-simulator unit-test environment without extra test harness.
 * The PHAsset scheme is covered by the unit `IosPHAssetMediaSource` equality checks here and
 * the live behaviour will be validated by a consumer-side sample app once CRA-94 ships.
 */
class IosMediaSourcesTest {

    @Test
    fun ofNsurlAcceptsFileScheme() {
        val url = NSURL.fileURLWithPath("/tmp/unused.jpg")

        val source = MediaSource.of(url)

        val urlSource = source.shouldBeInstanceOf<IosUrlMediaSource>()
        urlSource.url shouldBe url
    }

    @Test
    fun ofNsurlRejectsHttpSchemeWithCrossPlatformMessage() {
        val url = NSURL(string = "http://example.com/image.jpg")

        val e = shouldThrow<IllegalArgumentException> { MediaSource.of(url) }

        // Cross-platform invariant — Android `MediaSource.of(Uri)` emits the exact same string.
        // See `MediaSourceRejections.REMOTE_URL_INPUT` (commonMain).
        e.message shouldBe "Remote URLs not supported. Download the content locally first."
    }

    @Test
    fun ofNsurlRejectsHttpsSchemeWithSameMessage() {
        val url = NSURL(string = "https://example.com/image.jpg")

        val e = shouldThrow<IllegalArgumentException> { MediaSource.of(url) }

        e.message shouldBe "Remote URLs not supported. Download the content locally first."
    }

    @Test
    fun ofNsurlRejectsCustomScheme() {
        val url = NSURL(string = "ftp://example.com/image.jpg")

        val e = shouldThrow<IllegalArgumentException> { MediaSource.of(url) }

        e.message!! shouldContain "Unsupported NSURL scheme: ftp"
        e.message!! shouldContain "Expected 'file'"
    }

    @Test
    fun ofNsdataWrapsIntoIosDataMediaSource() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val data = bytes.toNsData()

        val source = MediaSource.of(data)

        val dataSource = source.shouldBeInstanceOf<IosDataMediaSource>()
        dataSource.data shouldBe data
    }

    @Test
    fun ofNsinputstreamWrapsIntoMediaSourceLocalStream() {
        val stream = platform.Foundation.NSInputStream(data = byteArrayOf(1, 2, 3).toNsData())

        val source = MediaSource.of(stream)

        val streamSource = source.shouldBeInstanceOf<MediaSource.Local.Stream>()
        streamSource.closeOnFinish shouldBe true
    }

    @Test
    fun ofNsinputstreamRespectsCloseOnFinishFlag() {
        val stream = platform.Foundation.NSInputStream(data = byteArrayOf(1, 2, 3).toNsData())

        val source = MediaSource.of(stream, closeOnFinish = false)

        val streamSource = source.shouldBeInstanceOf<MediaSource.Local.Stream>()
        streamSource.closeOnFinish shouldBe false
    }

    @Test
    fun remoteUrlInputRejectionConstantMatchesCrossPlatformSpec() {
        // Pin the literal string — the Android sibling (CRA-93) emits this exact message.
        // A typo-level change here would be an API break even if the tests still pass on a
        // single platform. The constant lives in commonMain (`MediaSourceRejections`) so both
        // platforms import the same source of truth — no drift possible.
        MediaSourceRejections.REMOTE_URL_INPUT shouldBe
            "Remote URLs not supported. Download the content locally first."
    }

    @Test
    fun iosUrlMediaSourceEqualsByUrl() {
        val url = NSURL.fileURLWithPath("/tmp/unused.jpg")

        val a = IosUrlMediaSource(url)
        val b = IosUrlMediaSource(url)

        a shouldBe b
        a.hashCode() shouldBe b.hashCode()
    }

    @Test
    fun iosDataMediaSourceEqualsByContent() {
        val data1 = byteArrayOf(1, 2, 3).toNsData()
        val data2 = byteArrayOf(1, 2, 3).toNsData()

        IosDataMediaSource(data1) shouldBe IosDataMediaSource(data2)
    }

    @Test
    fun iosDataMediaSourceDifferentContentNotEqual() {
        val data1 = byteArrayOf(1, 2, 3).toNsData()
        val data2 = byteArrayOf(1, 2, 4).toNsData()

        (IosDataMediaSource(data1) == IosDataMediaSource(data2)) shouldBe false
    }

    /**
     * Sanity check on the adapter wiring: consuming bytes through `NSInputStream.asOkioSource()`
     * yields the same buffer we fed in, and the okio `read() == -1L` EOF contract is honoured.
     * Exhaustive NSInputStreamSource coverage lives in [NSInputStreamSourceTest].
     */
    @Test
    fun streamAdapterRoundtripsBytes() {
        val stream = platform.Foundation.NSInputStream(
            data = byteArrayOf(10, 20, 30).toNsData(),
        )
        val source = stream.asOkioSource()

        val sink = Buffer()
        source.read(sink, Long.MAX_VALUE) shouldBe 3L
        sink.readByteArray(3) shouldBe byteArrayOf(10, 20, 30)
        source.read(sink, Long.MAX_VALUE) shouldBe -1L
    }
}

/**
 * Helper: copy a Kotlin [ByteArray] into an [NSData] buffer. Shared by all iosTest files that
 * need to mint test NSData / NSInputStream fixtures — kept as a top-level extension here rather
 * than in `iosMain` because production code has no need for it.
 */
internal fun ByteArray.toNsData(): NSData {
    // `addressOf(0)` on an empty ByteArray throws ArrayIndexOutOfBoundsException — index 0 is
    // past-the-end for a zero-length array. NSData supports a zero-length payload natively, so
    // short-circuit with a fresh empty NSData instead of pinning.
    if (isEmpty()) return NSData()
    return usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
}
