/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.io

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import okio.Buffer

class MediaDestinationTest {

    @Test
    fun filePathEqualityUsesPath() {
        val a = MediaDestination.Local.FilePath("/tmp/out.mp4")
        val b = MediaDestination.Local.FilePath("/tmp/out.mp4")
        val c = MediaDestination.Local.FilePath("/tmp/other.mp4")
        a shouldBe b
        a.hashCode() shouldBe b.hashCode()
        a shouldNotBe c
    }

    @Test
    fun streamCloseOnFinishDefaultIsTrue() {
        val dst = MediaDestination.Local.Stream(Buffer())
        dst.closeOnFinish shouldBe true
    }

    @Test
    fun streamCloseOnFinishCanBeOverridden() {
        val dst = MediaDestination.Local.Stream(Buffer(), closeOnFinish = false)
        dst.closeOnFinish shouldBe false
    }

    @Test
    fun allLocalVariantsAreMediaDestination() {
        val destinations: List<MediaDestination> = listOf(
            MediaDestination.Local.FilePath("/tmp/x.mp4"),
            MediaDestination.Local.Stream(Buffer()),
        )
        destinations.forEach { it.shouldBeInstanceOf<MediaDestination.Local>() }
    }

    @Test
    fun companionObjectIsAccessible() {
        MediaDestination shouldNotBe null
    }
}
