<h1 align="center">Kompressor</h1>

<p align="center">
  Compress images, videos, and audio on Android and iOS — one API, native hardware, zero binaries.
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/co.crackn/kompressor"><img src="https://img.shields.io/maven-central/v/co.crackn/kompressor?color=blue&label=Maven%20Central" /></a>
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
- 🎬 **Video compression** — H.264 + AAC, resolution/bitrate/framerate control, presets
- 🔊 **Audio compression** — AAC output from WAV/MP3/M4A/FLAC/OGG/Opus/AMR/MP4-audio; bitstream-passthrough fast path when the input is already AAC at the target settings
- 🧭 **Probe & capability check** — inspect a file's tracks (`probe`) and ask the device whether it can compress it (`canCompress`) *before* starting, so you can gate the UX on decoder/encoder availability, HDR, 10-bit, resolution and framerate caps
- 🚀 **Hardware-accelerated native backends** — `BitmapFactory` + Media3 `Transformer` on Android, Core Graphics + AVFoundation (`AVAssetExportSession` / `AVAssetWriter`) on iOS
- ⛑️ **Typed errors** — `when`-branch on `AudioCompressionError` / `VideoCompressionError` subtypes for actionable UX instead of "compression failed"
- 📱 **True KMP** — one API, shared business logic, native performance
- 🎛️ **Sensible defaults** — works out of the box, configurable when you need control
- **0 KB overhead** — no binaries, no FFmpeg, nothing added to your app

---

## Platform Support

| Platform | Minimum | Image backend | Video backend | Audio backend |
|----------|---------|--------------|--------------|--------------|
| Android | API 24 (7.0) | `BitmapFactory` + `Bitmap.compress` | Media3 `Transformer` 1.10 (H.264) | Media3 `Transformer` 1.10 (AAC / M4A) |
| iOS | iOS 15 | `UIImage` / Core Graphics | `AVAssetExportSession` / `AVAssetWriter` | `AVAssetExportSession` / `AVAssetWriter` |

---

## Installation

```kotlin
// build.gradle.kts (shared module)
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("co.crackn:kompressor:0.1.0")
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

### Video

```kotlin
val result = kompressor.video.compress(
    inputPath  = "/path/to/video.mp4",
    outputPath = "/path/to/out.mp4",
    onProgress = { fraction -> updateProgressBar(fraction) },
)

result.onSuccess { println("Done — ratio ${it.compressionRatio}") }
    .onFailure { println("Error: ${it.message}") }
```

### Audio

```kotlin
val result = kompressor.audio.compress(
    inputPath  = "/path/to/recording.wav",
    outputPath = "/path/to/recording.m4a",
)

result.onSuccess { println("${it.outputSize / 1000} KB") }
    .onFailure { println("Error: ${it.message}") }
```

---

## Before you compress: probe + capability check

Transcoding a file just to discover the device can't decode it wastes time and leaves users staring at an indeterminate spinner. Kompressor gives you two cheap checks to run first:

```kotlin
// 1. Read the source's track metadata (codec, resolution, bit depth, HDR, etc.)
val info: SourceMediaInfo = kompressor.probe(inputPath).getOrThrow()

