package co.crackn.kompressor

import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.audio.AndroidAudioCompressor
import co.crackn.kompressor.audio.AudioCompressionError
import co.crackn.kompressor.image.AndroidImageCompressor
import co.crackn.kompressor.testutil.createTestImage
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * True I/O fault coverage on Android. Existing `deletingOutputOnFailure` tests only exercise the
 * cancel-during-encode path; this file injects real filesystem failures (read-only parent,
 * non-creatable parent, missing input, output-is-a-directory) and asserts the compressors
 * surface them as `Result.failure` — and that the audio compressor produces a typed
 * `AudioCompressionError` (per the documented API contract), not an untyped exception.
 */
class IoFaultInjectionTest {

    private lateinit var tempDir: File
    private val imageCompressor = AndroidImageCompressor()
    private val audioCompressor = AndroidAudioCompressor()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        tempDir = File(context.cacheDir, "kompressor-io-fault-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        // Restore writability before recursive delete so cleanup actually succeeds even if a
        // test left the directory read-only.
        tempDir.walkBottomUp().forEach { it.setWritable(true, false) }
        tempDir.deleteRecursively()
    }

    @Test
    fun imageCompress_readOnlyParentDir_failsCleanly() = runTest {
        val input = createTestImage(tempDir, 200, 200)
        val readOnlyDir = File(tempDir, "readonly").apply { mkdirs() }
        // Note: setWritable(false) on Android external/internal app storage is honoured for
        // the JVM owner — FileOutputStream open will throw IOException.
        assertTrue(readOnlyDir.setWritable(false, false), "setWritable(false) must succeed")
        val output = File(readOnlyDir, "out.jpg")

        val result = imageCompressor.compress(input.absolutePath, output.absolutePath)

        assertTrue(result.isFailure, "Expected failure but got $result")
        assertTrue(!output.exists(), "No partial output should remain")
    }

    @Test
    fun imageCompress_parentPathIsAFile_failsCleanly() = runTest {
        val input = createTestImage(tempDir, 200, 200)
        val regularFile = File(tempDir, "not-a-dir").apply { writeBytes(byteArrayOf(0x00)) }
        // Place output "under" a regular file → mkdirs cannot succeed and FileOutputStream fails.
        val output = File(regularFile, "out.jpg")

        val result = imageCompressor.compress(input.absolutePath, output.absolutePath)

        assertTrue(result.isFailure, "Expected failure but got $result")
        assertTrue(!output.exists(), "No partial output should remain")
    }

    @Test
    fun imageCompress_outputPathIsADirectory_failsCleanly() = runTest {
        val input = createTestImage(tempDir, 200, 200)
        val output = File(tempDir, "output-dir").apply { mkdirs() }

        val result = imageCompressor.compress(input.absolutePath, output.absolutePath)

        assertTrue(result.isFailure, "Expected failure but got $result")
        // The directory itself is fine; what we assert is that no JPEG was written *into* it
        // under a fixed name — the directory remains a directory.
        assertTrue(output.isDirectory, "Output path should remain a directory")
    }

    @Test
    fun audioCompress_nonexistentInput_failsWithTypedError() = runTest {
        val missing = File(tempDir, "does-not-exist.wav")
        val output = File(tempDir, "out.m4a")

        val result = audioCompressor.compress(missing.absolutePath, output.absolutePath)

        assertTrue(result.isFailure, "Expected failure but got $result")
        val ex = result.exceptionOrNull()
        assertNotNull(ex, "Exception must be present")
        assertTrue(
            ex is AudioCompressionError,
            "Expected typed AudioCompressionError, got ${ex::class.simpleName}: $ex",
        )
        assertTrue(!output.exists(), "No partial output should remain")
    }

    @Test
    fun audioCompress_outputPathIsADirectory_failsWithTypedError() = runTest {
        // Create a tiny silent WAV so we have a valid input.
        val input = File(tempDir, "in.wav").apply {
            writeBytes(silentWavBytes())
        }
        val output = File(tempDir, "output-dir").apply { mkdirs() }

        val result = audioCompressor.compress(input.absolutePath, output.absolutePath)

        assertTrue(result.isFailure, "Expected failure but got $result")
        val ex = result.exceptionOrNull()
        assertNotNull(ex, "Exception must be present")
        assertTrue(
            ex is AudioCompressionError,
            "Expected typed AudioCompressionError, got ${ex::class.simpleName}: $ex",
        )
        assertTrue(output.isDirectory, "Output path should remain a directory")
    }

    private fun silentWavBytes(): ByteArray {
        // Minimal PCM-16 mono 8 kHz 0.1 s silent WAV. We hand-roll the header rather than depend
        // on WavGenerator so this test stays independent of the audio fixture utilities.
        val sampleRate = 8000
        val numSamples = sampleRate / 10
        val byteRate = sampleRate * 2
        val dataSize = numSamples * 2
        val totalSize = 36 + dataSize
        val bb = java.nio.ByteBuffer.allocate(44 + dataSize).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray())
        bb.putInt(totalSize)
        bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray())
        bb.putInt(16)
        bb.putShort(1) // PCM
        bb.putShort(1) // mono
        bb.putInt(sampleRate)
        bb.putInt(byteRate)
        bb.putShort(2) // block align
        bb.putShort(16) // bits per sample
        bb.put("data".toByteArray())
        bb.putInt(dataSize)
        repeat(numSamples) { bb.putShort(0) }
        return bb.array()
    }
}
