/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import co.crackn.kompressor.KompressorContext

/**
 * Active runtime probe for HEVC Main10 / HDR10 encode support on Android.
 *
 * ## Why an *active* probe and not just [MediaCodecList]?
 *
 * Phase 4 (the original `requireHdr10Hevc` gate) consulted `MediaCodecList` — a self-declared
 * capability matrix from the vendor OMX/Codec2 components. A handful of OEM firmwares (some
 * MTK / older Qualcomm) *advertise* [MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10] +
 * [MediaCodecInfo.CodecCapabilities.FEATURE_HdrEditing] but actually crash `configure()` or
 * silently fall back to 8-bit. Catching that mid-export surfaces a nasty `ExportException`
 * deep in Media3; catching it up-front via a real encoder allocation + first-frame queue lets
 * us raise the typed [VideoCompressionError.Hdr10NotSupported] before any output bytes are
 * produced.
 *
 * ## Cost + caching
 *
 * An active probe allocates a MediaCodec instance, calls `configure` + `start`, feeds one P010
 * frame, drains one output buffer, and tears down — ~100–300 ms on real devices. Not free.
 * Results are invariant for the life of a firmware so we cache them in [SHARED_PREFS_NAME]
 * keyed by `Build.MODEL + codecName`. A firmware update changes at least one of those (the
 * encoder's soname bumps on major AOSP rebases), which naturally invalidates the cache.
 *
 * Never call from the main thread — MediaCodec configuration does binder IPC to the
 * `mediaserver` process and can block. Always dispatch via `Dispatchers.IO`.
 */
@Suppress("TooManyFunctions") // Thin private helpers kept split for readability; see individual KDocs.
internal object Hdr10HevcProbe {

    /**
     * Active-probe outcome. [supported] is the only value stored in SharedPreferences;
     * [codecName] and [reason] are captured at probe time for the typed-error message and
     * regenerated with a generic reason on cache hits (see [probe]).
     */
    internal data class Outcome(
        val supported: Boolean,
        val codecName: String,
        val reason: String,
    )

