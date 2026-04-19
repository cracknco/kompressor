/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

@file:Suppress("TooManyFunctions")

package co.crackn.kompressor

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat

/**
 * Pure int→string / int→boolean mapping helpers for Android's
 * [CodecProfileLevel] constants, extracted so they're host-testable.
 *
 * These operate on the symbolic profile values reported by [MediaCodecList]
 * via [CodecProfileLevel]. The parallel probe-side helpers in
 * [AndroidProbeProfileNames] work on raw ints pulled from [MediaFormat]
 * which may differ on some OEMs.
 */

/** Humanises [CodecProfileLevel] for [mime]. Returns an empty string when unknown so callers can filter it out. */
internal fun humanProfileName(mime: String, profile: Int): String = when (mime) {
    MediaFormat.MIMETYPE_VIDEO_AVC -> avcProfileName(profile)
    MediaFormat.MIMETYPE_VIDEO_HEVC -> hevcProfileName(profile)
    MediaFormat.MIMETYPE_VIDEO_VP9 -> vp9ProfileName(profile)
    MediaFormat.MIMETYPE_VIDEO_AV1 -> av1ProfileName(profile)
    else -> ""
}

internal fun avcProfileName(profile: Int): String = when (profile) {
    CodecProfileLevel.AVCProfileBaseline -> "Baseline"
    CodecProfileLevel.AVCProfileMain -> "Main"
    CodecProfileLevel.AVCProfileExtended -> "Extended"
    CodecProfileLevel.AVCProfileHigh -> "High"
    CodecProfileLevel.AVCProfileHigh10 -> "High 10"
    CodecProfileLevel.AVCProfileHigh422 -> "High 4:2:2"
    CodecProfileLevel.AVCProfileHigh444 -> "High 4:4:4"
    CodecProfileLevel.AVCProfileConstrainedBaseline -> "Constrained Baseline"
    CodecProfileLevel.AVCProfileConstrainedHigh -> "Constrained High"
    else -> ""
}

internal fun hevcProfileName(profile: Int): String = when (profile) {
    CodecProfileLevel.HEVCProfileMain -> "Main"
    CodecProfileLevel.HEVCProfileMain10 -> "Main 10"
    CodecProfileLevel.HEVCProfileMainStill -> "Main Still"
    CodecProfileLevel.HEVCProfileMain10HDR10 -> "Main 10 HDR10"
    CodecProfileLevel.HEVCProfileMain10HDR10Plus -> "Main 10 HDR10+"
    else -> ""
}

internal fun vp9ProfileName(profile: Int): String = when (profile) {
    CodecProfileLevel.VP9Profile0 -> "Profile 0"
    CodecProfileLevel.VP9Profile1 -> "Profile 1"
    CodecProfileLevel.VP9Profile2 -> "Profile 2 (10-bit)"
    CodecProfileLevel.VP9Profile3 -> "Profile 3 (10-bit 4:4:4)"
    CodecProfileLevel.VP9Profile2HDR -> "Profile 2 HDR"
    CodecProfileLevel.VP9Profile3HDR -> "Profile 3 HDR"
    CodecProfileLevel.VP9Profile2HDR10Plus -> "Profile 2 HDR10+"
    CodecProfileLevel.VP9Profile3HDR10Plus -> "Profile 3 HDR10+"
    else -> ""
}

internal fun av1ProfileName(profile: Int): String = when (profile) {
    CodecProfileLevel.AV1ProfileMain8 -> "Main 8-bit"
    CodecProfileLevel.AV1ProfileMain10 -> "Main 10-bit"
    CodecProfileLevel.AV1ProfileMain10HDR10 -> "Main 10 HDR10"
    CodecProfileLevel.AV1ProfileMain10HDR10Plus -> "Main 10 HDR10+"
    else -> ""
}

internal fun isTenBitProfile(mime: String, profile: Int): Boolean = when (mime) {
    MediaFormat.MIMETYPE_VIDEO_HEVC -> profile in HEVC_TEN_BIT_PROFILES
    // AVC High 10 is 10-bit. High 4:2:2 / High 4:4:4 support 8/10/12-bit — treat as
    // possibly-10-bit so supports10Bit isn't a false negative on those profiles.
    MediaFormat.MIMETYPE_VIDEO_AVC -> profile in AVC_TEN_BIT_CAPABLE_PROFILES
    MediaFormat.MIMETYPE_VIDEO_VP9 -> profile in VP9_TEN_BIT_PROFILES
    MediaFormat.MIMETYPE_VIDEO_AV1 -> profile in AV1_TEN_BIT_PROFILES
    else -> false
}

private val HEVC_TEN_BIT_PROFILES = setOf(
    CodecProfileLevel.HEVCProfileMain10,
    CodecProfileLevel.HEVCProfileMain10HDR10,
    CodecProfileLevel.HEVCProfileMain10HDR10Plus,
)

private val AVC_TEN_BIT_CAPABLE_PROFILES = setOf(
    CodecProfileLevel.AVCProfileHigh10,
    CodecProfileLevel.AVCProfileHigh422,
    CodecProfileLevel.AVCProfileHigh444,
)

private val VP9_TEN_BIT_PROFILES = setOf(
    CodecProfileLevel.VP9Profile2,
    CodecProfileLevel.VP9Profile3,
    CodecProfileLevel.VP9Profile2HDR,
    CodecProfileLevel.VP9Profile3HDR,
    CodecProfileLevel.VP9Profile2HDR10Plus,
    CodecProfileLevel.VP9Profile3HDR10Plus,
)

private val AV1_TEN_BIT_PROFILES = setOf(
    CodecProfileLevel.AV1ProfileMain10,
    CodecProfileLevel.AV1ProfileMain10HDR10,
    CodecProfileLevel.AV1ProfileMain10HDR10Plus,
)