// 2. Ask the device whether it can actually compress this (required decoder
//    + required encoder + resolution / fps / bit depth / HDR caps).
when (val verdict = kompressor.canCompress(info)) {
    Supportability.Supported ->
        kompressor.video.compress(inputPath, outputPath)

    is Supportability.Unsupported ->
        showError("Can't compress: ${verdict.reasons.joinToString()}")

    is Supportability.Unknown ->
        // Probe couldn't verify something (e.g. bit depth). Warn and
        // optionally let the user attempt compression anyway.
        showWarning(verdict.reasons)
}
```

`probe` is a quick metadata read (`MediaExtractor` / `MediaMetadataRetriever` on Android, `AVURLAsset` on iOS) — not a transcode. `canCompress` compares the probe against the device's reported codec capability matrix. Both are advisory: a `Supported` verdict does not guarantee the transcode will succeed (drivers can still fail at runtime), but together they catch the common "no decoder for this profile" / "HEVC 10-bit on an 8-bit-only decoder" failures before the user ever starts a compression.

---

## Handling errors

Audio and video failures surface as typed subclasses so `when` branches can drive actionable UI — e.g. "we can't decode this codec, please convert first" versus "disk full" versus "this recording is too many channels for on-device compression":

```kotlin
result.onFailure { err ->
    when (err) {
        is AudioCompressionError.UnsupportedSourceFormat ->
            showConvertFirstBanner(err.details)
        is AudioCompressionError.UnsupportedConfiguration ->
            fallbackToMono()
        is AudioCompressionError.IoFailed ->
            showStorageError(err.details)
        is AudioCompressionError.DecodingFailed,
        is AudioCompressionError.EncodingFailed,
        is AudioCompressionError.Unknown ->
            reportToCrashlytics(err)
        else -> reportToCrashlytics(err)
    }
}
```

`VideoCompressionError` mirrors this hierarchy (`UnsupportedSourceFormat`, `DecodingFailed`, `EncodingFailed`, `IoFailed`, `Unknown`). Every subtype preserves the underlying platform `cause` for diagnostics.

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

### Cancellation

Cancellation uses structured concurrency — cancel the calling coroutine scope:

```kotlin
val job = scope.launch {
    kompressor.image.compress(inputPath, outputPath)
}

job.cancel() // compression is cancelled
```

---

## Video

Output is **H.264 in an MP4 container** on both platforms. Android runs through Media3 `Transformer` (hardware-first, software fallback); iOS runs through `AVAssetExportSession` / `AVAssetWriter`.

> **Known limitation (v1):** rotation metadata (`preferredTransform` on iOS, `KEY_ROTATION` on Android) is not yet preserved. Portrait-recorded videos may appear rotated in the output until this lands.

### Configuration

```kotlin
val config = VideoCompressionConfig(
    codec            = VideoCodec.H264,
    maxResolution    = MaxResolution.HD_720,
    videoBitrate     = 1_200_000,
    audioBitrate     = 128_000,
    maxFrameRate     = 30,
    keyFrameInterval = 2,
)

val result = kompressor.video.compress(inputPath, outputPath, config)
```

The output audio track is always AAC-LC (muxed into the MP4 container alongside the re-encoded video).

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `codec` | `VideoCodec` | `H264` | Video codec |
| `maxResolution` | `MaxResolution` | `HD_720` | Maximum output resolution |
| `videoBitrate` | `Int` | `1_200_000` | Video bitrate in bps |
| `audioBitrate` | `Int` | `128_000` | Audio bitrate in bps |
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

| Preset | Codec | Resolution | Video | Audio | Notes |
|--------|-------|-----------|-------|-------|-------|
| `MESSAGING` | H.264 | 720p | 1 200 kbps | 128 kbps AAC | Default frame rate, keyframe every 2s |
| `HIGH_QUALITY` | H.264 | 1080p | 3 500 kbps | 192 kbps AAC | Near-original quality |
| `LOW_BANDWIDTH` | H.264 | 480p | 600 kbps | 96 kbps AAC | Caps fps at 24, keyframe every 3s |
| `SOCIAL_MEDIA` | H.264 | 720p | 2 000 kbps | 128 kbps AAC | Keyframe every 1s for clean seeking/scrubbing |

---

## Audio

Output is **AAC in an `.m4a` (MP4) container**. Input can be anything the platform's default extractors open — WAV, MP3, M4A / AAC, FLAC, OGG / Opus, AMR, and the audio track of an MP4 (video is stripped).

> **Fast path:** when the input is already AAC and its bitrate / sample rate / channel count match the requested config within a small tolerance, the compressor activates a **bitstream-copy passthrough** — no decode, no re-encode, so the export finishes in milliseconds. Useful for pre-upload validation pipelines that might run the compressor against already-compressed files.

### Configuration

```kotlin
val config = AudioCompressionConfig(
    bitrate    = 128_000,
    sampleRate = 44100,
    channels   = AudioChannels.STEREO,
)

