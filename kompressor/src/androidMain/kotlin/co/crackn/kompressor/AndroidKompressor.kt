@file:Suppress("TooManyFunctions")

package co.crackn.kompressor

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import co.crackn.kompressor.audio.AndroidAudioCompressor
import co.crackn.kompressor.audio.AudioCompressor
import co.crackn.kompressor.image.AndroidImageCompressor
import co.crackn.kompressor.image.ImageCompressor
import co.crackn.kompressor.video.AndroidVideoCompressor
import co.crackn.kompressor.video.VideoCompressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AndroidKompressor : Kompressor {
    override val image: ImageCompressor by lazy { AndroidImageCompressor() }
    override val video: VideoCompressor by lazy { AndroidVideoCompressor() }
    override val audio: AudioCompressor by lazy { AndroidAudioCompressor() }

    private val capabilities: DeviceCapabilities by lazy { queryDeviceCapabilities() }

    override suspend fun probe(inputPath: String): Result<SourceMediaInfo> = withContext(Dispatchers.IO) {
        suspendRunCatching { probeAndroidSource(inputPath) }
    }

    override fun canCompress(info: SourceMediaInfo): Supportability =
        evaluateSupport(
            info = info,
            capabilities = capabilities,
            requiredOutputVideoMime = MIME_VIDEO_H264,
            requiredOutputAudioMime = MIME_AUDIO_AAC,
        )
}

/** Creates an Android [Kompressor] backed by MediaCodec and BitmapFactory. */
public actual fun createKompressor(): Kompressor = AndroidKompressor()

private fun probeAndroidSource(inputPath: String): SourceMediaInfo {
    val mmr = MediaMetadataRetriever()
    val extractor = MediaExtractor()
    try {
        mmr.setDataSource(inputPath)
        extractor.setDataSource(inputPath)
        return buildSourceInfo(mmr, extractor)
    } finally {
        runCatching { extractor.release() }
        mmr.release()
    }
}

private fun buildSourceInfo(mmr: MediaMetadataRetriever, extractor: MediaExtractor): SourceMediaInfo {
    val videoFormat = findTrack(extractor, "video/")
    val audioFormat = findTrack(extractor, "audio/")
    return SourceMediaInfo(
        containerMimeType = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
        videoCodec = videoFormat?.getString(MediaFormat.KEY_MIME),
        videoProfile = videoFormat?.let { readVideoProfileName(it) },
        videoLevel = videoFormat?.let { readVideoLevelName(it) },
        width = videoFormat?.getIntOrNull(MediaFormat.KEY_WIDTH),
        height = videoFormat?.getIntOrNull(MediaFormat.KEY_HEIGHT),
        bitDepth = videoFormat?.let { readBitDepth(it) },
        rotationDegrees = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull(),
        frameRate = videoFormat?.getFloatOrNull(MediaFormat.KEY_FRAME_RATE),
        bitrate = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull(),
        durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
        isHdr = videoFormat?.let { isHdrFormat(it) } ?: false,
        audioCodec = audioFormat?.getString(MediaFormat.KEY_MIME),
        audioSampleRate = audioFormat?.getIntOrNull(MediaFormat.KEY_SAMPLE_RATE),
        audioChannels = audioFormat?.getIntOrNull(MediaFormat.KEY_CHANNEL_COUNT),
    )
}

private fun findTrack(extractor: MediaExtractor, mimePrefix: String): MediaFormat? {
    for (i in 0 until extractor.trackCount) {
        val fmt = extractor.getTrackFormat(i)
        val mime = fmt.getString(MediaFormat.KEY_MIME)
        if (mime != null && mime.startsWith(mimePrefix)) return fmt
    }
    return null
}

private fun MediaFormat.getIntOrNull(key: String): Int? =
    if (containsKey(key)) getInteger(key) else null

private fun MediaFormat.getFloatOrNull(key: String): Float? {
    if (!containsKey(key)) return null
    // MediaFormat sometimes stores KEY_FRAME_RATE as Integer, sometimes as Float,
    // depending on the source (MediaExtractor vs. encoder output). getFloat()
    // throws ClassCastException on an Integer value, so try both.
    return runCatching { getFloat(key) }
        .recoverCatching { getInteger(key).toFloat() }
        .getOrNull()
}

private fun readVideoProfileName(fmt: MediaFormat): String? {
    val mime = fmt.getString(MediaFormat.KEY_MIME)
    val profile = fmt.getIntOrNull(MediaFormat.KEY_PROFILE)
    return when {
        mime == null || profile == null -> null
        else -> when (mime) {
            "video/hevc" -> hevcProbeProfileName(profile)
            "video/avc" -> avcProbeProfileName(profile)
            else -> null
        } ?: "Profile $profile"
    }
}

