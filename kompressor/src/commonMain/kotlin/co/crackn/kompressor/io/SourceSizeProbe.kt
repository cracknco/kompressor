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
 * **Activation status (CRA-96):** the single current call-site in [materializeStream] passes a
 * `MediaSource.Local.Stream` whose `sizeHint` is a direct passthrough of the probe's Stream
 * branch — so in practice this probe is a no-op for Stream inputs today. The real activation of
 * the `Uri` / `PFD` / `NSURL` / `PHAsset` / `NSData` branches lands in **CRA-99**, which rewires
 * `materializePfdHandle` (Android) and `materializeNsData` (iOS) through the probe-seeded
 * `TempFileMaterializer` path so pre-materialisation `MATERIALIZING_INPUT` fractions become
 * accurate for native-handle inputs too. The full probe surface is implemented + tested here as
 * preparatory infrastructure so CRA-99 can focus on the dispatch rewiring alone.
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
