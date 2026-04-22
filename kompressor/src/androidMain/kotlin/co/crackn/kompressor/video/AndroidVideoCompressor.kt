/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(co.crackn.kompressor.ExperimentalKompressorApi::class)

package co.crackn.kompressor.video

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import co.crackn.kompressor.CompressionResult
import co.crackn.kompressor.KompressorContext
import co.crackn.kompressor.awaitMedia3Export
import co.crackn.kompressor.buildTightMp4MuxerFactory
import co.crackn.kompressor.collectCodecMimeTypes
import co.crackn.kompressor.deletingOutputOnFailure
import co.crackn.kompressor.io.CompressionProgress
import co.crackn.kompressor.io.MediaDestination
import co.crackn.kompressor.io.MediaSource
import co.crackn.kompressor.io.requireFilePathOrThrow
import co.crackn.kompressor.logging.LogTags
import co.crackn.kompressor.logging.NoOpLogger
import co.crackn.kompressor.logging.SafeLogger
import co.crackn.kompressor.logging.instrumentCompress
import co.crackn.kompressor.resolveMediaInputSize
import co.crackn.kompressor.suspendRunCatching
import co.crackn.kompressor.toMediaItemUri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android video compressor backed by [Transformer][androidx.media3.transformer.Transformer]
 * (Media3 1.10).
 *
 * Media3 Transformer handles codec selection, hardware/software fallback, HDR tone mapping,
 * rotation, and all the codec state-machine quirks that OEM MediaCodec implementations throw at
 * us. This keeps the library truly native (uses MediaCodec under the hood) with zero bundled
 * codec binaries. The listener / progress / cancellation glue lives in [awaitMedia3Export], shared
 * with the audio compressor.
 *
 * Platform failures are translated into the typed [VideoCompressionError] hierarchy via
 * [toVideoCompressionError] so that callers can `when`-branch on actionable error subtypes
 * (e.g. [VideoCompressionError.UnsupportedSourceFormat] for the "device can't decode HEVC 10-bit"
 * case) rather than a generic `ExportException`.
 */
