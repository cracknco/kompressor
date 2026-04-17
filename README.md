<h1 align="center">Kompressor</h1>

<p align="center">
  Compress images, videos, and audio on Android and iOS — one API, native hardware, zero binaries.
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/co.crackn.kompressor/kompressor"><img src="https://img.shields.io/maven-central/v/co.crackn.kompressor/kompressor?color=blue&label=Maven%20Central" /></a>
  <a href="https://github.com/cracknco/kompressor/actions/workflows/pr.yml"><img src="https://github.com/cracknco/kompressor/actions/workflows/pr.yml/badge.svg" /></a>
  <a href="https://opensource.org/licenses/Apache-2.0"><img src="https://img.shields.io/badge/License-Apache%202.0-green.svg" /></a>
  <img src="https://img.shields.io/badge/Kotlin-Multiplatform-orange?logo=kotlin" />
  <img src="https://img.shields.io/badge/Android-API%2024%2B-brightgreen?logo=android" />
  <img src="https://img.shields.io/badge/iOS-15%2B-lightgrey?logo=apple" />
</p>

---

## Why Kompressor?

Every app that handles user-generated media has the same problem: images are too heavy to upload, videos take forever to share, and audio files pile up storage. Solving this per-platform means writing platform-specific code twice — or shipping a 30 MB FFmpeg binary nobody asked for.

Kompressor provides a **single Kotlin API** that delegates to the native hardware encoders already on the device. No binaries. No extra dependencies. Just compression.

|  | **Kompressor** | ffmpeg-kit.kmp | Platform-specific libs |
|--|--|--|--|
| Image + Video + Audio | ✅ | ✅ | ❌ Each is separate |
| Android + iOS (KMP) | ✅ | ✅ | ❌ Write it twice |
| Hardware acceleration | ✅ | ❌ CPU only | Varies |
| Binary overhead | **0 KB** | +15–30 MB | N/A |
| Coroutine progress callbacks | ✅ | ❌ | ❌ |
| Maintained (2026) | ✅ | ❌ Retired | Varies |

---

## Features

- 🖼️ **Image compression** — JPEG with quality control and resizing (PNG, WebP planned)
- 🎬 **Video compression** — H.264 config and presets defined, implementation coming soon
- 🔊 **Audio compression** — AAC config and presets defined, implementation coming soon
- 🚀 **Hardware-accelerated** — BitmapFactory on Android, UIImage/Core Graphics on iOS
- 📱 **True KMP** — one API, shared business logic, native performance
- 🎛️ **Sensible defaults** — works out of the box, configurable when you need control
- **0 KB overhead** — no binaries, no FFmpeg, nothing added to your app

---

## Platform Support

| Platform | Minimum | Image backend | Video backend | Audio backend |
|----------|---------|--------------|--------------|--------------|
| Android | API 24 (7.0) | `BitmapFactory` + `Bitmap.compress` | `MediaCodec` + `MediaMuxer` (planned) | `MediaCodec` (planned) |
| iOS | iOS 15 | `UIImage` / Core Graphics | `AVAssetExportSession` (planned) | `AVAssetExportSession` (planned) |

---

## Installation

```kotlin
// build.gradle.kts (shared module)
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("co.crackn.kompressor:kompressor:0.1.0")
        }
    }
}
```

> Snapshots: `https://s01.oss.sonatype.org/content/repositories/snapshots/`

---

## Quick Start

### Image

```kotlin
val kompressor = createKompressor()

val result = kompressor.image.compress(
    inputPath  = "/path/to/photo.jpg",
    outputPath = "/path/to/photo_compressed.jpg",
)

result.onSuccess { println("Compressed: ${it.compressionRatio} ratio in ${it.durationMs}ms") }
    .onFailure { println("Error: ${it.message}") }
```

### Video (coming soon)

```kotlin
val result = kompressor.video.compress(
    inputPath  = "/path/to/video.mp4",
    outputPath = "/path/to/out.mp4",
    onProgress = { fraction -> updateProgressBar(fraction) },
)

result.onSuccess { println("Done — ratio ${it.compressionRatio}") }
    .onFailure { println("Error: ${it.message}") }
```

### Audio (coming soon)

```kotlin
val result = kompressor.audio.compress(
    inputPath  = "/path/to/recording.wav",
    outputPath = "/path/to/recording.aac",
)

result.onSuccess { println("${it.outputSize / 1000} KB") }
    .onFailure { println("Error: ${it.message}") }
```

---

## Image

### Configuration

```kotlin
val config = ImageCompressionConfig(
    format  = ImageFormat.JPEG,
    quality = 80,              // 0–100
    maxWidth  = 1920,
    maxHeight = 1080,
    keepAspectRatio = true,
)

val result = kompressor.image.compress(inputPath, outputPath, config)
```

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `format` | `ImageFormat` | `JPEG` | Output format |
| `quality` | `Int` | `80` | Compression quality (0–100). |
| `maxWidth` | `Int?` | `null` | Max output width in pixels. `null` = no limit. |
| `maxHeight` | `Int?` | `null` | Max output height in pixels. `null` = no limit. |
| `keepAspectRatio` | `Boolean` | `true` | Maintain aspect ratio when resizing. |

