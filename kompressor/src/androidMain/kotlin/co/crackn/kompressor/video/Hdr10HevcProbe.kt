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
     * Active-probe outcome. [supported] and [reason] are persisted in SharedPreferences so that
     * cached-negative verdicts retain the specific failure reason (e.g. "configure() threw
     * IllegalArgumentException: …") rather than regressing to a generic "cached-unsupported"
     * string. [codecName] is re-derived from the capability matrix on cache hits.
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
     *
     * The body is wrapped in a catch-all so that an unexpected throw from
     * [MediaCodecList] construction — rare but observed on some OEM `mediaserver` variants
     * immediately post-boot — never escapes as an untyped `RuntimeException`, honouring the
     * `AndroidVideoCompressor.requireHdr10Hevc` contract that HDR10 failures always surface
     * as a typed [VideoCompressionError.Hdr10NotSupported].
     */
    @Suppress("TooGenericExceptionCaught")
    fun probe(context: Context = KompressorContext.appContext): Outcome = try {
        probeInternal(context)
    } catch (t: Throwable) {
        Outcome(
            supported = false,
            codecName = NO_MAIN10_CODEC,
            reason = "probe threw ${t::class.simpleName}: ${t.message ?: "no message"}",
        )
    }

    @Suppress("ReturnCount") // Each early return encodes a distinct no-support reason for the typed error.
    private fun probeInternal(context: Context): Outcome {
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
        readCache(context, cacheKey, candidate)?.let { return it }
        // Serialize the first-run miss per-process so two concurrent HDR10 `compress()` calls
        // don't both pay the 100–300 ms encoder-alloc cost, and — more importantly — don't
        // race on OEM encoders that refuse a second concurrent Main10 instance (a race here
        // can cache a false-negative forever).
        return synchronized(probeLock) {
            readCache(context, cacheKey, candidate) ?: run {
                val outcome = runActiveProbe(candidate)
                writeCache(context, cacheKey, outcome)
                outcome
            }
        }
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

    // Cache key includes `Build.VERSION.INCREMENTAL` so OEM OTAs that patch an encoder in place
    // (e.g. Samsung monthly security patches that update `c2.qcom.video.encoder.hevc` without
    // bumping MODEL) invalidate the cache and re-probe once, rather than permanently locking
    // the device to a stale-negative verdict from pre-fix firmware.
    private fun cacheKey(codecName: String): String =
        "${Build.MODEL}|${Build.VERSION.INCREMENTAL}|$codecName"

    private fun readCache(context: Context, key: String, codecName: String): Outcome? {
        val prefs = context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(key)) return null
        val supported = prefs.getBoolean(key, false)
        val reason = prefs.getString(key + REASON_KEY_SUFFIX, null)
            ?: if (supported) CACHE_HIT_OK else CACHE_HIT_FAILED
        return Outcome(supported, codecName, reason)
    }

    private fun writeCache(context: Context, key: String, outcome: Outcome) {
        context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key, outcome.supported)
            .putString(key + REASON_KEY_SUFFIX, outcome.reason)
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
    private fun pickHevcMain10Encoder(): String? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.asSequence()
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
        val failReason = drainOneOutputOrFormat(codec)
        return if (failReason == null) {
            Outcome(true, codecName, PROBE_OK)
        } else {
            Outcome(supported = false, codecName = codecName, reason = failReason)
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
     * honoured the HDR10 format) or the output format changes with a Main10 profile (also
     * success — the encoder committed to Main10 parameters). Returns `null` on success, or a
     * failure-reason string on timeout or on a silent downgrade to an 8-bit profile.
     *
     * The `INFO_OUTPUT_FORMAT_CHANGED` + profile-inspection path catches the narrow but real
     * class of OEM encoders that accept a Main10 `configure()`, don't throw, but silently
     * republish a Main (8-bit) profile at output time — without the profile check, those
     * devices would cache `supported = true` and then produce 8-bit output at real export
     * time (losing HDR10 without telling the caller).
     */
    // Suppresses DEPRECATION for INFO_OUTPUT_BUFFERS_CHANGED — deprecated but still
    // surfaces on some pre-API-34 devices, so we handle it explicitly.
    @Suppress("DEPRECATION")
    private fun drainOneOutputOrFormat(codec: MediaCodec): String? {
        val deadline = System.nanoTime() + DRAIN_OVERALL_TIMEOUT_NANOS
        val info = MediaCodec.BufferInfo()
        var done = false
        var failReason: String? = null
        while (!done && System.nanoTime() < deadline) {
            val outputIdx = codec.dequeueOutputBuffer(info, PROBE_TIMEOUT_US)
            when {
                outputIdx >= 0 -> {
                    codec.releaseOutputBuffer(outputIdx, false)
                    done = true
                }
                outputIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val negotiated = codec.outputFormat.getInteger(MediaFormat.KEY_PROFILE, -1)
                    done = true
                    if (negotiated !in main10Profiles) {
                        failReason = "encoder downgraded output to non-Main10 profile $negotiated"
                    }
                }
                // INFO_TRY_AGAIN_LATER, INFO_OUTPUT_BUFFERS_CHANGED, any other transient signal → keep draining.
            }
        }
        return when {
            !done -> "encoder consumed P010 input but produced no output within ${PROBE_TIMEOUT_US}us"
            else -> failReason
        }
    }

    private val probeLock = Any()

    private val main10Profiles = setOf(
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus,
    )

    private const val SHARED_PREFS_NAME = "co.crackn.kompressor.hdr10"
    private const val REASON_KEY_SUFFIX = "|reason"
    private const val MIME_HEVC = "video/hevc"

    /**
     * Marker used for [Outcome.codecName] / [VideoCompressionError.Hdr10NotSupported.codec]
     * when the device never even advertised a Main10 HEVC encoder, so there is no concrete
     * codec name to report. Consumed by [AndroidVideoCompressor.requireHdr10Hevc] — keeping
     * the field format consistent across the capability-gate and active-probe error paths.
     */
    internal const val NO_MAIN10_CODEC = "<no-main10-encoder>"

    private const val CACHE_HIT_OK = "cached-supported (reason unknown — pre-upgrade cache entry)"
    private const val CACHE_HIT_FAILED =
        "cached-unsupported (reason unknown — pre-upgrade cache entry; previous probe rejected encoder)"
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
