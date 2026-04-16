/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package co.crackn.kompressor

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSError

/**
 * iOS-internal carrier exception that preserves the underlying [NSError] surfaced by AVFoundation
 * (`AVAssetWriter`, `AVAssetReader`, `AVAssetExportSession`) instead of stringifying it through
 * `localizedDescription`.
 *
 * The typed-error mappers (`mapToVideoError` / `mapToAudioError`) inspect the [nsError] domain
 * and code to classify failures into the public `VideoCompressionError` / `AudioCompressionError`
 * hierarchy. Without this carrier, the mapper would only see a generic `IllegalStateException`
 * and have to fall back to `Unknown` for every AVFoundation failure.
 */
internal class AVNSErrorException(
    /** The platform error reported by AVFoundation. */
    val nsError: NSError,
    /** Human-readable context (e.g. "AVAssetWriter failed"). */
    message: String,
) : RuntimeException("$message: ${nsError.localizedDescription} (${nsError.domain}#${nsError.code})")
