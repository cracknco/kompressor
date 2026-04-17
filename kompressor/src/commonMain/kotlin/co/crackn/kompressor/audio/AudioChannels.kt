/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.audio

import co.crackn.kompressor.ExperimentalKompressorApi

/**
 * Audio channel layout for compression output.
 *
 * Mono and stereo are universally supported. Surround layouts ([FIVE_POINT_ONE],
 * [SEVEN_POINT_ONE]) are supported on Android (every device exposing the standard
 * `audio/mp4a-latm` encoder), but **not on iOS** — AudioToolbox's AAC-LC encoder rejects
 * multichannel output at every bitrate on real hardware (empirically confirmed on A15 / iOS 18,
 * Device Farm run 24536970778). Requesting surround on iOS surfaces a typed
 * [AudioCompressionError.UnsupportedConfiguration]. Upmix (e.g. mono source → stereo output,
 * or stereo source → 5.1 output) is also not supported on either platform; only same-count
 * passthrough or downmix (e.g. 5.1 → stereo) is allowed.
 *
 * Surround entries are gated by [ExperimentalKompressorApi] because the BS.775-3 downmix
 * matrix and per-device encoder coverage are still being validated pre-1.0 — see
 * `docs/api-inventory.md`.
 */
enum class AudioChannels(
    /** Number of audio channels represented by this layout. */
    val count: Int,
) {
    /** Single channel. */
    MONO(1),

    /** Two channels (left + right). */
    STEREO(2),

    /**
     * 5.1 surround: front-left, front-right, front-center, LFE, back-left, back-right.
     * Channel order follows the ITU / ISO/IEC 23001-8 Mpeg5_1_D layout.
     */
    @ExperimentalKompressorApi
    FIVE_POINT_ONE(6),

    /**
     * 7.1 surround: front-left, front-right, front-center, LFE, back-left, back-right,
     * side-left, side-right.
     */
    @ExperimentalKompressorApi
    SEVEN_POINT_ONE(8),
}
