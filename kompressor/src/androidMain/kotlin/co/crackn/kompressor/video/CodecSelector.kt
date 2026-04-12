package co.crackn.kompressor.video

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

/**
 * Selects the best available H.264 encoder for a given target resolution,
 * and finds software fallback decoders when hardware codecs fail.
 *
 * Codec enumeration via [MediaCodecList] is expensive (binder IPC on some OEMs),
 * so results are memoized for the lifetime of the process — codec availability
 * is static once the device has booted.
 */
internal object CodecSelector {

    data class EncoderChoice(
        val codecName: String,
        val useSurface: Boolean,
        val maxWidth: Int,
        val maxHeight: Int,
    )

    private val h264Encoders: List<EncoderChoice> by lazy { enumerateH264Encoders() }

    /**
     * Find the best H.264 encoder for the target dimensions.
     *
     * Prefers hardware encoders with Surface support. Falls back to software
     * encoders if no hardware encoder supports the resolution.
     */
    fun findEncoder(width: Int, height: Int): EncoderChoice? =
        h264Encoders.firstOrNull { it.useSurface && it.maxWidth >= width && it.maxHeight >= height }
            ?: h264Encoders.firstOrNull { !it.useSurface }
            ?: h264Encoders.firstOrNull()

    /** Find a software-only H.264 encoder for fallback. */
    fun findSoftwareEncoder(): EncoderChoice? =
        h264Encoders.firstOrNull { !it.useSurface }

    /** Find a software decoder name for the given MIME type. */
    fun findSoftwareDecoder(mime: String): String? =
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .firstOrNull { !it.isEncoder && mime in it.supportedTypes && isSoftware(it) }
            ?.name

    /**
     * Cap target dimensions to the encoder's supported range.
     *
     * If the target exceeds the encoder's maximum, scales down proportionally
     * while preserving aspect ratio and rounding to even numbers.
     */
    fun capToEncoderLimits(targetW: Int, targetH: Int, encoder: EncoderChoice): Pair<Int, Int> {
        if (targetW <= encoder.maxWidth && targetH <= encoder.maxHeight) {
            return targetW to targetH
        }
        val scaleW = encoder.maxWidth.toFloat() / targetW
        val scaleH = encoder.maxHeight.toFloat() / targetH
        val scale = minOf(scaleW, scaleH, 1f)
        return roundToEven((targetW * scale).toInt()) to roundToEven((targetH * scale).toInt())
    }

    fun isSoftware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isSoftwareOnly
        } else {
            info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android.")
        }

    private fun enumerateH264Encoders(): List<EncoderChoice> =
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { it.isEncoder && H264_MIME in it.supportedTypes }
            .mapNotNull { toEncoderChoice(it) }

    private fun toEncoderChoice(info: MediaCodecInfo): EncoderChoice? {
        val caps = info.getCapabilitiesForType(H264_MIME)
        val videoCaps = caps?.videoCapabilities
        if (caps == null || videoCaps == null) return null
        val supportsSurface = SURFACE_COLOR_FORMAT in caps.colorFormats
        return EncoderChoice(
            codecName = info.name,
            useSurface = supportsSurface && !isSoftware(info),
            maxWidth = videoCaps.supportedWidths.upper,
            maxHeight = videoCaps.supportedHeights.upper,
        )
    }

    private const val H264_MIME = "video/avc"
    private const val SURFACE_COLOR_FORMAT =
        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
}