private fun hevcProbeProfileName(profile: Int): String? = when (profile) {
    HEVC_PROFILE_MAIN -> "Main"
    HEVC_PROFILE_MAIN10 -> "Main 10"
    HEVC_PROFILE_MAIN10_HDR10 -> "Main 10 HDR10"
    HEVC_PROFILE_MAIN10_HDR10_PLUS -> "Main 10 HDR10+"
    else -> null
}

private fun avcProbeProfileName(profile: Int): String? = when (profile) {
    AVC_PROFILE_BASELINE -> "Baseline"
    AVC_PROFILE_MAIN -> "Main"
    AVC_PROFILE_HIGH -> "High"
    AVC_PROFILE_HIGH10 -> "High 10"
    else -> null
}

private fun readVideoLevelName(fmt: MediaFormat): String? =
    fmt.getIntOrNull(MediaFormat.KEY_LEVEL)?.let { "Level $it" }

private fun readBitDepth(fmt: MediaFormat): Int? {
    // Multi-signal detection — profile (HEVC Main 10 / AVC High 10), the P010
    // color format, and (API 33+) the explicit KEY_BIT_DEPTH. Returning null
    // when every signal is absent lets evaluateSupport report Unknown rather
    // than a false-positive 8-bit.
    fmt.getIntOrNull("bit-depth")?.let { return it } // MediaFormat.KEY_BIT_DEPTH, API 33+.
    val mime = fmt.getString(MediaFormat.KEY_MIME)
    val profile = fmt.getIntOrNull(MediaFormat.KEY_PROFILE)
    val colorFormat = fmt.getIntOrNull(MediaFormat.KEY_COLOR_FORMAT)
    return when {
        colorFormat == COLOR_FORMAT_YUVP010 -> TEN_BIT
        mime == "video/hevc" && profile != null && isHevc10BitProfile(profile) -> TEN_BIT
        mime == "video/avc" && profile == AVC_PROFILE_HIGH10 -> TEN_BIT
        mime == "video/hevc" && profile == HEVC_PROFILE_MAIN -> EIGHT_BIT
        mime == "video/avc" && profile != null -> EIGHT_BIT
        else -> null // Unknown — let evaluateSupport surface uncertainty.
    }
}

private fun isHevc10BitProfile(profile: Int?): Boolean =
    profile == HEVC_PROFILE_MAIN10 ||
        profile == HEVC_PROFILE_MAIN10_HDR10 ||
        profile == HEVC_PROFILE_MAIN10_HDR10_PLUS

private fun isHdrFormat(fmt: MediaFormat): Boolean {
    val transfer = fmt.getIntOrNull(MediaFormat.KEY_COLOR_TRANSFER)
    val hdrTransfer = transfer == HDR_TRANSFER_ST2084 || transfer == HDR_TRANSFER_HLG
    // BT.2020 color standard + 10-bit is a strong HDR proxy when transfer is
    // absent (some MKV/Matroska packagings don't expose KEY_COLOR_TRANSFER).
    val bt2020TenBit = fmt.getIntOrNull(MediaFormat.KEY_COLOR_STANDARD) == COLOR_STANDARD_BT2020 &&
        readBitDepth(fmt) == TEN_BIT
    return hdrTransfer || bt2020TenBit
}

private const val EIGHT_BIT = 8
private const val TEN_BIT = 10
private const val HDR_TRANSFER_ST2084 = 6
private const val HDR_TRANSFER_HLG = 7
// MediaFormat.COLOR_STANDARD_BT2020 = 6; KEY_COLOR_STANDARD exposes this as raw int.
private const val COLOR_STANDARD_BT2020 = 6
// MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010 = 54 — 10-bit 4:2:0 planar.
private const val COLOR_FORMAT_YUVP010 = 54

// MediaCodecInfo.CodecProfileLevel constant values (duplicated here because the
// probe layer works with raw ints pulled from MediaFormat, not CodecProfileLevel).
private const val HEVC_PROFILE_MAIN = 1
private const val HEVC_PROFILE_MAIN10 = 2
private const val HEVC_PROFILE_MAIN10_HDR10 = 4096
private const val HEVC_PROFILE_MAIN10_HDR10_PLUS = 8192
private const val AVC_PROFILE_BASELINE = 1
private const val AVC_PROFILE_MAIN = 2
private const val AVC_PROFILE_HIGH = 8
private const val AVC_PROFILE_HIGH10 = 16
