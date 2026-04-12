package co.crackn.kompressor.video

import android.media.MediaMetadataRetriever
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import co.crackn.kompressor.CompressionResult
import co.crackn.kompressor.KompressorContext
import co.crackn.kompressor.awaitMedia3Export
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
internal class AndroidVideoCompressor : VideoCompressor {

    override val supportedInputFormats: Set<String> by lazy {
        collectCodecMimeTypes(isEncoder = false, mediaTypePrefix = "video/")
    }

    override val supportedOutputFormats: Set<String> by lazy {
        collectCodecMimeTypes(isEncoder = true, mediaTypePrefix = "video/")
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
        try {
            withContext(Dispatchers.Main) {
                val context = KompressorContext.appContext
                val transformer = buildTransformer(context, config)
                val item = buildEditedMediaItem(inputPath, config.maxResolution)
                awaitMedia3Export(transformer, item, outputPath, onProgress)
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
        return Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .build()
    }

    private fun buildEditedMediaItem(inputPath: String, maxResolution: MaxResolution): EditedMediaItem {
        val videoEffects = buildList {
            maxResolution.toPresentationOrNull()?.let(::add)
        }
        return EditedMediaItem.Builder(MediaItem.fromUri(toMediaItemUri(inputPath)))
            .setEffects(Effects(emptyList(), videoEffects))
            .build()
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/** Scale down to the shortest edge target; no effect when [MaxResolution.Original]. */
private fun MaxResolution.toPresentationOrNull(): Presentation? = when (this) {
    is MaxResolution.Original -> null
    is MaxResolution.Custom -> Presentation.createForShortSide(maxShortEdge)
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
