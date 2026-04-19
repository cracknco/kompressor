/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.sample.image

import co.crackn.kompressor.CompressionResult

enum class PresetOption { THUMBNAIL, WEB, HIGH_QUALITY, CUSTOM }

data class ImageCompressState(
    val selectedImagePath: String? = null,
    val selectedFileName: String? = null,
    val compressedImagePath: String? = null,
    val selectedPreset: PresetOption = PresetOption.WEB,
    val customQuality: Int = 80,
    val customMaxWidth: String = "",
    val customMaxHeight: String = "",
    val isCompressing: Boolean = false,
    val result: CompressionResult? = null,
    val error: String? = null,
)
