/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertSame

/**
 * Device-side companion to [KompressorInitializerTest] — verifies the happy-path
 * `KompressorInitializer.create()` flow with a real Android [android.content.Context].
 */
class KompressorInitializerDeviceTest {

    @Before
    fun resetBefore() {
        KompressorContext.resetForTest()
    }

    @After
    fun restoreApplicationContext() {
        // The instrumentation test process re-uses the singleton across test classes, so make
        // sure subsequent tests still see the real application context after we reset above.
        KompressorContext.init(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @Test
    fun create_storesApplicationContext_andSubsequentReadsReturnSameInstance() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val expectedApp = targetContext.applicationContext

        KompressorInitializer().create(targetContext)

        val first = KompressorContext.appContext
        val second = KompressorContext.appContext
        assertSame(expectedApp, first, "Stored context should be the application context")
        assertSame(first, second, "Subsequent reads should return the same instance")
    }
}
