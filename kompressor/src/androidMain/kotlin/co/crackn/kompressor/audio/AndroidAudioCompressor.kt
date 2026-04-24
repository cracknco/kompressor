/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(co.crackn.kompressor.ExperimentalKompressorApi::class)

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
import co.crackn.kompressor.CompressionResult
import co.crackn.kompressor.KompressorContext
import co.crackn.kompressor.awaitMedia3Export
import co.crackn.kompressor.buildTightMp4MuxerFactory
import co.crackn.kompressor.collectCodecMimeTypes
import co.crackn.kompressor.deletingOutputOnFailure
import co.crackn.kompressor.io.CompressionProgress
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource
import co.crackn.kompressor.io.MediaType
import co.crackn.kompressor.io.toAndroidInputPath
import co.crackn.kompressor.io.toAndroidOutputHandle
import co.crackn.kompressor.logging.LogTags
import co.crackn.kompressor.logging.NoOpLogger
import co.crackn.kompressor.logging.SafeLogger
import co.crackn.kompressor.logging.instrumentCompress
import co.crackn.kompressor.logging.redactPath
import co.crackn.kompressor.resolveMediaInputSize
import co.crackn.kompressor.suspendRunCatching
import co.crackn.kompressor.toMediaItemUri
import java.io.File
import java.io.FileNotFoundException
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
    private val logger: SafeLogger = SafeLogger(NoOpLogger),
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

    // This implementation only emits AAC ([buildTransformer] hard-codes `MimeTypes.AUDIO_AAC`).
    // Intersect with the device's advertised encoders so we don't promise a MIME the device can't
    // actually produce.
    override val supportedOutputFormats: Set<String> by lazy {
        collectCodecMimeTypes(isEncoder = true, mediaTypePrefix = AUDIO_MIME_PREFIX)
            .intersect(setOf(MimeTypes.AUDIO_AAC))
    }

    override suspend fun waveform(
        input: MediaSource,
        targetSamples: Int,
        onProgress: suspend (CompressionProgress) -> Unit,
    ): Result<FloatArray> = suspendRunCatching {
        require(targetSamples > 0) { "targetSamples must be positive, was $targetSamples" }
        // Waveform streams PCM straight from the source — MATERIALIZING_INPUT is irrelevant
        // here. The common dispatch path still copies Stream / Bytes sources to a local file
        // behind the scenes, but we deliberately don't surface those ticks because the API's
        // progress contract specifies COMPRESSING-phase emissions only.
        val inputHandle = input.toAndroidInputPath(
            mediaType = MediaType.AUDIO,
            logger = logger,
        )
        try {
            withContext(Dispatchers.IO) {
                extractAndroidWaveform(inputHandle.path, targetSamples, onProgress)
            }
        } finally {
            inputHandle.cleanup()
        }
    }

    // LongMethod suppressed: see AndroidVideoCompressor. The nested try/finally lifecycle
    // around the input + output handle cleanup is the length driver; extracting it would
    // fragment the cleanup contract.
    @Suppress("LongMethod")
    override suspend fun compress(
        input: MediaSource,
        output: MediaDestination,
        config: AudioCompressionConfig,
        onProgress: suspend (CompressionProgress) -> Unit,
    ): Result<CompressionResult> = suspendRunCatching {
        val inputHandle = input.toAndroidInputPath(
            mediaType = MediaType.AUDIO,
            logger = logger,
        ) { fraction ->
            onProgress(
                CompressionProgress(
                    CompressionProgress.Phase.MATERIALIZING_INPUT,
                    fraction.coerceIn(0f, 1f),
                ),
            )
        }
        try {
            val outputHandle = output.toAndroidOutputHandle()
            try {
                val result = compressFilePath(inputHandle.path, outputHandle.tempPath, config) { fraction ->
                    // FINALIZING_OUTPUT(1f) is the canonical terminal — don't double-signal 100%
                    // by forwarding the inner pipeline's own 1f tick as a COMPRESSING emission.
                    if (fraction < 1f) {
                        onProgress(
                            CompressionProgress(
                                CompressionProgress.Phase.COMPRESSING,
                                fraction.coerceIn(0f, 1f),
                            ),
                        )
                    }
                }
                // Commit runs under the FINALIZING_OUTPUT phase: bytes move from the temp file
                // into the MediaStore / SAF URI / consumer Sink here. Failures during commit
                // propagate as `Result.failure` via the enclosing `suspendRunCatching`.
                outputHandle.commit { fraction ->
                    if (fraction < 1f) {
                        onProgress(
                            CompressionProgress(
                                CompressionProgress.Phase.FINALIZING_OUTPUT,
                                fraction.coerceIn(0f, 1f),
                            ),
                        )
                    }
                }
                onProgress(CompressionProgress(CompressionProgress.Phase.FINALIZING_OUTPUT, 1f))
                result
            } finally {
                outputHandle.cleanup()
            }
        } finally {
            inputHandle.cleanup()
        }
    }

    /**
     * Local-file → local-file compression with the CRA-47 [instrumentCompress] wrapper applied.
     * Throws [AudioCompressionError] subtypes on failure; the outer MediaSource dispatch wraps
     * those into `Result.failure` via `suspendRunCatching`.
     *
     * Emits coarse-grained `(Float)` progress in `[0f, 1f]` under the implicit `COMPRESSING`
     * phase — the MediaSource wrapper lifts these values into the [CompressionProgress] phase
     * machine.
     */
    // LongMethod suppressed: the body is an `instrumentCompress` wrap (CRA-47) around the Media3
    // Transformer integration. The message builders (tag + three messages + passthrough DEBUG log)
    // are the source of the extra lines. Extracting them as private helpers moves complexity
    // around without clarifying intent, so we accept the length here.
    @Suppress("LongMethod")
    private suspend fun compressFilePath(
        inputPath: String,
        outputPath: String,
        config: AudioCompressionConfig,
        onProgress: suspend (Float) -> Unit,
    ): CompressionResult = logger.instrumentCompress(
        tag = LogTags.AUDIO,
        startMessage = {
            "compress() start in=${redactPath(inputPath)} out=${redactPath(outputPath)} " +
                "bitrate=${config.bitrate} sampleRate=${config.sampleRate} " +
                "channels=${config.channels} trackIndex=${config.audioTrackIndex}"
        },
        successMessage = { r ->
            "compress() ok durationMs=${r.durationMs} " +
                "in=${r.inputSize}B out=${r.outputSize}B ratio=${r.compressionRatio}"
        },
        failureMessage = { "compress() failed in=${redactPath(inputPath)}" },
    ) {
        val startNanos = System.nanoTime()
        onProgress(0f)
        val inputSize = resolveMediaInputSize(inputPath)
        // Pre-flight: reject obviously-empty inputs with a typed IO error instead of letting
        // Media3 surface an opaque decoder-init failure. `resolveMediaInputSize` returns 0 for
        // unreadable sources as well, so we only reject when the file exists and is zero bytes;
        // a genuinely missing file will surface its own error downstream.
        if (inputPath.startsWith("/") && inputSize == 0L && File(inputPath).exists()) {
            throw AudioCompressionError.IoFailed("Input file is empty (0 bytes): $inputPath")
        }

        rejectNonFileOutputPath(outputPath)
        val probeResult = probeAndValidateInput(inputPath, config)

        deletingOutputOnFailure(outputPath) {
            runTransformerWithTrackSelection(
                inputPath, outputPath, config, probeResult.format, onProgress,
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
    private suspend fun runTransformerWithTrackSelection(
        inputPath: String,
        outputPath: String,
        config: AudioCompressionConfig,
        probe: InputAudioFormat?,
        onProgress: suspend (Float) -> Unit,
    ) {
        // Only pre-extract when the caller explicitly asks for a non-default track. Media3
        // Transformer already picks the first audio track on its own, so routing every
        // multi-track source through `extractAudioTrackToTempFile` just to land on index 0
        // would regress multi-track inputs whose first track uses a non-MP4-muxable codec
        // (Opus / Vorbis / FLAC / PCM) — `requireMp4MuxableCodec` would reject them even
        // though Media3 would have transcoded them cleanly without the extraction step.
        val transformerInputPath = if (config.audioTrackIndex > 0) {
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
                logger.debug(LogTags.AUDIO) {
                    "Transformer path: passthrough=$canPassthrough probeMime=${probe?.mime} " +
                        "probeSampleRate=${probe?.sampleRate} probeChannels=${probe?.channels} " +
                        "probeBitrate=${probe?.bitrate}"
                }
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

    /**
     * Pre-flight: single-pass probe off-Main (one MediaExtractor open returns both the selected
     * track's format AND the audio-track count — splitting the two cost us ~2× moov-atom parse
     * per call), then enforce the track-index bounds and channel-count envelope. Extracted from
     * [compress] so the main body stays within detekt's `LongMethod` threshold and the rules
     * are individually readable.
     */
    private suspend fun probeAndValidateInput(
        inputPath: String,
        config: AudioCompressionConfig,
    ): AudioProbeResult {
        val probeResult = withContext(Dispatchers.IO) {
            probeAudioInput(inputPath, config.audioTrackIndex)
        }
        if (config.audioTrackIndex >= probeResult.audioTrackCount) {
            throw AudioCompressionError.UnsupportedSourceFormat(
                "audioTrackIndex ${config.audioTrackIndex} out of bounds for " +
                    "${probeResult.audioTrackCount} audio track(s)",
            )
        }
        // Two complementary channel-count gates (both host-testable):
        //  - `checkSupportedInputChannelCount` rejects inputs outside the envelope (7-channel
        //    or 9+-channel sources have no mix path).
        //  - `checkChannelMixSupported` rejects (input, target) pairs the mixer can't satisfy —
        //    primarily upmix attempts like stereo → 5.1 — so the caller sees the typed
        //    `UnsupportedConfiguration` instead of a Media3 mid-pipeline crash.
        checkSupportedInputChannelCount(probeResult.format?.channels)
        checkChannelMixSupported(probeResult.format?.channels, config.channels.count)
        return probeResult
    }

    /**
     * Reject pre-existing non-file output paths (directories, sockets, fifos) up front. Media3
     * 1.10's `Transformer.start(item, outputPath)` eagerly `File.delete()`s an existing entry
     * before opening its muxer `FileOutputStream`, which silently wipes a caller-owned empty
     * directory before our `deletingOutputOnFailure` snapshot ever sees a throwable.
     */
    private fun rejectNonFileOutputPath(outputPath: String) {
        val outputFile = File(outputPath)
        if (outputFile.exists() && !outputFile.isFile) {
            throw AudioCompressionError.IoFailed(
                "Output path is not a writable file: $outputPath",
            )
        }
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
 * Carrier for the result of a single [MediaExtractor] pass over the input: both the selected
 * track's [InputAudioFormat] (or `null` if the index doesn't resolve) and the total audio-track
 * count (`0` on probe failure). Lets `compress()` do one I/O hit instead of opening the
 * extractor twice in a row.
 */
internal data class AudioProbeResult(val format: InputAudioFormat?, val audioTrackCount: Int)

/**
 * Single-pass probe: opens one [MediaExtractor], reports both the selected track's format AND
 * the audio-track count of the container. Runs on [Dispatchers.IO] from the caller.
 *
 * Failure taxonomy (chosen to keep the three test contracts distinct):
 * - [CancellationException] propagates so structured concurrency stays intact.
 * - [FileNotFoundException] / [SecurityException] surface a typed [AudioCompressionError.IoFailed]
 *   so callers can distinguish "I couldn't read the source at all" from "the source exposes zero
 *   audio tracks".
 * - All other failures — including plain [java.io.IOException] from a malformed container, which
 *   MediaExtractor throws as "Failed to instantiate extractor" — collapse to `(null, 0)` so the
 *   explicit bounds check in `compress()` surfaces `UnsupportedSourceFormat`. We split
 *   `FileNotFoundException` from plain `IOException` deliberately: the former is a file-level
 *   fault, the latter a format-level one, and `AudioInputRobustnessTest.randomBytes_*` depends
 *   on the latter ending up as `UnsupportedSourceFormat`.
 */
@Suppress("TooGenericExceptionCaught")
internal fun probeAudioInput(inputPath: String, audioTrackIndex: Int): AudioProbeResult = try {
    openAudioExtractor(inputPath).useThenRelease { extractor ->
        val count = extractor.countAudioTracks()
        val format = findAudioTrackIndexInContainer(extractor, audioTrackIndex)
            ?.let { extractor.getTrackFormat(it).toInputAudioFormat() }
        AudioProbeResult(format, count)
    }
} catch (ce: CancellationException) {
    throw ce
} catch (e: FileNotFoundException) {
    throw AudioCompressionError.IoFailed("Audio input not found: $inputPath", e)
} catch (e: SecurityException) {
    throw AudioCompressionError.IoFailed("Permission denied probing audio input: $inputPath", e)
} catch (_: Exception) {
    // Catch `Exception` (not `Throwable`): non-recoverable JVM `Error`s — `OutOfMemoryError`,
    // `LinkageError`, `StackOverflowError` — must propagate so the caller's runtime sees them
    // instead of being silently demoted to an `UnsupportedSourceFormat` error downstream.
    AudioProbeResult(null, 0)
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
 * Invoked only on the error path and only when [probeAudioInput] returned `null` (so the
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
