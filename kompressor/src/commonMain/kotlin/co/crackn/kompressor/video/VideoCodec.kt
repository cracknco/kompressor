/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.video

/** Supported video codecs for compression. */
enum class VideoCodec {
    /** H.264 / AVC — widely supported hardware-accelerated codec on both Android and iOS. 8-bit only. */
    H264,

    /**
     * HEVC / H.265 — required for [DynamicRange.HDR10] output. Hardware-accelerated on iOS ≥ 11
     * and on Android devices whose `MediaCodecList` advertises an HEVC encoder (most modern
     * devices). Software-encode fallback is used when no hardware encoder is available but is
     * significantly slower.
     */
    HEVC,
}
