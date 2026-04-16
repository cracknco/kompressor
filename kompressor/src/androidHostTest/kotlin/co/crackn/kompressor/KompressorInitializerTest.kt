/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test

/**
 * Host-side coverage for [KompressorContext] (the failure-mode path) and
 * [KompressorInitializer.dependencies].
 *
 * Why two test sites: the *uninitialized → access* path must execute on the host so it counts
 * toward Kover's gate; the happy-path `create()` flow needs a real Android Context and is
 * covered in [KompressorInitializerDeviceTest] under `androidDeviceTest`.
 */
class KompressorInitializerTest {

    @kotlin.test.BeforeTest
    fun resetSingletonBefore() {
        KompressorContext.resetForTest()
    }

    @kotlin.test.AfterTest
    fun resetSingletonAfter() {
        KompressorContext.resetForTest()
    }

    @Test
    fun appContext_uninitialized_throwsIllegalStateWithActionableMessage() {
        val failure = shouldThrow<IllegalStateException> {
            KompressorContext.appContext
        }
        val message = failure.message ?: error("expected non-null message on IllegalStateException")
        message shouldContain "Kompressor is not initialized"
        message shouldContain "KompressorInitializer"
    }

    @Test
    fun dependencies_reportsNoStartupDependencies() {
        val initializer = KompressorInitializer()
        initializer.dependencies().size shouldBe 0
    }
}
