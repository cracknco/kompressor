/*
 * Copyright 2026 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.image

import co.crackn.kompressor.ExperimentalKompressorApi
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

@OptIn(ExperimentalKompressorApi::class)
class ImageFormatGatesTest {

    @Test
    fun androidRejectsHeicInputBelowApi30() {
        val verdict = androidInputGate(InputImageFormat.HEIC, apiLevel = 29)
        verdict.shouldNotBeNull()
        verdict.format shouldBe "heic"
        verdict.platform shouldBe "android"
        verdict.minApi shouldBe HEIC_INPUT_MIN_API_ANDROID
    }

    @Test
    fun androidAllowsHeicInputAtApi30() {
        androidInputGate(InputImageFormat.HEIC, apiLevel = HEIC_INPUT_MIN_API_ANDROID).shouldBeNull()
    }

    @Test
    fun androidRejectsHeifInputBelowApi30() {
        androidInputGate(InputImageFormat.HEIF, apiLevel = 29).shouldNotBeNull()
    }

    @Test
    fun androidRejectsAvifInputBelowApi31() {
        val verdict = androidInputGate(InputImageFormat.AVIF, apiLevel = 30)
        verdict.shouldNotBeNull()
        verdict.format shouldBe "avif"
        verdict.minApi shouldBe AVIF_INPUT_MIN_API_ANDROID
    }

    @Test
    fun androidAllowsAvifInputAtApi31() {
        androidInputGate(InputImageFormat.AVIF, apiLevel = AVIF_INPUT_MIN_API_ANDROID).shouldBeNull()
    }

    @Test
    fun androidAllowsJpegPngWebpDngInputAtEveryApi() {
        listOf(
            InputImageFormat.JPEG, InputImageFormat.PNG, InputImageFormat.WEBP,
            InputImageFormat.GIF, InputImageFormat.BMP, InputImageFormat.DNG,
            InputImageFormat.UNKNOWN,
        ).forEach { format ->
            androidInputGate(format, apiLevel = 24).shouldBeNull()
        }
    }

    @Test
    fun androidRejectsAvifOutputBelowApi34() {
        val verdict = androidOutputGate(ImageFormat.AVIF, apiLevel = 33)
        verdict.shouldNotBeNull()
        verdict.format shouldBe "avif"
        verdict.minApi shouldBe AVIF_OUTPUT_MIN_API_ANDROID
    }

    @Test
    fun androidAllowsAvifOutputAtApi34() {
        androidOutputGate(ImageFormat.AVIF, apiLevel = AVIF_OUTPUT_MIN_API_ANDROID).shouldBeNull()
    }

    @Test
    fun androidRejectsHeicOutputAtEveryApiLevel() {
        androidOutputGate(ImageFormat.HEIC, apiLevel = 34).shouldNotBeNull()
        androidOutputGate(ImageFormat.HEIC, apiLevel = Int.MAX_VALUE - 1).shouldNotBeNull()
    }

    @Test
    fun androidAllowsJpegAndWebpOutputAtMinSdk() {
        androidOutputGate(ImageFormat.JPEG, apiLevel = 24).shouldBeNull()
        androidOutputGate(ImageFormat.WEBP, apiLevel = 24).shouldBeNull()
    }

    @Test
    fun iosRejectsAvifInputBeforeIos16() {
        val verdict = iosInputGate(InputImageFormat.AVIF, iosVersion = 15)
        verdict.shouldNotBeNull()
        verdict.minApi shouldBe AVIF_INPUT_MIN_IOS
        verdict.platform shouldBe "ios"
    }

    @Test
    fun iosAllowsAvifInputAtIos16() {
        iosInputGate(InputImageFormat.AVIF, iosVersion = AVIF_INPUT_MIN_IOS).shouldBeNull()
    }

    @Test
    fun iosAllowsHeicInputAtEveryVersionWeSupport() {
        iosInputGate(InputImageFormat.HEIC, iosVersion = 15).shouldBeNull()
    }

    @Test
    fun iosRejectsAvifOutputBeforeIos16() {
        iosOutputGate(ImageFormat.AVIF, iosVersion = 15).shouldNotBeNull()
    }

    @Test
    fun iosRejectsWebpOutputAtEveryVersion() {
        iosOutputGate(ImageFormat.WEBP, iosVersion = 15).shouldNotBeNull()
        iosOutputGate(ImageFormat.WEBP, iosVersion = 20).shouldNotBeNull()
    }

    @Test
    fun iosAllowsHeicAndJpegOutputAtIos15() {
        iosOutputGate(ImageFormat.HEIC, iosVersion = 15).shouldBeNull()
        iosOutputGate(ImageFormat.JPEG, iosVersion = 15).shouldBeNull()
    }
}
