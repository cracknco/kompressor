/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

/**
 * Progress signal emitted during compression.
 *
 * @property phase The current phase of the compression pipeline.
 * @property fraction Progress within the current phase, in `[0.0, 1.0]`. Resets to 0 at each
 *   phase transition. For a global "how far am I through the overall compression" estimate,
 *   callers should map phases to their own weighting scheme (e.g. materialization ≈ 10%,
 *   compression ≈ 85%, finalization ≈ 5% for typical stream-backed sources).
 */
public data class CompressionProgress(
    public val phase: Phase,
    public val fraction: Float,
) {
    /**
     * Compression pipeline phase.
     *
     * For [MediaSource.Local.FilePath] or platform-native builders (`Uri`, `NSURL`, `PHAsset`)
     * inputs, only [COMPRESSING] and [FINALIZING_OUTPUT] are emitted. For
     * [MediaSource.Local.Stream] / [MediaSource.Local.Bytes] inputs on video/audio compressors,
     * [MATERIALIZING_INPUT] is emitted first.
     */
    public enum class Phase {
        /** Copying a `Stream` or `Bytes` source into a temp file. */
        MATERIALIZING_INPUT,

        /** Active decode + re-encode by Media3 Transformer (Android) or AVFoundation (iOS). */
        COMPRESSING,

        /** Muxer flush, metadata injection, or sink copy for stream destinations. */
        FINALIZING_OUTPUT,
    }
}
