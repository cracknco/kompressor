/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package co.crackn.kompressor.io

import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import okio.Buffer
import okio.IOException
import okio.Source
import okio.Timeout
import platform.Foundation.NSInputStream
import platform.Foundation.NSStreamStatusClosed
import platform.Foundation.NSStreamStatusNotOpen

/**
 * Wrap [this] NSInputStream in an [okio.Source] that pulls bytes in chunks sized by the caller's
 * `byteCount`. Opens the stream lazily if its `streamStatus` is in the initial `NotOpen` state;
 * streams already opened by the caller are used as-is.
 *
 * Sibling of Android's `InputStream.source()` (from okio-jvm) — the JVM extension exists
 * pre-built in okio but there is no equivalent for NSInputStream in `okio-darwin`, so we roll our
 * own here. Invoked from [MediaSource.Companion.of] `(stream: NSInputStream)` and available to
 * the internal stream-materialization pipeline.
 *
 * Read failures surface the platform `streamError` via [okio.IOException] so the common
 * [TempFileMaterializer] error mapping can wrap them into the typed `SourceReadFailed` subtype
 * — same contract as the JVM side.
 */
internal fun NSInputStream.asOkioSource(): Source = NSInputStreamSource(this)

private class NSInputStreamSource(private val stream: NSInputStream) : Source {

    init {
        // Callers may hand us an already-opened stream (e.g. one produced by
        // `NSInputStream(data:)` that the platform auto-opens) or an unopened one minted by
        // `NSInputStream.inputStreamWithFileAtPath:`. Only `NotOpen` is the safe signal that we
        // need to open it ourselves — any other status (`Opening`, `Open`, `Reading`, `AtEnd`,
        // `Closed`, `Error`) means a prior consumer already drove the lifecycle and re-opening
        // would raise an Obj-C exception we cannot catch from Kotlin/Native.
        if (stream.streamStatus == NSStreamStatusNotOpen) {
            stream.open()
        }
    }

    override fun read(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0L) { "byteCount < 0: $byteCount" }
        if (byteCount == 0L) return 0L

        val chunkSize = minOf(byteCount, MAX_READ_CHUNK.toLong()).toInt()
        val buf = ByteArray(chunkSize)
        val read: Long = buf.usePinned { pinned ->
            // NSInputStream.read(buffer:maxLength:) takes an unsigned-byte pointer; the pinned
            // address is `CPointer<ByteVar>` (signed), hence the reinterpret. Max length is
            // `NSUInteger` (ULong on 64-bit iOS). Return type is `NSInteger` (Long) —
            // positive = bytes read, 0 = EOF, -1 = error (fetch `streamError`).
            stream.read(pinned.addressOf(0).reinterpret<UByteVar>(), chunkSize.toULong())
        }
        return when {
            read > 0L -> {
                sink.write(buf, 0, read.toInt())
                read
            }
            // okio's Source contract is: return -1 on EOF, not 0. NSInputStream signals EOF with
            // 0; translate here so the upstream `source.read() == -1L` guards behave correctly.
            read == 0L -> -1L
            else -> throw IOException(
                "NSInputStream.read failed: ${stream.streamError?.localizedDescription ?: "unknown"}",
            )
        }
    }

    override fun close() {
        // `close()` on an already-closed NSInputStream is a no-op in Apple's docs but we still
        // guard with a status check to avoid paying the Obj-C call when the stream is idle.
        if (stream.streamStatus != NSStreamStatusClosed) {
            stream.close()
        }
    }

    override fun timeout(): Timeout = Timeout.NONE

    private companion object {
        // Cap each chunk at 8 KiB — matches okio's `Segment.SIZE` so sink writes land in a single
        // segment per read without a follow-up copy.
        const val MAX_READ_CHUNK: Int = 8 * 1024
    }
}
