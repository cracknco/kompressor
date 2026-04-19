/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package co.crackn.kompressor

import co.crackn.kompressor.audio.AudioCompressionError
import co.crackn.kompressor.audio.IosAudioCompressor
import co.crackn.kompressor.image.IosImageCompressor
import co.crackn.kompressor.testutil.WavGenerator
import co.crackn.kompressor.testutil.writeBytes
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSFilePosixPermissions
import platform.Foundation.NSNumber
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * iOS mirror of `androidDeviceTest/.../IoFaultInjectionTest.kt`. Injects real filesystem faults
 * (read-only parent, non-creatable parent, missing input, output-is-a-directory) and asserts
 * the iOS compressors surface them as `Result.failure` with a typed error subtype.
 */
class IoFaultInjectionTest {

    private lateinit var tempDir: String
    private val image = IosImageCompressor()
    private val audio = IosAudioCompressor()

    @BeforeTest
    fun setUp() {
        tempDir = NSTemporaryDirectory() + "kompressor-io-fault-${NSUUID().UUIDString}/"
        NSFileManager.defaultManager.createDirectoryAtPath(
            tempDir, withIntermediateDirectories = true, attributes = null, error = null,
        )
    }

    @AfterTest
    fun tearDown() {
        // Restore writability before recursive delete so cleanup actually succeeds even if a test
        // left a subdirectory read-only.
        chmodRecursive(tempDir, 0b111_111_111) // 0777
        NSFileManager.defaultManager.removeItemAtPath(tempDir, null)
    }

    @Test
    fun imageCompress_readOnlyParentDir_failsCleanly() = runTest {
        val input = createSmallJpegInput()
        val readOnly = tempDir + "ro/"
        NSFileManager.defaultManager.createDirectoryAtPath(
            readOnly, withIntermediateDirectories = true, attributes = null, error = null,
        )
        // 0o555 — read+execute, no write.
        chmod(readOnly, 0b101_101_101)
        val output = readOnly + "out.jpg"

        val result = image.compress(input, output)

        assertTrue(result.isFailure, "Expected failure but got $result")
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath(output))
    }

    @Test
    fun imageCompress_parentPathIsAFile_failsCleanly() = runTest {
        val input = createSmallJpegInput()
        val regularFile = tempDir + "not-a-dir"
        NSFileManager.defaultManager.createFileAtPath(regularFile, contents = null, attributes = null)
        val output = "$regularFile/out.jpg"

        val result = image.compress(input, output)

        assertTrue(result.isFailure, "Expected failure but got $result")
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath(output))
    }

    @Test
    fun imageCompress_outputPathIsADirectory_failsCleanly() = runTest {
        val input = createSmallJpegInput()
        val output = tempDir + "outdir/"
        NSFileManager.defaultManager.createDirectoryAtPath(
            output, withIntermediateDirectories = true, attributes = null, error = null,
        )

        val result = image.compress(input, output)

        assertTrue(result.isFailure, "Expected failure but got $result")
    }

    @Test
    fun audioCompress_nonexistentInput_failsWithTypedError() = runTest {
        val missing = tempDir + "does-not-exist.wav"
        val output = tempDir + "out.m4a"

        val result = audio.compress(missing, output)

        assertTrue(result.isFailure, "Expected failure but got $result")
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(
            ex is AudioCompressionError,
            "Expected typed AudioCompressionError, got ${ex::class.simpleName}: $ex",
        )
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath(output))
    }

    @Test
    fun audioCompress_outputPathIsADirectory_failsWithTypedError() = runTest {
        val input = tempDir + "in.wav"
        writeBytes(input, WavGenerator.generateWavBytes(1, 8000, 1))
        val output = tempDir + "outdir/"
        NSFileManager.defaultManager.createDirectoryAtPath(
            output, withIntermediateDirectories = true, attributes = null, error = null,
        )

        val result = audio.compress(input, output)

        assertTrue(result.isFailure, "Expected failure but got $result")
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(
            ex is AudioCompressionError,
            "Expected typed AudioCompressionError, got ${ex::class.simpleName}: $ex",
        )
    }

    private fun createSmallJpegInput(): String {
        // Tiny 1x1 JPEG (valid header). The image compressor needs a decodable input.
        val path = tempDir + "in.jpg"
        writeBytes(path, MINIMAL_JPEG)
        return path
    }

    private fun chmod(path: String, mode: Int) {
        val attrs = mapOf<Any?, Any?>(NSFilePosixPermissions to NSNumber(int = mode))
        NSFileManager.defaultManager.setAttributes(attrs, ofItemAtPath = path, error = null)
    }

    private fun chmodRecursive(dir: String, mode: Int) {
        chmod(dir, mode)
        val children = NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, error = null)
            ?: return
        children.forEach { name ->
            val sub = dir + (name as String)
            chmod(sub, mode)
            // If sub is a directory, recurse — we don't bother distinguishing because chmod is
            // idempotent and harmless on files.
            chmodRecursive("$sub/", mode)
        }
    }

    private companion object {
        // Smallest possible "valid enough" JPEG that ImageIO will load — generated once and
        // pasted as bytes. 1x1 white pixel.
        // SOI + APP0 + DQT + SOF0 + DHT + SOS + image data + EOI.
        // To avoid hand-coding a fragile blob we use a tiny, well-known constant.
        val MINIMAL_JPEG: ByteArray = byteArrayOf(
            // SOI
            0xFF.toByte(), 0xD8.toByte(),
            // APP0 (JFIF)
            0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,
            0x4A, 0x46, 0x49, 0x46, 0x00,
            0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
            // DQT
            0xFF.toByte(), 0xDB.toByte(), 0x00, 0x43, 0x00,
            0x08, 0x06, 0x06, 0x07, 0x06, 0x05, 0x08, 0x07,
            0x07, 0x07, 0x09, 0x09, 0x08, 0x0A, 0x0C, 0x14,
            0x0D, 0x0C, 0x0B, 0x0B, 0x0C, 0x19, 0x12, 0x13,
            0x0F, 0x14, 0x1D, 0x1A, 0x1F, 0x1E, 0x1D, 0x1A,
            0x1C, 0x1C, 0x20, 0x24, 0x2E, 0x27, 0x20, 0x22,
            0x2C, 0x23, 0x1C, 0x1C, 0x28, 0x37, 0x29, 0x2C,
            0x30, 0x31, 0x34, 0x34, 0x34, 0x1F, 0x27, 0x39,
            0x3D, 0x38, 0x32, 0x3C, 0x2E, 0x33, 0x34, 0x32,
            // SOF0
            0xFF.toByte(), 0xC0.toByte(), 0x00, 0x0B, 0x08, 0x00, 0x01, 0x00, 0x01, 0x01, 0x01, 0x11, 0x00,
            // DHT (minimal)
            0xFF.toByte(), 0xC4.toByte(), 0x00, 0x14, 0x00,
            0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0xFF.toByte(), 0xC4.toByte(), 0x00, 0x14, 0x10,
            0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            // SOS
            0xFF.toByte(), 0xDA.toByte(), 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00,
            // entropy-coded segment (single byte)
            0x00,
            // EOI
            0xFF.toByte(), 0xD9.toByte(),
        )
    }
}
