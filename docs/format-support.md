# Image Format Support Matrix

This document enumerates every image input and output container that Kompressor recognises,
per platform, and the minimum platform version required for each. It is the **authoritative**
matrix — the Kotlin implementation under `kompressor/src/commonMain/kotlin/…/image/` derives
its version constants (`ImageFormatGates.kt`) and typed errors
(`ImageCompressionError.UnsupportedInputFormat` / `UnsupportedOutputFormat`) from it.

Cross-references:
- **[CRA-43 — format matrix](../README.md#formats)** — end-user table.
- **[CRA-5 — fixture prep](../fixtures/manifest.json)** — per-format test fixture entries.
- **[docs/api-stability.md](api-stability.md)** — when experimental formats graduate to stable.

## Decision model

Each cell below carries a minimum platform version ("API 30+") or `—` for "not supported
on this platform in this release". When a caller requests an input/output combination that
requires a newer platform than the device provides, Kompressor fails fast with a typed error
carrying `minApi` so the caller can either:

1. Surface a localised "requires iOS 16+" message, or
2. Catch and retry with a widely-supported format (JPEG, WEBP on Android).

There is no silent fallback. Typed rejection is preferred over producing a surprise format
because the caller usually wants to control the user-facing message.

## Input formats (decode)

| Format | Magic bytes | Android | iOS | Notes |
|--------|-------------|---------|-----|-------|
| **JPEG** | `FF D8 FF` | API 24 (minSdk) | iOS 15 | Universal baseline. |
| **PNG** | `89 50 4E 47 0D 0A 1A 0A` | API 24 | iOS 15 | Includes 16 bpc, palette, Adam7 (coverage varies by OEM). |
| **WEBP** | `RIFF…WEBP` | API 24 | iOS 15 | Lossy + lossless both accepted. |
| **GIF** | `GIF87a` / `GIF89a` | API 24 | iOS 15 | First frame only; animations are flattened. |
| **BMP** | `BM` | API 24 | iOS 15 | Rarely encountered but cheap to decode. |
| **HEIC** | `ftyp`+`heic`/`heix`/`hevc`/… | **API 30+** | iOS 15 | Android gate: OEM coverage of HEIC is spotty below API 30, so we reject-typé rather than hand a caller a lottery ticket. |
| **HEIF** | `ftyp`+`mif1`/`msf1` | **API 30+** | iOS 15 | Same gate as HEIC. |
| **AVIF** | `ftyp`+`avif`/`avis` | **API 31+** | **iOS 16+** | First shipped in `BitmapFactory` on API 31; iOS ImageIO in 16.0. |
| **DNG (raw)** | *(extension fallback)* | API 24 | iOS 15 | DNG uses a TIFF container that can't be disambiguated by magic bytes alone; we fall back to the `.dng` extension. Decode quality depends on the device's RAW pipeline. |
| **Unknown** | *(not matched)* | decode still attempted | decode still attempted | Lets exotic formats (e.g. TGA on OEMs that ship a decoder) pass through instead of being pre-rejected. |

The gate is implemented in `ImageFormatGates.kt::androidInputGate` / `iosInputGate`. Rejected
inputs surface as `ImageCompressionError.UnsupportedInputFormat(format, platform, minApi)`.

## Output formats (encode)

| Format | Android | iOS | Notes |
|--------|---------|-----|-------|
| **JPEG** | API 24 (`Bitmap.CompressFormat.JPEG`) | iOS 15 (`UIImageJPEGRepresentation`) | Always available; `quality` 0–100 maps 1:1. |
| **WEBP** | API 24 (`WEBP_LOSSY` ≥ API 30, deprecated `WEBP` below) | **—** (CGImageDestination has no `org.webmproject.webp` destination type) | iOS decode is fine; encode surfaces `UnsupportedOutputFormat`. |
| **HEIC** | **—** (no stable `Bitmap.CompressFormat.HEIC` — HeifWriter is a separate, heavier API tracked for a later milestone) | iOS 11+ (`CGImageDestination` with `public.heic`) | Always available at our iOS 15 floor. |
| **AVIF** | **API 34+** (`Bitmap.CompressFormat.AVIF`) | **iOS 16+** (`CGImageDestination` with `public.avif`) | Best compression at equivalent quality. Gated behind `@ExperimentalKompressorApi`. |

The gate is implemented in `ImageFormatGates.kt::androidOutputGate` / `iosOutputGate`. Rejected
outputs surface as `ImageCompressionError.UnsupportedOutputFormat(format, platform, minApi)`.

### Synthetic `minApi` sentinels

Two rows above reject across every platform version in this release:

| Platform × format | Sentinel | Meaning |
|-------------------|----------|---------|
| Android × HEIC output | `minApi = Int.MAX_VALUE` | Android has no stable `Bitmap.CompressFormat.HEIC`. Surfaces as `"requires android 2147483647+"` — deliberately obvious so callers don't read it as a real version number. HeifWriter wiring is tracked separately. |
| iOS × WEBP output | `minApi = Int.MAX_VALUE` | iOS ImageIO does not expose `org.webmproject.webp` as a destination UTI on any version we support. |

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
| Pure logic (sniffer + gates) | `commonTest/.../image/InputImageFormatTest.kt`, `ImageFormatGatesTest.kt` | JVM host + Kotlin/Native host (every push) |
| Android platform round-trip | `androidDeviceTest/.../ImageFormatMatrixTest.kt` | Firebase Test Lab, Pixel 6 API 33 |
| iOS platform round-trip | `iosTest/.../IosImageFormatMatrixTest.kt` | `macos-latest` simulator (iOS 17+) |

Real HEIC/AVIF/DNG fixture files (device round-trip + pixel-level assertions) are delivered by
**CRA-5**. This milestone intentionally scopes to platform-API coverage + typed-error contract so
the gate logic is shippable without blocking on fixture storage.
