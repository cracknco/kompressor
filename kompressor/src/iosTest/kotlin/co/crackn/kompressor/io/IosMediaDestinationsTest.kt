/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import okio.Buffer
import platform.Foundation.NSURL

/**
 * iOS sibling of `AndroidMediaDestinationsTest`. Pins the scheme-validation branch of
 * `MediaDestination.of(NSURL)` introduced in CRA-94.
 */
class IosMediaDestinationsTest {

    @Test
    fun ofNsurlAcceptsFileScheme() {
        val url = NSURL.fileURLWithPath("/tmp/unused.jpg")

        val destination = MediaDestination.of(url)

        val urlDest = destination.shouldBeInstanceOf<IosUrlMediaDestination>()
        urlDest.url shouldBe url
    }

    @Test
    fun ofNsurlRejectsHttpSchemeWithCrossPlatformMessage() {
        val url = NSURL(string = "http://example.com/image.jpg")

        val e = shouldThrow<IllegalArgumentException> { MediaDestination.of(url) }

        // Cross-platform invariant — Android `MediaDestination.of(Uri)` emits the exact same
        // string. See `MediaSourceRejections.REMOTE_URL_OUTPUT` (commonMain).
        e.message shouldBe "Remote URLs not supported. Write locally first then upload."
    }

    @Test
    fun ofNsurlRejectsHttpsSchemeWithSameMessage() {
        val url = NSURL(string = "https://example.com/image.jpg")

        val e = shouldThrow<IllegalArgumentException> { MediaDestination.of(url) }

        e.message shouldBe "Remote URLs not supported. Write locally first then upload."
    }

    @Test
    fun ofNsurlRejectsCustomScheme() {
        val url = NSURL(string = "ftp://example.com/image.jpg")

        val e = shouldThrow<IllegalArgumentException> { MediaDestination.of(url) }

        e.message!! shouldContain "Unsupported NSURL scheme: ftp"
        e.message!! shouldContain "Expected 'file'"
    }

    @Test
    fun remoteUrlOutputRejectionConstantMatchesCrossPlatformSpec() {
        // Pin the literal — a typo-level drift against the Android sibling (CRA-93) is an API
        // break even if the tests still pass on a single platform. The commonMain constant is
        // the single source of truth.
        MediaSourceRejections.REMOTE_URL_OUTPUT shouldBe
            "Remote URLs not supported. Write locally first then upload."
    }

    @Test
    fun iosUrlMediaDestinationEqualsByUrl() {
        val url = NSURL.fileURLWithPath("/tmp/unused.jpg")

        val a = IosUrlMediaDestination(url)
        val b = IosUrlMediaDestination(url)

        a shouldBe b
        a.hashCode() shouldBe b.hashCode()
    }

    @Test
    fun mediaDestinationCompanionStreamFactoryUnchanged() {
        // Sanity check — the commonMain `MediaDestination.Local.Stream` builder survives the
        // new NSURL overload addition (no ambiguity, still accepts a raw okio.Sink).
        val sink = Buffer()
        val dest = MediaDestination.Local.Stream(sink, closeOnFinish = false)

        dest.closeOnFinish shouldBe false
    }
}
