/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

/**
 * Cross-platform canonical rejection strings for the `MediaSource.of(...)` /
 * `MediaDestination.of(...)` platform builders.
 *
 * Declared in `commonMain` so every platform source set (Android, iOS, future JVM/JS) imports
 * the same literal. A consumer switching platforms sees byte-identical error text; a typo fix
 * on one side lands for every platform simultaneously. See PR #141 review (2026-04-22) for the
 * rationale behind centralising these here rather than duplicating them per platform.
 *
 * Pinned in host tests on every platform — if these strings drift, the drift is caught at
 * host-test time.
 */
internal object MediaSourceRejections {

    /** Emitted by `MediaSource.of(Uri | NSURL | …)` when the scheme is `http` / `https`. */
    const val REMOTE_URL_INPUT: String =
        "Remote URLs not supported. Download the content locally first."

    /** Emitted by `MediaDestination.of(Uri | NSURL | …)` when the scheme is `http` / `https`. */
    const val REMOTE_URL_OUTPUT: String =
        "Remote URLs not supported. Write locally first then upload."
}
