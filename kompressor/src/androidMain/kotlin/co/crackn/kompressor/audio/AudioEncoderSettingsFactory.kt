/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.audio

import android.content.Context
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Codec
import androidx.media3.transformer.DefaultEncoderFactory

/**
 * Pure factory that produces an [AudioEncoderSettings] instance from an [AudioCompressionConfig].
 * Extracted so the bitrate-plumbing is covered by a host test (no device, no Context).
 */
internal fun buildAudioEncoderSettings(config: AudioCompressionConfig): AudioEncoderSettings =
    AudioEncoderSettings.Builder()
        .setBitrate(config.bitrate)
        .build()

/**
 * Builds the [Codec.EncoderFactory] used by the Media3 [androidx.media3.transformer.Transformer]
 * for audio transcoding. Thin wrapper around [buildAudioEncoderSettings] + [DefaultEncoderFactory]
 * — [Context] is only required to resolve hardware encoder capabilities.
 */
internal fun buildAudioEncoderFactory(
    context: Context,
    config: AudioCompressionConfig,
): Codec.EncoderFactory =
    DefaultEncoderFactory.Builder(context)
        .setRequestedAudioEncoderSettings(buildAudioEncoderSettings(config))
        .build()
