/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.sample.video

import co.crackn.kompressor.CompressionResult
import co.crackn.kompressor.io.CompressionProgress
import co.crackn.kompressor.video.MaxResolution

enum class VideoPresetOption { MESSAGING, HIGH_QUALITY, LOW_BANDWIDTH, SOCIAL_MEDIA, CUSTOM }

data class VideoCompressState(
    val selectedVideoPath: String? = null,
    val selectedFileName: String? = null,
    val compressedVideoPath: String? = null,
    val selectedPreset: VideoPresetOption = VideoPresetOption.MESSAGING,
    val customVideoBitrate: String = "1200",
    val customMaxResolution: MaxResolution = MaxResolution.HD_720,
    val customMaxFrameRate: Int = 30,
    val customKeyFrameInterval: Int = 2,
    val progress: Float = 0f,
    /**
     * Current pipeline phase reflected in the progress label — `MATERIALIZING_INPUT` shows
     * "Preparing…", `COMPRESSING` shows "Compressing", `FINALIZING_OUTPUT` shows "Finalising…".
     * Only consumed when [isCompressing] is `true`; the default is informational and unused at
     * rest.
     */
    val phase: CompressionProgress.Phase = CompressionProgress.Phase.COMPRESSING,
    val isCompressing: Boolean = false,
    val result: CompressionResult? = null,
    val error: String? = null,
    val errorKind: VideoErrorKind? = null,
)

/** High-level bucket of the last failure — used to pick a localized message. */
enum class VideoErrorKind { UnsupportedFormat, DecodingFailed, EncodingFailed, IoFailed, Other }
