package co.crackn.kompressor.video

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.Test

class DeletingOutputOnFailureTest {

    private val tempDir: File = Files.createTempDirectory("deleting-output-test").toFile()

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `success leaves output file intact`() {
        val output = File(tempDir, "ok.mp4").apply { writeText("payload") }

        val result = deletingOutputOnFailure(output.path) { "done" }

        result shouldBe "done"
        output.exists() shouldBe true
        output.readText() shouldBe "payload"
    }

    @Test
    fun `throwing block deletes the output and rethrows`() {
        val output = File(tempDir, "partial.mp4").apply { writeText("garbage") }

        shouldThrow<IllegalStateException> {
            deletingOutputOnFailure(output.path) { error("boom") }
        }
        output.exists() shouldBe false
    }

    @Test
    fun `CancellationException propagates and still deletes the output`() {
        val output = File(tempDir, "cancelled.mp4").apply { writeText("garbage") }

        shouldThrow<CancellationException> {
            deletingOutputOnFailure(output.path) { throw CancellationException("cancelled") }
        }
        output.exists() shouldBe false
    }

    @Test
    fun `missing output path is tolerated on failure`() {
        val missing = File(tempDir, "never-written.mp4").path

        shouldThrow<IllegalStateException> {
            deletingOutputOnFailure(missing) { error("boom") }
        }
        File(missing).exists() shouldBe false
    }
}
