/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

/**
 * Best-effort size-in-bytes extractor for [MediaSource.Local] inputs.
 *
 * Consumed by [resolveStreamOrBytesToTempFile] when a [MediaSource.Local.Stream] arrives without a
 * caller-supplied `sizeHint`, so the materialization-phase progress fraction can still report real
 * percentages instead of a flat `0f` heartbeat (see [TempFileMaterializer] `computeFraction`).
 *
 * **Activation status (CRA-99).** Each `MediaSource.Local` subtype falls into one of three
 *  reachability buckets from the current dispatch:
 *
 *  1. **Active** — dispatch calls the probe and seeds `sizeHint` with the result:
 *    - `AndroidPfdMediaSource` — `materializePfdHandle` routes PFD materialisation through
 *      `TempFileMaterializer` with this probe as the sizeHint seed, so seekable-FD copies emit
 *      fraction-accurate `MATERIALIZING_INPUT` ticks (pipe / socket FDs with `statSize = -1`
 *      clamp to `null` and degrade gracefully to a flat-0 heartbeat).
 *
 *  2. **Bypassed (authoritative size already known)** — probe branch exists but dispatch reads
 *      the size directly without calling through the probe. Kept in sync with the Active bucket
 *      so the probe remains a single canonical source of truth for any future dispatch call-site.
 *    - `MediaSource.Local.FilePath` — `File.length()` / `NSFileManager[NSFileSize]` could be
 *      queried but today's dispatch passes `FilePath` directly to `compressFilePath` without
 *      materialisation, so there's no progress fraction to seed anyway.
 *    - `MediaSource.Local.Bytes` — dispatch reads `bytes.size.toLong()` directly when wrapping
 *      the buffer for materialisation.
 *    - `IosDataMediaSource` — dispatch reads `NSData.length` directly when copying into the
 *      `Bytes` path.
 *
 *  3. **Scaffolding only** — no materialisation today means no fraction to report on; branch is
 *      retained as ready-to-activate infrastructure for a future dispatch change.
 *    - `MediaSource.Local.Stream` — the Stream branch echoes `input.sizeHint` back unchanged.
 *      Introspecting an opaque `okio.Source` at runtime is not feasible: `okio.FileSource` is
 *      `internal` to okio, reflection isn't available on Kotlin/Native, and the `Source`
 *      interface itself has no size channel. Door closed pending a dedicated
 *      `MediaSource.Local.Stream.sized(source, bytes)` builder; callers with a known size should
 *      supply `sizeHint` at construction.
 *    - `AndroidUriMediaSource` / `IosUrlMediaSource` — content URIs and `file://` NSURLs pass
 *      through natively to Media3 / AVFoundation today (no local materialisation). Ready for a
 *      future "force local materialisation" switch (e.g. a debug flag forcing a temp-file copy
 *      for reproducibility).
 *    - `IosPHAssetMediaSource` — PHAsset image materialisation is a one-shot atomic
 *      `NSData.writeToURL(..., atomically = true)` today (no chunk boundary). Ready if we ever
 *      switch to a chunked write (50 MB Live Photo with mid-write cancellation cooperation).
 *
 * **Return contract:**
 *  - `null` — size genuinely unknown (unbounded stream, probe failed, PhotoKit private-KVC miss).
 *    Callers MUST treat `null` as "skip fraction reporting" — not as a zero.
 *  - `>= 0` — authoritative byte count. Negative values are never returned (floor-clamped on
 *    platform paths that can surface `-1` sentinels such as `ParcelFileDescriptor.statSize`).
 *
 * **No throw:** every probe variant wraps its platform call in `runCatching` so a revoked URI
 * permission, deleted file, or PhotoKit access-denied surfaces as `null` rather than crashing the
 * compression pipeline. Progress is a UX concern; a probe failure must never abort a compression
 * that could otherwise succeed.
 *
 * **Not public API:** probe state is a pipeline detail — the single consumer is the resolver.
 * Keeping this `internal` means the per-platform variant coverage can grow without an `apiCheck`
 * churn on every new native source wrapper.
 *
 * The declaration is a top-level `expect fun` rather than an `expect object` because
 * `expect`/`actual` classes are still Beta in Kotlin 2.3.20 and the project's `-Werror` compiler
 * policy would otherwise force an `-Xexpect-actual-classes` opt-in flag. The existing KMP bridge
 * pattern in the repo (`randomMaterializationId`, `kompressorFileSystem`) is plain
 * `expect fun` / `expect val` — mirrored here for consistency.
 */
internal expect fun estimateSourceSize(input: MediaSource.Local): Long?