val result = kompressor.audio.compress(inputPath, outputPath, config)
```

Output is always AAC-LC in an M4A container on both Android and iOS.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `bitrate` | `Int` | `128_000` | Bitrate in bps |
| `sampleRate` | `Int` | `44100` | Sample rate in Hz |
| `channels` | `AudioChannels` | `STEREO` | Mono or Stereo |

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

    /** Read the source's track metadata (codec, resolution, HDR, bit depth, ...). */
    suspend fun probe(inputPath: String): Result<SourceMediaInfo>

    /** Advisory verdict: does the device have the decoders + encoders to handle [info]? */
    fun canCompress(info: SourceMediaInfo): Supportability
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
    val compressionRatio: Float        // outputSize / inputSize (< 1.0 = smaller)
    val isSmallerThanOriginal: Boolean // outputSize < inputSize
}
```

> **Output can be larger than input.** Re-encoding a file that is already heavily compressed (common with JPEG and already-low-bitrate AAC) can produce a *bigger* output, especially if the requested quality / bitrate is higher than the source's. Check `isSmallerThanOriginal` at the call site and discard the compressed file when it would hurt the user's storage budget.

### `SourceMediaInfo`

Returned by `Kompressor.probe(...)`. All fields are nullable because not every container exposes every field.

| Field | Type | Notes |
|-------|------|-------|
| `containerMimeType` | `String?` | e.g. `video/mp4`, `audio/mp4` |
| `videoCodec` / `audioCodec` | `String?` | Track MIMEs, e.g. `video/hevc`, `audio/mp4a-latm` |
| `videoProfile` / `videoLevel` | `String?` | Human-readable, e.g. `"Main 10"`, `"5.0"` |
| `width` / `height` / `rotationDegrees` | `Int?` | Pre-rotation pixel dimensions + rotation metadata |
| `frameRate` | `Float?` | fps |
| `bitDepth` | `Int?` | 8 / 10 / 12 (relevant for HDR / 10-bit HEVC) |
| `isHdr` | `Boolean` | Whether an HDR transfer function is present |
| `bitrate` / `durationMs` | `Int?` / `Long?` | Container total bitrate and duration |
| `audioSampleRate` / `audioChannels` | `Int?` | Audio track sample rate and channel count |
| `isPlayable` | `Boolean?` | Populated on iOS (`AVAssetTrack.isPlayable`); null on Android |

### `Supportability`

Returned by `Kompressor.canCompress(...)`:

```kotlin
sealed class Supportability {
    object Supported                                        : Supportability()
    data class Unsupported(val reasons: List<String>)       : Supportability()  // hard blocker
    data class Unknown(val reasons: List<String>)           : Supportability()  // probe couldn't verify
}
```

- **`Unsupported`** — at least one hard blocker (missing decoder / encoder, source exceeds decoder max resolution or fps, 10-bit source on an 8-bit-only decoder, HDR on a non-HDR decoder). Don't start a compression.
- **`Unknown`** — something the probe can't confirm (e.g. bit depth not present in metadata). Surface a warning and let the user attempt compression; the real outcome comes back through the typed error hierarchy if it fails.

### Error types

```kotlin
sealed class AudioCompressionError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class UnsupportedSourceFormat(val details: String, cause: Throwable? = null) :
        AudioCompressionError("Unsupported source format: $details", cause)
    class DecodingFailed(val details: String, cause: Throwable? = null) :
        AudioCompressionError("Decoding failed: $details", cause)
    class EncodingFailed(val details: String, cause: Throwable? = null) :
        AudioCompressionError("Encoding failed: $details", cause)
    class IoFailed(val details: String, cause: Throwable? = null) :
        AudioCompressionError("IO failed: $details", cause)
    class UnsupportedConfiguration(val details: String, cause: Throwable? = null) :
        AudioCompressionError("Unsupported configuration: $details", cause)
    class Unknown(val details: String, cause: Throwable? = null) :
        AudioCompressionError("Compression failed: $details", cause)
}
// VideoCompressionError mirrors this shape without UnsupportedConfiguration.
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