/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.image

import co.crackn.kompressor.ExperimentalKompressorApi

/**
 * Supported image output formats.
 *
 * Experimental entries are gated by [ExperimentalKompressorApi] because their availability depends
 * on platform versions still being tuned (Android's `Bitmap.CompressFormat.AVIF` lands in API 34,
 * iOS AVIF encode requires iOS 16+; HEIC output on Android lacks a stable `Bitmap.CompressFormat`
 * entry and iOS HEIC encode is iOS 11+). On devices that can't honour the requested output format,
 * the compressor fails with [ImageCompressionError.UnsupportedOutputFormat] carrying the minimum
 * required platform version so the caller can catch-and-retry with a different format.
 */
public enum class ImageFormat {
    /** Lossy compression. Best for photos. Quality parameter applies. Always available. */
    JPEG,

    /**
     * Lossy/lossless compression. Smaller than JPEG at equivalent quality. Quality parameter
     * applies (ignored for lossless mode on platforms that support it). Always available on
     * Android (API 24+) and iOS 15+.
     */
    WEBP,

    /**
     * HEIC (High Efficiency Image Coding). Lossy. ~50% smaller than JPEG at equivalent quality.
     *
     * **Availability:**
     * - Android: not implemented as an output on Android in this release (no stable
     *   `Bitmap.CompressFormat.HEIC`). Requesting HEIC output on Android returns
     *   [ImageCompressionError.UnsupportedOutputFormat].
     * - iOS 11.0+ via `CGImageDestination` with `public.heic`.
     */
    @ExperimentalKompressorApi
    HEIC,

    /**
     * AVIF (AV1 Image File Format). Lossy. Best compression of the four at equivalent quality.
     *
     * **Availability:**
     * - Android API 34+ via `Bitmap.CompressFormat.AVIF`.
     * - iOS 16.0+ via `CGImageDestination` with `public.avif`.
     *
     * Requests on older platforms fail with [ImageCompressionError.UnsupportedOutputFormat].
     */
    @ExperimentalKompressorApi
    AVIF,
}
