/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

import okio.Source

/**
 * Source of media data for compression.
 *
 * Today only [Local] variants exist (filesystem, in-memory, streams). A future `Remote` sibling
 * may be added to support streaming from remote URLs without prior download — consumers who
 * exhaust-match `MediaSource` today will need a new branch when a remote variant ships.
 *
 * Builders for platform-specific sources (`Uri`, `NSURL`, `PHAsset`, `NSData`, streams) are
 * declared in platform source sets; all return [Local] subtypes.
 *
 * See `docs/concepts/io-model.md` (ships with T8) for the full I/O model overview.
 */
public sealed interface MediaSource {

    /**
     * Media sources backed by local storage, device filesystem, or app memory.
     *
     * **Not sealed** — platform source sets (`androidMain`, `iosMain`) add their own wrappers for
     * native input handles (`content://` URIs, `ParcelFileDescriptor`, `NSURL`, `PHAsset`, …).
     * Kotlin's sealed-hierarchy-per-module rule prohibits cross-module extension, so the base is
     * left open; `commonMain` callers that exhaustive-match on `Local` must include an `else`
     * branch to handle the platform wrappers — see `AndroidMediaDispatch.toAndroidInputPath`
     * / `IosMediaDispatch.toIosInputPath` for the canonical pattern.
     */
    public interface Local : MediaSource {

        /**
         * Absolute filesystem path to the source media file. Simplest ergonomic form.
         *
         * @property path Absolute filesystem path. Relative paths produce platform-dependent
         *   behavior and should be avoided.
         */
        public data class FilePath(public val path: String) : Local

        /**
         * Streamable source via okio. Materialized to a temp file for video/audio pipelines
         * (Media3 Transformer / AVFoundation require seekable file inputs). Images short-circuit
         * to direct decode without temp file.
         *
         * Declared as a plain `class` rather than `data class` because the underlying
         * [okio.Source] is a stateful resource handle: `equals`/`hashCode` by identity (the
         * default) is the only defensible semantic — two distinct sources can never be
         * "equal" even if they currently hold the same bytes, and `copy()` semantics would
         * silently share a consumed resource between instances. Sibling on
         * [MediaDestination.Local.Stream] [CRA-90 review].
         *
         * @property source The okio [Source] to read from.
         * @property sizeHint Optional total byte count for progress estimation during
         *   materialization. Pass `null` when unknown; progress fraction will stay at 0 during
         *   the materialization phase. Negative values are rejected at construction time.
         * @property closeOnFinish If `true` (default), Kompressor calls `source.close()` at the
         *   end of compression (success or failure). Set to `false` when the stream lifecycle
         *   is externally managed (e.g. shared with an uploader running in parallel).
         *
         * @throws IllegalArgumentException if [sizeHint] is negative.
         */
        public class Stream(
            public val source: Source,
            public val sizeHint: Long? = null,
            public val closeOnFinish: Boolean = true,
        ) : Local {
            init {
                require(sizeHint == null || sizeHint >= 0) {
                    "sizeHint must be >= 0 when provided, was $sizeHint"
                }
            }

            override fun toString(): String =
                "MediaSource.Local.Stream(source=$source, sizeHint=$sizeHint, closeOnFinish=$closeOnFinish)"
        }

        /**
         * In-memory byte buffer. Safe for images up to ~50 MB.
         *
         * **For video/audio, prefer [FilePath] or [Stream]** — using [Bytes] for a 500 MB video
         * forces the entire content into memory and causes OOM on mid-range Android devices
         * (heap limit ≈ 256 MB). Kompressor emits a WARN log when [Bytes] is used for video/audio.
         *
         * The referenced [bytes] array MUST NOT be mutated during the `compress()` call.
         * Kompressor keeps a reference (no defensive copy).
         *
         * `equals` / `hashCode` are overridden to compare by content (via
         * [ByteArray.contentEquals] / [ByteArray.contentHashCode]) — the only deliberate
         * deviation from pure data-class semantics in this hierarchy, required because Kotlin's
         * default array equality is identity-based.
         *
         * @property bytes The in-memory byte buffer backing the source.
         */
        public data class Bytes(public val bytes: ByteArray) : Local {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Bytes) return false
                return bytes.contentEquals(other.bytes)
            }

            override fun hashCode(): Int = bytes.contentHashCode()

            /**
             * Stable, content-free summary — the data-class default prints the raw
             * `ByteArray` identity string (`[B@abcdef12`) which is useless for debug and
             * inconsistent with the content-based [equals]. Does not include contents to
             * avoid leaking PII in logs [CRA-90 review].
             */
            override fun toString(): String = "MediaSource.Local.Bytes(size=${bytes.size})"
        }
    }

    /** Companion namespace reserved for future platform-agnostic factory helpers. */
    public companion object
}