### Formats

Input containers (auto-detected from magic bytes, with an extension fallback for DNG):

| Input | Android | iOS | Notes |
|-------|---------|-----|-------|
| JPEG / PNG / WebP / GIF / BMP | ✅ API 24+ | ✅ iOS 15+ | Universal. |
| HEIC / HEIF | ✅ API **30+** | ✅ iOS 15+ | Below API 30, Kompressor fails with `UnsupportedInputFormat(minApi = 30)` rather than gamble on OEM decoder coverage. |
| AVIF | ✅ API **31+** | ✅ iOS **16+** | Typed `UnsupportedInputFormat` on older platforms. |
| DNG (raw) | ✅ API 24+ | ✅ iOS 15+ | Extension-based detection; quality depends on the device's RAW pipeline. |

Output formats:

| `ImageFormat` | Android | iOS | Notes |
|--------------|---------|-----|-------|
| `JPEG` | ✅ API 24+ | ✅ iOS 15+ | Lossy. Best for photos. |
| `WEBP` | ✅ API 24+ | ❌ | Lossy WebP (deprecated `WEBP` constant below API 30, `WEBP_LOSSY` above). iOS surfaces `UnsupportedOutputFormat`. |
| `HEIC` (`@ExperimentalKompressorApi`) | ❌ | ✅ iOS 15+ | Android has no stable `Bitmap.CompressFormat.HEIC` in this release. |
| `AVIF` (`@ExperimentalKompressorApi`) | ✅ API **34+** | ✅ iOS **16+** | Best ratio. Typed `UnsupportedOutputFormat` on older platforms. |

Full matrix, decision rationale, and sentinel `minApi` values: see [`docs/format-support.md`](docs/format-support.md).

### Presets

```kotlin
kompressor.image.compress(inputPath, outputPath, ImagePresets.THUMBNAIL)
kompressor.image.compress(inputPath, outputPath, ImagePresets.WEB)
kompressor.image.compress(inputPath, outputPath, ImagePresets.HIGH_QUALITY)
```

| Preset | Format | Quality | Max size |
|--------|--------|---------|---------|
| `THUMBNAIL` | JPEG | 60 | 320px |
| `WEB` | JPEG | 80 | 1920px |
| `HIGH_QUALITY` | JPEG | 95 | Original |

### Progress tracking

Audio and video compression report real-time progress via an `onProgress` callback:

```kotlin
val result = kompressor.audio.compress(
    inputPath  = inputPath,
    outputPath = outputPath,
    onProgress = { fraction -> println("Progress: ${(fraction * 100).toInt()}%") },
)
```

> **Note:** Image compression does not offer progress tracking because the underlying platform APIs
> (`Bitmap.compress` on Android, `UIImageJPEGRepresentation` on iOS) are synchronous single-step
> operations with no intermediate progress data.
>
> **Migration note:** `ImageCompressor.compress(...)` no longer accepts `onProgress`.
> Remove that argument from existing image compression call sites.

### Cancellation

Cancellation uses structured concurrency — cancel the calling coroutine scope:

```kotlin
val job = scope.launch {
    kompressor.image.compress(inputPath, outputPath)
}

job.cancel() // compression is cancelled
```

---

## Video (coming soon)

> Video compression is defined at the API level but not yet implemented. The interfaces, configs, and presets below are available — the platform implementations are in progress.

### Configuration

```kotlin
val config = VideoCompressionConfig(
    codec            = VideoCodec.H264,
    maxResolution    = MaxResolution.HD_720,
    videoBitrate     = 1_200_000,
    audioBitrate     = 128_000,
    audioCodec       = AudioCodec.AAC,
    maxFrameRate     = 30,
    keyFrameInterval = 2,
)

val result = kompressor.video.compress(inputPath, outputPath, config)
```

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `codec` | `VideoCodec` | `H264` | Video codec |
| `maxResolution` | `MaxResolution` | `HD_720` | Maximum output resolution |
| `videoBitrate` | `Int` | `1_200_000` | Video bitrate in bps |
| `audioBitrate` | `Int` | `128_000` | Audio bitrate in bps |
| `audioCodec` | `AudioCodec` | `AAC` | Audio codec |
| `maxFrameRate` | `Int` | `30` | Max frame rate |
| `keyFrameInterval` | `Int` | `2` | Key frame interval in seconds |

### Resolution

| Constant | Value |
|----------|-------|
| `MaxResolution.SD_480` | 480p |
| `MaxResolution.HD_720` | 720p |
| `MaxResolution.HD_1080` | 1080p |
| `MaxResolution.Original` | Keep source resolution |
| `MaxResolution.Custom(n)` | Custom shortest-edge value |

### Presets

| Preset | Codec | Resolution | Video | Audio |
|--------|-------|-----------|-------|-------|
| `MESSAGING` | H.264 | 720p | 1 200 kbps | 128 kbps AAC |
| `HIGH_QUALITY` | H.264 | 1080p | 3 500 kbps | 192 kbps AAC |
| `LOW_BANDWIDTH` | H.264 | 480p | 600 kbps | 96 kbps AAC |

