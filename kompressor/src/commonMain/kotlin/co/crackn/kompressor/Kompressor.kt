/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

import co.crackn.kompressor.audio.AudioCompressor
import co.crackn.kompressor.image.ImageCompressor
import co.crackn.kompressor.video.VideoCompressor

/**
 * Main entry point for compression operations.
 *
 * Obtain an instance via [createKompressor]. Each compressor is lazily initialised —
 * only the ones you access are created.
 *
 * **Thread-safety:** implementations are stateless and thread-safe. Concurrent `compress()`,
 * [probe], and [canCompress] calls from different coroutines or OS processes on the same
 * instance are safe **provided every call writes to a distinct output path**. Concurrent
 * calls that share an output path produce undefined results (partial file, EncodingFailed,
 * or one writer silently losing the race). See `docs/threading-model.md` for the full
 * threading inventory and the inter-process coverage matrix.
 */
public interface Kompressor {
    /** Image compressor for JPEG (and future format) compression. */
    public val image: ImageCompressor

    /** Video compressor for H.264 (and future codec) compression. */
    public val video: VideoCompressor

    /** Audio compressor for AAC (and future codec) compression. */
    public val audio: AudioCompressor

    /**
     * Inspects [inputPath] and returns its media tracks' metadata.
     *
     * Useful for gating UX: probe first, show the user a preview, then call
     * [canCompress] to check device support before offering compression.
     *
     * @return [Result] wrapping [SourceMediaInfo] on success, or the platform
     *         error on failure (file missing, unreadable, etc.).
     */
    public suspend fun probe(inputPath: String): Result<SourceMediaInfo>

    /**
     * Returns whether the running device has the decoders (and required encoders)
     * needed to compress a source matching [info].
     *
     * This is advisory — it reflects the platform's reported capabilities and
     * does not guarantee the actual compress call will succeed (drivers can
     * still fail at runtime). But it catches the common "no decoder for this
     * profile" failure before the user ever kicks off a transcode.
     */
    public fun canCompress(info: SourceMediaInfo): Supportability
}

/**
 * Create a platform-specific [Kompressor] instance.
 *
 * On Android the [android.content.Context] is obtained automatically via AndroidX App Startup.
 *
 * **Thread-safety:** safe to call from any thread and from multiple processes concurrently.
 * Returns a fresh instance each call; share the instance within a process to benefit from
 * lazy sub-compressor init.
 */
public expect fun createKompressor(): Kompressor
