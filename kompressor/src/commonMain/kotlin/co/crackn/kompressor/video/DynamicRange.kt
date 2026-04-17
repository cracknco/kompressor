/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.video

import co.crackn.kompressor.ExperimentalKompressorApi

/**
 * Dynamic-range envelope of a video stream.
 *
 * [SDR] is the standard 8-bit BT.709 gamut used by every device since the '90s and is the
 * default for [VideoCompressionConfig.dynamicRange].
 *
 * [HDR10] is 10-bit BT.2020 primaries with the SMPTE ST 2084 (PQ) transfer function. It
 * preserves the wider luminance range of modern phone captures (iPhone XS+, Pixel 4+) but
 * requires a 10-bit codec: HDR10 is only valid in combination with [VideoCodec.HEVC]. The
 * [VideoCompressionConfig] constructor rejects HDR10 + H.264 at build time. HDR10 is gated
 * by [ExperimentalKompressorApi] because the tonemapping / metadata preservation contract
 * across devices is still being tuned pre-1.0 — see `docs/api-inventory.md`.
 *
 * Device support: callers can inspect
 * [co.crackn.kompressor.CodecSupport.supports10Bit] and
 * [co.crackn.kompressor.CodecSupport.supportsHdr] via
 * [co.crackn.kompressor.queryDeviceCapabilities] before picking [HDR10].
 */
public enum class DynamicRange {
    /** Standard dynamic range: 8-bit BT.709. Compatible with every codec. */
    SDR,

    /** High dynamic range: 10-bit BT.2020 + SMPTE ST 2084 (PQ). Requires HEVC. */
    @ExperimentalKompressorApi
    HDR10,
}
