package co.crackn.kompressor.video

import android.media.MediaCodecList
import android.media.MediaMetadataRetriever
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import co.crackn.kompressor.CompressionResult
import co.crackn.kompressor.KompressorContext
import co.crackn.kompressor.suspendRunCatching
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Android video compressor backed by [Transformer][androidx.media3.transformer.Transformer]
 * (Media3 1.10).
 *
 * Media3 Transformer handles codec selection, hardware/software fallback, HDR tone
 * mapping, rotation, and all the codec state-machine quirks that OEM MediaCodec
 * implementations throw at us. This keeps the library truly native (uses MediaCodec
 * under the hood) with zero bundled codec binaries.
 *
 * Platform failures are translated into the typed [VideoCompressionError]
 * hierarchy via [toVideoCompressionError] so that callers can `when`-branch on
 * actionable error subtypes (e.g. [VideoCompressionError.UnsupportedSourceFormat]
 * for the "device can't decode HEVC 10-bit" case) rather than a generic
 * `ExportException`.
 */
internal class AndroidVideoCompressor : VideoCompressor {

    override val supportedInputFormats: Set<String> by lazy {
        collectCodecMimeTypes(isEncoder = false, videoOnly = true)
    }

    override val supportedOutputFormats: Set<String> by lazy {
        collectCodecMimeTypes(isEncoder = true, videoOnly = true)
    }

    override suspend fun compress(
        inputPath: String,
        outputPath: String,
        config: VideoCompressionConfig,
        onProgress: suspend (Float) -> Unit,
    ): Result<CompressionResult> = suspendRunCatching {
        val startNanos = System.nanoTime()
        onProgress(0f)
        val inputSize = resolveInputSize(inputPath)

        deletingOutputOnFailure(outputPath) {
            runTransformer(inputPath, outputPath, config, onProgress)
        }

        onProgress(1f)
        val outputSize = File(outputPath).length()
        val durationMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLI
        CompressionResult(inputSize, outputSize, durationMs)
    }

    /**
     * Input size for `file://`, `content://`, or raw filesystem paths. `File(path).length()`
     * returns 0 for URIs, which would poison [CompressionResult.compressionRatio].
     */
    private fun resolveInputSize(inputPath: String): Long {
        if (inputPath.startsWith("content://")) {
            val uri = android.net.Uri.parse(inputPath)
            return runCatching {
                KompressorContext.appContext.contentResolver
                    .openFileDescriptor(uri, "r")
                    ?.use { it.statSize.coerceAtLeast(0L) }
                    ?: 0L
            }.getOrDefault(0L)
        }
        val path = if (inputPath.startsWith("file://")) {
            android.net.Uri.parse(inputPath).path ?: inputPath.removePrefix("file://")
        } else {
            inputPath
        }
        return runCatching { File(path).length() }.getOrDefault(0L)
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
                awaitExport(transformer, item, outputPath, onProgress)
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
        val uri = if (inputPath.startsWith("content://") || inputPath.startsWith("file://")) {
            inputPath
        } else {
            "file://$inputPath"
        }
        val videoEffects = buildList {
            maxResolution.toPresentationOrNull()?.let(::add)
        }
        return EditedMediaItem.Builder(MediaItem.fromUri(uri))
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
 * Build a short human-readable description of the source file (codec + resolution)
 * to embed in error messages. Best-effort — silently returns null if the retriever
 * can't open the file.
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

private fun collectCodecMimeTypes(isEncoder: Boolean, videoOnly: Boolean): Set<String> =
    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .asSequence()
        .filter { it.isEncoder == isEncoder }
        .flatMap { it.supportedTypes.asSequence() }
        .filter { !videoOnly || it.startsWith("video/") }
        .toSet()

/**
 * Wraps [Transformer]'s listener + progress-holder API in a cancellable suspend function.
 *
 * The Transformer must be started and polled on its application-looper thread
 * (we run the whole export on [Dispatchers.Main] via `runTransformer`).
 */
private suspend fun awaitExport(
    transformer: Transformer,
    item: EditedMediaItem,
    outputPath: String,
    onProgress: suspend (Float) -> Unit,
) = coroutineScope {
    val progressJob = launchProgressPoller(transformer, onProgress)
    try {
        suspendCancellableCoroutine<Unit> { continuation ->
            val listener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    transformer.removeListener(this)
                    // Guard against the cancel-race: invokeOnCancellation may have already
                    // cancelled the continuation before the Main-looper drains this callback.
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onError(
                    composition: Composition,
                    result: ExportResult,
                    exception: ExportException,
                ) {
                    transformer.removeListener(this)
                    if (continuation.isActive) continuation.resumeWithException(exception)
                }
            }
            continuation.invokeOnCancellation {
                transformer.removeListener(listener)
                transformer.cancel()
            }
            transformer.addListener(listener)
            transformer.start(item, outputPath)
        }
    } finally {
        progressJob.cancel()
    }
}

private fun CoroutineScope.launchProgressPoller(
    transformer: Transformer,
    onProgress: suspend (Float) -> Unit,
) = launch {
    val holder = androidx.media3.transformer.ProgressHolder()
    var lastReported = 0f
    while (isActive) {
        val state = transformer.getProgress(holder)
        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
            val progress = (holder.progress / PROGRESS_PERCENT_MAX).coerceIn(0f, 1f)
            if (progress - lastReported >= PROGRESS_REPORT_THRESHOLD) {
                onProgress(progress)
                lastReported = progress
            }
        }
        delay(PROGRESS_POLL_INTERVAL_MS)
    }
}

private const val PROGRESS_POLL_INTERVAL_MS = 100L
private const val PROGRESS_REPORT_THRESHOLD = 0.01f
private const val PROGRESS_PERCENT_MAX = 100f
