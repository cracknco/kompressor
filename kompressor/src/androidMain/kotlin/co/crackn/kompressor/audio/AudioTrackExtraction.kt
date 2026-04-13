package co.crackn.kompressor.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import co.crackn.kompressor.KompressorContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.cancellation.CancellationException

/**
 * MediaExtractor-based helpers that let `AndroidAudioCompressor` honour
 * `AudioCompressionConfig.audioTrackIndex`. Media3 Transformer always picks the first audio
 * track it discovers, so when the caller wants a different track we pre-extract it into a
 * single-track temp MP4 and feed that to Transformer. The extraction is a pure bitstream copy
 * (no re-encode), so the source codec is preserved end-to-end and the AAC-passthrough fast path
 * still qualifies on eligible inputs.
 */

/**
 * Count how many audio tracks the container exposes. Returns `0` on any failure (including a
 * missing/unreadable file) so the compress path then surfaces a typed error via the bounds
 * check rather than a platform exception.
 */
@Suppress("TooGenericExceptionCaught")
internal fun countAudioTracks(inputPath: String): Int = try {
    openAudioExtractor(inputPath).use { extractor ->
        (0 until extractor.trackCount).count { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
    }
} catch (ce: CancellationException) {
    throw ce
} catch (_: Throwable) {
    0
}

/**
 * Extract the [audioTrackIndex]-th audio track from [inputPath] into a freshly-created MP4
 * temporary file by bitstream-copying samples (no decode/encode). Returns the newly-created
 * temp [File]; caller is responsible for deletion.
 */
internal fun extractAudioTrackToTempFile(inputPath: String, audioTrackIndex: Int): File {
    val tempFile = File.createTempFile("kompressor-audio-track-", ".mp4")
    // Why: We must clean up the freshly-created temp file on *any* failure — including
    // IllegalStateException from MediaMuxer, IOException from the extractor, or the "no such
    // track" precondition below. Narrowing the catch to each concrete type buries the cleanup
    // logic under a `when` ladder that would still miss future failure modes.
    @Suppress("TooGenericExceptionCaught")
    try {
        openAudioExtractor(inputPath).use { extractor ->
            val trackIndex = findAudioTrackIndexInContainer(extractor, audioTrackIndex)
                ?: error("No audio track at index $audioTrackIndex")
            val format = extractor.getTrackFormat(trackIndex)
            val muxer = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                val dstTrack = muxer.addTrack(format)
                muxer.start()
                extractor.selectTrack(trackIndex)
                copyTrackSamples(extractor, muxer, dstTrack)
            } finally {
                runCatching { muxer.stop() }
                muxer.release()
            }
        }
        return tempFile
    } catch (t: Throwable) {
        tempFile.delete()
        throw t
    }
}

private fun copyTrackSamples(extractor: MediaExtractor, muxer: MediaMuxer, dstTrack: Int) {
    val buf = ByteBuffer.allocate(EXTRACT_COPY_BUFFER_SIZE)
    val info = MediaCodec.BufferInfo()
    while (true) {
        buf.clear()
        val size = extractor.readSampleData(buf, 0)
        if (size < 0) break
        info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
        muxer.writeSampleData(dstTrack, buf, info)
        extractor.advance()
    }
}

/** Map the caller's "Nth audio track" (ignoring video/caption tracks) to a container track index. */
internal fun findAudioTrackIndexInContainer(extractor: MediaExtractor, audioTrackIndex: Int): Int? {
    var seen = 0
    for (i in 0 until extractor.trackCount) {
        val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
        val isAudio = mime != null && mime.startsWith("audio/")
        if (isAudio) {
            if (seen == audioTrackIndex) return i
            seen++
        }
    }
    return null
}

internal fun openAudioExtractor(inputPath: String): MediaExtractor = MediaExtractor().apply {
    // MediaExtractor.setDataSource(String) can't read content:// URIs (SAF). Use the
    // Context/Uri overload for content:// and file:// so the probe doesn't silently
    // fail and disable the AAC passthrough fast path for supported inputs.
    if (inputPath.startsWith("content://") || inputPath.startsWith("file://")) {
        setDataSource(KompressorContext.appContext, Uri.parse(inputPath), null)
    } else {
        setDataSource(inputPath)
    }
}

internal inline fun <R> MediaExtractor.use(block: (MediaExtractor) -> R): R {
    try {
        return block(this)
    } finally {
        release()
    }
}

private const val EXTRACT_COPY_BUFFER_SIZE = 1 shl 18 // 256 KiB — large enough for AAC access units.
