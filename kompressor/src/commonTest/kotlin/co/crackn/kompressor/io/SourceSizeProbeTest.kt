/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

import io.kotest.matchers.shouldBe
import okio.Buffer
import kotlin.test.Test

/**
 * Platform-agnostic coverage for [estimateSourceSize].
 *
 * The [MediaSource.Local.Stream] and [MediaSource.Local.Bytes] branches route identically on
 * Android and iOS (`sizeHint` pass-through / `ByteArray.size`), so they live here in commonTest
 * rather than being duplicated per platform. Platform-specific wrappers (`AndroidUriMediaSource`,
 * `AndroidPfdMediaSource`, `IosUrlMediaSource`, `IosPHAssetMediaSource`, `IosDataMediaSource`)
 * are exercised in the androidHostTest / iosTest siblings.
 */
class SourceSizeProbeTest {

    @Test
    fun streamWithExplicitSizeHintReportsIt() {
        val stream = MediaSource.Local.Stream(source = Buffer(), sizeHint = 1024L)

        estimateSourceSize(stream) shouldBe 1024L
    }

    @Test
    fun streamWithoutSizeHintReportsNull() {
        // Stream has no usable size-probing channel when the caller didn't supply a hint.
        // The probe MUST return null (not 0) so downstream consumers know to skip fraction
        // reporting rather than emit a fake "0 bytes total" progress sentinel.
        val stream = MediaSource.Local.Stream(source = Buffer())

        estimateSourceSize(stream) shouldBe null
    }

    @Test
    fun streamWithZeroSizeHintReportsZero() {
        // Zero is a legitimate hint (empty buffer) — must NOT be conflated with null.
        val stream = MediaSource.Local.Stream(source = Buffer(), sizeHint = 0L)

        estimateSourceSize(stream) shouldBe 0L
    }

    @Test
    fun bytesReportsArrayLength() {
        val bytes = MediaSource.Local.Bytes(ByteArray(512) { it.toByte() })

        estimateSourceSize(bytes) shouldBe 512L
    }

    @Test
    fun bytesEmptyArrayReportsZero() {
        val bytes = MediaSource.Local.Bytes(ByteArray(0))

        estimateSourceSize(bytes) shouldBe 0L
    }
}