    /**
     * Run the probe — SharedPrefs cache first, active MediaCodec allocation on miss. Returns
     * an [Outcome] containing the cached/observed verdict plus the codec name and reason used
     * to build a [VideoCompressionError.Hdr10NotSupported] when the verdict is negative.
     *
     * The probe short-circuits `false` on pre-API-33 (mirrors [deviceSupportsHdr10Hevc]) and
     * on devices whose `MediaCodecList` does not advertise HEVC Main10 + `FEATURE_HdrEditing`
     * at all, so we never pay the 100–300 ms encoder-alloc cost on devices we already know
     * can't do HDR10.
     */
    @Suppress("ReturnCount") // Each early return encodes a distinct no-support reason for the typed error.
    fun probe(context: Context = KompressorContext.appContext): Outcome {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return Outcome(
                supported = false,
                codecName = NO_MAIN10_CODEC,
                reason = "Android API ${Build.VERSION.SDK_INT} < 33 — HDR editing requires API 33+",
            )
        }
        val candidate = pickHevcMain10Encoder()
            ?: return Outcome(
                supported = false,
                codecName = NO_MAIN10_CODEC,
                reason = "no HEVC encoder on this device advertises Main10 + FEATURE_HdrEditing",
            )
        val cacheKey = cacheKey(candidate)
        val cached = readCache(context, cacheKey)
        if (cached != null) {
            return Outcome(
                supported = cached,
                codecName = candidate,
                reason = if (cached) CACHE_HIT_OK else CACHE_HIT_FAILED,
            )
        }
        val outcome = runActiveProbe(candidate)
        writeCache(context, cacheKey, outcome.supported)
        return outcome
    }

    /**
     * Public-facing boolean helper required by the ticket DoD: "new function
     * `probeHdr10HevcSupport(): Boolean` …". Wraps [probe] and discards the diagnostic fields.
     */
    fun probeHdr10HevcSupport(context: Context = KompressorContext.appContext): Boolean =
        probe(context).supported

    /**
     * Clear the SharedPreferences cache. Intended for device tests that want a cold run —
     * **not** part of the public API.
     */
    internal fun clearCacheForTest(context: Context = KompressorContext.appContext) {
        context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    private fun cacheKey(codecName: String): String = "${Build.MODEL}|$codecName"

    private fun readCache(context: Context, key: String): Boolean? {
        val prefs = context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
        return if (prefs.contains(key)) prefs.getBoolean(key, false) else null
    }

    private fun writeCache(context: Context, key: String, supported: Boolean) {
        context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key, supported)
            .apply()
    }

    /**
     * Pick the first HEVC encoder that advertises Main10 / Main10HDR10 *and* the
     * `FEATURE_HdrEditing` flag. Mirrors the gate in [deviceSupportsHdr10Hevc] exactly —
     * any encoder that passes this filter is a probe candidate. Returns the codec's canonical
     * name (e.g. `c2.qti.hevc.encoder`) which [MediaCodec.createByCodecName] uses to allocate
     * the specific component rather than relying on `createEncoderByType`'s vendor-preferred
     * ordering (which can silently pick a different codec than the one we advertised).
     */
    private fun pickHevcMain10Encoder(): String? {
        val main10Profiles = setOf(
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus,
        )
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.asSequence()
            .filter { it.isEncoder }
            .mapNotNull { info ->
                val caps = runCatching { info.getCapabilitiesForType(MIME_HEVC) }.getOrNull()
                    ?: return@mapNotNull null
                val hasMain10 = caps.profileLevels.any { pl -> pl.profile in main10Profiles }
                val hasHdrEditing = caps.isFeatureSupported(
                    MediaCodecInfo.CodecCapabilities.FEATURE_HdrEditing,
                )
                if (hasMain10 && hasHdrEditing) info.name else null
            }
            .firstOrNull()
    }

    /**
     * Allocate the specific [codecName], configure it for HEVC Main10 at [PROBE_DIM]×[PROBE_DIM]
     * with P010 colour format and BT.2020 + ST 2084 (PQ) colour metadata, queue one blank P010
     * frame, and drain until we see either the output format change or the first encoded frame.
     *
     * Any thrown exception below this function is the signal we're after — it means the
     * advertised capability is a lie and HDR10 export would crash mid-pipeline. `finally` is
     * aggressive because MediaCodec leaks silently on abandoned instances.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun runActiveProbe(codecName: String): Outcome {
        var codec: MediaCodec? = null
        return try {
            codec = MediaCodec.createByCodecName(codecName)
            codec.configure(buildProbeFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            executeProbeFrame(codec, codecName)
        } catch (ce: MediaCodec.CodecException) {
            Outcome(false, codecName, "MediaCodec.CodecException: ${ce.message ?: ce::class.simpleName}")
        } catch (iae: IllegalArgumentException) {
            Outcome(false, codecName, "encoder rejected HDR10 format: ${iae.message ?: iae::class.simpleName}")
        } catch (ise: IllegalStateException) {
            Outcome(
                false,
                codecName,
                "encoder threw IllegalStateException during probe: ${ise.message ?: ise::class.simpleName}",
            )
        } catch (t: Throwable) {
            Outcome(false, codecName, "${t::class.simpleName}: ${t.message ?: "no message"}")
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }
    }

    private fun executeProbeFrame(codec: MediaCodec, codecName: String): Outcome {
        if (!feedOneProbeFrame(codec)) {
            return Outcome(false, codecName, "MediaCodec.dequeueInputBuffer timed out for HDR10 probe frame")
        }
        return if (drainOneOutputOrFormat(codec)) {
            Outcome(true, codecName, PROBE_OK)
        } else {
            Outcome(
                supported = false,
                codecName = codecName,
                reason = "encoder consumed P010 input but produced no output within ${PROBE_TIMEOUT_US}us",
            )
        }
    }

    private fun buildProbeFormat(): MediaFormat = MediaFormat.createVideoFormat(
        MIME_HEVC, PROBE_DIM, PROBE_DIM,
    ).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, COLOR_FORMAT_YUVP010)
        setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
        setInteger(MediaFormat.KEY_BIT_RATE, PROBE_BITRATE)
        setInteger(MediaFormat.KEY_FRAME_RATE, PROBE_FRAMERATE)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
        setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
        setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
    }

    @Suppress("ReturnCount") // Two early exits map to distinct MediaCodec "not ready" states.
    private fun feedOneProbeFrame(codec: MediaCodec): Boolean {
        val inputIdx = codec.dequeueInputBuffer(PROBE_TIMEOUT_US)
        if (inputIdx < 0) return false
        val input = codec.getInputBuffer(inputIdx) ?: return false
        // P010 layout: Y plane (2 bytes/pixel) then interleaved UV at half resolution, also
        // 2 bytes/pixel → total bytes = width * height * 3. A 1×1 frame needs 3 bytes; we
        // zero them so the encoder sees black-at-PQ-reference. Many OEM encoders round the
        // working buffer up to a tile size so we also write up to `input.capacity()` if it's
        // larger than our 3-byte payload.
        val payload = PROBE_DIM * PROBE_DIM * P010_BYTES_PER_PIXEL
        input.clear()
        val bytesToWrite = minOf(payload, input.capacity())
        repeat(bytesToWrite) { input.put(0) }
        codec.queueInputBuffer(inputIdx, 0, bytesToWrite, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        return true
    }

    /**
     * Drain the encoder until we see either the first output buffer (success — encoder
     * honoured the HDR10 format) or the output format changes (also success — the encoder
     * decided on an output format that includes Main10 parameters). TRY_AGAIN_LATER or
     * INFO_OUTPUT_BUFFERS_CHANGED loops continue until we see one of those terminal signals
     * or hit the overall deadline.
     */
    // Suppresses DEPRECATION for INFO_OUTPUT_BUFFERS_CHANGED — deprecated but still
    // surfaces on some pre-API-34 devices, so we handle it explicitly.
    @Suppress("DEPRECATION")
    private fun drainOneOutputOrFormat(codec: MediaCodec): Boolean {
        val deadline = System.nanoTime() + DRAIN_OVERALL_TIMEOUT_NANOS
        val info = MediaCodec.BufferInfo()
        var seenTerminal = false
        while (!seenTerminal && System.nanoTime() < deadline) {
            val outputIdx = codec.dequeueOutputBuffer(info, PROBE_TIMEOUT_US)
            seenTerminal = when {
                outputIdx >= 0 -> {
                    codec.releaseOutputBuffer(outputIdx, false)
                    true
                }
                outputIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> true
                else -> false // INFO_TRY_AGAIN_LATER, INFO_OUTPUT_BUFFERS_CHANGED, and any other transient signal.
            }
        }
        return seenTerminal
    }

    private const val SHARED_PREFS_NAME = "co.crackn.kompressor.hdr10"
    private const val MIME_HEVC = "video/hevc"
    private const val NO_MAIN10_CODEC = "<none>"
    private const val CACHE_HIT_OK = "cached-supported"
    private const val CACHE_HIT_FAILED = "cached-unsupported"
    private const val PROBE_OK = "active probe succeeded"

    // 1×1 matches the ticket DoD; a few OEM encoders reject sub-16px inputs so if a future
    // compat report shows noise here, bump to PROBE_DIM = 16 (still orders of magnitude
    // smaller than any real HDR10 frame and invariant to the capability we're testing).
    private const val PROBE_DIM = 1
    private const val PROBE_BITRATE = 64_000
    private const val PROBE_FRAMERATE = 30

    // MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010 (API 29+).
    // Hard-coded because we still compile against a minSdk that pre-dates the constant.
    private const val COLOR_FORMAT_YUVP010 = 54
    private const val P010_BYTES_PER_PIXEL = 3
    private const val PROBE_TIMEOUT_US = 50_000L
    private const val DRAIN_OVERALL_TIMEOUT_NANOS = 2_000_000_000L // 2s hard ceiling
}

/**
 * Public-file-level alias matching the ticket DoD's requested signature. Delegates to
 * [Hdr10HevcProbe.probeHdr10HevcSupport] so callers that want the plain boolean verdict
 * don't have to know about the [Hdr10HevcProbe.Outcome] data class.
 */
internal fun probeHdr10HevcSupport(): Boolean = Hdr10HevcProbe.probeHdr10HevcSupport()
