/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test
import okio.Buffer

/**
 * Pins the behaviour of the shared `requireFilePathOrThrow` helpers used by every platform
 * compressor's new `compress(MediaSource, MediaDestination, ...)` overload. The dispatch is the
 * public contract for CRA-92 — "FilePath only for now, everything else throws with a CRA
 * reference" — and must stay stable until CRA-93 / CRA-94 / CRA-95 wire up the remaining
 * variants.
 */
class FilePathDispatchTest {

    @Test
    fun sourceFilePathReturnsPath() {
        MediaSource.Local.FilePath("/tmp/in.jpg").requireFilePathOrThrow() shouldBe "/tmp/in.jpg"
    }

    @Test
    fun destinationFilePathReturnsPath() {
        MediaDestination.Local.FilePath("/tmp/out.jpg").requireFilePathOrThrow() shouldBe "/tmp/out.jpg"
    }

    @Test
    fun streamInputThrowsWithCra95Reference() {
        val e = shouldThrow<UnsupportedOperationException> {
            MediaSource.Local.Stream(Buffer()).requireFilePathOrThrow()
        }
        e.message!! shouldContain "CRA-95"
        e.message!! shouldContain "MediaSource.Local.Stream"
        e.message!! shouldContain "MediaSource.Local.FilePath"
    }

    @Test
    fun bytesInputThrowsWithCra95Reference() {
        val e = shouldThrow<UnsupportedOperationException> {
            MediaSource.Local.Bytes(byteArrayOf(1, 2, 3)).requireFilePathOrThrow()
        }
        e.message!! shouldContain "CRA-95"
        e.message!! shouldContain "MediaSource.Local.Bytes"
    }

    @Test
    fun streamOutputThrowsWithCra95Reference() {
        val e = shouldThrow<UnsupportedOperationException> {
            MediaDestination.Local.Stream(Buffer()).requireFilePathOrThrow()
        }
        e.message!! shouldContain "CRA-95"
        e.message!! shouldContain "MediaDestination.Local.Stream"
    }
}
