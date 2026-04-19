/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.testutil

import androidx.test.platform.app.InstrumentationRegistry

/** Loads a test resource from the Android instrumentation test APK assets. */
@Suppress("unused") // Infrastructure for fixture-based golden tests
fun readTestResource(path: String): ByteArray {
    val context = InstrumentationRegistry.getInstrumentation().context
    return context.assets.open(path).use { it.readBytes() }
}
