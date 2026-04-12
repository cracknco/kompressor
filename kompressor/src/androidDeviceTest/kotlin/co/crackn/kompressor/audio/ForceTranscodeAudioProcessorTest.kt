package co.crackn.kompressor.audio

import androidx.media3.common.audio.AudioProcessor.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals
import org.junit.Test
import kotlin.test.assertTrue

/**
 * Unit tests for the force-transcode no-op audio processor.
 *
 * This test lives in `androidDeviceTest` rather than `androidHostTest` because Media3's
 * [androidx.media3.common.audio.BaseAudioProcessor] reaches into `android.util.SparseArray`
 * and similar device-only classes during its internal state management, which the host JVM
 * can't stub cleanly.
 *
 * Why bother testing a one-line no-op: the production [AndroidAudioCompressor] relies on this
 * processor reporting `isActive() == true` after configure — that's what flips Media3 into the
 * audio pipeline so our encoder factory actually runs. If a future Media3 update changes
 * [androidx.media3.common.audio.BaseAudioProcessor]'s isActive contract (e.g. requiring a
 * differing output format), this test catches the regression before the whole compressor
 * silently reverts to bitstream-passthrough.
 */
@Suppress("DEPRECATION") // AudioProcessor.flush() replaced in 1.11+ but we target 1.10.
class ForceTranscodeAudioProcessorTest {

    @Test
    fun isActive_afterConfigure_returnsTrue() {
        val processor = ForceTranscodeAudioProcessor()
        val format = AudioFormat(
            /* sampleRate = */ 44_100,
            /* channelCount = */ 2,
            /* encoding = */ android.media.AudioFormat.ENCODING_PCM_16BIT,
        )

        val outputFormat = processor.configure(format)

        assertEquals(format, outputFormat, "onConfigure returns the input format unchanged")
        assertTrue(
            processor.isActive,
            "Processor must report isActive=true so Media3 routes audio through the encoder pipeline",
        )
    }

    @Test
    fun queueInput_copiesBytesVerbatim() {
        val processor = ForceTranscodeAudioProcessor()
        val format = AudioFormat(
            /* sampleRate = */ 44_100,
            /* channelCount = */ 2,
            /* encoding = */ android.media.AudioFormat.ENCODING_PCM_16BIT,
        )
        processor.configure(format)
        processor.flush()

        val samples = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val input = ByteBuffer.allocateDirect(samples.size).order(ByteOrder.nativeOrder()).apply {
            put(samples).flip()
        }

        processor.queueInput(input)
        val output = processor.output

        assertEquals(samples.size, output.remaining(), "All input bytes must appear on output")
        val roundtrip = ByteArray(samples.size).also { output.get(it) }
        assertTrue(
            roundtrip.contentEquals(samples),
            "Output bytes must match input verbatim: ${roundtrip.toList()} vs ${samples.toList()}",
        )
        assertEquals(
            0,
            input.remaining(),
            "queueInput must fully consume its input buffer (Media3 contract)",
        )
    }

    @Test
    fun queueInput_emptyBuffer_isNoOp() {
        val processor = ForceTranscodeAudioProcessor()
        val format = AudioFormat(
            /* sampleRate = */ 44_100,
            /* channelCount = */ 2,
            /* encoding = */ android.media.AudioFormat.ENCODING_PCM_16BIT,
        )
        processor.configure(format)
        processor.flush()

        val empty = ByteBuffer.allocateDirect(0)
        processor.queueInput(empty)
        assertEquals(0, processor.output.remaining(), "Empty input must not produce output")
    }
}
