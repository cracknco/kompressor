/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.testutil

/**
 * Byte-level search helpers for structural assertions on encoded media (JPEG markers, MP4
 * boxes, FLAC metadata blocks, MP3 Xing tags). Pure Kotlin — shared between androidDeviceTest
 * and iosTest so fixture-contract tests don't copy the same scan loop per source set.
 */
object ByteSearch {

    /** Returns the first offset of [needle] in [haystack], or `-1` when absent. */
    fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    /** Returns the first offset of the first matching needle, or `-1` when none are present. */
    fun indexOfAny(haystack: ByteArray, vararg needles: ByteArray): Int {
        for (needle in needles) {
            val idx = indexOf(haystack, needle)
            if (idx >= 0) return idx
        }
        return -1
    }

    /** UTF-8 / ASCII convenience: search for the byte form of [needle]. */
    fun indexOfAscii(haystack: ByteArray, needle: String): Int =
        indexOf(haystack, needle.encodeToByteArray())
}
