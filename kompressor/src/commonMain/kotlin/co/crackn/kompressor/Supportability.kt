package co.crackn.kompressor

/**
 * Shared matching logic that compares a [SourceMediaInfo] against a
 * [DeviceCapabilities] snapshot and reports whether compression is viable.
 *
 * Three possible verdicts:
 *  - [Supportability.Supported] — every required signal is present and matches.
 *  - [Supportability.Unsupported] — at least one hard blocker was found
 *    (missing decoder/encoder, 10-bit on 8-bit-only decoder, source beyond
 *    decoder's max resolution/fps).
 *  - [Supportability.Unknown] — information was missing that we couldn't
 *    verify one way or the other (e.g. bit depth not readable from probe,
 *    resolution unknown). The caller should warn the user but allow a retry.
 */
internal fun evaluateSupport(
    info: SourceMediaInfo,
    capabilities: DeviceCapabilities,
    requiredOutputVideoMime: String,
    requiredOutputAudioMime: String,
): Supportability {
    // Platform-level hard no from e.g. AVFoundation.isPlayable = false.
    if (info.isPlayable == false) {
        return Supportability.Unsupported(listOf("Platform reports file is not playable/decodable"))
    }

    val reasons = mutableListOf<String>()
    val uncertainties = mutableListOf<String>()
    info.videoCodec?.let { videoMime ->
        val (hard, soft) = videoTrackIssues(info, capabilities, videoMime, requiredOutputVideoMime)
        reasons += hard
        uncertainties += soft
    }
    info.audioCodec?.let { audioMime ->
        reasons += audioTrackReasons(capabilities, audioMime, requiredOutputAudioMime)
    }
    return when {
        reasons.isNotEmpty() -> Supportability.Unsupported(reasons)
        uncertainties.isNotEmpty() -> Supportability.Unknown(uncertainties)
        else -> Supportability.Supported
    }
}

/** Returns (hard failures, uncertainties). */
private fun videoTrackIssues(
    info: SourceMediaInfo,
    capabilities: DeviceCapabilities,
    videoMime: String,
    requiredOutputVideoMime: String,
): Pair<List<String>, List<String>> {
    val hard = mutableListOf<String>()
    val soft = mutableListOf<String>()
    val decoders = capabilities.video.filter {
        it.role == CodecSupport.Role.Decoder && it.mimeType.equals(videoMime, ignoreCase = true)
    }
    if (decoders.isEmpty()) {
        hard += "No decoder for $videoMime on this device"
    } else {
        val (h, s) = decoderVerdictAcrossCandidates(info, videoMime, decoders)
        hard += h
        soft += s
    }
    val encoder = capabilities.video.firstOrNull {
        it.role == CodecSupport.Role.Encoder && it.mimeType.equals(requiredOutputVideoMime, ignoreCase = true)
    }
    if (encoder == null) {
        hard += "No encoder for output $requiredOutputVideoMime on this device"
    }
    return hard to soft
}

/**
 * Devices can expose multiple decoders for the same MIME (e.g. HW + SW). Take the
 * LEAST severe verdict — if any decoder can handle the source, we're Supported.
 */
private fun decoderVerdictAcrossCandidates(
    info: SourceMediaInfo,
    videoMime: String,
    decoders: List<CodecSupport>,
): Pair<List<String>, List<String>> {
    val results = decoders.map { decoderIssues(info, videoMime, it) }
    return when {
        results.any { it.first.isEmpty() && it.second.isEmpty() } -> emptyList<String>() to emptyList()
        results.any { it.first.isEmpty() } -> emptyList<String>() to results.first { it.first.isEmpty() }.second
        else -> results.minBy { it.first.size } // Shortest hard list ≈ least severe.
    }
}

private fun decoderIssues(
    info: SourceMediaInfo,
    videoMime: String,
    decoder: CodecSupport,
): Pair<List<String>, List<String>> {
    val hard = mutableListOf<String>()
    val soft = mutableListOf<String>()
    val (bdHard, bdSoft) = bitDepthIssues(info, videoMime, decoder)
    hard += bdHard
    soft += bdSoft
    if (info.isHdr && !decoder.supportsHdr) {
        hard += "Decoder for $videoMime does not support HDR"
    }
    val (resHard, resSoft) = resolutionIssues(info, decoder)
    hard += resHard
    soft += resSoft
    val maxFps = decoder.maxFrameRate
    val fps = info.frameRate
    if (maxFps != null && fps != null && fps > maxFps + FPS_TOLERANCE) {
        hard += "Source ${fps.toInt()}fps exceeds decoder's max ${maxFps}fps"
    }
    return hard to soft
}

