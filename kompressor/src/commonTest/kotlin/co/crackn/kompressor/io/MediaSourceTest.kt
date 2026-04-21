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
        // Anchor — guards that the public `companion object` lives on `MediaSource` so future
        // factory helpers (e.g. `MediaSource.fromUri(...)`) can be declared as extensions
        // without breaking callers.
        MediaSource shouldNotBe null
    }
}
