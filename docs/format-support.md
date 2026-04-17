# Format Support Matrix

This document is the single source-of-truth answer to the "does Kompressor support &lt;format&gt;
on &lt;platform&gt; at &lt;version&gt;?" question. It is enforced against code by
`FormatSupportDocUpToDateTest` (host JVM) and `FormatSupportMatrixConsistencyTest` (common) —
CI fails if the tables below drift from the Kotlin implementation under
`kompressor/src/commonMain/kotlin/…/image/`, `…/audio/`, and `…/video/`.

Cross-references:
- **[CRA-43 — format matrix](../README.md#formats)** — end-user summary with quick-start links.
- **[CRA-5 — fixture prep](../fixtures/manifest.json)** — per-format test fixture entries.
- **[docs/api-stability.md](api-stability.md)** — when experimental formats graduate to stable.

## Decision model

Each cell below carries a minimum platform version ("API 30+") or `—` for "not supported on this
platform in this release". When a caller requests an input/output combination that requires a
newer platform than the device provides, Kompressor fails fast with a typed error carrying
`minApi` so the caller can either:

1. Surface a localised "requires iOS 16+" message, or
2. Catch and retry with a widely-supported format (JPEG, WEBP on Android).

There is no silent fallback. Typed rejection is preferred over producing a surprise format
because the caller usually wants to control the user-facing message.

### Fast path

The `Fast-path (Android)` / `Fast-path (iOS)` columns in the tables below refer to Kompressor's
bitstream-copy shortcut, tracked per platform because the two SDKs differ materially (see the
Video bullet). `—` means the format is unsupported on that platform; `Yes` / `No` mean the
format decodes but the fast path is / isn't available:

- **Audio**: AAC-in / AAC-out when sample rate, channel count, and bitrate match the config
  within ±20 % — Media3 activates a passthrough on Android, `AVAssetExportSession` does the same
  on iOS.
- **Video**: iOS only — `AVAssetExportSession` rewrites the container when the caller asks for
  the default config (no bitrate / resolution change). Android's Media3 Transformer always
  re-encodes.
- **Image**: no fast-path in this release — every image compression round-trips through the
  platform decoder and encoder because the common use case is a format or quality change.

### Auto-generation contract

The three tables below are generated from [`FormatSupportMatrix`][matrix-src]. Do not edit them
by hand — run `./scripts/regenerate-format-support-doc.sh` and commit the result. The
`FormatSupportDocUpToDateTest` test enforces this at PR time (via
`./gradlew :kompressor:testAndroidHostTest`) and a dedicated
`format-support-check.yml` workflow runs the same check on every push, bypassing the
`docs/**` paths-ignore filter so doc-only PRs cannot regress the matrix either.

[matrix-src]: ../kompressor/src/commonMain/kotlin/co/crackn/kompressor/matrix/FormatSupportMatrix.kt

## Matrix

<!-- BEGIN GENERATED: format-support-matrix (CRA-43) -->
<!-- DO NOT EDIT BY HAND — regenerate via `./scripts/regenerate-format-support-doc.sh`. -->

### Image formats

| Format in | Format out | Android min-API | iOS min-version | Codec path | Fast-path (Android) | Fast-path (iOS) | Notes |
|-----------|------------|-----------------|-----------------|------------|---------------------|-----------------|-------|
| JPEG | JPEG / WEBP (Android) / HEIC (iOS) / AVIF | 24 | 15 | Android `BitmapFactory` + `Bitmap.compress` / iOS `CGImageSource` + `CGImageDestination` | No | No | Universal baseline. Always decodable. |
| PNG | JPEG / WEBP (Android) / HEIC (iOS) / AVIF | 24 | 15 | Android `BitmapFactory` + `Bitmap.compress` / iOS `CGImageSource` + `CGImageDestination` | No | No | Alpha channel is dropped when transcoding to JPEG (no alpha support). |
| WEBP | JPEG / WEBP (Android) / HEIC (iOS) / AVIF | 24 | 15 | Android `BitmapFactory` + `Bitmap.compress` / iOS `CGImageSource` + `CGImageDestination` | No | No | Lossy + lossless both accepted on decode. WebP output is Android-only (iOS ImageIO lacks a destination UTI). |
| HEIC | JPEG / WEBP (Android) / HEIC (iOS) / AVIF | **30** | 15 | Android `BitmapFactory` + `Bitmap.compress` / iOS `CGImageSource` + `CGImageDestination` | No | No | `@ExperimentalKompressorApi`. Android gate: OEM HEIC decoder coverage is spotty below API 30. |
| HEIF | JPEG / WEBP (Android) / HEIC (iOS) / AVIF | **30** | 15 | Android `BitmapFactory` + `Bitmap.compress` / iOS `CGImageSource` + `CGImageDestination` | No | No | Same gate as HEIC. |
| AVIF | JPEG / WEBP (Android) / HEIC (iOS) / AVIF | **31** | **16** | Android `BitmapFactory` + `Bitmap.compress` / iOS `CGImageSource` + `CGImageDestination` | No | No | `@ExperimentalKompressorApi`. Android decode added in `BitmapFactory` at API 31; iOS ImageIO in 16.0. |
| GIF | JPEG / WEBP (Android) / HEIC (iOS) / AVIF | 24 | 15 | Android `BitmapFactory` + `Bitmap.compress` / iOS `CGImageSource` + `CGImageDestination` | No | No | Animations are flattened — only the first frame is decoded. |
| BMP | JPEG / WEBP (Android) / HEIC (iOS) / AVIF | 24 | 15 | Android `BitmapFactory` + `Bitmap.compress` / iOS `CGImageSource` + `CGImageDestination` | No | No | Rarely encountered; cheap to decode. |
| DNG (raw) | JPEG / WEBP (Android) / HEIC (iOS) / AVIF | 24 | 15 | Extension-only sniffer → platform RAW pipeline | No | No | TIFF-based container; magic-byte sniffing falls back to the `.dng` extension. Decode quality depends on the device's RAW pipeline. |

### Audio formats

| Format in | Format out | Android min-API | iOS min-version | Codec path | Fast-path (Android) | Fast-path (iOS) | Notes |
|-----------|------------|-----------------|-----------------|------------|---------------------|-----------------|-------|
| AAC (M4A / MP4) | AAC | 24 | 15 | Android Media3 `MediaExtractor` → AAC encoder / iOS `AVAssetReader` → `AVAssetWriter` | Yes | Yes | Bitstream-copy passthrough when input config (sample rate / channels / bitrate within ±20%) matches the requested output. |
| MP3 | AAC | 24 | — | Android Media3 MP3 extractor → AAC encoder | No | — | iOS: AVFoundation's built-in extractors do not decode MP3 in the Kompressor pipeline (M4A / WAV / AIF only). |
| FLAC | AAC | 24 | — | Android Media3 FLAC extractor → AAC encoder | No | — | iOS: not supported (same reason as MP3). |
| OGG (Vorbis) | AAC | 24 | — | Android Media3 OGG extractor → AAC encoder | No | — | iOS: not supported. |
| Opus (OGG container) | AAC | 24 | — | Android Media3 OGG extractor → AAC encoder | No | — | iOS: not supported. Multi-track Opus sources additionally fail on Android because `MediaMuxer`'s MP4 container only accepts AAC / AMR — see `AudioCompressionConfig.audioTrackIndex` docs. |
| AMR-NB | AAC | 24 | — | Android Media3 AMR extractor → AAC encoder | No | — | Phone-call codec (8 kHz mono). iOS: not supported. |
| WAV (PCM) | AAC | 24 | 15 | Android Media3 WAV extractor → AAC encoder / iOS `AVAudioFile` | No | No | Supported on both platforms. Kompressor resamples 24-bit PCM losslessly to the encoder input format. |

### Video formats

| Format in | Format out | Android min-API | iOS min-version | Codec path | Fast-path (Android) | Fast-path (iOS) | Notes |
|-----------|------------|-----------------|-----------------|------------|---------------------|-----------------|-------|
| H.264 (AVC) | H.264 / HEVC | 24 | 15 | Android Media3 `Transformer` (MediaCodec) / iOS `AVAssetReader` + `AVAssetWriter` | No | Yes | iOS fast path: `AVAssetExportSession` passthrough at default config (no bitrate / resolution change). Android always re-encodes via Media3. |
| H.265 / HEVC | H.264 / HEVC | 24 | 15 | Android Media3 `Transformer` (MediaCodec) / iOS `AVAssetReader` + `AVAssetWriter` | No | Yes | HDR10 preservation requires HEVC output (see `VideoPresets.HDR10_1080P`). iOS fast path applies at default config. |
| VP9 | H.264 / HEVC | 24 | — | Android Media3 `Transformer` (MediaCodec, device-dependent decoder) | No | — | Android support is device-dependent — advertised by `MediaCodecList` on most modern devices. Not part of the iOS guarantee. |
| AV1 | H.264 / HEVC | **29** | — | Android Media3 `Transformer` (MediaCodec AV1 decoder, API 29+) | No | — | Android: AV1 decoder is platform-level from API 29. Not part of the iOS guarantee. |

<!-- END GENERATED: format-support-matrix (CRA-43) -->

## Image output gate detail

The image output surface is narrower than the input surface. Every non-trivial row is
encoded in `ImageFormatGates.kt::androidOutputGate` / `iosOutputGate`; rejected outputs surface
as `ImageCompressionError.UnsupportedOutputFormat(format, platform, minApi)` so callers can
branch on `isNotImplementedOnPlatform` vs a genuine version gate.

| Format | Android | iOS | Notes |
|--------|---------|-----|-------|
| **JPEG** | API 24 (`Bitmap.CompressFormat.JPEG`) | iOS 15 (`CGImageDestination` with `public.jpeg`) | Always available; `quality` 0–100 maps 1:1. |
| **WEBP** | API 24 (`WEBP_LOSSY` ≥ API 30, deprecated `WEBP` below) | **—** (`CGImageDestination` has no `org.webmproject.webp` destination type) | iOS decode is fine; encode surfaces `UnsupportedOutputFormat` with the `NOT_IMPLEMENTED` sentinel. |
| **HEIC** | **—** (no stable `Bitmap.CompressFormat.HEIC` — HeifWriter is a separate, heavier API tracked for a later milestone) | iOS 11+ (`CGImageDestination` with `public.heic`) | Always available at our iOS 15 floor. |
| **AVIF** | **API 34+** (`Bitmap.CompressFormat.AVIF`) | **iOS 16+** (`CGImageDestination` with `public.avif`) | Best compression at equivalent quality. Gated behind `@ExperimentalKompressorApi`. |

### Synthetic `minApi` sentinels

Two rows above reject across every platform version in this release:

| Platform × format | Sentinel | Meaning |
|-------------------|----------|---------|
| Android × HEIC output | `minApi = ImageCompressionError.NOT_IMPLEMENTED` | Android has no stable `Bitmap.CompressFormat.HEIC`. HeifWriter wiring is tracked separately. |
| iOS × WEBP output | `minApi = ImageCompressionError.NOT_IMPLEMENTED` | iOS ImageIO does not expose `org.webmproject.webp` as a destination UTI on any version we support. |

## Experimental gating

`HEIC` and `AVIF` entries in the public `ImageFormat` enum are annotated with
`@ExperimentalKompressorApi`. Callers must opt in at the call site:

```kotlin
@OptIn(ExperimentalKompressorApi::class)
val config = ImageCompressionConfig(format = ImageFormat.AVIF, quality = 80)
```

This is because the underlying platform support is still tightening:

- Android AVIF encode lands in API 34; OEM decoder coverage for AVIF input varies at API 31–33.
- iOS HEIC output is stable at iOS 11+, but the encoder's internal codec choice (hevc vs. av01 for
  AVIF-in-HEIC containers) is opaque and has changed between minor iOS releases.

