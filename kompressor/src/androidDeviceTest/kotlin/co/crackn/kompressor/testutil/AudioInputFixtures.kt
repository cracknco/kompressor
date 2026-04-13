package co.crackn.kompressor.testutil

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Generates on-device audio fixtures in formats other than WAV, so tests can exercise the Media3
 * extractor path end-to-end without shipping binary assets in the repo.
 *
 * The approach: encode a WAV (produced by [WavGenerator]) through [MediaCodec] AAC, then mux
 * into the desired container. For MP4-with-video we additionally feed a trivial video track via
 * [Mp4Generator] so we can test [androidx.media3.transformer.EditedMediaItem.Builder.setRemoveVideo].
 */
object AudioInputFixtures {

    /**
     * Generate an `.m4a` (AAC-in-MP4) audio file from a freshly generated WAV. Used to exercise
     * the AAC→AAC passthrough fast path.
     */
    @Suppress("LongParameterList")
    fun createAacM4a(
        output: File,
        durationSeconds: Int = 2,
        sampleRate: Int = 44_100,
        channels: Int = 2,
        bitrate: Int = 128_000,
    ): File {
        val wavBytes = WavGenerator.generateWavBytes(durationSeconds, sampleRate, channels)
        val pcm = wavBytes.copyOfRange(WAV_HEADER_SIZE, wavBytes.size)
        encodeToAacContainer(
            output = output,
            pcm = pcm,
            sampleRate = sampleRate,
            channels = channels,
            bitrate = bitrate,
            outputFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
        )
        return output
    }

    /**
     * Generate an MP4 file containing **both** a video track and an audio track. Used to test
     * that the audio compressor extracts audio-only output when handed a mixed-media input.
     */
    fun createMp4WithVideoAndAudio(
        output: File,
        durationSeconds: Int = 2,
        sampleRate: Int = 44_100,
        channels: Int = 2,
    ): File {
        // Simple approach: generate audio-only m4a, then overwrite with a muxed video+audio MP4.
        // Building a combined muxer here duplicates Mp4Generator too much — instead we use the
        // existing Mp4Generator for a 1s video and a separate AAC fixture, then mux both tracks
        // into a single output via MediaMuxer.
        val tmpDir = output.parentFile ?: error("output has no parent dir")
        val audioFixture = File(tmpDir, "_mix_audio_${System.nanoTime()}.m4a")
        val videoFixture = File(tmpDir, "_mix_video_${System.nanoTime()}.mp4")
        try {
            createAacM4a(audioFixture, durationSeconds, sampleRate, channels, bitrate = 96_000)
            Mp4Generator.generateMp4(
                output = videoFixture,
                frameCount = durationSeconds * DEFAULT_FPS,
                fps = DEFAULT_FPS,
            )
            muxVideoAndAudio(videoFixture, audioFixture, output)
        } finally {
            audioFixture.delete()
            videoFixture.delete()
        }
        return output
    }

    private fun encodeToAacContainer(
        output: File,
        pcm: ByteArray,
        sampleRate: Int,
        channels: Int,
        bitrate: Int,
        outputFormat: Int,
    ) = encodeToContainer(
        output = output,
        pcm = pcm,
        mime = AAC_MIME,
        sampleRate = sampleRate,
        channels = channels,
        bitrate = bitrate,
        outputFormat = outputFormat,
        configureFormat = { format ->
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        },
    )

