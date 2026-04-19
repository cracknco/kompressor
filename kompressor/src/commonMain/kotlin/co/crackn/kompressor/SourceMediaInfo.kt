/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

/**
 * Describes the media tracks inside a source file. Fields are nullable because
 * not every field is available for every container / codec / platform probe API.
 *
 * [audioTrackCount] reflects the total number of audio tracks detected in the source container.
 * Callers can use it to decide whether to surface a multi-track picker before invoking audio
 * compression with a non-default [co.crackn.kompressor.audio.AudioCompressionConfig.audioTrackIndex].
 */
public data class SourceMediaInfo(
    /** Container MIME (e.g. `video/mp4`, `video/x-matroska`). */
    public val containerMimeType: String? = null,
    /** Video track codec MIME (e.g. `video/hevc`, `video/avc`). Null if no video track. */
    public val videoCodec: String? = null,
    /** Human-readable profile name (e.g. `"Main"`, `"Main 10"`, `"High"`). */
    public val videoProfile: String? = null,
    /** Human-readable level (e.g. `"5.0"`, `"4.1"`). */
    public val videoLevel: String? = null,
    /** Width in pixels (pre-rotation). */
    public val width: Int? = null,
    /** Height in pixels (pre-rotation). */
    public val height: Int? = null,
    /** Bits per sample (8 / 10 / 12). */
    public val bitDepth: Int? = null,
    /** Rotation metadata in degrees. */
    public val rotationDegrees: Int? = null,
    /** Frame rate in frames per second. */
    public val frameRate: Float? = null,
    /** Total bitrate across all tracks, in bits per second. */
    public val bitrate: Int? = null,
    /** Duration in milliseconds. */
    public val durationMs: Long? = null,
    /** HDR transfer function present (HDR10, HLG, ...). */
    public val isHdr: Boolean = false,
    /** Audio track codec MIME. Null if no audio track. */
    public val audioCodec: String? = null,
    /** Audio sample rate in Hz. */
    public val audioSampleRate: Int? = null,
    /** Audio channel count (1 = mono, 2 = stereo). */
    public val audioChannels: Int? = null,
    /**
     * Platform-level readability hint — true/false when the platform can
     * authoritatively say whether this file can be decoded. iOS populates this
     * via `AVAssetTrack.isPlayable`. Null on Android (the capability matrix is
     * the authoritative source there).
     */
    public val isPlayable: Boolean? = null,
    /**
     * Number of audio tracks detected in the source container. `0` when the source has no audio
     * track (or the probe couldn't enumerate tracks). Callers pass an index in `[0, audioTrackCount)`
     * as [co.crackn.kompressor.audio.AudioCompressionConfig.audioTrackIndex] to choose which track
     * to compress.
     *
     * Gated by [ExperimentalKompressorApi] together with
     * [co.crackn.kompressor.audio.AudioCompressionConfig.audioTrackIndex] — the multi-track
     * probe contract is still being stabilised pre-1.0.
     *
     * Appended last to keep the positional ABI of pre-existing fields stable for Java / non-Kotlin
     * KMP consumers — Kotlin named-arg callers are unaffected either way.
     */
    @property:ExperimentalKompressorApi
    public val audioTrackCount: Int = 0,
)

/**
 * Verdict returned by [Kompressor.canCompress] when matching a [SourceMediaInfo]
 * against the device's capability matrix.
 */
public sealed class Supportability {
    /** Every required decoder/encoder is available on this device. */
    public object Supported : Supportability() {
        override fun toString(): String = "Supported"
    }

    /**
     * One or more required decoders/encoders are definitely missing or the
     * source exceeds a hard capability limit (resolution, fps, bit depth, HDR).
     * [reasons] is a list of human-readable explanations suitable for developer
     * logs; apps should still present their own localized strings to users.
     */
    public data class Unsupported(
        /** Human-readable explanations of the hard blockers. */
        public val reasons: List<String>,
    ) : Supportability()

    /**
     * Some critical fact about the source couldn't be determined from the
     * probe (e.g. bit depth unreadable from a packaging quirk). The device
     * *might* be able to compress the file — apps should surface a warning
     * and allow the user to attempt compression. The real outcome is then
     * reported via the typed error hierarchy if it fails.
     */
    public data class Unknown(
        /** Human-readable explanations of what couldn't be verified. */
        public val reasons: List<String>,
    ) : Supportability()
}