internal class AndroidVideoCompressor(
    private val logger: SafeLogger = SafeLogger(NoOpLogger),
    /**
     * Extra audio processors appended to the Effects chain **for testing only** — parallel to
     * the seam on `AndroidAudioCompressor`. Device tests inject a `SlowAudioProcessor` here
     * to stall the encoder long enough that cancellation tests reliably land mid-export.
     * Production `createKompressor()` uses the default empty list.
     */
    private val testExtraAudioProcessors: List<androidx.media3.common.audio.AudioProcessor> = emptyList(),
) : VideoCompressor {

    override val supportedInputFormats: Set<String> by lazy {
        collectCodecMimeTypes(isEncoder = false, mediaTypePrefix = "video/")
    }

    // Output mime depends on `VideoCompressionConfig.codec` — H.264 (default) or HEVC (required
    // for HDR10). Intersect with the device's advertised encoders so we don't promise codecs
    // this device cannot produce. Both H.264 and HEVC are advertised when the device supports
    // them; callers pick via the config.
    override val supportedOutputFormats: Set<String> by lazy {
        collectCodecMimeTypes(isEncoder = true, mediaTypePrefix = "video/")
            .intersect(setOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265))
    }

    override suspend fun compress(
        inputPath: String,
        outputPath: String,
        config: VideoCompressionConfig,
        onProgress: suspend (Float) -> Unit,
    ): Result<CompressionResult> = suspendRunCatching {
        logger.instrumentCompress(
            tag = LogTags.VIDEO,
            startMessage = {
                "compress() start in=$inputPath out=$outputPath " +
                    "codec=${config.codec} dynamicRange=${config.dynamicRange} " +
                    "videoBitrate=${config.videoBitrate} maxRes=${config.maxResolution}"
            },
            successMessage = { r ->
                "compress() ok durationMs=${r.durationMs} " +
                    "in=${r.inputSize}B out=${r.outputSize}B ratio=${r.compressionRatio}"
            },
            failureMessage = { "compress() failed in=$inputPath" },
        ) {
            val startNanos = System.nanoTime()
            onProgress(0f)
            val inputSize = resolveMediaInputSize(inputPath)

            deletingOutputOnFailure(outputPath) {
                runTransformer(inputPath, outputPath, config, onProgress)
            }

            onProgress(1f)
            val outputSize = File(outputPath).length()
            val durationMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLI
            CompressionResult(inputSize, outputSize, durationMs)
        }
    }

    override suspend fun compress(
        input: MediaSource,
        output: MediaDestination,
        config: VideoCompressionConfig,
        onProgress: suspend (CompressionProgress) -> Unit,
    ): Result<CompressionResult> {
        val inputPath: String
        val outputPath: String
        try {
            inputPath = input.requireFilePathOrThrow()
            outputPath = output.requireFilePathOrThrow()
        } catch (e: UnsupportedOperationException) {
            return Result.failure(e)
        }
        val result = compress(inputPath, outputPath, config) { fraction ->
            onProgress(
                CompressionProgress(
                    CompressionProgress.Phase.COMPRESSING,
                    fraction.coerceIn(0f, 1f),
                ),
            )
        }
        if (result.isSuccess) {
            onProgress(CompressionProgress(CompressionProgress.Phase.FINALIZING_OUTPUT, 1f))
        }
        return result
    }

    private suspend fun runTransformer(
        inputPath: String,
        outputPath: String,
        config: VideoCompressionConfig,
        onProgress: suspend (Float) -> Unit,
    ) {
        // Pre-flight HDR10: if the device cannot encode HEVC Main10 HDR10, fail fast with a
        // typed error rather than crashing deep in the Media3 encoder selection.
        if (config.dynamicRange == DynamicRange.HDR10) {
            withContext(Dispatchers.IO) { requireHdr10Hevc() }
        }
        // Single probe pass — opening two separate MediaExtractor / MediaMetadataRetriever
        // sessions on the same source was doing the same native setDataSource work twice.
        // `probeVideo` folds the "has video track?" and "what's the shortest edge?" questions
        // into one MediaMetadataRetriever pass so SAF content:// and file:// URIs are handled
        // uniformly (both via the Context + Uri overload).
        val probe = withContext(Dispatchers.IO) { probeVideo(inputPath) }
        if (probe != null && !probe.hasVideoTrack) {
            throw VideoCompressionError.UnsupportedSourceFormat(
                "Input has no video track (only audio): $inputPath",
            )
        }
        val sourceShortSide = probe?.shortSide
        // When the probe fails we default to "probably has audio" so the sequence is permissive
        // — declaring TRACK_TYPE_AUDIO for a track that's actually absent just causes Media3 to
        // mux in silence (the regression we're avoiding goes the other way — declaring it when
        // NOT present). For unreadable sources Media3 will surface its own error either way.
        val sourceHasAudio = probe?.hasAudioTrack ?: true
        try {
            withContext(Dispatchers.Main) {
                val context = KompressorContext.appContext
                val transformer = buildTransformer(context, config)
                val item = buildEditedMediaItem(inputPath, config.maxResolution, sourceShortSide)
                val composition = buildComposition(item, config, sourceHasAudio)
                awaitMedia3Export(transformer, composition, outputPath, onProgress)
            }
        } catch (e: ExportException) {
            // describeSource does blocking I/O via MediaMetadataRetriever — keep it off Main.
            val description = withContext(Dispatchers.IO) { describeSource(inputPath) }
            throw e.toVideoCompressionError(description)
        }
    }

    private fun buildTransformer(
        context: android.content.Context,
        config: VideoCompressionConfig,
    ): Transformer {
        val videoSettings = VideoEncoderSettings.Builder()
            .setBitrate(config.videoBitrate)
            .build()
        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(videoSettings)
            .build()
        val outputMime = when (config.codec) {
            VideoCodec.H264 -> MimeTypes.VIDEO_H264
            VideoCodec.HEVC -> MimeTypes.VIDEO_H265
        }
        return Transformer.Builder(context)
            .setVideoMimeType(outputMime)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .setMuxerFactory(buildTightMp4MuxerFactory())
            .build()
    }

    /**
     * Wraps the [item] in a [Composition] with the HDR mode that matches [config.dynamicRange].
     * SDR explicitly opts into OpenGL tone-mapping (the most consistent path across devices per
     * Media3's KDoc); HDR10 keeps the Composition default `HDR_MODE_KEEP_HDR` so HDR sources
     * pass through 10-bit BT.2020+PQ end-to-end.
     */
    private fun buildComposition(
        item: EditedMediaItem,
        config: VideoCompressionConfig,
        sourceHasAudio: Boolean,
    ): Composition {
        // Use the addItem(...) builder API — the vararg/List Builder constructors are
        // @Deprecated in Media3 1.10 (the trackTypes-based constructor + addItem is the
        // forward-compatible path). `trackTypes` must match the tracks the *source actually
        // carries*: declaring TRACK_TYPE_AUDIO for a video-only source makes Media3 mux in a
        // silent audio track, which regresses the video-only-stays-video-only contract.
        val trackTypes = buildSet {
            add(C.TRACK_TYPE_VIDEO)
            if (sourceHasAudio) add(C.TRACK_TYPE_AUDIO)
        }
        val sequence = EditedMediaItemSequence.Builder(trackTypes).addItem(item).build()
        val builder = Composition.Builder(sequence)
        if (config.dynamicRange == DynamicRange.SDR) {
            builder.setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
        }
        // HDR10 path leaves hdrMode at the Composition default (HDR_MODE_KEEP_HDR).
        return builder.build()
    }

    private fun buildEditedMediaItem(
        inputPath: String,
        maxResolution: MaxResolution,
        sourceShortSide: Int?,
    ): EditedMediaItem {
        val videoEffects = buildList {
            maxResolution.toPresentationOrNull(sourceShortSide)?.let(::add)
        }
        return EditedMediaItem.Builder(MediaItem.fromUri(toMediaItemUri(inputPath)))
            .setEffects(Effects(testExtraAudioProcessors, videoEffects))
            .build()
    }

    /**
     * Pre-flight check for HDR10, two-stage:
     *
     *  1. **Capability check** — [deviceSupportsHdr10Hevc] reads `MediaCodecList` to confirm an
     *     HEVC encoder advertises Main10 + `FEATURE_HdrEditing`. Cheap (~1 ms) and gates out
     *     pre-API-33 / devices that don't even claim the profile.
     *  2. **Active probe** — [Hdr10HevcProbe.probe] actually allocates the encoder, configures
     *     it with 10-bit P010 + BT.2020/PQ metadata, queues a probe frame, and drains one
     *     output. Expensive (~100–300 ms) but catches the OEM firmware class of "advertises
     *     Main10 but crashes `configure()`" that the capability check cannot detect.
     *
     * Both failure modes raise the typed [VideoCompressionError.Hdr10NotSupported] so callers
     * see a consistent subtype they can `when`-branch on to offer an SDR fallback.
     */
    private fun requireHdr10Hevc() {
        if (!deviceSupportsHdr10Hevc()) {
            throw VideoCompressionError.Hdr10NotSupported(
                device = android.os.Build.MODEL,
                // No concrete MediaCodec component was retained by the capability gate, so
                // surface the shared marker (rather than the `VideoCodec` enum value, which
                // would drift from the canonical MediaCodec names the active-probe branch
                // reports — see CodeRabbit review on PR #120).
                codec = Hdr10HevcProbe.NO_MAIN10_CODEC,
                reason = "MediaCodecList reports no HEVC encoder with Main10 + FEATURE_HdrEditing " +
                    "on API ${android.os.Build.VERSION.SDK_INT}",
            )
        }
        val probe = Hdr10HevcProbe.probe()
        if (!probe.supported) {
            throw VideoCompressionError.Hdr10NotSupported(
                device = android.os.Build.MODEL,
                codec = probe.codecName,
                reason = probe.reason,
            )
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/**
 * Returns true when the device has at least one HEVC encoder that can actually keep an HDR10
 * source HDR10 through Media3 — i.e. the device runs API 33+ AND an encoder advertises a
 * Main10 / Main10HDR10 profile with the `FEATURE_HdrEditing` MediaCodec feature.
 *
 * The two-stage check mirrors Media3's own gate in `EncoderUtil.getSupportedEncodersForHdrEditing`
 * (annotated `@RequiresApi(33)` — empty list on older SDKs) and
 * `TransformerUtil.getOutputMimeTypeAndHdrModeAfterFallback` (which flips `HDR_MODE_KEEP_HDR`
 * to `HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL` whenever that list is empty or the encoder
 * doesn't advertise `FEATURE_HdrEditing`). Aligning the pre-flight with Media3 means
 * `VideoCompressionError.UnsupportedSourceFormat` surfaces to callers instead of a
 * surprise-SDR output on devices that advertise Main10 but not `FEATURE_HdrEditing`
 * (Pixel 6 API 33 is one such device).
 *
 * Cached after the first probe — `MediaCodecList(REGULAR_CODECS)` enumerates every encoder on
 * the device and is non-trivial; on hot HDR10 paths (re-encode loops) the result is invariant
 * for the process lifetime so re-probing per call is wasted work.
 */
private val hdr10HevcSupported: Boolean by lazy {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return@lazy false
    val codecs = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
    val main10Profiles = setOf(
        android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
        android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
        android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus,
    )
    codecs.any { info ->
        if (!info.isEncoder) return@any false
        val caps = runCatching { info.getCapabilitiesForType("video/hevc") }.getOrNull() ?: return@any false
        val hasMain10 = caps.profileLevels.any { pl -> pl.profile in main10Profiles }
        hasMain10 && caps.isFeatureSupported(android.media.MediaCodecInfo.CodecCapabilities.FEATURE_HdrEditing)
    }
}

internal fun deviceSupportsHdr10Hevc(): Boolean = hdr10HevcSupported

/**
 * Scale down to the shortest edge target; no effect when [MaxResolution.Original], and no effect
 * when the source is already within the target (no upscaling).
 *
 * `Presentation.createForShortSide(n)` forces the output's shortest edge to be exactly `n`,
 * including upscaling when the source is smaller. We cap this to strictly downscaling by
 * comparing against the probed source; when the source already fits, returning `null` keeps
 * the original resolution.
 *
 * `sourceShortSide == null` (probe failed) falls back to the original force-scale behaviour
 * since we can't prove no-upscale; Media3 will surface a real error for genuinely unreadable
 * inputs anyway.
 */
private fun MaxResolution.toPresentationOrNull(sourceShortSide: Int?): Presentation? =
    when (this) {
        is MaxResolution.Original -> null
        is MaxResolution.Custom ->
            if (sourceShortSide != null && sourceShortSide <= maxShortEdge) {
                null
            } else {
                Presentation.createForShortSide(maxShortEdge)
            }
    }

/**
 * Combined probe result: video track presence, audio track presence, and the source's shortest
 * edge. `hasAudioTrack` drives whether the Composition sequence advertises `TRACK_TYPE_AUDIO` —
 * declaring that for a video-only source makes Media3 synthesise a silent audio track on the way
 * out, which regressed `VideoEdgeCasesTest.videoOnlyNoAudioTrack_compressesSuccessfully`.
 */
internal data class VideoProbe(
    val hasVideoTrack: Boolean,
    val hasAudioTrack: Boolean,
    val shortSide: Int?,
)

/**
 * Best-effort single-pass probe: whether the container has a video track AND an audio track AND
 * what its shortest edge is, from one [MediaMetadataRetriever] open. Returns `null` when the
 * source can't be read at all — callers should treat that as "unknown, let the downstream
 * pipeline report its own error" rather than a hard pre-flight rejection.
 *
 * Handles `content://` (SAF) and `file://` URIs via the `setDataSource(Context, Uri)` overload;
 * plain `setDataSource(String)` silently fails on content URIs, which would nil out
 * [VideoProbe.hasVideoTrack] and let audio-only SAF inputs slip past the pre-flight.
 */
@Suppress("TooGenericExceptionCaught")
internal fun probeVideo(inputPath: String): VideoProbe? = try {
    val mmr = MediaMetadataRetriever()
    try {
        mmr.applyDataSource(inputPath)
        val hasVideo = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
        val hasAudio = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
        val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
        val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
        val shortSide = if (w != null && h != null) minOf(w, h) else null
        VideoProbe(hasVideoTrack = hasVideo, hasAudioTrack = hasAudio, shortSide = shortSide)
    } finally {
        mmr.release()
    }
} catch (_: Throwable) {
    null
}

private fun MediaMetadataRetriever.applyDataSource(inputPath: String) {
    if (inputPath.startsWith("content://") || inputPath.startsWith("file://")) {
        setDataSource(KompressorContext.appContext, Uri.parse(inputPath))
    } else {
        setDataSource(inputPath)
    }
}

/**
 * Build a short human-readable description of the source file (codec + resolution) to embed in
 * error messages. Best-effort — silently returns null if the retriever can't open the file.
 */
private fun describeSource(inputPath: String): String? = runCatching {
    val mmr = MediaMetadataRetriever()
    try {
        mmr.setDataSource(inputPath)
        val mime = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
        val width = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
        val height = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        val bitrate = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
        buildString {
            mime?.let { append(it) }
            if (width != null && height != null) {
                if (isNotEmpty()) append(' ')
                append(width).append('x').append(height)
            }
            bitrate?.let {
                if (isNotEmpty()) append(' ')
                append(it).append(" bps")
            }
        }.ifBlank { null }
    } finally {
        mmr.release()
    }
}.getOrNull()
