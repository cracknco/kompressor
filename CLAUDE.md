# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Kompressor** is a Kotlin Multiplatform (KMP) library for compressing images, videos, and audio on Android and iOS using native hardware encoders (zero binary overhead). Published as `co.crackn.kompressor:kompressor` on Maven Central.

Image, audio, and video compression are all implemented on both platforms:
- **Image**: Android `BitmapFactory` / iOS `UIImage` + Core Graphics.
- **Audio**: Android **Media3 `Transformer` 1.10** (audio-only export via `setRemoveVideo`, `SonicAudioProcessor` for resampling, `ChannelMixingAudioProcessor` for mono/stereo, AAC-in-M4A output, passthrough fast path when input is already AAC at target bitrate/rate/channels) / iOS `AVAssetExportSession`.
- **Video**: Android **Media3 `Transformer` 1.10** via `AndroidVideoCompressor` (handles codec selection, HW/SW fallback, HDR tone-mapping, rotation — zero bundled codec binaries). iOS uses `AVAssetExportSession` / `AVAssetWriter`. See `kompressor/src/*/kotlin/co/crackn/kompressor/video/`.

## Build Commands

```bash
# Build everything
./gradlew build

# Fetch test fixtures (required for device + iOS tests; uses Git LFS)
./scripts/fetch-fixtures.sh

# Verify public API hasn't broken binary compatibility
./gradlew apiCheck

# Run all tests
./gradlew allTests

# Platform-specific tests
./gradlew iosSimulatorArm64Test
./gradlew testAndroidHostTest    # Android unit tests (run on host JVM)

# Run a single test class
./gradlew testAndroidHostTest --tests "co.crackn.kompressor.CompressionResultTest"

# Lint & format
./gradlew ktlintCheck            # Check formatting (ktlint 1.3.1)
./gradlew ktlintFormat           # Auto-fix formatting
./gradlew detekt                 # Static analysis (config: config/detekt/detekt.yml)

# Code coverage
./gradlew koverXmlReport         # Generate coverage report
./gradlew koverVerify            # Quality gate — minimum 85% coverage

# Publish to Maven Central (requires signing keys)
./gradlew publishToMavenCentral
```

## Architecture

### Module Structure

Two Gradle modules (see `settings.gradle.kts`):
- **`:kompressor`** — the published KMP library. All library source lives here.
- **`:sample`** — a sample app that exercises the library. Per user preference, it follows production-grade architecture (kotlin-inject DI, nav library, ViewModels, i18n) even though it's a sample.

The `iosApp/` directory at the repo root is a standalone Xcode project that consumes the KMP framework for iOS sample/testing — it is not a Gradle module.

### KMP Targets

- **Android** (API 24+) — `BitmapFactory` for images; Media3 `Transformer` for video and audio
- **iOS** (15+) — `UIImage`/Core Graphics for images; `AVAssetExportSession` / `AVAssetWriter` for video and audio

### Source Set Layout

```text
kompressor/src/
├── commonMain/              # Shared API: Kompressor interface, configs, result types
├── commonTest/              # Shared tests (Kotest + Turbine)
├── androidMain/             # Android implementations using MediaCodec, BitmapFactory
├── androidHostTest/         # Android unit tests (run on host JVM, not device)
├── androidDeviceTest/       # Android device-only tests — video golden + property tests live here (MediaCodec requires a real device/emulator)
├── iosMain/                 # iOS implementations using AVFoundation, VideoToolbox
└── iosTest/                 # iOS tests (run on simulator)
```

### Key API Pattern

Uses KMP `expect`/`actual` for the factory function:

```kotlin
// commonMain
expect fun createKompressor(): Kompressor

interface Kompressor {
    val image: ImageCompressor
    val video: VideoCompressor
    val audio: AudioCompressor

    suspend fun probe(inputPath: String): Result<SourceMediaInfo>
    fun canCompress(info: SourceMediaInfo): Supportability
}
```

Each platform provides an `actual fun createKompressor()` that returns platform-native implementations.

Use `probe` → `canCompress` to gate UX before attempting compression — returns a typed `Supportability` reflecting decoder/encoder availability on the running device (advisory, not a runtime guarantee).

All compress operations return `Result<CompressionResult>` — a unified result type with `inputSize`, `outputSize`, `compressionRatio`, and `durationMs`. Audio and video `compress()` methods accept an `onProgress: suspend (Float) -> Unit` callback for real-time progress tracking (0.0–1.0). Image compression has no progress callback because the underlying platform APIs are synchronous single-step operations.

### Important Patterns