Once the platform matrix settles (typically two MINOR Kompressor releases), the annotation is
removed and the format graduates to the stable surface — this is additive per
[`docs/api-stability.md`](api-stability.md#experimental-apis-experimentalkompressorapi).

## Testing matrix

| Layer | File | Runs on |
|-------|------|---------|
| Matrix cell-by-cell consistency | `commonTest/.../matrix/FormatSupportMatrixConsistencyTest.kt` | JVM host + Kotlin/Native host (every push) |
| Doc file up-to-date | `androidHostTest/.../matrix/FormatSupportDocUpToDateTest.kt` | JVM host (every push, including doc-only PRs via `format-support-check.yml`) |
| Image gate logic | `commonTest/.../image/ImageFormatGatesTest.kt`, `InputImageFormatTest.kt` | JVM host + Kotlin/Native host |
| Audio input round-trip | `androidDeviceTest/.../MultiFormatInputTest.kt`, `iosTest/.../AudioInputRobustnessTest.kt` | Firebase Test Lab / `macos-latest` simulator |
| Video input round-trip | `androidDeviceTest/.../HdrVideoCompressionTest.kt`, `iosTest/.../IosHdrCompressionTest.kt` | Firebase Test Lab / `macos-latest` simulator |
| `Supportability.evaluateSupport` decoder/encoder match | `commonTest/.../SupportabilityTest.kt` | JVM host + Kotlin/Native host |

Real HEIC/AVIF/DNG fixture files (device round-trip + pixel-level assertions) are delivered by
**CRA-5**. This doc's job is to make the gate logic legible to consumers without reading code.
