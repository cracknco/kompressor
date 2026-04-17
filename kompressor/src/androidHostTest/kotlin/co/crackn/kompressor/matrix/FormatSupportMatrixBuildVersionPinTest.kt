/*
 * Copyright 2026 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.matrix

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Pins the hand-mirrored [FormatSupportMatrix.ANDROID_MIN_SDK] and
 * [FormatSupportMatrix.IOS_MIN_VERSION] constants against the authoritative build-time values
 * sourced from `gradle/libs.versions.toml`.
 *
 * The matrix deliberately duplicates the floor values (`24` / `15`) instead of importing them
 * from `ImageFormatGates` or the build script, so the source-of-truth stays self-contained and
 * the renderer remains pure `commonMain`. That choice only holds up if a test actually pins the
 * duplicates to the build — otherwise bumping `android-minSdk` to 26 would silently leave the
 * published doc claiming the wrong floor.
 *
 * Values are passed in from `kompressor/build.gradle.kts` via JVM system properties
 * (`kompressor.buildAndroidMinSdk`, `kompressor.buildIosDeploymentTarget`) so the test can run
 * in `androidHostTest` without needing the Gradle project model at runtime.
 */
class FormatSupportMatrixBuildVersionPinTest {

    @Test
    fun androidMinSdkMatchesLibsVersionsToml() {
        val expected = requiredIntProperty(ANDROID_MIN_SDK_PROPERTY)
        FormatSupportMatrix.ANDROID_MIN_SDK shouldBe expected
    }

    @Test
    fun iosMinVersionMatchesLibsVersionsToml() {
        val expected = requiredIntProperty(IOS_DEPLOYMENT_TARGET_PROPERTY)
        FormatSupportMatrix.IOS_MIN_VERSION shouldBe expected
    }

    private fun requiredIntProperty(name: String): Int {
        val raw = System.getProperty(name)
            ?: error(
                "Missing JVM system property `$name`. `kompressor/build.gradle.kts` injects it on " +
                    "every Test task; if you see this, either Gradle isn't driving the run or the " +
                    "injection block was edited out.",
            )
        return raw.toIntOrNull()
            ?: error("System property `$name` is not an Int: `$raw`")
    }

    private companion object {
        const val ANDROID_MIN_SDK_PROPERTY: String = "kompressor.buildAndroidMinSdk"
        const val IOS_DEPLOYMENT_TARGET_PROPERTY: String = "kompressor.buildIosDeploymentTarget"
    }
}
