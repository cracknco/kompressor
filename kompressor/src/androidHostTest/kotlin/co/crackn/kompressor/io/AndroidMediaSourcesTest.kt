/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

import android.net.Uri
import android.os.ParcelFileDescriptor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import kotlin.test.Test

/**
 * Host-side unit tests for the Android `MediaSource.of(...)` builders introduced in CRA-93.
 *
 * These tests pin the *scheme-validation* branch of each builder — the piece that runs before any
 * platform I/O and is therefore safely host-testable without `MediaStore`, `ContentResolver`, or a
 * real filesystem. The end-to-end path (a real `content://` URI flowing through the compressor and
 * producing compressed output) is covered by the sibling `UriInputEndToEndTest` in
 * `androidDeviceTest` where `ContentResolver` and the AOSP `MediaProvider` are actually reachable.
 *
 * `Uri` is mocked rather than constructed via `Uri.parse(...)` because the host test classpath is
 * the stubbed `android.jar` shipped with AGP — static framework calls throw `RuntimeException("Stub!")`
 * without Robolectric, which this project does not use. Mocking the instance `scheme`/`authority`
 * getters keeps the tests on pure JVM.
 */
class AndroidMediaSourcesTest {

    @Test
    fun ofUriAcceptsFileScheme() {
        val uri = mockk<Uri> { every { scheme } returns "file" }

        val source = MediaSource.of(uri)

        val uriSource = source.shouldBeInstanceOf<AndroidUriMediaSource>()
        uriSource.uri shouldBe uri
    }

    @Test
    fun ofUriAcceptsContentScheme() {
        val uri = mockk<Uri> { every { scheme } returns "content" }

        val source = MediaSource.of(uri)

        val uriSource = source.shouldBeInstanceOf<AndroidUriMediaSource>()
        uriSource.uri shouldBe uri
    }

    @Test
    fun ofUriRejectsHttpSchemeWithCrossPlatformMessage() {
        val uri = mockk<Uri> { every { scheme } returns "http" }

        val e = shouldThrow<IllegalArgumentException> { MediaSource.of(uri) }

        // Cross-platform invariant — the iOS `MediaSource.of(NSURL)` sibling (T5) MUST emit the
        // exact same string. See `MediaSourceRejections.REMOTE_URL_INPUT` (commonMain).
        e.message shouldBe "Remote URLs not supported. Download the content locally first."
    }

    @Test
    fun ofUriRejectsHttpsSchemeWithSameMessage() {
        val uri = mockk<Uri> { every { scheme } returns "https" }

        val e = shouldThrow<IllegalArgumentException> { MediaSource.of(uri) }

        e.message shouldBe "Remote URLs not supported. Download the content locally first."
    }

    @Test
    fun ofUriRejectsUnsupportedScheme() {
        val uri = mockk<Uri> { every { scheme } returns "ftp" }

        val e = shouldThrow<IllegalArgumentException> { MediaSource.of(uri) }

        e.message!! shouldContain "Unsupported URI scheme: ftp"
        e.message!! shouldContain "file"
        e.message!! shouldContain "content"
    }

    @Test
    fun ofUriRejectsNullScheme() {
        val uri = mockk<Uri> { every { scheme } returns null }

        val e = shouldThrow<IllegalArgumentException> { MediaSource.of(uri) }

        e.message!! shouldContain "<null>"
    }

    @Test
    fun ofParcelFileDescriptorWrapsIntoAndroidPfdMediaSource() {
        val pfd = mockk<ParcelFileDescriptor> { every { fd } returns 42 }

        val source = MediaSource.of(pfd)

        val pfdSource = source.shouldBeInstanceOf<AndroidPfdMediaSource>()
        pfdSource.pfd shouldBe pfd
    }

    @Test
    fun ofInputStreamWrapsIntoMediaSourceLocalStream() {
        val stream = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5))

        val source = MediaSource.of(stream)

        val streamSource = source.shouldBeInstanceOf<MediaSource.Local.Stream>()
        streamSource.closeOnFinish shouldBe true
    }

    @Test
    fun ofInputStreamRespectsCloseOnFinishFlag() {
        val stream = ByteArrayInputStream(byteArrayOf(1, 2, 3))

        val source = MediaSource.of(stream, closeOnFinish = false)

        val streamSource = source.shouldBeInstanceOf<MediaSource.Local.Stream>()
        streamSource.closeOnFinish shouldBe false
    }

    @Test
    fun remoteUrlInputRejectionConstantMatchesCrossPlatformSpec() {
        // Pin the literal string — iOS T5 sibling must emit this exact message. A typo-level
        // change here would be an API break even if the tests still pass on a single platform.
        // The constant now lives in commonMain (`MediaSourceRejections`) so both platforms
        // import the same source of truth — no drift possible.
        MediaSourceRejections.REMOTE_URL_INPUT shouldBe
            "Remote URLs not supported. Download the content locally first."
    }

    @Test
    fun androidUriMediaSourceEqualsByUri() {
        val uri = mockk<Uri>()

        val a = AndroidUriMediaSource(uri)
        val b = AndroidUriMediaSource(uri)

        a shouldBe b
        a.hashCode() shouldBe b.hashCode()
    }

    @Test
    fun androidPfdMediaSourceEqualsByFd() {
        val pfd1 = mockk<ParcelFileDescriptor> { every { fd } returns 7 }
        val pfd2 = mockk<ParcelFileDescriptor> { every { fd } returns 7 }

        AndroidPfdMediaSource(pfd1) shouldBe AndroidPfdMediaSource(pfd2)
    }
}
