/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package co.crackn.kompressor.io

import co.crackn.kompressor.io.MediaSource
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.writeToURL

/**
 * iOS simulator coverage for the iOS actual of [estimateSourceSize].
 *
 * Covers the concrete wrappers resolvable without a real PhotoKit library (file-system NSURL,
 * in-memory NSData, FilePath). [IosPHAssetMediaSource] is intentionally NOT exercised here —
 * constructing a real `PHAsset` requires the Photos framework permission and at least one
 * library asset, which is out of reach in the unit-test simulator; the PhotoKit path is
 * validated by [ProgressionE2ETest] style integration harnesses and by the consumer-side
 * sample app. The `null`-on-probe-failure contract is still documented in [estimateSourceSize]
 * KDoc.
 */
class SourceSizeProbeIosTest {

    @Test
    fun filePathReportsSizeViaNsFileManager() {
        val path = writeTempFile(bytes = ByteArray(4096) { it.toByte() })
        try {
            estimateSourceSize(MediaSource.Local.FilePath(path)) shouldBe 4096L
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(path, null)
        }
    }

    @Test
    fun filePathReportsNullWhenMissing() {
        val missing = "${NSTemporaryDirectory()}kmp_probe_missing_${NSUUID().UUIDString}.bin"
        estimateSourceSize(MediaSource.Local.FilePath(missing)) shouldBe null
    }

    @Test
    fun iosUrlMediaSourceReportsSize() {
        val path = writeTempFile(bytes = ByteArray(321) { 1 })
        try {
            val url = NSURL.fileURLWithPath(path)
            estimateSourceSize(IosUrlMediaSource(url)) shouldBe 321L
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(path, null)
        }
    }

    @Test
    fun iosUrlMediaSourceReportsNullWhenUnderlyingFileMissing() {
        val url = NSURL.fileURLWithPath(
            "${NSTemporaryDirectory()}kmp_probe_url_missing_${NSUUID().UUIDString}.bin",
        )
        estimateSourceSize(IosUrlMediaSource(url)) shouldBe null
    }

    @Test
    fun iosDataMediaSourceReportsLength() {
        val payload = ByteArray(2048) { (it % 256).toByte() }
        val data = payload.toNsData()

        estimateSourceSize(IosDataMediaSource(data)) shouldBe 2048L
    }

    @Test
    fun iosDataEmptyReportsZero() {
        val data = ByteArray(0).toNsData()

        estimateSourceSize(IosDataMediaSource(data)) shouldBe 0L
    }

    /** Write [bytes] to a fresh path under `NSTemporaryDirectory()`. Caller owns cleanup. */
    private fun writeTempFile(bytes: ByteArray): String {
        val path = "${NSTemporaryDirectory()}kmp_probe_${NSUUID().UUIDString}.bin"
        val data = bytes.toNsData()
        val url = NSURL.fileURLWithPath(path)
        check(data.writeToURL(url, atomically = true)) { "writeToURL failed for $path" }
        return path
    }
}