---

## Audio (coming soon)

> Audio compression is defined at the API level but not yet implemented. The interfaces, configs, and presets below are available — the platform implementations are in progress.

### Configuration

```kotlin
val config = AudioCompressionConfig(
    codec      = AudioCodec.AAC,
    bitrate    = 128_000,
    sampleRate = 44100,
    channels   = AudioChannels.STEREO,
)

val result = kompressor.audio.compress(inputPath, outputPath, config)
```

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `codec` | `AudioCodec` | `AAC` | Output codec |
| `bitrate` | `Int` | `128_000` | Bitrate in bps |
| `sampleRate` | `Int` | `44100` | Sample rate in Hz |
| `channels` | `AudioChannels` | `STEREO` | Mono or Stereo |

### Codecs

| `AudioCodec` | Android | iOS | Notes |
|-------------|---------|-----|-------|
| `AAC` | ✅ | ✅ | Best choice for speech and music. |

### Presets

| Preset | Codec | Bitrate | Sample rate | Channels |
|--------|-------|---------|-------------|----------|
| `VOICE_MESSAGE` | AAC | 32 kbps | 22 050 Hz | Mono |
| `PODCAST` | AAC | 96 kbps | 44 100 Hz | Stereo |
| `HIGH_QUALITY` | AAC | 192 kbps | 44 100 Hz | Stereo |

---

## API Reference

### Entry point

```kotlin
// commonMain
expect fun createKompressor(): Kompressor

interface Kompressor {
    val image: ImageCompressor
    val video: VideoCompressor
    val audio: AudioCompressor
}
```

On Android, `Context` is obtained automatically via AndroidX App Startup — no manual initialization needed.

### `ImageCompressor`

```kotlin
interface ImageCompressor {
    suspend fun compress(
        inputPath: String,
        outputPath: String,
        config: ImageCompressionConfig = ImageCompressionConfig(),
    ): Result<CompressionResult>
}
```

### `VideoCompressor`

```kotlin
interface VideoCompressor {
    suspend fun compress(
        inputPath: String,
        outputPath: String,
        config: VideoCompressionConfig = VideoCompressionConfig(),
        onProgress: suspend (Float) -> Unit = {},
    ): Result<CompressionResult>
}
```

### `AudioCompressor`

```kotlin
interface AudioCompressor {
    suspend fun compress(
        inputPath: String,
        outputPath: String,
        config: AudioCompressionConfig = AudioCompressionConfig(),
        onProgress: suspend (Float) -> Unit = {},
    ): Result<CompressionResult>
}
```

### `CompressionResult`

```kotlin
data class CompressionResult(
    val inputSize: Long,        // bytes
    val outputSize: Long,       // bytes
    val durationMs: Long,       // milliseconds
) {
    val compressionRatio: Float // outputSize / inputSize (< 1.0 = smaller)
}
```

---

## Expected Performance

### Image

| Input | Quality 80 JPEG |
|-------|----------------|
| 5 MB DSLR photo | ~800 KB |
| 3 MB iPhone photo | ~500 KB |
| 1 MB screenshot | ~200 KB |

---

## Versioning & Stability

Kompressor follows [Semantic Versioning 2.0.0](https://semver.org) **strictly** from version **1.0.0** onward:

- **MAJOR** — breaking changes to the public API.
- **MINOR** — additive, backward-compatible features.
- **PATCH** — backward-compatible bug fixes.

APIs annotated with `@ExperimentalKompressorApi` or declared `internal` are **not** covered by the semver contract and may change in any release.

Binary compatibility is maintained across MINOR and PATCH releases for all artifact types (AAR, klib, Kotlin/Native framework).

> Full policy, binary compatibility details, and exemptions: **[docs/api-stability.md](docs/api-stability.md)**

---

## SBOM & supply chain

Every Kompressor release ships a **CycloneDX 1.5** Software Bill of Materials
as a GitHub Release asset:

```
https://github.com/cracknco/kompressor/releases/download/vX.Y.Z/kompressor-X.Y.Z.sbom.json
```

The SBOM lists every runtime dependency with its `purl`, version, license, and
SHA-1/256/384/512 hashes — enough to answer *"am I exposed to this CVE?"* in
under five minutes. It's reproducible from Maven Central via
`syft packages maven-central:co.crackn.kompressor:kompressor:X.Y.Z` and can be
ingested into [Dependency-Track](https://dependencytrack.org/) for continuous
vulnerability tracking.

Full guide — verification with `syft`, Dependency-Track integration, and the
regulatory context (US EO 14028 / EU Cyber Resilience Act) — lives in
**[docs/supply-chain.md](docs/supply-chain.md)**.

> Adjacent asset: `kompressor-spdx.json` (SPDX 2.3 license report). SPDX covers
> licenses; CycloneDX covers components and vulnerabilities.

---

## Contributing

We welcome contributions! See **[CONTRIBUTING.md](CONTRIBUTING.md)** for setup
instructions, commit conventions, and the PR process.

Please note that this project is released with a
**[Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md)**. By participating
in this project you agree to abide by its terms.

---

## License

```
Copyright 2026 crackn.co

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0
```