- **`suspendRunCatching`** — custom wrapper around `runCatching` that re-throws `CancellationException` to preserve structured concurrency. All platform compress implementations must use this instead of bare `runCatching`.
- **`internal` visibility** — all platform implementation classes (e.g., `AndroidImageCompressor`, `IosAudioCompressor`) are `internal`. Only the `createKompressor()` factory and shared interfaces/configs are public API.
- **Android Context via App Startup** — `KompressorInitializer` (an `androidx.startup.Initializer`) captures the application `Context` at startup, so `createKompressor()` needs no parameters on Android.
- **Config validation** — all configuration classes validate inputs in `init` blocks (quality 0–100, positive dimensions, etc.). Invalid configs throw `IllegalArgumentException` at construction time.
- **No upscaling** — image resize never upscales. If the source is already within constraints, original dimensions are returned.
- **EXIF orientation** — Android image compression reads EXIF data and applies rotation transforms before encoding.

### Testing

Tests use **Kotest** (assertions + property testing) and **Turbine** (Flow testing). Android host tests run on the JVM without a device. iOS tests run on the simulator.

Testing layers and what each catches:

- **`commonTest`** — pure-logic units reachable from every target: config validation, `Supportability`/`evaluateSupport`, `VideoCompressionError` / `AudioCompressionError` subtypes, `Kompressor.probe/canCompress` contract. No platform I/O.
- **`androidHostTest`** — Android-flavoured unit tests that run on the host JVM (no device): `MediaCodec`-free logic like `classifyMedia3ErrorBand`, `Media3ErrorMapping`, `AudioMedia3ErrorMapping`, `planAudioProcessors`, `buildAudioEncoderSettings`, `deletingOutputOnFailure`. Kover-included; gated to 85%.
- **`androidDeviceTest`** — on-device (emulator or physical) end-to-end golden + property tests for the actual transcoder (`AndroidVideoCompressor`, `AndroidAudioCompressor`). Not run by PR CI; validates the Media3 `Transformer` integration, listener lifecycle, partial-output cleanup, AAC-passthrough fast path, and cross-format input (WAV/MP3/AAC/MP4-with-audio).
- **`iosTest`** — iOS simulator end-to-end tests for `IosVideoCompressor` / `IosAudioCompressor` (progress dedup, `AVAssetExportSession` / `AVAssetWriter` paths). Runs in the release workflow on macOS.

Platform-native calls (`MediaCodecList`, `MediaMetadataRetriever`, `AVURLAsset`, `AVAssetExportSession`) are exercised only in `androidDeviceTest` / `iosTest`. Host tests must not try to stand them up.

### Build System

- **Gradle** with version catalog at `gradle/libs.versions.toml`
- **Kotlin 2.3.20**, **AGP 8.13.2**
- Uses `com.android.kotlin.multiplatform.library` plugin (not the legacy `com.android.library`)
- Publishing via `com.vanniktech.maven.publish` (0.36.0) — `./gradlew publishToMavenCentral --no-configuration-cache`
- Configuration cache is enabled (disabled for the publish task because the `com.vanniktech.maven.publish` plugin can't serialise its signing config)

### Code Quality

- **Compiler**: `allWarningsAsErrors = true` — all warnings are build failures
- **Ktlint** for formatting (max line length 120, 4-space indent)
- **Detekt** for static analysis — requires KDoc on all public API (`UndocumentedPublicClass/Function/Property` active), max method length 30, cyclomatic complexity 15
- **Kover** for coverage — 85% minimum enforced

## MCP Servers

The project configures MCP servers in `.mcp.json` for AI-assisted development:

- **kotlin-mcp-server** — 32+ tools for Kotlin/Android development (Lint, Detekt, ktlint, code generation, architecture scaffolding). Requires local setup: `git clone https://github.com/normaltusker/kotlin-mcp-server.git ~/kotlin-mcp-server && pip install -r ~/kotlin-mcp-server/requirements.txt`
- **claude-in-mobile** — Mobile development assistant (pre-existing)

## CI

PR checks (`.github/workflows/pr.yml`) — 5 jobs, Java 21. Device tests + merged-coverage gates were retired per `docs/adr/002-decline-level-3-supply-chain.md` (see PR #104).
- **Ktlint**, **Detekt**, **Gitleaks** — `ubuntu-latest`.
- **Tests & coverage (host)** — `ubuntu-latest`. Fetches LFS fixtures, runs `testAndroidHostTest` + `apiCheck` + `koverXmlReport` + `koverVerify` (≥85%). `apiCheck` failures are surfaced as GitHub annotations pointing callers at `./gradlew apiDump`. Fast feedback gate (~3–5 min).
- **iOS simulator tests** — `macos-latest`. Fetches LFS fixtures, caches `~/.konan`, runs `./gradlew :kompressor:iosSimulatorArm64Test` (~6–10 min).

Release pipeline (`.github/workflows/release.yml`): push to `main` → `test-native` (iOS simulator tests on `macos-latest`) → `release` (`semantic-release` cuts a GitHub Release + CHANGELOG bump) → `publish` (`./gradlew publishToMavenCentral --no-configuration-cache` with signing keys from repo secrets). The iOS test gate is duplicated across `pr.yml` and `release.yml` on purpose: PR gate catches regressions before merge, release gate is a safety net before publishing.
