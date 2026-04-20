# Error taxonomy

Kompressor's compress APIs surface failures as sealed class hierarchies wrapped in
`Result.failure(...)`. Typed errors let callers `when`-branch on the concrete subtype
instead of parsing platform messages — `UnsupportedSourceFormat` warrants a "convert
first" UI, while `IoFailed` warrants a "free up storage" UI, and the two need
different telemetry.

This page is the engineering reference. For the shorter user-facing walkthrough see
[`docs/concepts/errors.mdx`](concepts/errors.mdx) (rendered into the Mintlify site).

> **Auto-generated from source.** Do not edit by hand — run
> `kotlin scripts/gen-error-taxonomy.kts` after modifying any of the three
> `…CompressionError.kt` sealed classes and commit the result. `--check` gives
> a non-zero exit code when the committed doc is stale.

## How to read this page

Each hierarchy section has three parts:

1. **Tree** — the sealed class and its direct subtypes, with platform markers for
   subtypes that only fire on one side.
2. **Subtypes** — one row per subtype: *when* the library raises it (extracted from
   the KDoc summary), whether retrying the same call is safe, and what a consumer
   app should typically do.
3. **Cross-hierarchy** — the [Platform divergence](#platform-divergence) and
   [Handling critical cases](#handling-critical-cases) sections at the bottom pull
   the platform-specific / action-critical rows into one place.

Retry-safe column values:

- **`no`** — the same input will fail the same way on this device. Don't retry.
- **`yes (…)`** — retry after the parenthesised change (different config, fallback
  format, resolved user action).
- **`depends`** / **`maybe`** — check the `details` field or error fields to decide.

## `ImageCompressionError`

Source: [`ImageCompressionError.kt`](../kompressor/src/commonMain/kotlin/co/crackn/kompressor/image/ImageCompressionError.kt)

### Tree

```text
ImageCompressionError (sealed)
├── UnsupportedSourceFormat
├── UnsupportedInputFormat
├── UnsupportedOutputFormat
├── DecodingFailed
├── EncodingFailed
├── IoFailed
└── Unknown
```

### Subtypes

| Subtype | When it fires | Retry-safe? | Consumer fix |
|---------|---------------|-------------|--------------|
| `UnsupportedSourceFormat` | The source file's container / codec is not recognised by the platform image decoder (e.g. a random-bytes file passed through an image compressor) | no | Offer a "convert first" flow. The bytes can't be decoded by the current platform decoder. |
| `UnsupportedInputFormat` | The source file's container is recognised but the current platform version cannot decode it | depends | Show a localised "requires $platform $minApi+" hint when `isNotImplementedOnPlatform == false`; otherwise reject (this platform never decodes the format). Never retry the same config on this device. |
| `UnsupportedOutputFormat` | The requested output format cannot be produced on the current platform version | yes (fallback format) | Retry with a widely-supported format — JPEG everywhere, WEBP on Android, HEIC on iOS 15+. |
| `DecodingFailed` | The platform decoder was invoked but failed to produce a bitmap (truncated JPEG, corrupt PNG, unsupported bit depth / color space for this OEM, etc.) | no | File-specific — ask the user to re-export or reacquire the source. |
| `EncodingFailed` | Encoding the output JPEG/PNG failed (OOM, bitmap.compress returned false, etc.) | maybe (OOM path) | Usually device-wide. Free memory and retry once with smaller `maxWidth` / `maxHeight`; else report. |
| `IoFailed` | I/O failure reading the input or writing the output (missing file, permission denied, disk full, revoked `content://` URI, etc.) | yes (after user fix) | Show storage / permission UI and retry when the user resolves it. |
| `Unknown` | Fallback for platform errors we couldn't classify | no | Report with `cause` attached — we failed to classify it. |

## `AudioCompressionError`

Source: [`AudioCompressionError.kt`](../kompressor/src/commonMain/kotlin/co/crackn/kompressor/audio/AudioCompressionError.kt)

### Tree

```text
AudioCompressionError (sealed)
├── UnsupportedSourceFormat
├── DecodingFailed
├── EncodingFailed
├── IoFailed
├── UnsupportedConfiguration
├── UnsupportedBitrate *(iOS-only)*
└── Unknown
```

### Subtypes

| Subtype | When it fires | Retry-safe? | Consumer fix |
|---------|---------------|-------------|--------------|
| `UnsupportedSourceFormat` | No decoder on this device supports the source file's codec / profile, or the container format is not recognised | no | Offer a "convert first" flow (AAC / MP3 / WAV). iOS additionally rejects MP3 / FLAC / OGG inputs — see `docs/format-support.md`. |
| `DecodingFailed` | A decoder was found and initialised but failed while decoding the stream (corrupt file, OEM codec bug mid-stream, unexpected end of stream) | no | File-specific — don't retry the same bytes. |
| `EncodingFailed` | Encoding the output failed (no AAC encoder, out of memory, muxer refused a sample, audio-processor reconfiguration error, etc.) | maybe | Usually device-wide. Try a different bitrate / sample rate once; else report. |
| `IoFailed` | I/O failure reading the input file or writing the output (permission denied, disk full, `content://` URI backed by a revoked provider, etc.) | yes (after user fix) | Show storage / permission UI and retry when resolved. |
| `UnsupportedConfiguration` | The requested [AudioCompressionConfig] is not supported for this input on the current platform — e.g. iOS cannot upmix a mono source to a stereo output, or the input has more channels (5.1 / 7.1) than the compressor's channel mixer can handle | yes (narrower config) | Retry with a config the source supports — typically `channels = AudioChannels.MONO` or drop the upmix ask. |
| `UnsupportedBitrate` | The requested bitrate falls outside the platform encoder's supported range for the given sample rate and channel count | yes (in-range bitrate) | Read `details`, clamp the requested bitrate to the reported range, retry. |
| `Unknown` | Fallback for platform errors we couldn't classify | no | Report with `cause` attached. |

## `VideoCompressionError`

Source: [`VideoCompressionError.kt`](../kompressor/src/commonMain/kotlin/co/crackn/kompressor/video/VideoCompressionError.kt)

### Tree

```text
VideoCompressionError (sealed)
├── UnsupportedSourceFormat
├── Hdr10NotSupported *(Android-only)*
├── DecodingFailed
├── EncodingFailed
├── IoFailed
└── Unknown
```

### Subtypes

| Subtype | When it fires | Retry-safe? | Consumer fix |
|---------|---------------|-------------|--------------|
| `UnsupportedSourceFormat` | No decoder on this device supports the source file's codec/profile/level (e.g. HEVC Main 10 on a device that only ships HEVC Main), or the container format is not recognised | no | Offer a "convert first" flow — the codec / profile / level isn't decodable on this device. |
| `Hdr10NotSupported` | The device's HEVC encoder declared HDR10 Main10 support via its capability matrix but the runtime probe (actually allocating the encoder with 10-bit P010 input) failed — an OEM firmware bug where `MediaCodecList` over-advertises | yes (SDR fallback) | Retry with `DynamicRange.SDR`, or surface "HDR10 requires a newer device". `device` / `codec` / `reason` are the bug-report payload. We deliberately don't auto-downgrade — that would be silent data loss. |
| `DecodingFailed` | A decoder was found and initialised but failed while decoding the stream (corrupt file, OEM codec bug mid-stream, unexpected end of stream) | no | File-specific — ask the user to re-export the source. |
| `EncodingFailed` | Encoding the output failed (no H.264 encoder, out of memory, muxer refused a sample, etc.) | maybe | Try once at a lower resolution or bitrate; else report. |
| `IoFailed` | I/O failure reading the input file or writing the output (permission denied, disk full, network-backed URI failed, etc.) | yes (after user fix) | Show storage / permission UI and retry when resolved. |
| `Unknown` | Fallback for platform errors we couldn't classify | no | Report with `cause` attached. |

## Platform divergence

Most subtypes are symmetric across Android and iOS — callers can share `when`-branches.
The divergent cases below are the ones worth singling out in platform-aware code.

| Subtype | Android | iOS | Reason |
|---------|:-------:|:---:|--------|
| `AudioCompressionError.UnsupportedBitrate` | — | ✅ | Raised exclusively by the iOS pipeline. |
| `VideoCompressionError.Hdr10NotSupported` | ✅ | — | Raised exclusively by the Android pipeline. |

## Handling critical cases

The snippet below covers the critical branches: the retry-worthy subtypes get
dedicated arms so their remediation is explicit, while the "no retry" subtypes
collapse into a reporting fallback. Adapt it to your app's reporting surface.

```kotlin
kompressor.video.compress(inputPath, outputPath, config)
    .onFailure { err ->
        when (err) {
            is VideoCompressionError.UnsupportedSourceFormat ->
                showConvertFirstBanner(err.details)

            is VideoCompressionError.Hdr10NotSupported ->
                // Android-only. Retry with SDR; never silently downgrade.
                retry(config.copy(dynamicRange = DynamicRange.SDR))

            is VideoCompressionError.IoFailed ->
                showStorageErrorDialog(err.details)

            is VideoCompressionError.DecodingFailed ->
                // File-specific — ask for a re-export.
                reportBadFile(err)

            is VideoCompressionError.EncodingFailed,
            is VideoCompressionError.Unknown ->
                reportToCrashAnalytics(err)
        }
    }
```

The audio and image hierarchies follow the same shape. `AudioCompressionError`
additionally has `UnsupportedConfiguration` (retry with a narrower channel layout)
and — iOS-only — `UnsupportedBitrate` (retry with an in-range bitrate);
`ImageCompressionError` additionally has `UnsupportedInputFormat` /
`UnsupportedOutputFormat` (version-gated — branch on `minApi` and
`isNotImplementedOnPlatform`).

## Don't forget `CancellationException`

Because every `compress` call is a `suspend` function, `CancellationException` is
**re-thrown**, not wrapped in `Result.failure`. Your `onFailure { }` block won't see
cancellations. See [`docs/threading-model.md`](threading-model.md) for the structured
concurrency contract.

## Regenerating this document

```bash
# From the repo root:
kotlin scripts/gen-error-taxonomy.kts            # rewrite docs/error-handling.md
kotlin scripts/gen-error-taxonomy.kts --check    # CI drift check, exits non-zero on drift
```

The script parses the three `…CompressionError.kt` sources with regex, renders this
file, and (in `--check` mode) diffs it against the committed copy.
