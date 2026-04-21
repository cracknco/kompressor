/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

import okio.Sink

/**
 * Destination for compressed output.
 *
 * Today only [Local] variants exist (filesystem, streams). See [MediaSource] for the future
 * `Remote` extension strategy.
 */
public sealed interface MediaDestination {

    /** Media destinations backed by local storage or writable streams. */
    public sealed interface Local : MediaDestination {

        /**
         * Absolute filesystem path where the compressed output will be written.
         *
         * @property path Absolute filesystem path to the output file. Any intermediate
         *   directories must already exist; Kompressor does not create them.
         */
        public data class FilePath(public val path: String) : Local

        /**
         * Writable okio sink. Images stream directly into the sink. Video/audio are first
         * written to a temp file (Media3 Transformer / AVAssetWriter require file outputs),
         * then the temp file is copied into the sink — double I/O cost.
         *
         * @property sink The okio [Sink] to write to.
         * @property closeOnFinish If `true` (default), Kompressor calls `sink.close()` at the
         *   end of compression (success or failure). Set to `false` when the sink lifecycle
         *   is externally managed.
         */
        public data class Stream(
            public val sink: Sink,
            public val closeOnFinish: Boolean = true,
        ) : Local
    }

    /** Companion namespace reserved for future platform-agnostic factory helpers. */
    public companion object
}
