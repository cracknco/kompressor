# Public API Inventory

This document is the source of truth for the stability tier of every public
Kompressor symbol. It is curated alongside the committed ABI baseline
(`kompressor/api/kompressor.api`) and the S/E/I review checklist in
[`docs/api-stability.md`](api-stability.md).

Three tiers exist:

- **Stable (S)** — covered by the SemVer 2.0.0 contract from 1.0.0 onward.
  Source- and binary-breaking changes require a MAJOR release.
- **Experimental (E)** — annotated `@ExperimentalKompressorApi`. Opt-in is
  required at the call site. These APIs may change or be removed in any
  MINOR release without a MAJOR bump. Consumers see a WARNING-level
  opt-in requirement from the Kotlin compiler.
- **Internal (I)** — declared `internal`. Invisible to consumers, no
  stability guarantee. These are **absent from the ABI dump by
  construction**; they're documented in the
  [Internal audit](#internal-audit--what-was-kept-hidden) section
  purely for review-trail purposes.

The inventory below is the 1.0 **freeze list**. Reviewers promise that
every line of the committed `kompressor/api/kompressor.api` has a row
here and vice versa. Two maintainer sign-offs on the PR that publishes
this doc attest to that one-to-one mapping.

## Stable symbols

> Covered by the SemVer 2.0.0 contract from 1.0.0 onward. Source-breaking
> changes require a MAJOR release. Each symbol below has already been
> KDoc-gated by Detekt's `UndocumentedPublicClass / Function / Property`
> rules (`config/detekt/detekt.yml`) — removing or weakening the KDoc
> fails CI.

### `co.crackn.kompressor` — top-level

| Symbol | Kind | Justification for stability |
|--------|------|----------------------------|
| `Kompressor` | Interface | Primary entry point. Lazily-initialised sub-compressors (`image`, `video`, `audio`), `probe(inputPath): Result<SourceMediaInfo>`, `canCompress(info): Supportability`. Thread-safety and inter-process contract are documented on the interface and in `docs/threading-model.md`. |
| `createKompressor(): Kompressor` | `expect` top-level fun | The only construction entry point; `actual`s live in `AndroidKompressorKt.createKompressor` and `IosKompressorKt.createKompressor`. Context is obtained via AndroidX App Startup on Android. |
| `CompressionResult` | `data class` | Success payload for all three compressors. Fields `inputSize`, `outputSize`, `durationMs` and derived `compressionRatio` / `isSmallerThanOriginal` are stable outputs. Non-data constructor already validates inputs with `require`. |
| `SourceMediaInfo` | `data class` | Probe result. Stable fields are nullable on purpose — not every container exposes every field. One property (`audioTrackCount`) is Experimental, see below. |
| `Supportability` | `sealed class` | Tri-state verdict for `Kompressor.canCompress`. |
| `Supportability.Supported` | `object` | Singleton. |
| `Supportability.Unsupported(reasons: List<String>)` | `data class` | Hard-blocker verdict. |
| `Supportability.Unknown(reasons: List<String>)` | `data class` | Can't-tell verdict; callers warn and retry. |
| `DeviceCapabilities` | `data class` | Aggregate codec capability snapshot. Used by `Kompressor.canCompress` internally and by the sample app's Capabilities screen directly. |
| `CodecSupport` | `data class` | One entry in the capability matrix. 12 fields — the full matrix is part of the contract because consumers are expected to render / filter on it. |
| `CodecSupport.Role` | `enum class` | `Decoder` / `Encoder`. |
| `queryDeviceCapabilities(): DeviceCapabilities` | `expect` top-level fun | Expensive (iterates `MediaCodecList.REGULAR_CODECS` on Android) — doc explicitly tells callers to cache. |
| `AudioCodec` | `enum class` | Output audio codec selector. Only `AAC` today; future additions are source-compat MINORs. |
| `ExperimentalKompressorApi` | `annotation class` | The opt-in marker itself is the *public* mechanism by which Experimental surface is gated. Its own stability is part of the contract. |
| `KompressorInitializer` | `class : androidx.startup.Initializer<Unit>` | Required public. Referenced from the library's `AndroidManifest.xml` `<provider>` by AndroidX App Startup. It's public for reflective instantiation by the framework, not for consumer calls — the KDoc states this explicitly. |

### `co.crackn.kompressor.image`

| Symbol | Kind | Justification for stability |
|--------|------|----------------------------|
| `ImageCompressor` | Interface | `compress(inputPath, outputPath, config): Result<CompressionResult>` + `supportedInputFormats` / `supportedOutputFormats` default getters. |
| `ImageCompressionConfig` | `data class` | Full parameter bag: `format`, `quality`, `maxWidth`, `maxHeight`, `keepAspectRatio`. `init` validates ranges. |
| `ImagePresets` | `object` | `THUMBNAIL`, `WEB`, `HIGH_QUALITY`. Adding presets in a MINOR is source-compatible. |
| `ImageFormat` | `enum class` | Entries `JPEG` and `WEBP` are Stable. `HEIC` and `AVIF` are Experimental — see below. |
| `ImageFormat.JPEG` | Enum entry | Always available on both platforms. |
| `ImageFormat.WEBP` | Enum entry | Input: Android API 24+, iOS 14+ (decode). Output: Android API 24+. iOS output deliberately surfaces `UnsupportedOutputFormat(minApi = NOT_IMPLEMENTED)` — contract is documented. |
| `ImageCompressionError` | `sealed class : RuntimeException` | Typed error hierarchy; callers `when`-branch. |
| `ImageCompressionError.Companion` | `object` | Holds `NOT_IMPLEMENTED`. |
| `ImageCompressionError.Companion.NOT_IMPLEMENTED: Int` | `const val` | Sentinel for "never supported at any version" on a platform. Int.MAX_VALUE; callers branch on `minApi == NOT_IMPLEMENTED` before formatting user-facing text. |
| `ImageCompressionError.UnsupportedSourceFormat(details, cause)` | `class` | Container / codec not recognised. |
| `ImageCompressionError.UnsupportedInputFormat(format, platform, minApi, cause)` | `class` | Version-gated read failure. Exposes `format`, `platform`, `minApi`, `isNotImplementedOnPlatform`. |
| `ImageCompressionError.UnsupportedOutputFormat(format, platform, minApi, cause)` | `class` | Version-gated write failure. Same shape as `UnsupportedInputFormat`. |
| `ImageCompressionError.DecodingFailed(details, cause)` | `class` | Decoder produced no bitmap. |
| `ImageCompressionError.EncodingFailed(details, cause)` | `class` | Encoder / bitmap.compress failure. |
| `ImageCompressionError.IoFailed(details, cause)` | `class` | Read/write I/O failure. |
| `ImageCompressionError.Unknown(details, cause)` | `class` | Fallback for un-classifiable platform errors. |

### `co.crackn.kompressor.video`

| Symbol | Kind | Justification for stability |
|--------|------|----------------------------|
| `VideoCompressor` | Interface | `compress(inputPath, outputPath, config, onProgress): Result<CompressionResult>` + `supportedInputFormats` / `supportedOutputFormats` default getters. Rotation-preservation contract documented on the interface. |
| `VideoCompressionConfig` | `data class` | Full parameter bag: `codec`, `maxResolution`, `videoBitrate`, `audioBitrate`, `audioCodec`, `maxFrameRate`, `keyFrameInterval`, `dynamicRange`. `init` validates ranges and rejects HDR10+H.264. |
| `VideoPresets` | `object` | `MESSAGING`, `HIGH_QUALITY`, `LOW_BANDWIDTH`, `SOCIAL_MEDIA`. `HDR10_1080P` is Experimental. |
| `VideoCodec` | `enum class` | `H264`, `HEVC`. |
| `DynamicRange` | `enum class` | `SDR` is Stable. `HDR10` is Experimental. |
| `DynamicRange.SDR` | Enum entry | Default; compatible with every codec. |
| `MaxResolution` | `sealed class` | Closed hierarchy: `Original` or `Custom(maxShortEdge)`. |
| `MaxResolution.Companion` | `object` | Hosts preset constants `SD_480`, `HD_720`, `HD_1080`. |
| `MaxResolution.Companion.SD_480/HD_720/HD_1080` | `Custom` constants | Common device resolutions. |
| `MaxResolution.Custom(maxShortEdge: Int)` | `data class` | Validates `maxShortEdge > 0`. |
| `MaxResolution.Original` | `data object` | Sentinel to keep source resolution. |
| `VideoCompressionError` | `sealed class : Exception` | Typed error hierarchy. |
| `VideoCompressionError.UnsupportedSourceFormat(details, cause)` | `class` | No decoder / unrecognised container. |
| `VideoCompressionError.DecodingFailed(details, cause)` | `class` | Decoder failed mid-stream. |
| `VideoCompressionError.EncodingFailed(details, cause)` | `class` | Encoder / muxer failure. |
| `VideoCompressionError.IoFailed(details, cause)` | `class` | Read/write I/O failure. |
| `VideoCompressionError.Unknown(details, cause)` | `class` | Fallback. |

### `co.crackn.kompressor.audio`

| Symbol | Kind | Justification for stability |
|--------|------|----------------------------|
| `AudioCompressor` | Interface | `compress(inputPath, outputPath, config, onProgress): Result<CompressionResult>` + `supportedInputFormats` / `supportedOutputFormats`. Programmer-error contract documented (IllegalArgumentException wrapping). |
| `AudioCompressionConfig` | `data class` | Full parameter bag: `codec`, `bitrate`, `sampleRate`, `channels`. One property (`audioTrackIndex`) is Experimental. |
| `AudioPresets` | `object` | `VOICE_MESSAGE`, `PODCAST`, `HIGH_QUALITY`. |
| `AudioChannels` | `enum class` | `MONO`, `STEREO` are Stable. `FIVE_POINT_ONE`, `SEVEN_POINT_ONE` are Experimental. `count: Int` property is stable. |
| `AudioChannels.MONO`, `AudioChannels.STEREO` | Enum entries | Universally supported across Android and iOS. |
| `AudioCompressionError` | `sealed class : Exception` | Typed error hierarchy. |
| `AudioCompressionError.UnsupportedSourceFormat(details, cause)` | `class` | No decoder / unrecognised container. |
| `AudioCompressionError.DecodingFailed(details, cause)` | `class` | Decoder failed mid-stream. |
| `AudioCompressionError.EncodingFailed(details, cause)` | `class` | Encoder / muxer / audio-processor failure. |
| `AudioCompressionError.IoFailed(details, cause)` | `class` | Read/write I/O failure. |
| `AudioCompressionError.UnsupportedConfiguration(details, cause)` | `class` | Source + config combination the mixer can't honour (e.g. iOS upmix, unsupported surround). Surfaced *before* the encoder opens. |
| `AudioCompressionError.UnsupportedBitrate(details, cause)` | `class` | Requested bitrate outside encoder's supported range for the given sample rate / channel count. Recoverable by retrying with a different bitrate. |
| `AudioCompressionError.Unknown(details, cause)` | `class` | Fallback. |

## Experimental symbols

> Pre-1.0 incubating surface. Gated by
> `@ExperimentalKompressorApi`. Opting in is an explicit acknowledgement
> that consumer code may have to change across MINOR releases while
> these APIs are stabilised.

### `co.crackn.kompressor.image`

| Symbol | Kind | Rationale for experimental status |
|--------|------|-----------------------------------|
| `ImageFormat.HEIC` | Enum entry | iOS 11+ only; Android has no stable `Bitmap.CompressFormat.HEIC` in this release. Output contract may change once Android adds encoder support. |
| `ImageFormat.AVIF` | Enum entry | Android API 34+, iOS 16+. Platform coverage still expanding; the fallback error shape is under review. |

### `co.crackn.kompressor.video`

| Symbol | Kind | Rationale for experimental status |
|--------|------|-----------------------------------|
| `DynamicRange.HDR10` | Enum entry | BT.2020 + PQ requires HEVC Main10 encoder. Tonemapping / HDR metadata preservation contract across devices is still being tuned. |
| `VideoPresets.HDR10_1080P` | `VideoCompressionConfig` preset | Uses `DynamicRange.HDR10`; inherits the same stability caveats. |

### `co.crackn.kompressor.audio`

| Symbol | Kind | Rationale for experimental status |
|--------|------|-----------------------------------|
| `AudioChannels.FIVE_POINT_ONE` | Enum entry | BS.775-3 downmix matrices + per-device encoder coverage are still being validated. See [`docs/audio-downmix.md`](audio-downmix.md). |
| `AudioChannels.SEVEN_POINT_ONE` | Enum entry | Same as 5.1 — surround output stability depends on in-progress encoder characterisation work. |
| `AudioCompressionConfig.audioTrackIndex` | Property | Multi-track selection semantics (Android MP4-muxer stream-copy restrictions, probe ordering across containers) are still being stabilised. |

### `co.crackn.kompressor`

| Symbol | Kind | Rationale for experimental status |
|--------|------|-----------------------------------|
| `SourceMediaInfo.audioTrackCount` | Property | Paired with `AudioCompressionConfig.audioTrackIndex`; the track-enumeration probe contract moves in lockstep. |

## Internal audit — what was kept hidden

Internal symbols are `internal` (or `@PublishedApi internal` if referenced
from `inline`) and therefore **do not appear in
`kompressor/api/kompressor.api`**. They have no stability guarantee. This
section records what the M2 review deliberately left on the hidden side
of the fence so future audits can check at a glance that nothing drifted
back to public by accident.

### commonMain

| Symbol | File | Why it stays internal |
|--------|------|-----------------------|
| `evaluateSupport(info, capabilities, requiredOutputVideoMime, requiredOutputAudioMime)` | `Supportability.kt` | Matching logic between `SourceMediaInfo` and `DeviceCapabilities`. Exposed indirectly through `Kompressor.canCompress`; there's no reason for consumers to rewire it. |
| `suspendRunCatching(block)` | `SuspendRunCatching.kt` | `runCatching` variant that rethrows `CancellationException`. Implementation helper; trivially one-line at the call site. |
| `MIME_VIDEO_H264`, `MIME_VIDEO_HEVC`, `MIME_AUDIO_AAC` | `Supportability.kt` | Compressor-owned output MIMEs. Consumers discover them via `CodecSupport.mimeType`. |

### androidMain

| Symbol | File | Why it stays internal |
|--------|------|-----------------------|
| `AndroidKompressor` | `AndroidKompressor.kt` | Platform implementation of `Kompressor`; `createKompressor()` is the public constructor. |
| `KompressorContext` | `KompressorInitializer.kt` | Process-local holder for the application `Context`. AndroidX App Startup writes into it via `KompressorInitializer`; consumers never read it. |
| `AndroidImageCompressor`, `AndroidVideoCompressor`, `AndroidAudioCompressor` | `image/audio/video` subpackages | Platform-specific implementations obtained through `Kompressor.image / video / audio`. |
| Media3 / MediaCodec glue (`Media3ExportRunnerKt`, `MediaCodecUtilsKt`, audio/video probes, `ExifRotation`, `ImageSource`, `FilePathSource`, `ContentUriSource`, …) | Various | Implementation detail of the Media3-based transformer pipeline; shape changes every time Media3 rev'd. Keeping these internal is a contract, not an accident — Kover excludes them from the host-only gate (`kompressor/build.gradle.kts:228-249`) precisely because they're device-only. |

### iosMain

| Symbol | File | Why it stays internal |
|--------|------|-----------------------|
| `IosKompressor` | `IosKompressor.kt` | Platform implementation of `Kompressor`; `createKompressor()` is the public constructor. |
| `IosImageCompressor`, `IosVideoCompressor`, `IosAudioCompressor` | `image/audio/video` subpackages | Platform-specific implementations obtained through `Kompressor.image / video / audio`. |
| `IosAVWriterUtils`, `IosFileUtils`, `IosTransformRotation`, `AVNSErrorException`, `DeletingOutputOnFailure.ios.kt` | iosMain | AVFoundation / AVAssetWriter glue. |
| `worker.main(Array<String>)` (`CompressWorkerMain.kt`) | iosSimulator executable | Entry point for the inter-process test binary; K/N's entry-point resolver can't look up `internal`-mangled names, so it stays `public` in source but is namespaced under `co.crackn.kompressor.worker` and ships only in the `iosSimulatorArm64` test executable — never in the framework that consumers link against. Called out here so a future audit doesn't mistake it for a leak. |

No symbol currently in `kompressor/api/kompressor.api` was reclassified to
`I` during this review — every line of the committed dump earns its
place on the Stable or Experimental list.

## How to opt in

**Per call site** (recommended for consumer code — keeps the opt-in
explicit to anyone reading the call):

```kotlin
@OptIn(ExperimentalKompressorApi::class)
fun compressWithSurround() {
    val config = AudioCompressionConfig(channels = AudioChannels.FIVE_POINT_ONE)
    // ...
}
```

**Per module** (appropriate for test modules that exercise the full
experimental surface — used in `kompressor/build.gradle.kts` for
`commonTest` / `androidHostTest` / `androidDeviceTest` / iOS test
targets):

```kotlin
// kompressor/build.gradle.kts
sourceSets {
    matching { it.name.endsWith("Test") }.configureEach {
        languageSettings.optIn("co.crackn.kompressor.ExperimentalKompressorApi")
    }
}
```

## How this list stays in sync

- New public API gated by `@ExperimentalKompressorApi` → add a row under
  [Experimental symbols](#experimental-symbols) **in the same PR** that
  introduces the symbol.
- New Stable public API → add a row under
  [Stable symbols](#stable-symbols) in the same PR. Detekt's
  `UndocumentedPublic*` rules block the PR if the symbol lacks KDoc.
- Stabilising an experimental symbol (removing the annotation) → move
  its row from Experimental to Stable; bump the CHANGELOG under
  `Changed` with a "stabilised" note; no MAJOR bump required because
  removing an opt-in requirement is additive.
- Renaming or removing an experimental symbol → update/delete its row;
  CHANGELOG entry under `Changed (experimental)` so opt-in consumers
  see the breakage.
- Downgrading a previously-public symbol to `internal` → delete its row
  from the Stable section, note it in the
  [Internal audit](#internal-audit--what-was-kept-hidden) section, and
  treat the ABI diff as a MAJOR-only breaking change (pre-1.0 exempt).

Reviewers must confirm the inventory delta matches the ABI dump delta
when a PR modifies `kompressor/api/kompressor.api`. See the S/E/I
checklist in [`docs/api-stability.md`](api-stability.md#pr-review-checklist-for-abi-diffs--s--e--i).

## Sign-off for 1.0 freeze

The 1.0 public-API freeze requires the PR that lands this inventory to
carry approvals from **≥ 2 maintainers**. Reviewer checklist:

- [ ] Every line in `kompressor/api/kompressor.api` has a row here.
- [ ] Every row here points at a symbol in the dump (or, for Internal,
      a file + reason).
- [ ] No symbol is tagged `S` unless it has KDoc on every member Detekt
      would flag (enforced automatically by `./gradlew detekt`).
- [ ] Every `E` row matches a source-level `@ExperimentalKompressorApi`
      annotation (enforced manually — BCV's JVM dump doesn't carry the
      marker).