    /**
     * Shared PCM → encoded-container pipeline: drives a single MediaCodec encoder into a
     * MediaMuxer of the requested [outputFormat], with sample-accurate PTS tracked via
     * fractional accumulation so 44.1 kHz doesn't drift (`1_000_000 / 44_100 = 22 µs`
     * truncates — >1 % error accumulates in seconds).
     *
     * The [configureFormat] lambda lets each codec-specific wrapper attach its own extra
     * [MediaFormat] keys (AAC profile, AMR mode, …) without duplicating the buffer loop.
     */
    @Suppress("LongParameterList", "LongMethod")
    private fun encodeToContainer(
        output: File,
        pcm: ByteArray,
        mime: String,
        sampleRate: Int,
        channels: Int,
        bitrate: Int,
        outputFormat: Int,
        configureFormat: (MediaFormat) -> Unit = {},
    ) {
        val format = MediaFormat.createAudioFormat(mime, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            configureFormat(this)
        }
        val encoder = MediaCodec.createEncoderByType(mime)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val muxer = MediaMuxer(output.absolutePath, outputFormat)
        var muxerTrack = -1
        var muxerStarted = false
        val info = MediaCodec.BufferInfo()

        var srcOffset = 0
        val bytesPerFrame = channels * BYTES_PER_SAMPLE
        var ptsUs = 0L
        var remainderNumerator = 0L
        var inputDone = false

        try {
            while (true) {
                if (!inputDone) {
                    val idx = encoder.dequeueInputBuffer(TIMEOUT_US)
                    if (idx >= 0) {
                        val buf = encoder.getInputBuffer(idx) ?: error("encoder input null")
                        val remaining = pcm.size - srcOffset
                        if (remaining < bytesPerFrame) {
                            // Treat a sub-frame tail as end-of-stream rather than copying a
                            // half-frame — MediaCodec expects whole PCM frames.
                            encoder.queueInputBuffer(
                                idx, 0, 0, ptsUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            // Align toCopy down to a whole number of frames.
                            var toCopy = minOf(buf.capacity(), remaining)
                            toCopy -= toCopy % bytesPerFrame
                            buf.put(pcm, srcOffset, toCopy)
                            encoder.queueInputBuffer(idx, 0, toCopy, ptsUs, 0)
                            srcOffset += toCopy
                            val frames = (toCopy / bytesPerFrame).toLong()
                            val deltaNum = frames * 1_000_000L + remainderNumerator
                            ptsUs += deltaNum / sampleRate
                            remainderNumerator = deltaNum % sampleRate
                        }
                    }
                }

                val status = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    muxerTrack = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                } else if (status >= 0) {
                    val out = encoder.getOutputBuffer(status) ?: error("encoder output null")
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxerStarted) {
                        muxer.writeSampleData(muxerTrack, out, info)
                    }
                    encoder.releaseOutputBuffer(status, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            runCatching { encoder.stop() }
            encoder.release()
            runCatching { if (muxerStarted) muxer.stop() }
            muxer.release()
        }
    }

    private fun muxVideoAndAudio(video: File, audio: File, output: File) {
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val vExt = MediaExtractor().apply { setDataSource(video.absolutePath) }
        val aExt = MediaExtractor().apply { setDataSource(audio.absolutePath) }
        try {
            val vTrackSrc = findTrack(vExt, "video/")
            val aTrackSrc = findTrack(aExt, "audio/")
            val vTrackDst = muxer.addTrack(vExt.getTrackFormat(vTrackSrc))
            val aTrackDst = muxer.addTrack(aExt.getTrackFormat(aTrackSrc))
            muxer.start()
            copyTrack(vExt, vTrackSrc, muxer, vTrackDst)
            copyTrack(aExt, aTrackSrc, muxer, aTrackDst)
        } finally {
            vExt.release()
            aExt.release()
            runCatching { muxer.stop() }
            muxer.release()
        }
    }

    private fun findTrack(ext: MediaExtractor, prefix: String): Int {
        for (i in 0 until ext.trackCount) {
            val mime = ext.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        error("No track starting with $prefix")
    }

    /**
     * Generate an AMR-NB `.3gp` file. AMR-NB is 8 kHz mono 16-bit PCM fed into the always-
     * available `audio/3gpp` MediaCodec encoder, muxed into a 3GPP container. The input PCM
     * is generated directly at 8 kHz mono via [WavGenerator.generateWavBytes] — no
     * post-resampling step. Fidelity is irrelevant since we only round-trip to AAC.
     */
    fun createAmrNb(output: File, durationSeconds: Int = 1): File {
        val amrSampleRate = AMR_NB_SAMPLE_RATE
        val wavBytes = WavGenerator.generateWavBytes(durationSeconds, amrSampleRate, 1)
        val pcm = wavBytes.copyOfRange(WAV_HEADER_SIZE, wavBytes.size)
        encodeAmrNbToThreeGpp(
            output = output,
            pcm = pcm,
            sampleRate = amrSampleRate,
        )
        return output
    }

    private fun encodeAmrNbToThreeGpp(output: File, pcm: ByteArray, sampleRate: Int) =
        encodeToContainer(
            output = output,
            pcm = pcm,
            mime = AMR_NB_MIME,
            sampleRate = sampleRate,
            channels = 1,
            bitrate = AMR_NB_BITRATE,
            outputFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP,
        )

    private fun copyTrack(src: MediaExtractor, srcIdx: Int, dst: MediaMuxer, dstIdx: Int) {
        src.selectTrack(srcIdx)
        val buf = ByteBuffer.allocate(MUX_BUFFER_SIZE)
        val info = MediaCodec.BufferInfo()
        while (true) {
            buf.clear()
            val size = src.readSampleData(buf, 0)
            if (size < 0) break
            info.set(0, size, src.sampleTime, src.sampleFlags)
            dst.writeSampleData(dstIdx, buf, info)
            src.advance()
        }
        src.unselectTrack(srcIdx)
    }

    private const val AAC_MIME = "audio/mp4a-latm"
    private const val AMR_NB_MIME = "audio/3gpp"
    private const val AMR_NB_SAMPLE_RATE = 8_000
    private const val AMR_NB_BITRATE = 12_200 // the standard AMR-NB mode (MR122).
    private const val TIMEOUT_US = 10_000L
    private const val BYTES_PER_SAMPLE = 2
    private const val WAV_HEADER_SIZE = 44
    private const val MUX_BUFFER_SIZE = 256 * 1024
    private const val DEFAULT_FPS = 30
}
