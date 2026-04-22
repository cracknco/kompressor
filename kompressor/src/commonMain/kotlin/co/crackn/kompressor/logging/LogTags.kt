/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.logging

/**
 * Library-internal tag vocabulary.
 *
 * All tags are kept under 23 characters because [android.util.Log] enforces that limit on
 * API levels below 26 and truncates / throws otherwise. Using a central constant means a
 * consumer who filters `Kompressor.*` in Logcat or Console.app captures every library message
 * with one filter expression.
 *
 * Tag layout: `Kompressor.<Facet>` where the facet names the sub-system (Image / Video / Audio /
 * Probe / Init). Avoid deeper nesting — the consumer's logger is usually the layer that wants
 * finer structure (Timber `Timber.tag()`, SwiftLog `subsystem/category`), not ours.
 */
internal object LogTags {
    const val IMAGE: String = "Kompressor.Image"
    const val VIDEO: String = "Kompressor.Video"
    const val AUDIO: String = "Kompressor.Audio"
    const val PROBE: String = "Kompressor.Probe"
    const val INIT: String = "Kompressor.Init"
    const val IO: String = "Kompressor.IO"
}
