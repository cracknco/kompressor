package co.crackn.kompressor.video

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
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

    private suspend fun runTransformer(
        inputPath: String,
        outputPath: String,
        config: VideoCompressionConfig,
        onProgress: suspend (Float) -> Unit,
    ) {
        // Pre-flight HDR10: if the device cannot encode HEVC Main10 HDR10, fail fast with a
        // typed error rather than crashing deep in the Media3 encoder selection.
        if (config.dynamicRange == DynamicRange.HDR10) {
            withContext(Dispatchers.IO) { requireHdr10Hevc(config) }
        }
        // Probe source short side off-Main so [toPresentationOrNull] can skip scaling when the
        // source already satisfies the target (preventing unintended upscale — e.g. 1280×720
        // input with `HIGH_QUALITY` preset targeting 1080p).
        val sourceShortSide = withContext(Dispatchers.IO) { probeVideoShortSide(inputPath) }
        // Pre-flight: reject inputs with no video track (audio-only MP4s) with a typed error.
        // Without this check Media3 surfaces an opaque `ERROR_CODE_IO_UNSPECIFIED` or decoder
        // init failure mid-export; the typed `UnsupportedSourceFormat` lets callers `when`-branch
        // cleanly. A null probe result (file unreadable) falls through so Media3's existing
        // error reporting wins for genuinely broken inputs.
        val probe = withContext(Dispatchers.IO) { probeVideoTracks(inputPath) }
        if (probe != null && !probe.hasVideoTrack) {
            throw VideoCompressionError.UnsupportedSourceFormat(
                "Input has no video track (only audio): $inputPath",
            )
        }
        try {
            withContext(Dispatchers.Main) {
                val context = KompressorContext.appContext
                val transformer = buildTransformer(context, config)
                val item = buildEditedMediaItem(inputPath, config.maxResolution, sourceShortSide)
                val composition = buildComposition(item, config)
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
    private fun buildComposition(item: EditedMediaItem, config: VideoCompressionConfig): Composition {
        // Use the addItem(...) builder API — the vararg/List Builder constructors are
        // @Deprecated in Media3 1.10 (the trackTypes-based constructor + addItem is the
        // forward-compatible path).
        val trackTypes = setOf(
            androidx.media3.common.C.TRACK_TYPE_AUDIO,
            androidx.media3.common.C.TRACK_TYPE_VIDEO,
        )
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
     * Pre-flight check for HDR10: throws [VideoCompressionError.UnsupportedSourceFormat] when
     * the device exposes no HEVC encoder that advertises a Main10/Main10HDR10 profile. This
     * fails fast with a typed error instead of letting Media3 surface a generic encoder-init
     * failure deep in the export pipeline.
     */
    private fun requireHdr10Hevc(config: VideoCompressionConfig) {
        if (!deviceSupportsHdr10Hevc()) {
            throw VideoCompressionError.UnsupportedSourceFormat(
                details = "HEVC Main10 HDR10 encoder unavailable on this device " +
                    "(requested DynamicRange.HDR10 with codec=${config.codec})",
            )
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/**
 * Returns true when the device's `MediaCodecList` advertises at least one HEVC encoder
 * supporting the Main10 or Main10HDR10 profile. Used to pre-flight HDR10 compression requests.
 *
 * Cached after the first probe — `MediaCodecList(REGULAR_CODECS)` enumerates every encoder on
 * the device and is non-trivial; on hot HDR10 paths (re-encode loops) the result is invariant
 * for the process lifetime so re-probing per call is wasted work.
 */
private val hdr10HevcSupported: Boolean by lazy {
    val codecs = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
    val main10Profiles = setOf(
        android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
        android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
        android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus,
    )
    codecs.any { info ->
        if (!info.isEncoder) return@any false
        val caps = runCatching { info.getCapabilitiesForType("video/hevc") }.getOrNull() ?: return@any false
        caps.profileLevels.any { pl -> pl.profile in main10Profiles }
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

/** Summary of the track layout in the input container. */
internal data class VideoTrackProbe(val hasVideoTrack: Boolean)

/**
 * Best-effort probe of the input container to determine whether a video track is present.
 * Returns `null` when the file can't be opened; callers should treat that as "unknown, let the
 * downstream pipeline report its own error" rather than a hard pre-flight rejection.
 */
@Suppress("TooGenericExceptionCaught")
internal fun probeVideoTracks(inputPath: String): VideoTrackProbe? = try {
    val extractor = MediaExtractor().apply { setDataSource(inputPath) }
    try {
        val hasVideo = (0 until extractor.trackCount).any { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        }
        VideoTrackProbe(hasVideoTrack = hasVideo)
    } finally {
        extractor.release()
    }
} catch (_: Throwable) {
    null
}

/**
 * Best-effort probe of a video's shortest edge via [MediaMetadataRetriever]. Returns `null`
 * when the file can't be opened or either dimension is missing / zero.
 */
@Suppress("TooGenericExceptionCaught")
internal fun probeVideoShortSide(inputPath: String): Int? = try {
    val mmr = MediaMetadataRetriever()
    try {
        mmr.setDataSource(inputPath)
        val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
        val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
        if (w != null && h != null) minOf(w, h) else null
    } finally {
        mmr.release()
    }
} catch (_: Throwable) {
    null
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
