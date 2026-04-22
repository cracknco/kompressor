/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

import android.net.Uri
import android.provider.MediaStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import kotlin.test.Test

/**
 * Host-side unit tests for the Android `MediaDestination.of(...)` builders introduced in CRA-93.
 *
 * See [AndroidMediaSourcesTest] for the general rationale — identical constraints apply: host
 * classpath is AGP's stubbed `android.jar`, so `Uri` is mocked via mockk rather than parsed.
 *
 * End-to-end MediaStore round-trip coverage (real `contentResolver.insert(...)` + IS_PENDING dance)
 * lives in `MediaStoreOutputEndToEndTest` under `androidDeviceTest`.
 */
class AndroidMediaDestinationsTest {

    @Test
    fun ofUriAcceptsFileScheme() {
        val uri = mockk<Uri> {
            every { scheme } returns "file"
            every { authority } returns null
        }

        val destination = MediaDestination.of(uri)

        val uriDestination = destination.shouldBeInstanceOf<AndroidUriMediaDestination>()
        uriDestination.uri shouldBe uri
        uriDestination.isMediaStoreUri shouldBe false
    }

    @Test
    fun ofUriAcceptsContentScheme() {
        val uri = mockk<Uri> {
            every { scheme } returns "content"
            every { authority } returns "com.example.customprovider"
        }

        val destination = MediaDestination.of(uri)

        destination.shouldBeInstanceOf<AndroidUriMediaDestination>()
    }

    @Test
    fun ofUriFlagsMediaStoreAuthority() {
        val uri = mockk<Uri> {
            every { scheme } returns "content"
            every { authority } returns MediaStore.AUTHORITY
        }

        val destination = MediaDestination.of(uri).shouldBeInstanceOf<AndroidUriMediaDestination>()

        // The `isMediaStoreUri` gate is what routes the write through [MediaStoreOutputStrategy]
        // (IS_PENDING lifecycle) vs. a plain `ContentResolver.openOutputStream(uri)`. Pinning it
        // here guards against a future refactor swapping `MediaStore.AUTHORITY` for a literal.
        destination.isMediaStoreUri shouldBe true
    }

    @Test
    fun ofUriTreatsNonMediaStoreAuthorityAsGenericUri() {
        val uri = mockk<Uri> {
            every { scheme } returns "content"
            every { authority } returns "com.android.externalstorage.documents"
        }

        val destination = MediaDestination.of(uri).shouldBeInstanceOf<AndroidUriMediaDestination>()

        destination.isMediaStoreUri shouldBe false
    }

    @Test
    fun ofUriRejectsHttpSchemeWithCrossPlatformMessage() {
        val uri = mockk<Uri> { every { scheme } returns "http" }

        val e = shouldThrow<IllegalArgumentException> { MediaDestination.of(uri) }

        // iOS T5 sibling must emit this exact string — shared `commonMain` source of truth
        // is `MediaSourceRejections.REMOTE_URL_OUTPUT`.
        e.message shouldBe "Remote URLs not supported. Write locally first then upload."
    }

    @Test
    fun ofUriRejectsHttpsSchemeWithSameMessage() {
        val uri = mockk<Uri> { every { scheme } returns "https" }

        val e = shouldThrow<IllegalArgumentException> { MediaDestination.of(uri) }

        e.message shouldBe "Remote URLs not supported. Write locally first then upload."
    }

    @Test
    fun ofUriRejectsUnsupportedScheme() {
        val uri = mockk<Uri> { every { scheme } returns "s3" }

        val e = shouldThrow<IllegalArgumentException> { MediaDestination.of(uri) }

        e.message!! shouldContain "Unsupported URI scheme: s3"
    }

    @Test
    fun ofUriRejectsNullSchemeWithNullSentinel() {
        val uri = mockk<Uri> { every { scheme } returns null }

        val e = shouldThrow<IllegalArgumentException> { MediaDestination.of(uri) }

        e.message!! shouldContain "<null>"
    }

    @Test
    fun ofOutputStreamWrapsIntoMediaDestinationLocalStream() {
        val sink = ByteArrayOutputStream()

        val destination = MediaDestination.of(sink)

        val streamDest = destination.shouldBeInstanceOf<MediaDestination.Local.Stream>()
        streamDest.closeOnFinish shouldBe true
    }

    @Test
    fun ofOutputStreamRespectsCloseOnFinishFlag() {
        val sink = ByteArrayOutputStream()

        val destination = MediaDestination.of(sink, closeOnFinish = false)

        val streamDest = destination.shouldBeInstanceOf<MediaDestination.Local.Stream>()
        streamDest.closeOnFinish shouldBe false
    }

    @Test
    fun remoteUrlOutputRejectionConstantMatchesCrossPlatformSpec() {
        // Pinned in commonMain now — both Android + (future) iOS import the same literal.
        MediaSourceRejections.REMOTE_URL_OUTPUT shouldBe
            "Remote URLs not supported. Write locally first then upload."
    }

    @Test
    fun androidUriMediaDestinationEqualsByUri() {
        val uri = mockk<Uri> { every { authority } returns null }

        val a = AndroidUriMediaDestination(uri)
        val b = AndroidUriMediaDestination(uri)

        a shouldBe b
        a.hashCode() shouldBe b.hashCode()
    }
}
