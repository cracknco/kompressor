package co.crackn.kompressor.audio

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * A no-op [AudioProcessor] that copies input PCM bytes to output unchanged, but always reports
 * [isActive] as `true`.
 *
 * Why this exists: Media3 [Transformer][androidx.media3.transformer.Transformer] decides
 * whether to bitstream-passthrough the source or re-encode via the configured
 * [EncoderFactory][androidx.media3.transformer.EncoderFactory] based on whether *any* active
 * audio processor is present in the [Effects][androidx.media3.transformer.Effects] chain.
 * When an input's sample rate and channel count already match the target config,
 * [AudioProcessorPlan] is empty and [SonicAudioProcessor][androidx.media3.common.audio.SonicAudioProcessor] /
 * [ChannelMixingAudioProcessor][androidx.media3.common.audio.ChannelMixingAudioProcessor] are
 * both inactive — Media3 then passthroughs the source stream (even PCM WAV → MP4) instead of
 * honouring our [setEncoderFactory][androidx.media3.transformer.Transformer.Builder.setEncoderFactory]
 * with the requested bitrate.
 *
 * Inserting this processor when we explicitly want re-encoding forces Media3 through the audio
 * pipeline, which in turn invokes our encoder factory at the target bitrate.
 */
internal class ForceTranscodeAudioProcessor : BaseAudioProcessor() {
    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat = inputAudioFormat

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val output = replaceOutputBuffer(remaining)
        output.put(inputBuffer).flip()
    }
}
