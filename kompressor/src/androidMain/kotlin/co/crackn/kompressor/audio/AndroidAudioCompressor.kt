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
import co.crackn.kompressor.buildTightMp4MuxerFactory
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
internal class AndroidAudioCompressor(
    /**
     * Extra audio processors appended to the Effects chain **for testing only**. Production
     * `createKompressor()` uses the default empty list. Device tests inject a slow / blocking
     * processor here to make cancellation and timeout scenarios deterministic regardless of
     * the encoder's wall-clock throughput.
     */
    private val testExtraAudioProcessors: List<androidx.media3.common.audio.AudioProcessor> = emptyList(),
) : AudioCompressor {

    override val supportedInputFormats: Set<String> by lazy {
        collectCodecMimeTypes(isEncoder = false, mediaTypePrefix = AUDIO_MIME_PREFIX)
    }

    // This implementation only emits AAC (see the `require(config.codec == AudioCodec.AAC)` below
    // and [buildTransformer] hard-coding `MimeTypes.AUDIO_AAC`). Intersect with the device's
    // advertised encoders so we don't promise a MIME the device can't actually produce.
    override val supportedOutputFormats: Set<String> by lazy {
        collectCodecMimeTypes(isEncoder = true, mediaTypePrefix = AUDIO_MIME_PREFIX)
            .intersect(setOf(MimeTypes.AUDIO_AAC))
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

        // Probe off-Main because MediaExtractor does blocking I/O. Honour the configured
        // audioTrackIndex so passthrough/fast-path logic and error diagnostics reflect the
        // track we'll actually feed into Media3.
        val probe = withContext(Dispatchers.IO) {
            probeInputFormat(inputPath, config.audioTrackIndex)
        }

        // Bounds-check the audio track selection up front so callers get a typed error instead
        // of racing Media3's "no audio track" ExportException. We probe audioTrackCount even on
        // the default (index 0) path so requests against audio-less inputs fail fast.
        val trackCount = withContext(Dispatchers.IO) { countAudioTracks(inputPath) }
        if (config.audioTrackIndex >= trackCount) {
            throw AudioCompressionError.UnsupportedSourceFormat(
                "audioTrackIndex ${config.audioTrackIndex} out of bounds for $trackCount audio track(s)",
            )
        }

        // Reject configurations Media3's channel mixer cannot handle before kicking off an
        // export. Check is extracted to `checkSupportedInputChannelCount` so the rule is
        // covered by host tests without needing a real 5.1 / 7.1 fixture.
        checkSupportedInputChannelCount(probe?.channels)

        deletingOutputOnFailure(outputPath) {
            runTransformerWithTrackSelection(
                inputPath, outputPath, config, probe, trackCount, onProgress,
            )
        }

        onProgress(1f)
        val outputSize = File(outputPath).length()
        val durationMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLI
        CompressionResult(inputSize, outputSize, durationMs)
    }

    /**
     * Media3 Transformer doesn't expose "pick the Nth audio track" directly — it always picks
     * the first audio track discovered by ExoPlayer. When the caller asked for a non-default
     * index (or the source has >1 audio track), extract the selected track to a single-track
     * temporary MP4 via MediaExtractor+MediaMuxer (bitstream copy, preserves codec so the AAC
     * passthrough fast path still qualifies), then feed that to Transformer.
     */
    @Suppress("LongParameterList")
    private suspend fun runTransformerWithTrackSelection(
        inputPath: String,
        outputPath: String,
        config: AudioCompressionConfig,
        probe: InputAudioFormat?,
        trackCount: Int,
        onProgress: suspend (Float) -> Unit,
    ) {
        val transformerInputPath = if (trackCount > 1 || config.audioTrackIndex > 0) {
            withContext(Dispatchers.IO) {
                extractAudioTrackToTempFile(inputPath, config.audioTrackIndex)
            }
        } else {
            null
        }
        try {
            runTransformer(
                inputPath = transformerInputPath?.absolutePath ?: inputPath,
                outputPath = outputPath,
                config = config,
                probe = probe,
                onProgress = onProgress,
            )
        } finally {
            transformerInputPath?.delete()
        }
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
                val item = buildEditedMediaItem(inputPath, plan, canPassthrough)
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
        val builder = Transformer.Builder(context)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setMuxerFactory(buildTightMp4MuxerFactory())
        if (!canPassthrough) {
            builder.setEncoderFactory(buildAudioEncoderFactory(context, config))
        }
        return builder.build()
    }

    private fun buildEditedMediaItem(
        inputPath: String,
        plan: AudioProcessorPlan,
        canPassthrough: Boolean,
    ): EditedMediaItem {
        // When we explicitly want re-encoding (not passthrough) but the plan is empty — i.e.
        // source sample-rate/channels already match the target — Media3 would otherwise bitstream-
        // copy the input track (including PCM WAV → MP4 unchanged), ignoring our encoder factory
        // and requested bitrate. Inserting an always-active no-op processor forces the audio
        // pipeline and our [setEncoderFactory] call to actually run. See
        // [ForceTranscodeAudioProcessor] for the full rationale.
        val baseProcessors = plan.toProcessors().ifEmpty {
            if (canPassthrough) emptyList() else listOf(ForceTranscodeAudioProcessor())
        }
        val processors = baseProcessors + testExtraAudioProcessors
        return EditedMediaItem.Builder(MediaItem.fromUri(toMediaItemUri(inputPath)))
            // Ignore any video track if the input is e.g. an MP4 with both video and audio.
            .setRemoveVideo(true)
            .setEffects(Effects(processors, emptyList()))
            .build()
    }

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
 * Inspect the input file with a short-lived [MediaExtractor] to find the [audioTrackIndex]-th
 * audio track (zero-based) and its (sample rate, channel count, bitrate). Runs on
 * [Dispatchers.IO] from the caller. Returns `null` if the file has no readable audio track at
 * that index — the explicit bounds check in `compress()` then surfaces a typed error.
 * Cancellation propagates; other failures become `null` (best-effort probe).
 */
@Suppress("TooGenericExceptionCaught")
private fun probeInputFormat(inputPath: String, audioTrackIndex: Int): InputAudioFormat? = try {
    openAudioExtractor(inputPath).use { extractor ->
        findAudioTrackIndexInContainer(extractor, audioTrackIndex)
            ?.let { extractor.getTrackFormat(it).toInputAudioFormat() }
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
 * Returns `false` when the source bitrate is unknown: without a bitrate to compare against we
 * cannot guarantee the source satisfies the requested target, so we force a full re-encode rather
 * than risk passthrough-ing an unknown (possibly much higher) bitrate.
 */
private fun Int?.qualifiesForPassthrough(targetBitrate: Int): Boolean {
    if (this == null) return false
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
