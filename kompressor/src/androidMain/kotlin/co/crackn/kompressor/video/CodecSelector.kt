package co.crackn.kompressor.video

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

/**
 * Selects the best available H.264 encoder for a given target resolution.
 *
 * Queries [MediaCodecList] to find hardware and software encoders, validates
 * resolution support via [MediaCodecInfo.VideoCapabilities], and provides a
 * software fallback when hardware codecs cannot handle the input.
 */
internal object CodecSelector {

    /**
     * Describes a selected H.264 encoder and its capabilities.
     *
     * @property codecName The codec name for [android.media.MediaCodec.createByCodecName].
     * @property useSurface Whether the codec supports Surface input (zero-copy pipeline).
     * @property maxWidth Maximum supported width.
     * @property maxHeight Maximum supported height.
     */
    data class EncoderChoice(
        val codecName: String,
        val useSurface: Boolean,
        val maxWidth: Int,
        val maxHeight: Int,
    )

    /**
     * Find the best H.264 encoder for the target dimensions.
     *
     * Prefers hardware encoders with Surface support for zero-copy performance.
     * Falls back to software encoders if no hardware encoder supports the resolution.
     *
     * @return the best available encoder, or `null` if no H.264 encoder exists.
     */
    fun findEncoder(width: Int, height: Int): EncoderChoice? {
        val candidates = collectEncoders()
        return candidates.firstOrNull { it.useSurface && it.maxWidth >= width && it.maxHeight >= height }
            ?: candidates.firstOrNull { !it.useSurface }
            ?: candidates.firstOrNull()
    }

    /**
     * Find a software-only H.264 encoder for fallback.
     *
     * Software encoders are slower (~3-5x) but handle any resolution and
     * never cause SIGSEGV from hardware resource exhaustion.
     */
    fun findSoftwareEncoder(): EncoderChoice? =
        collectEncoders().firstOrNull { !it.useSurface }

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
        val cappedW = roundToEven((targetW * scale).toInt())
        val cappedH = roundToEven((targetH * scale).toInt())
        return cappedW to cappedH
    }

    private fun collectEncoders(): List<EncoderChoice> {
        val codecs = MediaCodecList(MediaCodecList.ALL_CODECS)
        return codecs.codecInfos
            .filter { it.isEncoder && H264_MIME in it.supportedTypes }
            .mapNotNull { toEncoderChoice(it) }
    }

    private fun toEncoderChoice(info: MediaCodecInfo): EncoderChoice? {
        val caps = info.getCapabilitiesForType(H264_MIME)
        val videoCaps = caps?.videoCapabilities
        if (caps == null || videoCaps == null) return null
        val isSoftware = isSoftwareCodec(info)
        val supportsSurface = SURFACE_COLOR_FORMAT in caps.colorFormats
        return EncoderChoice(
            codecName = info.name,
            useSurface = supportsSurface && !isSoftware,
            maxWidth = videoCaps.supportedWidths.upper,
            maxHeight = videoCaps.supportedHeights.upper,
        )
    }

    /** Detect if a codec is software-based (works on API 24+). */
    fun isSoftware(info: MediaCodecInfo): Boolean = isSoftwareCodec(info)

    private fun isSoftwareCodec(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isSoftwareOnly
        } else {
            info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android.")
        }

    private fun roundToEven(value: Int): Int =
        if (value % 2 == 0) value else value + 1

    private const val H264_MIME = "video/avc"
    private const val SURFACE_COLOR_FORMAT =
        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
}
