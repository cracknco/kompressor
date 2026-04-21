/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import okio.Buffer

class MediaSourceTest {

    @Test
    fun filePathDataClassEqualityUsesPath() {
        val a = MediaSource.Local.FilePath("/tmp/a")
        val b = MediaSource.Local.FilePath("/tmp/a")
        val c = MediaSource.Local.FilePath("/tmp/b")
        a shouldBe b
        a.hashCode() shouldBe b.hashCode()
        a shouldNotBe c
    }

    @Test
    fun bytesEqualityUsesContentNotReference() {
        val a = MediaSource.Local.Bytes(byteArrayOf(1, 2, 3))
        val b = MediaSource.Local.Bytes(byteArrayOf(1, 2, 3))
        a shouldBe b
        a.hashCode() shouldBe b.hashCode()
    }

    @Test
    fun bytesDifferentContentNotEqual() {
        MediaSource.Local.Bytes(byteArrayOf(1, 2)) shouldNotBe MediaSource.Local.Bytes(byteArrayOf(3, 4))
    }

    @Test
    fun bytesSelfEqualityShortCircuits() {
        val a = MediaSource.Local.Bytes(byteArrayOf(1, 2, 3))
        (a == a) shouldBe true
    }

    @Test
    fun bytesNotEqualToOtherType() {
        val bytes = MediaSource.Local.Bytes(byteArrayOf(1, 2, 3))
        @Suppress("EqualsBetweenInconvertibleTypes")
        (bytes.equals("not bytes")) shouldBe false
    }

    @Test
    fun streamCloseOnFinishDefaultIsTrue() {
        val stream = MediaSource.Local.Stream(Buffer())
        stream.closeOnFinish shouldBe true
        stream.sizeHint shouldBe null
    }

    @Test
    fun streamCloseOnFinishCanBeOverridden() {
        val stream = MediaSource.Local.Stream(Buffer(), sizeHint = 1024L, closeOnFinish = false)
        stream.closeOnFinish shouldBe false
        stream.sizeHint shouldBe 1024L
    }

    @Test
    fun allLocalVariantsAreMediaSourceViaLocalSealed() {
        val sources: List<MediaSource> = listOf(
            MediaSource.Local.FilePath("/tmp/x"),
            MediaSource.Local.Stream(Buffer()),
            MediaSource.Local.Bytes(ByteArray(0)),
        )
        sources.forEach { it.shouldBeInstanceOf<MediaSource.Local>() }
    }

    @Test
    fun companionObjectIsAccessible() {
        // Compile-time anchor — future factory helpers (e.g. `MediaSource.fromUri(...)`)
        // declared as extensions on `MediaSource.Companion` require this companion to stay
        // public. A typed reference fails to compile if the companion is removed, which is
        // what we actually want to catch (the runtime `shouldNotBe null` variant is a
        // tautology because companions are singletons that cannot be null) [CRA-90 review].
        val anchor: MediaSource.Companion = MediaSource
        anchor.toString() // suppress unused-local warning
    }

    // ── CRA-90 review: Stream-as-`class` semantics ─────────────────────────────
    // Stream is a plain `class` (not `data class`) — the underlying `okio.Source` is a
    // stateful resource handle where identity-based equality is the only defensible
    // semantic. The tests below pin this so a future change back to `data class` regresses.

    @Test
    fun streamEqualityIsIdentityBased() {
        val a = MediaSource.Local.Stream(Buffer())
        val b = MediaSource.Local.Stream(Buffer())
        (a == b) shouldBe false // two distinct wrappers — not equal even if contents match
        (a == a) shouldBe true // identity
    }

    @Test
    fun streamRejectsNegativeSizeHint() {
        shouldThrow<IllegalArgumentException> {
            MediaSource.Local.Stream(Buffer(), sizeHint = -1L)
        }
    }

    @Test
    fun streamAcceptsZeroSizeHint() {
        MediaSource.Local.Stream(Buffer(), sizeHint = 0L).sizeHint shouldBe 0L
    }

    @Test
    fun bytesToStringSummarizesSizeWithoutContent() {
        MediaSource.Local.Bytes(ByteArray(42)).toString() shouldBe "MediaSource.Local.Bytes(size=42)"
    }
}
