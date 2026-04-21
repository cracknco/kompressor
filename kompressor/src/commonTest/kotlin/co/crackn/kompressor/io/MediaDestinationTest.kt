/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import okio.Buffer

class MediaDestinationTest {

    @Test
    fun filePathEqualityUsesPath() {
        val a = MediaDestination.Local.FilePath("/tmp/out.mp4")
        val b = MediaDestination.Local.FilePath("/tmp/out.mp4")
        val c = MediaDestination.Local.FilePath("/tmp/other.mp4")
        a shouldBe b
        a.hashCode() shouldBe b.hashCode()
        a shouldNotBe c
    }

    @Test
    fun streamCloseOnFinishDefaultIsTrue() {
        val dst = MediaDestination.Local.Stream(Buffer())
        dst.closeOnFinish shouldBe true
    }

    @Test
    fun streamCloseOnFinishCanBeOverridden() {
        val dst = MediaDestination.Local.Stream(Buffer(), closeOnFinish = false)
        dst.closeOnFinish shouldBe false
    }

    @Test
    fun allLocalVariantsAreMediaDestination() {
        val destinations: List<MediaDestination> = listOf(
            MediaDestination.Local.FilePath("/tmp/x.mp4"),
            MediaDestination.Local.Stream(Buffer()),
        )
        destinations.forEach { it.shouldBeInstanceOf<MediaDestination.Local>() }
    }

    @Test
    fun companionObjectIsAccessible() {
        // Compile-time anchor — see sibling test in `MediaSourceTest` for the rationale
        // [CRA-90 review].
        val anchor: MediaDestination.Companion = MediaDestination
        anchor.toString()
    }

    @Test
    fun streamEqualityIsIdentityBased() {
        // Sibling of MediaSource.Local.Stream identity semantics [CRA-90 review]. Sink
        // is a stateful resource handle; data-class equality would incorrectly compare
        // two distinct wrappers as equal whenever they happened to share a sink reference.
        val a = MediaDestination.Local.Stream(Buffer())
        val b = MediaDestination.Local.Stream(Buffer())
        (a == b) shouldBe false
        (a == a) shouldBe true
    }
}
