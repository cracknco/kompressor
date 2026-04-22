/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package co.crackn.kompressor.io

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import okio.Buffer
import platform.Foundation.NSInputStream

/**
 * Unit coverage for [NSInputStream.asOkioSource] — the chunked-read adapter introduced in
 * CRA-94 so the iOS-side builder can wrap an NSInputStream into an [okio.Source] and route
 * it through the common `MediaSource.Local.Stream` pipeline.
 *
 * Each test constructs an NSInputStream from an [platform.Foundation.NSData] fixture (seeded
 * via the shared [toNsData] helper in [IosMediaSourcesTest]) and verifies the adapter's
 * contract around byte-for-byte fidelity, EOF signalling (NSInputStream's `0` translates to
 * okio's `-1L`), chunking at the 8 KiB `MAX_READ_CHUNK` boundary, and `byteCount == 0` /
 * negative-argument handling.
 */
class NSInputStreamSourceTest {

    @Test
    fun readsAllBytesThenEof() {
        val source = NSInputStream(data = byteArrayOf(1, 2, 3, 4, 5).toNsData()).asOkioSource()

        val sink = Buffer()
        source.read(sink, Long.MAX_VALUE) shouldBe 5L
        sink.readByteArray(5) shouldBe byteArrayOf(1, 2, 3, 4, 5)
        source.read(sink, Long.MAX_VALUE) shouldBe -1L
    }

    @Test
    fun emptyStreamReportsEofImmediately() {
        val source = NSInputStream(data = ByteArray(0).toNsData()).asOkioSource()

        val sink = Buffer()
        source.read(sink, Long.MAX_VALUE) shouldBe -1L
    }

    @Test
    fun byteCountZeroReturnsZeroWithoutReading() {
        val source = NSInputStream(data = byteArrayOf(1, 2, 3).toNsData()).asOkioSource()

        val sink = Buffer()
        source.read(sink, 0L) shouldBe 0L
        // Next read still gets the full payload — the zero-byte read did not consume anything.
        source.read(sink, Long.MAX_VALUE) shouldBe 3L
    }

    @Test
    fun negativeByteCountThrows() {
        val source = NSInputStream(data = byteArrayOf(1, 2, 3).toNsData()).asOkioSource()

        shouldThrow<IllegalArgumentException> { source.read(Buffer(), -1L) }
    }

    @Test
    fun chunkedReadRoundtripsLargePayload() {
        // 20 KiB — forces at least three iterations through the 8 KiB MAX_READ_CHUNK loop.
        val payload = ByteArray(20 * 1024) { (it and 0xFF).toByte() }
        val source = NSInputStream(data = payload.toNsData()).asOkioSource()

        val sink = Buffer()
        var total = 0L
        while (true) {
            val n = source.read(sink, Long.MAX_VALUE)
            if (n == -1L) break
            total += n
        }
        total shouldBe payload.size.toLong()
        sink.readByteArray(payload.size.toLong()) shouldBe payload
    }

    @Test
    fun closeIsIdempotent() {
        val source = NSInputStream(data = byteArrayOf(1, 2, 3).toNsData()).asOkioSource()

        // Two closes in a row should be safe — the adapter guards against the NSStreamStatusClosed
        // transition so Apple's "close on already-closed stream is a no-op" doc reads as "we
        // never ask the platform" for the second close.
        source.close()
        source.close()
    }
}
