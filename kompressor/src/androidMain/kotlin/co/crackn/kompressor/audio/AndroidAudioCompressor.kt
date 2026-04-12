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
import co.crackn.kompressor.resolveMediaInputSize
import co.crackn.kompressor.suspendRunCatching
import co.crackn.kompressor.toMediaItemUri
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
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
 * Fast path: when the probed input is already AAC at roughly the requested sample rate, channels,
 * and bitrate (within ±20%), we omit the encoder factory and any audio processors so Media3
 * activates a bitstream-copy passthrough and finishes in milliseconds.
 *
 * Platform failures are translated into the typed [AudioCompressionError] hierarchy via
 * [toAudioCompressionError] so callers can `when`-branch on subtypes.
 */
internal class AndroidAudioCompressor : AudioCompressor {

    override val supportedInputFormats: Set<String> by lazy {
        collectCodecMimeTypes(isEncoder = false, mediaTypePrefix = AUDIO_MIME_PREFIX)
    }

    override val supportedOutputFormats: Set<String> by lazy {
        collectCodecMimeTypes(isEncoder = true, mediaTypePrefix = AUDIO_MIME_PREFIX)
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
        val inputSize = resolveMediaInputSize(inputPath)

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
            // Prefer the probe (populated on the happy path) for the error description — it's
            // already in memory and richer than what MediaMetadataRetriever returns. Fall back
            // to MMR only when the probe itself couldn't read the file.
            val description = probe?.describe()
                ?: withContext(Dispatchers.IO) { describeAudioSource(inputPath) }
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
    ): EditedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(toMediaItemUri(inputPath)))
        // Ignore any video track if the input is e.g. an MP4 with both video and audio.
        .setRemoveVideo(true)
        .setEffects(Effects(plan.toProcessors(), emptyList()))
        .build()

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val AUDIO_MIME_PREFIX = "audio/"
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
) {
    /** Format this probe as a short human-readable description for error messages. */
    fun describe(): String? = buildString {
        mime?.let { append(it) }
        if (sampleRate != null) {
            if (isNotEmpty()) append(' ')
            append(sampleRate).append("Hz")
        }
        if (channels != null) {
            if (isNotEmpty()) append(' ')
            append(channels).append("ch")
        }
        if (bitrate != null) {
            if (isNotEmpty()) append(' ')
            append(bitrate).append(" bps")
        }
    }.ifBlank { null }
}

/**
 * Inspect the input file with a short-lived [MediaExtractor] to find the first audio track and
 * its (sample rate, channel count, bitrate). Runs on [Dispatchers.IO] from the caller.
 * Returns `null` if the file has no readable audio track — Media3 will surface a proper error
 * later. Cancellation propagates; other failures become `null` (best-effort probe).
 */
@Suppress("TooGenericExceptionCaught")
private fun probeInputFormat(inputPath: String): InputAudioFormat? = try {
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
} catch (ce: CancellationException) {
    throw ce
} catch (_: Throwable) {
    null
}

private fun MediaFormat.toInputAudioFormat(): InputAudioFormat = InputAudioFormat(
    mime = getString(MediaFormat.KEY_MIME),
    sampleRate = intOrNull(MediaFormat.KEY_SAMPLE_RATE),
    channels = intOrNull(MediaFormat.KEY_CHANNEL_COUNT),
    bitrate = intOrNull(MediaFormat.KEY_BIT_RATE),
)

private fun MediaFormat.intOrNull(key: String): Int? =
    if (containsKey(key)) getInteger(key).takeIf { it > 0 } else null

/**
 * Should we bitstream-passthrough the input AAC instead of re-encoding?
 *
 * We require the source bitrate to be **approximately equal** to the target — within ±20% on
 * both sides. The lower bound avoids masquerading a low-quality source as fulfilling a higher
 * target bitrate; the upper bound avoids passing through a source that is significantly larger
 * than the user asked for (which would defeat the purpose of "compress").
 *
 * Returns `true` when the source bitrate is unknown — Media3 is the authority in that case.
 */
private fun Int?.qualifiesForPassthrough(targetBitrate: Int): Boolean {
    if (this == null) return true
    val lower = (targetBitrate * (1f - PASSTHROUGH_BITRATE_TOLERANCE)).toInt()
    val upper = (targetBitrate * (1f + PASSTHROUGH_BITRATE_TOLERANCE)).toInt()
    return this in lower..upper
}

/**
 * Build a short human-readable description of the source using [MediaMetadataRetriever].
 * Invoked only on the error path and only when [probeInputFormat] returned `null` (so the
 * cheap in-memory probe wasn't available). Best-effort — returns null on any failure other
 * than cancellation.
 */
@Suppress("TooGenericExceptionCaught")
private fun describeAudioSource(inputPath: String): String? = try {
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
} catch (ce: CancellationException) {
    throw ce
} catch (_: Throwable) {
    null
}

private const val PASSTHROUGH_BITRATE_TOLERANCE = 0.2f
