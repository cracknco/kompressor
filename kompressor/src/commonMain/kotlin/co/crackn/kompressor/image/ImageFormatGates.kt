/*
 * Copyright 2026 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(co.crackn.kompressor.ExperimentalKompressorApi::class)

package co.crackn.kompressor.image

/**
 * Platform-version gate decisions for image input/output formats. Pure functions on top of
 * [InputImageFormat] / [ImageFormat] + an integer API level, kept in commonMain so both
 * Android and iOS share one matrix and the logic is host-testable without a device/simulator.
 *
 * All minimum-API constants below reflect the **decision** recorded in CRA-72, not necessarily
 * the earliest theoretical version that could work. The decision trades narrow decoder coverage
 * for predictable behaviour across OEM builds: e.g. Android BitmapFactory accepts HEIC from API
 * 28 but coverage is spotty, so we gate HEIC input on API 30.
 */

/** Minimum Android API for HEIC/HEIF **input** (decode). */
internal const val HEIC_INPUT_MIN_API_ANDROID: Int = 30

/** Minimum Android API for AVIF **input** (decode). */
internal const val AVIF_INPUT_MIN_API_ANDROID: Int = 31

/**
 * Minimum Android API for AVIF **output** (encode). `Bitmap.CompressFormat.AVIF` was added in
 * Android 14 (API 34). Older OEM backports exist but aren't part of the platform guarantee.
 */
internal const val AVIF_OUTPUT_MIN_API_ANDROID: Int = 34

/**
 * HEIC output on Android is **not implemented** by this library in this release. The platform
 * has no stable `Bitmap.CompressFormat.HEIC` (HeifWriter is a separate, more involved API), so
 * Android always fails fast with `UnsupportedOutputFormat` pointing at the synthetic "API 999"
 * sentinel. iOS implements HEIC output normally.
 */
internal const val HEIC_OUTPUT_MIN_API_ANDROID: Int = Int.MAX_VALUE

/** Minimum iOS version for AVIF **input** (decode). */
internal const val AVIF_INPUT_MIN_IOS: Int = 16

/** Minimum iOS version for AVIF **output** (encode). */
internal const val AVIF_OUTPUT_MIN_IOS: Int = 16

/** Minimum iOS version for HEIC **output** (encode). iOS 11+, always satisfied at our iOS 15 floor. */
internal const val HEIC_OUTPUT_MIN_IOS: Int = 11

/**
 * WebP **output** on iOS is not wired through `CGImageDestination` in this release. Apple's
 * ImageIO decodes WebP from iOS 14+ but does not expose an `org.webmproject.webp` destination
 * type across the iOS 15 baseline, so we reject WebP output with a sentinel "requires iOS 999+".
 */
internal const val WEBP_OUTPUT_MIN_IOS: Int = Int.MAX_VALUE

internal const val PLATFORM_ANDROID: String = "android"
internal const val PLATFORM_IOS: String = "ios"

/** Sentinel meaning "no gate applies; supported at every version we ship". */
private const val NO_MIN_API: Int = 0

/**
 * Verdict for an **input** format given the current Android API level. Returns `null` when the
 * format is supported, or an [ImageCompressionError.UnsupportedInputFormat] ready to throw.
 */
internal fun androidInputGate(
    format: InputImageFormat,
    apiLevel: Int,
): ImageCompressionError.UnsupportedInputFormat? {
    val minApi = androidInputMinApi(format)
    return inputVerdict(format, PLATFORM_ANDROID, minApi, apiLevel)
}

/**
 * Verdict for an **output** format given the current Android API level. Returns `null` when the
 * format is supported, or an [ImageCompressionError.UnsupportedOutputFormat] ready to throw.
 */
internal fun androidOutputGate(
    format: ImageFormat,
    apiLevel: Int,
): ImageCompressionError.UnsupportedOutputFormat? {
    val minApi = androidOutputMinApi(format)
    return outputVerdict(format, PLATFORM_ANDROID, minApi, apiLevel)
}

/** Verdict for an **input** format given the current iOS major version. */
internal fun iosInputGate(
    format: InputImageFormat,
    iosVersion: Int,
): ImageCompressionError.UnsupportedInputFormat? {
    val minVersion = iosInputMinVersion(format)
    return inputVerdict(format, PLATFORM_IOS, minVersion, iosVersion)
}

/** Verdict for an **output** format given the current iOS major version. */
internal fun iosOutputGate(
    format: ImageFormat,
    iosVersion: Int,
): ImageCompressionError.UnsupportedOutputFormat? {
    val minVersion = iosOutputMinVersion(format)
    return outputVerdict(format, PLATFORM_IOS, minVersion, iosVersion)
}

private fun androidInputMinApi(format: InputImageFormat): Int = when (format) {
    InputImageFormat.HEIC, InputImageFormat.HEIF -> HEIC_INPUT_MIN_API_ANDROID
    InputImageFormat.AVIF -> AVIF_INPUT_MIN_API_ANDROID
    else -> NO_MIN_API
}

private fun androidOutputMinApi(format: ImageFormat): Int = when (format) {
    ImageFormat.JPEG, ImageFormat.WEBP -> NO_MIN_API
    ImageFormat.AVIF -> AVIF_OUTPUT_MIN_API_ANDROID
    ImageFormat.HEIC -> HEIC_OUTPUT_MIN_API_ANDROID
}

private fun iosInputMinVersion(format: InputImageFormat): Int = when (format) {
    // HEIC/HEIF decode is part of iOS since 11, below our iOS 15 floor — no gate needed.
    InputImageFormat.AVIF -> AVIF_INPUT_MIN_IOS
    else -> NO_MIN_API
}

private fun iosOutputMinVersion(format: ImageFormat): Int = when (format) {
    ImageFormat.JPEG -> NO_MIN_API
    ImageFormat.WEBP -> WEBP_OUTPUT_MIN_IOS
    ImageFormat.HEIC -> HEIC_OUTPUT_MIN_IOS
    ImageFormat.AVIF -> AVIF_OUTPUT_MIN_IOS
}

private fun inputVerdict(
    format: InputImageFormat,
    platform: String,
    minApi: Int,
    currentApi: Int,
): ImageCompressionError.UnsupportedInputFormat? =
    if (minApi == NO_MIN_API || currentApi >= minApi) {
        null
    } else {
        ImageCompressionError.UnsupportedInputFormat(
            format = format.id,
            platform = platform,
            minApi = minApi,
        )
    }

private fun outputVerdict(
    format: ImageFormat,
    platform: String,
    minApi: Int,
    currentApi: Int,
): ImageCompressionError.UnsupportedOutputFormat? =
    if (minApi == NO_MIN_API || currentApi >= minApi) {
        null
    } else {
        ImageCompressionError.UnsupportedOutputFormat(
            format = format.id,
            platform = platform,
            minApi = minApi,
        )
    }

/** Lower-case identifier used in typed errors and docs. */
private val ImageFormat.id: String
    get() = name.lowercase()