private fun bitDepthIssues(
    info: SourceMediaInfo,
    videoMime: String,
    decoder: CodecSupport,
): Pair<List<String>, List<String>> {
    val hard = mutableListOf<String>()
    val soft = mutableListOf<String>()
    when (info.bitDepth) {
        null -> {
            val maybeTenBit = maybe10BitProfile(videoMime, info.videoProfile)
            val isHevc = videoMime.equals("video/hevc", ignoreCase = true)
            when {
                maybeTenBit ->
                    soft += "Source bit depth unknown; profile \"${info.videoProfile}\" may require 10-bit decoding"
                isHevc && !decoder.supports10Bit ->
                    soft += "Source bit depth unknown for HEVC; decoder is 8-bit only"
            }
        }
        in TEN_BIT..MAX_BIT_DEPTH -> if (!decoder.supports10Bit) {
            hard += "Decoder for $videoMime does not support 10-bit"
        }
    }
    return hard to soft
}

private fun resolutionIssues(
    info: SourceMediaInfo,
    decoder: CodecSupport,
): Pair<List<String>, List<String>> {
    val hard = mutableListOf<String>()
    val soft = mutableListOf<String>()
    val max = decoder.maxResolution
    val w = info.width
    val h = info.height
    when {
        max != null && w != null && h != null -> {
            val (maxW, maxH) = max
            // Compare long/short edges independently — `w*h > maxW*maxH` would
            // let 2560x720 slip past a 1920x1080 cap since 2560·720 < 1920·1080
            // even though the width alone already exceeds 1920.
            val srcLong = maxOf(w, h)
            val srcShort = minOf(w, h)
            val maxLong = maxOf(maxW, maxH)
            val maxShort = minOf(maxW, maxH)
            if (srcLong > maxLong || srcShort > maxShort) {
                hard += "Source ${w}x$h exceeds decoder's max ${maxW}x$maxH"
            }
        }
        max == null && w != null && h != null && w >= HUGE_RES_THRESHOLD ->
            soft += "Decoder max resolution unknown; source is very large (${w}x$h)"
    }
    return hard to soft
}

private fun maybe10BitProfile(mime: String, profileName: String?): Boolean {
    if (profileName == null) return false
    val lc = profileName.lowercase()
    return when (mime.lowercase()) {
        "video/hevc" -> "main 10" in lc || "hdr" in lc
        "video/avc" -> "high 10" in lc
        "video/av1" -> "main 10" in lc
        "video/x-vnd.on2.vp9", "video/vp9" -> "profile 2" in lc || "profile 3" in lc || "10-bit" in lc
        else -> false
    }
}

private fun audioTrackReasons(
    capabilities: DeviceCapabilities,
    audioMime: String,
    requiredOutputAudioMime: String,
): List<String> {
    val reasons = mutableListOf<String>()
    val decoder = capabilities.audio.firstOrNull {
        it.role == CodecSupport.Role.Decoder && it.mimeType.equals(audioMime, ignoreCase = true)
    }
    if (decoder == null) {
        reasons += "No decoder for $audioMime on this device"
    }
    val encoder = capabilities.audio.firstOrNull {
        it.role == CodecSupport.Role.Encoder && it.mimeType.equals(requiredOutputAudioMime, ignoreCase = true)
    }
    if (encoder == null) {
        reasons += "No encoder for output $requiredOutputAudioMime on this device"
    }
    return reasons
}

private const val TEN_BIT = 10
private const val MAX_BIT_DEPTH = 16
private const val HUGE_RES_THRESHOLD = 4096
private const val FPS_TOLERANCE = 0.5f

/** MIME for H.264, our single supported output video codec (today). */
internal const val MIME_VIDEO_H264: String = "video/avc"

/** MIME for HEVC. */
internal const val MIME_VIDEO_HEVC: String = "video/hevc"

/** MIME for AAC, our single supported output audio codec. */
internal const val MIME_AUDIO_AAC: String = "audio/mp4a-latm"
