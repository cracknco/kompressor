/*
 * Copyright 2026 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

/**
 * Marks an API that may change or be removed in any MINOR release without a MAJOR version bump.
 *
 * Opt in at the call site with `@OptIn(ExperimentalKompressorApi::class)` or propagate the opt-in
 * to the enclosing declaration. See `docs/api-stability.md` for the full stability contract.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "This Kompressor API is experimental and may change or be removed without a major version bump.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPEALIAS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
)
public annotation class ExperimentalKompressorApi
