/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.sample.preview

/**
 * Aggregate state for the Preview screen — three sub-states, one per preview API surface
 * (`ImageCompressor.thumbnail`, `VideoCompressor.thumbnail`, `AudioCompressor.waveform`). The
 * sections are independent: the user can pick an image, a video, and an audio file in any order
 * without resetting the others. Each sub-state owns its own `error: String?`; the screen
 * observes them independently so a simultaneous failure in two sections produces two snackbars
 * rather than silently dropping one.
 */
data class PreviewState(
    val image: ImagePreview = ImagePreview(),
    val video: VideoPreview = VideoPreview(),
    val audio: AudioPreview = AudioPreview(),
)

/** Image-section state — encoded JPEG thumbnail produced by `ImageCompressor.thumbnail`. */
data class ImagePreview(
    val sourceFileName: String? = null,
    /** File path of the encoded thumbnail JPEG, ready to be loaded by `AsyncImage`. */
    val thumbnailPath: String? = null,
    val isComputing: Boolean = false,
    val error: String? = null,
)

/** Video-section state — frame extracted at [atMillis] via `VideoCompressor.thumbnail`. */
data class VideoPreview(
    val sourceFileName: String? = null,
    val sourcePath: String? = null,
    /** Probed source duration in milliseconds, used to bound the slider range. */
    val durationMs: Long = 0L,
    /** Current `atMillis` requested via the slider — drives the thumbnail re-extraction. */
    val atMillis: Long = 0L,
    /** File path of the encoded frame JPEG. */
    val thumbnailPath: String? = null,
    val isComputing: Boolean = false,
    val error: String? = null,
)

/** Audio-section state — normalized peak amplitudes produced by `AudioCompressor.waveform`. */
data class AudioPreview(
    val sourceFileName: String? = null,
    /** Normalized peak amplitudes in `[0f, 1f]`, one per bucket. `null` until extraction completes. */
    val peaks: FloatArray? = null,
    /** `[0f, 1f]` progress fraction for the in-flight `waveform()` call. */
    val progress: Float = 0f,
    val isComputing: Boolean = false,
    val error: String? = null,
) {
    // FloatArray data classes need explicit equals / hashCode because the default identity-based
    // contract on the array makes the synthetic equals worthless for state diffing.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioPreview) return false
        if (sourceFileName != other.sourceFileName) return false
        if (!peaksEqual(peaks, other.peaks)) return false
        if (progress != other.progress) return false
        if (isComputing != other.isComputing) return false
        if (error != other.error) return false
        return true
    }

    override fun hashCode(): Int {
        var result = sourceFileName?.hashCode() ?: 0
        result = 31 * result + (peaks?.contentHashCode() ?: 0)
        result = 31 * result + progress.hashCode()
        result = 31 * result + isComputing.hashCode()
        result = 31 * result + (error?.hashCode() ?: 0)
        return result
    }

    private companion object {
        fun peaksEqual(a: FloatArray?, b: FloatArray?): Boolean = when {
            a === b -> true
            a == null || b == null -> false
            else -> a.contentEquals(b)
        }
    }
}
