package co.crackn.kompressor

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class MediaCodecProgressTest {

    @Test
    fun `returns lastReported and does not fire callback when duration is zero`() = runTest {
        val reported = mutableListOf<Float>()

        val result = reportMediaCodecProgress(
            currentTimeUs = 500,
            totalDurationUs = 0,
            lastReported = PROGRESS_SETUP,
            onProgress = { reported += it },
        )

        result shouldBe PROGRESS_SETUP
        reported.shouldBeEmpty()
    }

    @Test
    fun `returns lastReported when currentTime is non-positive`() = runTest {
        val reported = mutableListOf<Float>()

        val result = reportMediaCodecProgress(
            currentTimeUs = 0,
            totalDurationUs = 1000,
            lastReported = PROGRESS_SETUP,
            onProgress = { reported += it },
        )

        result shouldBe PROGRESS_SETUP
        reported.shouldBeEmpty()
    }

    @Test
    fun `does not fire below threshold but advances above`() = runTest {
        val reported = mutableListOf<Float>()

        // 1% of total duration → progress ~= PROGRESS_SETUP + 0.009 → below threshold
        val below = reportMediaCodecProgress(10, 1000, PROGRESS_SETUP, { reported += it })
        below shouldBe PROGRESS_SETUP
        reported.shouldBeEmpty()

        // 50% of total duration → PROGRESS_SETUP + 0.45 → well above threshold
        val above = reportMediaCodecProgress(500, 1000, PROGRESS_SETUP, { reported += it })
        reported.size shouldBe 1
        above shouldBe (PROGRESS_SETUP + PROGRESS_TRANSCODE_RANGE * 0.5f)
    }

    @Test
    fun `clamps output fraction at 1_0`() = runTest {
        val reported = mutableListOf<Float>()

        val result = reportMediaCodecProgress(
            currentTimeUs = 2_000,
            totalDurationUs = 1_000,
            lastReported = 0f,
            onProgress = { reported += it },
        )

        reported.single() shouldBe (PROGRESS_SETUP + PROGRESS_TRANSCODE_RANGE)
        result shouldBeGreaterThanOrEqual PROGRESS_SETUP
        result shouldBeLessThanOrEqual (PROGRESS_SETUP + PROGRESS_TRANSCODE_RANGE)
    }
}
