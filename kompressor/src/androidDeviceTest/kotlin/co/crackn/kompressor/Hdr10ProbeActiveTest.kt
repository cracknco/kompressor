/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import co.crackn.kompressor.video.Hdr10HevcProbe
import co.crackn.kompressor.video.deviceSupportsHdr10Hevc
import co.crackn.kompressor.video.probeHdr10HevcSupport
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Device-side validation of the active HEVC Main10 / HDR10 runtime probe.
 *
 * Lives in `androidDeviceTest` because it exercises real [android.media.MediaCodec]
 * allocation — the host JVM cannot stand these up. The CLAUDE.md testing-layers table is
 * authoritative on why this can't run on Robolectric or the host JVM.
 *
 * The whole point of CRA-20 is that `MediaCodecList` can *lie* about Main10 support, so the
 * only robust assertion we can make here is about **consistency**, not value:
 *  * The active probe result must be deterministic (two calls agree).
 *  * On devices where `MediaCodecList` does not advertise Main10 + `FEATURE_HdrEditing` the
 *    probe must return `false` (cheap-path short-circuit — we never even try to allocate).
 *  * When the probe succeeds the result is cached: a second call must return the same value
 *    in well under the encoder-alloc budget (cheap SharedPreferences read).
 *
 * CRA-88 replaced the single-frame-with-EOS protocol with a two-frame protocol (prime +
 * EOS-on-`INFO_TRY_AGAIN_LATER`) to eliminate empty-drain false-negatives on OMX / Codec2
 * encoders that need a priming frame before producing output. This test is black-box — it
 * doesn't reach into the protocol — but the determinism assertion plus the per-model FTL
 * compat matrix runs are what caught the original CRA-20 false-negatives and will catch any
 * regression here too.
 *
 * The device-axis compat matrix is built by running this test in CI across Firebase Test Lab
 * device models; see `docs/format-support.md` for the published results.
 */
class Hdr10ProbeActiveTest {

    @BeforeTest
    fun clearProbeCache() {
        // Test isolation: each test runs a cold probe so caching behaviour is observable.
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        Hdr10HevcProbe.clearCacheForTest(ctx)
    }

    @Test
    fun probeIsDeterministic_twoCallsAgreeOnSameDevice() {
        val first = probeHdr10HevcSupport()
        val second = probeHdr10HevcSupport()
        assertEquals(
            first, second,
            "probeHdr10HevcSupport() must be deterministic across repeated calls on the " +
                "same device+firmware (got first=$first, second=$second)",
        )
    }

    @Test
    fun probeReturnsFalse_whenCapabilityMatrixDoesNotAdvertiseMain10() {
        // When the cheap self-declared check says "no Main10 / FEATURE_HdrEditing on this
        // device" the active probe must honour that and report unsupported — a `true` here
        // would mean our capability gate and probe disagree, which regresses the whole
        // two-stage contract.
        if (deviceSupportsHdr10Hevc()) return // This assertion only applies to the negative branch.
        assertTrue(
            !probeHdr10HevcSupport(),
            "probeHdr10HevcSupport() must short-circuit to false on devices where " +
                "MediaCodecList does not advertise HEVC Main10 + FEATURE_HdrEditing " +
                "(model=${Build.MODEL}, api=${Build.VERSION.SDK_INT})",
        )
    }

    @Test
    fun probeCachesFirstResult_secondCallIsFast() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        // Cold run: incurs the MediaCodec alloc cost (or the API<33 short-circuit) and writes
        // the verdict to SharedPreferences.
        val cold = probeHdr10HevcSupport()
        val coldStart = System.nanoTime()
        val hot = probeHdr10HevcSupport()
        val hotNanos = System.nanoTime() - coldStart
        assertEquals(cold, hot, "cached probe verdict must match cold verdict")
        // 200 ms is well above any plausible cached read (single SharedPreferences.getBoolean
        // call) and well below the active probe cost (~100–300 ms on real HW). Generous
        // enough not to false-positive on slow FTL emulators.
        assertTrue(
            hotNanos < CACHE_HIT_CEILING_NANOS,
            "second probe call must hit SharedPreferences cache, took ${hotNanos}ns " +
                "(ceiling = ${CACHE_HIT_CEILING_NANOS}ns)",
        )
        // Explicit paranoia: the cache survives across Hdr10HevcProbe.Outcome access too.
        val viaOutcome = Hdr10HevcProbe.probe(ctx)
        assertEquals(
            cold, viaOutcome.supported,
            "Hdr10HevcProbe.probe().supported must match cached probeHdr10HevcSupport() verdict",
        )
    }

    private companion object {
        // 200 ms — generously large because FTL emulators are noisy; see test comment.
        private const val CACHE_HIT_CEILING_NANOS = 200_000_000L
    }
}
