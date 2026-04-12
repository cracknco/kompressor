package co.crackn.kompressor.audio

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.Transformer
import co.crackn.kompressor.AudioCodec
import co.crackn.kompressor.CompressionResult
import co.crackn.kompressor.KompressorContext
import co.crackn.kompressor.awaitMedia3Export
import co.crackn.kompressor.collectCodecMimeTypes
import co.crackn.kompressor.deletingOutputOnFailure
import co.crackn.kompressor.suspendRunCatching
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android audio compressor backed by [Transformer][androidx.media3.transformer.Transformer]
 * (Media3 1.10), configured as an audio-only export via
 * [EditedMediaItem.Builder.setRemoveVideo].
 *
 * Media3 wraps the same `MediaCodec` encoders as the previous hand-rolled pipeline, but hands us
 * the buffer state-machine, resampling ([androidx.media3.common.audio.SonicAudioProcessor]),
 * channel mixing ([androidx.media3.common.audio.ChannelMixingAudioProcessor]), and extractor /
 * muxer plumbing for free. Input formats supported include MP3, AAC, M4A, FLAC, OGG/Opus, WAV,
 * AMR, ADTS — anything Media3's default extractors can open.
 *
 * Fast path: when the probed input is AAC at the requested sample rate / channels / bitrate,
 * we deliberately omit the encoder factory and any audio processors — Media3 then activates a
 * bitstream-copy passthrough and finishes in milliseconds.
 *
 * Platform failures are translated into the typed [AudioCompressionError] hierarchy via
 * [toAudioCompressionError] so callers can `when`-branch on subtypes (e.g.
 * [AudioCompressionError.UnsupportedSourceFormat] for an obscure codec).
 */
internal class AndroidAudioCompressor : AudioCompressor {

    override val supportedInputFormats: Set<String> by lazy {
        collectCodecMimeTypes(isEncoder = false, mediaTypePrefix = "audio/")
    }

    override val supportedOutputFormats: Set<String> by lazy {
        collectCodecMimeTypes(isEncoder = true, mediaTypePrefix = "audio/")
    }

    override suspend fun compress(
        inputPath: String,
        outputPath: String,
        config: AudioCompressionConfig,
        onProgress: suspend (Float) -> Unit,
    ): Result<CompressionResult> = suspendRunCatching {
        require(config.codec == AudioCodec.AAC) { "Only AAC codec is currently supported" }

        val startNanos = System.nanoTime()
        onProgress(0f)
        val inputSize = File(inputPath).length()

        // Probe off-Main because MediaExtractor does blocking I/O.
        val probe = withContext(Dispatchers.IO) { probeInputFormat(inputPath) }

        deletingOutputOnFailure(outputPath) {
            runTransformer(inputPath, outputPath, config, probe, onProgress)
        }

        onProgress(1f)
        val outputSize = File(outputPath).length()
        val durationMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLI
        CompressionResult(inputSize, outputSize, durationMs)
    }

    private suspend fun runTransformer(
        inputPath: String,
        outputPath: String,
        config: AudioCompressionConfig,
        probe: InputAudioFormat?,
        onProgress: suspend (Float) -> Unit,
    ) {
        try {
            withContext(Dispatchers.Main) {
                val context = KompressorContext.appContext
                val plan = planAudioProcessors(probe?.sampleRate, probe?.channels, config)
                val canPassthrough = plan.isEmpty &&
                    probe?.mime == MimeTypes.AUDIO_AAC &&
                    probe.bitrate.qualifiesForPassthrough(config.bitrate)
                val transformer = buildTransformer(context, config, canPassthrough)
                val item = buildEditedMediaItem(inputPath, plan)
                awaitMedia3Export(transformer, item, outputPath, onProgress)
            }
        } catch (e: ExportException) {
            val description = withContext(Dispatchers.IO) { describeAudioSource(inputPath) }
            throw e.toAudioCompressionError(description)
        }
    }

    private fun buildTransformer(
        context: Context,
        config: AudioCompressionConfig,
        canPassthrough: Boolean,
    ): Transformer {
        val builder = Transformer.Builder(context).setAudioMimeType(MimeTypes.AUDIO_AAC)
        if (!canPassthrough) {
            builder.setEncoderFactory(buildAudioEncoderFactory(context, config))
        }
        return builder.build()
    }

    private fun buildEditedMediaItem(
        inputPath: String,
        plan: AudioProcessorPlan,
    ): EditedMediaItem {
        val uri = if (inputPath.startsWith("content://") || inputPath.startsWith("file://")) {
            inputPath
        } else {
            "file://$inputPath"
        }
        return EditedMediaItem.Builder(MediaItem.fromUri(uri))
            // Ignore any video track if the input is e.g. an MP4 with both video and audio.
            .setRemoveVideo(true)
            .setEffects(Effects(plan.toProcessors(), emptyList()))
            .build()
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/**
 * Tuple of the format facts we need to decide the Transformer wiring. Absence of a field
 * (represented by `null`) means the extractor didn't report it and we should defer to Media3
 * defaults.
 */
internal data class InputAudioFormat(
    val mime: String?,
    val sampleRate: Int?,
    val channels: Int?,
    val bitrate: Int?,
)

/**
 * Inspect the input file with a short-lived [MediaExtractor] to find the first audio track and
 * its (sample rate, channel count, bitrate). Runs on [Dispatchers.IO] from the caller.
 * Returns `null` if the file has no readable audio track — Media3 will surface a proper error
 * later.
 */
private fun probeInputFormat(inputPath: String): InputAudioFormat? = runCatching {
    val extractor = MediaExtractor().apply { setDataSource(inputPath) }
    try {
        (0 until extractor.trackCount)
            .asSequence()
            .map { extractor.getTrackFormat(it) }
            .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?.toInputAudioFormat()
    } finally {
        extractor.release()
    }
}.getOrNull()

private fun MediaFormat.toInputAudioFormat(): InputAudioFormat = InputAudioFormat(
    mime = getString(MediaFormat.KEY_MIME),
    sampleRate = intOrNull(MediaFormat.KEY_SAMPLE_RATE),
    channels = intOrNull(MediaFormat.KEY_CHANNEL_COUNT),
    bitrate = intOrNull(MediaFormat.KEY_BIT_RATE),
)

private fun MediaFormat.intOrNull(key: String): Int? =
    if (containsKey(key)) getInteger(key).takeIf { it > 0 } else null

/**
 * Is the source bitrate close enough to the target that re-encoding would only throw quality
 * away? We allow passthrough when the source is within 80% of the requested bitrate (typical
 * AAC encoder jitter) or when the source bitrate is unknown.
 */
private fun Int?.qualifiesForPassthrough(targetBitrate: Int): Boolean =
    this == null || this >= (targetBitrate * PASSTHROUGH_BITRATE_TOLERANCE).toInt()

/**
 * Build a short human-readable description of the source (codec, sample rate, channel count)
 * to embed in error messages. Best-effort — silently returns null if the retriever can't open
 * the file.
 */
private fun describeAudioSource(inputPath: String): String? = runCatching {
    val mmr = MediaMetadataRetriever()
    try {
        mmr.setDataSource(inputPath)
        val mime = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
        val bitrate = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
        buildString {
            mime?.let { append(it) }
            bitrate?.let {
                if (isNotEmpty()) append(' ')
                append(it).append(" bps")
            }
        }.ifBlank { null }
    } finally {
        mmr.release()
    }
}.getOrNull()

private const val PASSTHROUGH_BITRATE_TOLERANCE = 0.8f
