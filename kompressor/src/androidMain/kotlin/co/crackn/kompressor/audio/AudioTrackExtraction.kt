package co.crackn.kompressor.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import co.crackn.kompressor.KompressorContext
import java.io.File
import java.nio.ByteBuffer

/**
 * MediaExtractor-based helpers that let `AndroidAudioCompressor` honour
 * `AudioCompressionConfig.audioTrackIndex`. Media3 Transformer always picks the first audio
 * track it discovers, so when the caller wants a different track we pre-extract it into a
 * single-track temp MP4 and feed that to Transformer. The extraction is a pure bitstream copy
 * (no re-encode), so the source codec is preserved end-to-end and the AAC-passthrough fast path
 * still qualifies on eligible inputs.
 */

internal fun MediaExtractor.countAudioTracks(): Int =
    (0 until trackCount).count { i ->
        getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
    }

/**
 * Extract the [audioTrackIndex]-th audio track from [inputPath] into a freshly-created MP4
 * temporary file by bitstream-copying samples (no decode/encode). Returns the newly-created
 * temp [File]; caller is responsible for deletion.
 *
 * The file is created under the app's cache directory rather than `java.io.tmpdir` so that the
 * OS can reclaim the space when the device is under storage pressure (Android's tmpdir is the
 * same directory but documented contracts differ). For multi-GB sources the bitstream copy is
 * still essentially full-file-sized — caller must call `delete()` on success too.
 */
internal fun extractAudioTrackToTempFile(inputPath: String, audioTrackIndex: Int): File {
    val cacheDir = KompressorContext.appContext.cacheDir
    val tempFile = File.createTempFile("kompressor-audio-track-", ".mp4", cacheDir)
    // Why: We must clean up the freshly-created temp file on *any* failure — including
    // IllegalStateException from MediaMuxer, IOException from the extractor, or the "no such
    // track" precondition below. Narrowing the catch to each concrete type buries the cleanup
    // logic under a `when` ladder that would still miss future failure modes.
    @Suppress("TooGenericExceptionCaught")
    try {
        openAudioExtractor(inputPath).useThenRelease { extractor ->
            val trackIndex = findAudioTrackIndexInContainer(extractor, audioTrackIndex)
                ?: error("No audio track at index $audioTrackIndex")
            val format = extractor.getTrackFormat(trackIndex)
            requireMp4MuxableCodec(format)
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

/**
 * Validate that the selected track can be bitstream-copied into an MP4 container. Attempting
 * `MediaMuxer.addTrack()` with a non-MP4-remuxable codec (Opus, Vorbis, FLAC, raw PCM, …) throws
 * `IllegalStateException` deep inside the muxer; pre-validating lets the caller receive a typed,
 * actionable error instead of a platform failure. Transcode fallback (decode → AAC) is out of
 * scope for the multi-track-selection feature — MP4-remuxable inputs cover the vast majority of
 * multi-track audio containers in practice.
 */
private fun requireMp4MuxableCodec(format: MediaFormat) {
    val mime = format.getString(MediaFormat.KEY_MIME)
    if (mime == null || mime !in MP4_MUXABLE_AUDIO_MIMES) {
        throw AudioCompressionError.UnsupportedSourceFormat(
            "Multi-track audio selection does not support codec '$mime' " +
                "(MP4 container supports only: $MP4_MUXABLE_AUDIO_MIMES)",
        )
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

/**
 * Run [block] with this [MediaExtractor] and call [MediaExtractor.release] on exit. Named
 * `useThenRelease` (rather than shadowing `kotlin.use`) because [MediaExtractor] is not
 * `Closeable` — keeping the name distinct prevents IDE auto-imports from picking the wrong
 * helper if `MediaExtractor` ever grows a `Closeable` overload upstream.
 */
internal inline fun <R> MediaExtractor.useThenRelease(block: (MediaExtractor) -> R): R {
    try {
        return block(this)
    } finally {
        release()
    }
}

private const val EXTRACT_COPY_BUFFER_SIZE = 1 shl 18 // 256 KiB — large enough for AAC access units.

// MP4 container audio codecs supported by `android.media.MediaMuxer` with MUXER_OUTPUT_MPEG_4.
// Source: Android MediaMuxer docs (AAC, AMR-NB, AMR-WB are the only officially-supported entries).
private val MP4_MUXABLE_AUDIO_MIMES = setOf(
    "audio/mp4a-latm", // AAC
    "audio/3gpp", // AMR-NB
    "audio/amr-wb", // AMR-WB
)
