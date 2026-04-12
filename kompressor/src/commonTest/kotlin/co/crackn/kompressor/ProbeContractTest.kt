package co.crackn.kompressor

import co.crackn.kompressor.audio.AudioCompressor
import co.crackn.kompressor.image.ImageCompressor
import co.crackn.kompressor.video.VideoCompressor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

/**
 * Contract tests for the [Kompressor.probe] + [Kompressor.canCompress] pair. These
 * lock the *interface* semantics that every platform implementation must honour:
 *
 *  - `probe` returns a [Result] (never throws) for normal failures, but
 *    [CancellationException] must propagate so structured concurrency still works.
 *  - `canCompress` takes the info returned by a successful probe and yields a
 *    [Supportability] verdict.
 *
 * The platform probe implementations open `MediaMetadataRetriever` / `AVURLAsset`
 * which isn't reachable from commonTest — this test exercises a fake that mimics
 * the expected semantics instead.
 */
class ProbeContractTest {

    // NOTE: test methods deliberately use camelCase rather than Kotlin's backtick
    // "fun testWithSpaces()" style. When these tests run in androidDeviceTest
    // (commonTest source is shared with the device test tree), the compiler emits
    // anonymous inner classes whose FQN includes the enclosing method name. D8
    // refuses SimpleNames containing spaces before DEX version 040 (i.e. for any
    // variant with `minSdk < 26`). We keep minSdk 24 on the library, so backticked
    // names elsewhere in this file used to break `connectedAndroidDeviceTest`.

    @Test
    fun probeWrapsNormalFailureInResultFailure() = runTest {
        val kompressor = FakeKompressor(probeOutcome = FakeOutcome.FailIo)

        val result = kompressor.probe("whatever")

        result.isFailure shouldBe true
    }

    @Test
    fun probeSurfacesSourceMediaInfoOnSuccess() = runTest {
        val info = SourceMediaInfo(videoCodec = "video/avc", width = 1280, height = 720)
        val kompressor = FakeKompressor(probeOutcome = FakeOutcome.Succeed(info))

        val result = kompressor.probe("whatever")

        result.isSuccess shouldBe true
        result.getOrNull() shouldBe info
    }

    @Test
    fun probePropagatesCancellationException() = runTest {
        val kompressor = FakeKompressor(probeOutcome = FakeOutcome.Cancel)

        shouldThrow<CancellationException> { kompressor.probe("whatever") }
    }

    @Test
    fun canCompressReturnsSupportedWhenProbedInfoMatchesCapabilities() = runTest {
        val info = SourceMediaInfo(
            videoCodec = "video/avc",
            audioCodec = "audio/mp4a-latm",
            width = 1280,
            height = 720,
        )
        val kompressor = FakeKompressor(
            probeOutcome = FakeOutcome.Succeed(info),
            canCompressVerdict = Supportability.Supported,
        )

        val probed = kompressor.probe("whatever").getOrThrow()
        kompressor.canCompress(probed).shouldBeInstanceOf<Supportability.Supported>()
    }
}

private sealed interface FakeOutcome {
    data class Succeed(val info: SourceMediaInfo) : FakeOutcome
    data object FailIo : FakeOutcome
    data object Cancel : FakeOutcome
}

private class FakeKompressor(
    private val probeOutcome: FakeOutcome,
    private val canCompressVerdict: Supportability = Supportability.Unknown(listOf("stub")),
) : Kompressor {
    override val image: ImageCompressor get() = error("unused")
    override val video: VideoCompressor get() = error("unused")
    override val audio: AudioCompressor get() = error("unused")

    override suspend fun probe(inputPath: String): Result<SourceMediaInfo> = suspendRunCatching {
        when (val outcome = probeOutcome) {
            is FakeOutcome.Succeed -> outcome.info
            FakeOutcome.FailIo -> throw IllegalStateException("io failure")
            FakeOutcome.Cancel -> throw CancellationException("cancelled")
        }
    }

    override fun canCompress(info: SourceMediaInfo): Supportability = canCompressVerdict
}
