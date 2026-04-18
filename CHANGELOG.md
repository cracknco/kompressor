# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Removed

* **test:** retire hardware-characterization suite per [`docs/adr/002-decline-level-3-supply-chain.md`](docs/adr/002-decline-level-3-supply-chain.md) — removed tests that exercised the *platform's behaviour* (pixel fidelity, empirical bitrate matrices, matrix conformance, inter-process compress, `androidx.startup` race, compile-exhaustive error-taxonomy) rather than the wrapper's contract. Inventory:
  * **Pixel / matrix fidelity:** `Bs775DownmixMatrixTest` + `Bs775ReferenceFixture` [CRA-13]; `Hdr10PixelFidelityRoundTripTest` + `Hdr10ColorMath` + `Hdr10Mp4Generator` + `GenerateHdr10Fixture` + `scripts/generate-hdr10-fixture.sh` [CRA-6].
  * **Empirical bitrate tables:** `AudioToolboxBitrateCharacterizationTest` + `docs/audio-bitrate-matrix.md` [CRA-78/CRA-82]; `Surround51RoundTripTest` + `Surround71RoundTripTest` + `IosSurroundAudioTest`.
  * **Inter-process:** `ConcurrentCompressInterProcessTest` (Android + iOS), `InterProcessCompressWorker`, `CompressWorkerMain`, `PosixSpawn.def`/`.h`, the `iosSimulatorArm64` `compressWorker` executable target, and `KOMPRESSOR_COMPRESS_WORKER_PATH` wiring [CRA-14, CRA-80].
  * **Large-file / init race / taxonomy:** `LargeVideoInputStreamingTest` + `LargeMp4Fixture` + `PeakMemorySampler` (iOS `LargeFileCompressionTest` kept as the representative) [CRA-11/CRA-81]; `KompressorInitializerConcurrencyTest` [CRA-15]; `ErrorTaxonomyCompletenessTest` (Detekt `TooGenericExceptionThrown` extension remains as the static guardrail) [CRA-21].
  * **Production revert:** iOS audio constants (`iosAacMaxBitrate`/`iosAacMinBitrate`/`checkSupportedIosBitrate`) use the linear per-channel model only — reverted the mono-44.1 empirical 256 kbps override and the surround zero-out; platform-native rejection surfaces through the existing error-mapping.
  * **Docs:** `docs/audio-downmix.md` trimmed (194 → 37 lines); `docs/threading-model.md` inter-process section removed [CRA-38]
* **ci:** retire CI overbuild — CodeBadger (Joern CPG static analysis on every PR, 600+ lines of workflow + custom queries) + AWS Device Farm iOS smoke + `ios-audio-characterization` workflow + Firebase Test Lab on-PR hard-gate + merged-90% coverage gate + `iosDeviceSmokeTests/` standalone Xcode project. Rationale: the indie KMP CI benchmark (FileKit / Kermit / Turbine / Coil-KMP / Koin) runs ktlint + detekt + gitleaks + host tests + iOS simulator tests on PR — supply-chain-regulated-project signalling (Joern, multi-OEM device farms, merged-coverage hard-gates tied to external services) is out of scope per `docs/adr/002-decline-level-3-supply-chain.md`. Preserved: host tests + apiCheck + kover 85%, iOS simulator tests, ktlint, detekt, gitleaks, license-headers (SPDX convention), GPG Maven Central signing. Secrets decommissioneable: AWS_ROLE_ARN, AWS_DEVICEFARM_*, APPLE_TEAM_ID, IOS_CERT_P12/PASSWORD, IOS_PROVISIONING_PROFILE, FIREBASE_SERVICE_ACCOUNT, MOBILE_CI_BUDGET_MONTH [CRA-37]
* **ci/supply-chain:** retire the level-3 supply-chain pipeline (SPDX generation + CycloneDX SBOM + transitive license allowlist) introduced pre-v1.0 and now out of scope for v1.1+. Aligns the project with standard indie-KMP scope (FileKit / Kermit / Turbine / Coil-KMP / Koin don't publish SBOMs or enforce license allowlists either). Removed: `scripts/generate-spdx.sh`, `scripts/check-licenses.sh`, `docs/supply-chain.md`, root + `:kompressor` `cyclonedxBom` task, `com.github.jk1.dependency-license-report` + `org.cyclonedx.bom` plugins, `spdx-report` + `sbom` jobs in `release.yml`, `dependency-licenses` job in `license-check.yml`. Preserved: GPG-signed Maven Central publication, SPDX header convention per `.kt` file, Gitleaks secret scanning, semantic-release + CHANGELOG. Decision documented in new `docs/adr/002-decline-level-3-supply-chain.md` [CRA-28, CRA-30]

### Changed

* **docs/api:** expand `docs/api-inventory.md` into the full 1.0 S/E/I freeze list. Every line of `kompressor/api/kompressor.api` is now classified Stable or Experimental with a justification; Internal symbols (absent from the dump by construction) are enumerated separately as a review trail. No symbol was reclassified — the existing ABI dump was audited and every entry stands as intentional public surface. Adds a maintainer sign-off checklist at the bottom of the doc so the 1.0 freeze PR carries explicit two-approver attestation [CRA-17]
* **audio:** zero out iOS surround (5.1/7.1) AAC caps — Device Farm run 24536970778 (iPhone 13 / A15 / iOS 18) confirmed AudioToolbox rejects multichannel AAC output at every tested bitrate (32k–1280k). `iosAacMaxBitrate` / `iosAacMinBitrate` now return 0 for ≥3 channels, and `checkSupportedIosBitrate` surfaces `UnsupportedConfiguration` instead of letting the request reach the hardware encoder probe. Surround AAC remains supported on Android [CRA-82, CRA-78]
* **audio/ios:** encode mono-at-44.1 kHz AAC-LC non-linear cap — the same Device Farm run ([run 24536970778](https://github.com/cracknco/kompressor/actions/runs/24536970778)) found AudioToolbox allocates a larger VBR budget to single-channel streams at 44.1 kHz than the linear per-channel model predicts: up to **256 kbps** accepted (60% above the old 160 kbps cap). `iosAacMaxBitrate` adds a targeted `sampleRate == 44_100` override; other rates retain the linear model. `docs/audio-bitrate-matrix.md` spliced with the full empirical Y/N grid [CRA-78]

### Fixed

* **ci:** add `CUSTOMER_ARTIFACT` type to Device Farm artifact download in `ios-audio-characterization.yml` and unzip before scanning — XCTAttachments are packaged inside CUSTOMER_ARTIFACT zips, not as FILE type [CRA-82]

### Added

* **api:** `@ExperimentalKompressorApi` opt-in marker (WARNING level) now gates the pre-1.0 incubating surface so consumers can distinguish stable from stabilising APIs without surprise breakage. Applied to `DynamicRange.HDR10`, `VideoPresets.HDR10_1080P`, `AudioChannels.FIVE_POINT_ONE` / `AudioChannels.SEVEN_POINT_ONE`, `AudioCompressionConfig.audioTrackIndex`, and `SourceMediaInfo.audioTrackCount` (joins the previously-annotated `ImageFormat.HEIC` / `ImageFormat.AVIF`). The `kompressor/build.gradle.kts` test source sets opt in at module level via `languageSettings.optIn(...)` so test code never needs per-class `@OptIn`. Exhaustive inventory + call-site / module-level opt-in recipes live in new `docs/api-inventory.md`; stability contract continues to live in `docs/api-stability.md` [CRA-16]
* **test:** iOS inter-process `compress()` regression guard — `iosTest/ConcurrentCompressInterProcessTest` spawns 4 real OS processes (verified by distinct PIDs) × 4 coroutines = 16 parallel `createKompressor().image.compress(...)` calls via a dedicated `compressWorker` K/N executable (`iosMain/.../worker/CompressWorkerMain.kt`) launched through `posix_spawn(2)`. A cinterop shim (`PosixSpawn.h`/`.def`) bridges `posix_spawn`/`waitpid` + `WIFEXITED`/`WEXITSTATUS` since K/N's iOS `platform.posix` binding omits them, and forwards `*_NSGetEnviron()` to preserve the simulator's `DYLD_ROOT_PATH` through the spawn. Gradle wires the linked worker binary into the `iosSimulatorArm64Test` task via `KOMPRESSOR_COMPRESS_WORKER_PATH` (both plain and `SIMCTL_CHILD_`-prefixed so `simctl spawn` forwards it to the simulator child). Closes the `iosMain`-specific static-lock regression gap documented in `docs/threading-model.md#ios-inter-process-coverage--known-gap` [CRA-80]
* **ci/test:** typed-error taxonomy audit (`scripts/audit-throws.sh`) — classifies every `throw` / `error(` site under `kompressor/src/{commonMain,androidMain,iosMain}/` into `TYPED` / `RETHROW` / `REMAPPED` / `INTERNAL` buckets with an explicit allowlist, and exits non-zero on any unclassified hit. Wired into the Detekt CI job. Paired with `ErrorTaxonomyCompletenessTest` (commonTest) which enforces the subtype contract on every platform via compile-time-exhaustive `when`-branches, and a Detekt `TooGenericExceptionThrown` config extension that blocks `throw IllegalStateException(...)` / `IllegalArgumentException(...)` / `RuntimeException(...)` / `Exception(...)` / `Throwable(...)` / `Error(...)` / `NullPointerException(...)` statically [CRA-21]
* **image:** modern-input format expansion phase 1 — decode HEIC/HEIF on Android API 30+ and iOS 15+, decode AVIF on Android 31+ and iOS 16+, DNG routed to `BitmapFactory` (best-effort). Encode AVIF on Android 34+, encode HEIC and AVIF on iOS 16+ via `CGImageDestination`. New typed errors `ImageCompressionError.UnsupportedInputFormat` / `UnsupportedOutputFormat` carry `format`, `platform`, and `minApi` so callers can gate on the decision matrix directly; `ImageCompressionError.NOT_IMPLEMENTED` sentinel + `isNotImplementedOnPlatform` convenience let callers distinguish "requires newer OS" from "never supported on this platform". New `@ExperimentalKompressorApi` annotation gates the new `ImageFormat.HEIC` / `ImageFormat.AVIF` enum values (WEBP remains stable — the typed `ImageCompressionError.UnsupportedOutputFormat` / `NOT_IMPLEMENTED` already makes the iOS decode-only contract explicit without an opt-in gate). Matrix is authoritatively documented in `docs/format-support.md` (cross-ref CRA-43) and covered by the `ImageFormatMatrixTest` / `IosImageFormatMatrixTest` platform sweeps; fixture manifest gains `sample_1024x768.heic`, `sample_1024x768.avif`, and `sample_canon_5d_mk2.dng` (cross-ref CRA-5) [CRA-72] (#92)
* **test/ci:** Swift port of the AudioToolbox AAC-LC bitrate characterization sweep (`iosDeviceSmokeTests/Tests/AudioBitrateCharacterizationTests.swift`) runs the full `[1, 2, 6, 8] × 32k–1280k` grid on real A15+ hardware via AWS Device Farm, emitting the acceptance matrix as an `XCTAttachment` / GitHub Actions artifact. New `workflow_dispatch`-only workflow (`.github/workflows/ios-audio-characterization.yml`) and dedicated `CharacterizationTests` xctest target keep the one-shot discovery run separate from the per-PR smoke lane. The existing Kotlin sibling stays simulator-gated to `[1, 2]` as a cheap sanity guardrail [CRA-78]
* **test:** iOS path-encoding contract for AVFoundation-hostile Unicode — `IosPathEncodingTest` drives each iOS compressor (image / audio / video) through 12 output-path cases covering emoji ZWJ family (👨‍👩‍👧), Arabic RTL (`مرحبا`), a bare zero-width joiner between ASCII letters (`a\u200Db`), and NFD café (`cafe\u0301`, ca + U+0301 combining acute), asserting every case either succeeds with a non-empty output or fails with the typed `*CompressionError.IoFailed` carrying a non-blank reason — never an opaque `Unknown`, NPE, or platform crash. Complements the existing `PathEncodingTest` (spaces / NFC accents / CJK / single-code-point emoji) [CRA-10]
* **test:** iOS >100 MB streaming stress test on device — `IosLargeInputStreamingTests` generates a ~200 MB 1080p 60s H.264 fixture on the fly (no commit), runs it through `IosVideoCompressor.compress`, and asserts process-level peak `phys_footprint` stays ≤ 300 MB (via Mach `task_info(TASK_VM_INFO)`), output is re-probable (AVURLAsset + video track + positive duration), and `onProgress` emits ≥ 5 updates. Runs on AWS Device Farm only (placement in `iosDeviceSmokeTests` bundle) [CRA-11]
* **test:** Android >100 MB video streaming stress test on device — `LargeVideoInputStreamingTest` (sibling of `IosLargeInputStreamingTests`) generates a ~200 MB 1080p 60s H.264 fixture on-device via `MediaCodec` + `MediaMuxer` (no commit), runs it through `AndroidVideoCompressor.compress`, and asserts process-level peak PSS stays ≤ 400 MB (via `Debug.getPss()` sampled every 50 ms on a background thread — budget calibrated to Android PSS semantics, which counts shared ART/system pages that iOS `phys_footprint` does not; a full-load implementation would still sit well above 500 MB), output is re-probable (`MediaExtractor` + video track + positive duration), and `onProgress` emits ≥ 5 updates. Runs on Firebase Test Lab (Pixel 6 API 33) only [CRA-81]
* **test:** HDR10 pixel-fidelity round-trip test — canonical P010/HEVC Main10 fixture generator (`scripts/generate-hdr10-fixture.sh` + on-device instrumented generator), `Hdr10PixelFidelityRoundTripTest` asserts BT.2020 primaries survive compression within ΔE ≤ 2 (CIEDE2000) and `MediaFormat` color metadata (BT.2020 primaries/matrix + ST.2084 transfer) is preserved end-to-end [CRA-6]
* **test:** pixel-content sentinel for video rotation preservation — `VideoRotationSentinelTest` (Android instrumented) and `IosVideoRotationSentinelTest` (iOS simulator, covers both `AVAssetExportSession` fast-path and `AVAssetWriter` transcode path) plant coloured corner markers into a rotated YUV fixture and assert the displayed first frame still carries the same markers at the same corners (red TL, green TR, blue BL, ±12 RGB on Android / ±5 on iOS to absorb codec quantisation), catching 90° wrong-way (CW-vs-CCW) bugs that dim-swap assertions miss [CRA-8]
* **test/docs:** `Bs775DownmixMatrixTest` host-side conformance check pinning the 7.1 → stereo / 7.1 → 5.1 surround downmix matrices against ITU-R BS.775-3 reference coefficients (±0.01 tolerance, intentional divergences pinned with rationale), canonical 7.1 PCM fixture generator, and `docs/audio-downmix.md` documenting the impl / BS.775-3 / Dolby (ATSC A/52) matrices and every divergence [CRA-13]
* **test:** reproducible M1 edge-case fixtures (VBR MP3, FLAC with embedded cover art, CMYK JPEG) and round-trip tests pinning compressor contract (Xing VBR round-trip, FLAC PICTURE block dropped from AAC output, CMYK → RGB conversion or typed `ImageCompressionError`) [CRA-5]
* **test:** inter-process concurrent `compress()` regression guard on Android host JVM (4 processes × 4 coroutines), 16-coroutine stress variant on iOS simulator and Android device, `docs/threading-model.md`, and consistent thread-safety KDoc on all public compressor APIs [CRA-14]
* **license:** Apache-2.0 SPDX headers, transitive dependency license audit, and SPDX release asset [CRA-26] (#79)
* GitHub issue templates: bug report, feature request, regression (form-based `.yml`) [CRA-34]
* GitHub issue template config with link to Discussions for questions [CRA-34]
* Pull request template with DoD checklist and changelog entry section [CRA-34]
* **ci/docs:** committed ABI baseline + fail-hard CI gate — the `test` job in `pr.yml` now annotates `apiCheck` failures with an actionable `::error::` message ("Run `./gradlew apiDump` and commit the result if intentional"), and `docs/api-stability.md` documents the dump-update workflow plus the S/E/I review checklist (Source compatibility, Experimental?, Intentional?) that reviewers must walk through on every ABI diff [CRA-23]

### Changed

* **ci:** `changelog-check` workflow now parses `CHANGELOG.md` content directly at the base and head revisions via `scripts/ci/check-unreleased-entry.sh`, instead of scraping `git diff` output with sed/grep. Eliminates false failures when a new bullet sits outside the unified-diff context window (previously mitigated by a fragile `-U1000` widen). Ships with a self-contained shell test suite (`scripts/ci/check-unreleased-entry.test.sh`, 8 cases) that reproduces PR #83's pre-fix layout [CRA-79]

### Fixed

* **errors:** iOS `VideoCompressor.compress` no longer leaks an untyped `IllegalArgumentException` from the "no video track found in input file" path — this pre-condition now surfaces as the typed `VideoCompressionError.UnsupportedSourceFormat` that Android has returned all along, so KMP callers get consistent `when`-branchable failures. Same treatment for a file-size race on the happy path of both iOS compressors: the post-pipeline size read now goes through `sizeOrTypedError`, preventing a stray `IllegalStateException("Cannot read file size")` from escaping if the output file disappears between `writer.finishWriting()` and the size probe. `AudioCompressor.compress` KDoc now documents the one remaining `IllegalArgumentException` surface (programmer-error config, e.g. non-AAC codec) [CRA-21]
* **video:** tighten Android HDR10 pre-flight to also require `MediaCodecInfo.CodecCapabilities.FEATURE_HdrEditing` on API 33+ — aligns with Media3's internal `HDR_MODE_KEEP_HDR` gate, so devices that advertise HEVC Main10 but lack the feature now surface `VideoCompressionError.UnsupportedSourceFormat` instead of silently tone-mapping to SDR BT.709 [CRA-6]
* **video:** catch ObjC NSException from AVAssetWriterInput.init via cinterop @try/@catch — prevents crash when HEVC Main10 output settings are rejected by the hardware encoder [CRA-7]

## 1.0.0 (2026-04-15)

### Added

* add AndroidX Startup dependency ([13690d4](https://github.com/cracknco/kompressor/commit/13690d45a8e0de642ac9ada202f624ed67349d49))
* add audio playback support to the sample app ([e38bda7](https://github.com/cracknco/kompressor/commit/e38bda776a9053d6bb5088ccb29929ecf540f082))
* add Compose Multiplatform sample application ([563d8ab](https://github.com/cracknco/kompressor/commit/563d8ab1057136e3cb586755c360f0bf0addea0c))
* add consumer ProGuard rules and suppress false positive resource leak warnings ([990c359](https://github.com/cracknco/kompressor/commit/990c359aad149e17e4991b1638aa50ee3cf4fda8))
* add core compression interfaces and configurations for audio, video, and images. ([3d33ae9](https://github.com/cracknco/kompressor/commit/3d33ae934b80965e64e3151ca3d98a9e62408a59))
* add KDoc to platform-specific createKompressor functions ([9e896ca](https://github.com/cracknco/kompressor/commit/9e896ca69b856cdb0db839d9a915e796998e93fa))
* add secrets to config ([497afad](https://github.com/cracknco/kompressor/commit/497afad4e9cf8342c6cab484365b9962c87bac0e))
* add tests and fix FLAC audio compression on iOS ([#41](https://github.com/cracknco/kompressor/issues/41)) ([cd12be1](https://github.com/cracknco/kompressor/commit/cd12be1fffd85b25f6996b9f056667144a04fa00))
* add validation for image write and compression operations ([8cbacb1](https://github.com/cracknco/kompressor/commit/8cbacb1dfcf7924a102493db6d049b9c1d042837))
* **audio:** 5.1 / 7.1 surround support via ITU-R BS.775 downmix matrices ([6ccb30a](https://github.com/cracknco/kompressor/commit/6ccb30ab0318d6ba6eebceb9f0abc3d38d2657e3))
* **audio:** add audioTrackIndex API + audioTrackCount probe field ([31a20bd](https://github.com/cracknco/kompressor/commit/31a20bd18a2cc575428f9b27b3d821b068de5ac6))
* **audio:** AudioToolbox nonlinear bitrate caps — characterization test + UnsupportedBitrate [CRA-12] ([9a3ddcf](https://github.com/cracknco/kompressor/commit/9a3ddcf33984fcef73cf3307e0e7ba4348357594))
* **audio:** prep characterization test for hardware surround sweep [CRA-78] ([c0fe145](https://github.com/cracknco/kompressor/commit/c0fe145326d1974e52e366abb4d699ea9d603d52))
* **changelog:** Keep-a-Changelog + semantic-release auto-generation [CRA-24] ([a6c9d37](https://github.com/cracknco/kompressor/commit/a6c9d37e68c3c7a47fe4ac612b0e90b8f6cb33e9))
* enable documentation checks in Detekt configuration ([fa0cdd5](https://github.com/cracknco/kompressor/commit/fa0cdd51c57436d375d92046eb91c71b16f5d677))
* enhance testing infrastructure and API stability ([682889a](https://github.com/cracknco/kompressor/commit/682889aa3a8a22ee56d0adc969ce82bd8b1103ed))
* handle EXIF orientation during image compression on Android and iOS ([ba13771](https://github.com/cracknco/kompressor/commit/ba1377135db7b3ecd61b58e77548f81b930b2056))
* **image:** introduce ImageCompressionError typed hierarchy ([c8de7e8](https://github.com/cracknco/kompressor/commit/c8de7e802d275494abb5e9d62ab12c5b9d094783))
* implement Android and iOS image compression ([e25ec36](https://github.com/cracknco/kompressor/commit/e25ec3698edeab40cf090a13ce50dcc55dce75f1))
* implement Android and iOS image compression ([e3fd688](https://github.com/cracknco/kompressor/commit/e3fd68829cf4825f24fad5849eed610d3f42e88a))
* implement Android and iOS platform stubs for Kompressor ([e84bfa2](https://github.com/cracknco/kompressor/commit/e84bfa21b12e1f3566de9de891eb2a22e8e51417))
* implement audio compression on Android and iOS ([4daacbf](https://github.com/cracknco/kompressor/commit/4daacbfe971f286fca35f002a5254a828f10f621))
* initialize iOS application project ([f5ed572](https://github.com/cracknco/kompressor/commit/f5ed57228138a102b7dc347fcfb377e4afe8ffd3))
* **kompressor:** atomic KompressorContext.init via CAS + concurrency test [CRA-15] ([0979d30](https://github.com/cracknco/kompressor/commit/0979d3018e1ad45029d468538e7a51b4ee732208))
* pin GitHub action versions to specific SHAs in `pr.yml` ([27baa26](https://github.com/cracknco/kompressor/commit/27baa26ee39b56209e848ff4bcf0f10dcb42db19))
* refactor CompressionResult and enhance initialization safety ([2e348a2](https://github.com/cracknco/kompressor/commit/2e348a2396829e2848690ae444f080b12ad785eb))
* refactor sample app and enhance image compression handling ([b54dd9b](https://github.com/cracknco/kompressor/commit/b54dd9bdb350c517796db5d00615ec3daa8c7d82))
* remove progress tracking from image compression ([00b8b6e](https://github.com/cracknco/kompressor/commit/00b8b6e20af2285928391bf1537218dd4be53819))
* replace `coroutineContext` with `currentCoroutineContext()` and enable `allWarningsAsErrors` ([3409d5d](https://github.com/cracknco/kompressor/commit/3409d5dc1d5e146bf97bd0f1c2562899f293f315))
* set up code quality tools and CI workflows ([9ea800b](https://github.com/cracknco/kompressor/commit/9ea800bb8455fb0bfa653d4c9ca7565e6d442093))
* **test:** add centralized fixture bank infrastructure [CRA-71] ([c9bdf4d](https://github.com/cracknco/kompressor/commit/c9bdf4d9930a16d676c39c846b55603470b15fda))
* update CI/CD workflows and dependencies ([b2b5526](https://github.com/cracknco/kompressor/commit/b2b5526e81fa5a487f172e488d066baf186bc52f))
* update README with Kompressor project details ([1c74d1f](https://github.com/cracknco/kompressor/commit/1c74d1f6078ac31c18a8e9514177b66e162f7b9f))
* **video:** HDR10 / 10-bit output via HEVC ([118d658](https://github.com/cracknco/kompressor/commit/118d658dcb13986d985962752f0c604cd17bb4b5))
* **video:** preserve source rotation on iOS custom pipeline + document contract ([8d8d983](https://github.com/cracknco/kompressor/commit/8d8d983adff09c67ec10049ca834b137fb2042e6))

### Fixed

* add audio resampling and channel conversion to Android transcode pipeline ([10684a2](https://github.com/cracknco/kompressor/commit/10684a241915c3458b23ed993558ded98de9af76))
* add backpressure support to `AndroidAudioCompressor` and `PcmRingBuffer`. ([f8e9b20](https://github.com/cracknco/kompressor/commit/f8e9b203f9535e95e6100ec20026f12b35d02e57))
* add missing newline at end of IosAudioCompressor.kt ([76a6677](https://github.com/cracknco/kompressor/commit/76a66775a521de440cfea709a954b2402b097f90))
* address code review — memory leak, thread safety, dedup, cleanup ([9c2dc69](https://github.com/cracknco/kompressor/commit/9c2dc69e7e5c9d13dff42e9f6998690c252eb5ef))
* address CodeRabbit findings on PR [#53](https://github.com/cracknco/kompressor/issues/53) ([83c711c](https://github.com/cracknco/kompressor/commit/83c711c7cda11990369b516c5a8cb451d75de207))
* address CodeRabbit review comments on audio resampling PR ([308c126](https://github.com/cracknco/kompressor/commit/308c12698a7fb2ef1c24388e9c79be3f80b916bc))
* address CodeRabbit review comments on sample app ([3728810](https://github.com/cracknco/kompressor/commit/3728810646336aa04ec1742ef90e56a983b32f74))
* address CodeRabbit review feedback on Joern queries and CI workflow ([d4fdd78](https://github.com/cracknco/kompressor/commit/d4fdd78bbc04fa46f5923ea8ae8346ae6866c2d9))
* address CodeRabbit review feedback on PR [#51](https://github.com/cracknco/kompressor/issues/51) ([0050b3a](https://github.com/cracknco/kompressor/commit/0050b3a208b892da5eb32b0e91a511665bb1239e))
* address deep code review — 16 bugs across Android, iOS, and ViewModel ([02c6aff](https://github.com/cracknco/kompressor/commit/02c6aff30d433814d63da2835a23c5c1561f90cc))
* address PR [#55](https://github.com/cracknco/kompressor/issues/55) CI failures (FTL Pixel 6 + Joern) ([33c2add](https://github.com/cracknco/kompressor/commit/33c2addcdafb0de42cfc0a1a338f75586bd2097b))
* address PR review — resource safety, input validation, and result assertions ([0591d9c](https://github.com/cracknco/kompressor/commit/0591d9c746bfadcb034a677df307f7c09bb7939d))
* address second CodeRabbit review round ([8a0bf87](https://github.com/cracknco/kompressor/commit/8a0bf878f7bb7bd6d7ab0201d1a500ca2f54edad))
* **android/audio:** force re-encoding when plan is empty but passthrough not allowed ([0319145](https://github.com/cracknco/kompressor/commit/0319145c381a5fb340243e617760a419fc5388f2))
* **android/video+tests:** apply tight muxer, enrich Mp4 fixture, derace cancellation ([8214441](https://github.com/cracknco/kompressor/commit/8214441791fa15e9793e68b3cde80e559da86c73))
* **android:** add kotlinx-coroutines-android for Dispatchers.Main ([b1a957b](https://github.com/cracknco/kompressor/commit/b1a957b9cdccdd13735f757dfbd595a5e8202bbc))
* **android:** Media3 muxer padding, channel-mixing crash, video upscale, cancel race ([c1eb00e](https://github.com/cracknco/kompressor/commit/c1eb00e419be6f64c8ee299a4095d645ad4e0943))
* apply CodeRabbit auto-fixes ([9387891](https://github.com/cracknco/kompressor/commit/93878911e864a7ad9758dbc8567058f966caa2be))
* apply CodeRabbit auto-fixes ([debd3a1](https://github.com/cracknco/kompressor/commit/debd3a17fb622ab7ba1ab222e85263044e2864bf))
* apply CodeRabbit auto-fixes ([3c6318f](https://github.com/cracknco/kompressor/commit/3c6318fd3fc7b053e42f41b846fec5f86b9ee5ba))
* apply CodeRabbit auto-fixes ([ec28175](https://github.com/cracknco/kompressor/commit/ec28175f17da04039ee39794ceefc03cd7187d95))
* apply CodeRabbit auto-fixes ([1e14feb](https://github.com/cracknco/kompressor/commit/1e14feb578e16a374c64aae70f7e000295704df2))
* apply CodeRabbit auto-fixes ([e7eeb5b](https://github.com/cracknco/kompressor/commit/e7eeb5bce8659d1515e19077fb0b14fec961bf02))
* apply CodeRabbit auto-fixes ([f903fb7](https://github.com/cracknco/kompressor/commit/f903fb760a7467c02ec50f41b7d71c7c724ee2a7))
* apply CodeRabbit auto-fixes ([9b81059](https://github.com/cracknco/kompressor/commit/9b8105946ffcd8ce1128a564080514b2daed4c39))
* **audio:** address CodeRabbit review — KDoc, sweep range, doc wording [CRA-12] ([92f8208](https://github.com/cracknco/kompressor/commit/92f8208e8b2333727f7444e6200896b085fcdd61))
* **audio:** address PR [#57](https://github.com/cracknco/kompressor/issues/57) review — default-path regression + Error swallow ([c0114d2](https://github.com/cracknco/kompressor/commit/c0114d22449600e63502ac33d967f3cc11469aaa))
* **audio:** address PR [#57](https://github.com/cracknco/kompressor/issues/57) review + FTL directory-output failure ([ba5dbee](https://github.com/cracknco/kompressor/commit/ba5dbee66900800d07a7745f37debd988c4a88a0)), closes [#1](https://github.com/cracknco/kompressor/issues/1) [#2](https://github.com/cracknco/kompressor/issues/2) [#3](https://github.com/cracknco/kompressor/issues/3)
* **audio:** address PR [#57](https://github.com/cracknco/kompressor/issues/57) review nits ([1e39631](https://github.com/cracknco/kompressor/commit/1e3963179de2c39eb6deb971439cd6fde92a5287))
* **audio:** address PR [#59](https://github.com/cracknco/kompressor/issues/59) review + multichannel matrix avalanche ([90cff41](https://github.com/cracknco/kompressor/commit/90cff412424639460c6190861d1e2e18eaacefd9))
* **audio:** reject non-file output paths up front ([90569de](https://github.com/cracknco/kompressor/commit/90569de72b19746d124a23d82bf41e87385b1bc6))
* **audio:** restore UnsupportedSourceFormat for malformed inputs + detekt ([a1f5764](https://github.com/cracknco/kompressor/commit/a1f5764f8e888838201983db25ef51015a4c53ea))
* bitmap leak, iOS context dedup, ViewModel cleanup, durationMs validation ([75406a7](https://github.com/cracknco/kompressor/commit/75406a7a40682eceaabd4967917dc1b9852e027c))
* **changelog:** add persist-credentials + changelogTitle to prevent duplication [CRA-24] ([9bafef0](https://github.com/cracknco/kompressor/commit/9bafef08e7b97811a4b4238e6651b07356a68c2e))
* **changelog:** include context lines in sed range for Unreleased gate [CRA-24] ([c584be7](https://github.com/cracknco/kompressor/commit/c584be7fadc6dfab9b70f9bd8282ed767ffd8471))
* **changelog:** tighten gate to check Unreleased section only [CRA-24] ([67e0052](https://github.com/cracknco/kompressor/commit/67e0052265c9b12086dfaeba4ac71b0784e5dca9))
* compact PcmRingBuffer before capacity check and derive high-water mark ([a8c1d18](https://github.com/cracknco/kompressor/commit/a8c1d18ee45f246680e95ecc31f665482d923f3f))
* **deps:** update dependency androidx.activity:activity-compose to v1.13.0 ([0eb4a6f](https://github.com/cracknco/kompressor/commit/0eb4a6f061b9952e3fe85c49ce9fdaa6f9566dda))
* **deps:** update dependency androidx.exifinterface:exifinterface to v1.4.2 ([512b772](https://github.com/cracknco/kompressor/commit/512b772d3c61a22f231fc3c333fb5b78465ed997))
* **deps:** update dependency app.cash.turbine:turbine to v1.2.1 ([434b2b3](https://github.com/cracknco/kompressor/commit/434b2b33031de5164fccbc63937ca9ff0f0388a1))
* **deps:** update dependency io.mockk:mockk to v1.14.9 ([b2b998b](https://github.com/cracknco/kompressor/commit/b2b998b37d82eb07034e40e0ebc1ccce1dae08e3))
* **deps:** update kotest to v6.1.11 ([ceb9d00](https://github.com/cracknco/kompressor/commit/ceb9d0040950d4d6b6f51485412b721a713fb8d2))
* **deps:** update kotlininject to v0.9.0 ([7cc6775](https://github.com/cracknco/kompressor/commit/7cc6775f8a95ddbf1b1069f388d4065b3b147a41))
* **docs:** correct Xcode min version and CI note in CONTRIBUTING.md [CRA-31] ([8319cbc](https://github.com/cracknco/kompressor/commit/8319cbcab8ebffbc88192e8ff7ed98f33e8d147c))
* eof new line ([f0b526c](https://github.com/cracknco/kompressor/commit/f0b526c8157c13745d3a73025127d6e0742b260f))
* exclude sample app from kover ([155abbd](https://github.com/cracknco/kompressor/commit/155abbd03a380b4f71d85f526f0f6af7ada67734))
* fix formatting in ProgressSection.kt ([7709513](https://github.com/cracknco/kompressor/commit/77095137d2f167c030ba92568908e0003a7e2e50))
* fix formatting in ProgressSection.kt ([27823cc](https://github.com/cracknco/kompressor/commit/27823cc52b55b3a2c23ea1a248bf9c9d436c9f9d))
* fix frame count in `PcmProcessorTest` split processing test ([e9fd9c7](https://github.com/cracknco/kompressor/commit/e9fd9c78ad3698ef84ba46397ecdd63398f67af6))
* fix Joern path in GitHub Actions workflow ([8b4762f](https://github.com/cracknco/kompressor/commit/8b4762f543cf9a49cc641896917b2434570093d7))
* handle edge case for `sourceNode` type in Joern queries ([26ae831](https://github.com/cracknco/kompressor/commit/26ae831ea232bd3c723a35319548e48ce2ccb14c))
* **image:** move throw outside FileOutputStream.use to eliminate leak flag ([31083eb](https://github.com/cracknco/kompressor/commit/31083eb4dee8b45fd5bcf5b3a2c857ca9e1edcee))
* implement platform-specific image importing to support HEIC conversion on iOS ([1d97e43](https://github.com/cracknco/kompressor/commit/1d97e43c323fcb23ea33b6268e83c33aaa427e71))
* import ([a6c2d49](https://github.com/cracknco/kompressor/commit/a6c2d4927bbac72eddc3d37decd29daf9cd35a96))
* **ios/audio:** reject bitrates iOS's AAC-LC encoder can't honour; raise Kover coverage ([74f0b24](https://github.com/cracknco/kompressor/commit/74f0b24da7d70d2c2f92b60e7edbbfbf13ae8399))
* **ios:** repair the 10 pre-existing iOS simulator test failures ([3b05b56](https://github.com/cracknco/kompressor/commit/3b05b56dca23adbd19c2bf62f618d71c292d09ec)), closes [#41](https://github.com/cracknco/kompressor/issues/41)
* **joern:** move codebadger:suppress adjacent to flagged throw ([7a4a635](https://github.com/cracknco/kompressor/commit/7a4a6350c7be69dbbcef9a0499e82cc1ee77a674))
* **kover:** merge host-only excludes into single classes() call ([02ff5fb](https://github.com/cracknco/kompressor/commit/02ff5fb6c57ffb317000b81a7b372757ba05d236))
* **kover:** sync root excludes with :kompressor to fix 80.7% gate ([7bdb6a0](https://github.com/cracknco/kompressor/commit/7bdb6a0bbb37901f2655a9b137f685334b802ab9))
* map AVAssetWriter cancellation to CancellationException ([8982660](https://github.com/cracknco/kompressor/commit/89826607de4a3d045ae8e3020468ee784f08b40c))
* position Joern suppress comments correctly + bump iOS writer timeout ([ffab87c](https://github.com/cracknco/kompressor/commit/ffab87ca74077e33438bd26b37ec56630dcf980c))
* pr feedback ([9cdb470](https://github.com/cracknco/kompressor/commit/9cdb4706566c305825a6aa3b481bebe8cc8cfb68))
* refactor bitmap recycling and update CodeBadger analysis rules ([a293530](https://github.com/cracknco/kompressor/commit/a2935308cbdf490f84b024c966d98557587b0831))
* refactor Joern queries for improved type evaluation and data flow analysis ([d24441a](https://github.com/cracknco/kompressor/commit/d24441af437753f6d237dd32754b5b0d4d5e4cd0))
* refactor tests and extract common utilities ([b07af17](https://github.com/cracknco/kompressor/commit/b07af17504c5a579f8cdccea42a668895ca7b847))
* refactor tests and extract common utilities ([a092d8d](https://github.com/cracknco/kompressor/commit/a092d8d8de3090a962b1d3729dcae618cadccf47))
* refine audio and image compression tests and utilities ([a6740b7](https://github.com/cracknco/kompressor/commit/a6740b723bda89f863c6e4ff13e49361c1f91464))
* refine error handling and resource management ([b12cd5f](https://github.com/cracknco/kompressor/commit/b12cd5fa9b49cff3525303375dedc97699aa4355))
* refine sample UI and improve compression error handling ([860f6f8](https://github.com/cracknco/kompressor/commit/860f6f8d2eb73a57d22e24afbbd5218a7cd331b0))
* resolve detekt LongMethod and wire sample project into Kover ([7a3a8ed](https://github.com/cracknco/kompressor/commit/7a3a8ed9e78a8eed5322a89b0baebae359b6dac2))
* resolve MediaCodec deadlock that hangs on large audio files ([ea073d2](https://github.com/cracknco/kompressor/commit/ea073d243077661a30691ccee753fd9f134c5077))
* **test:** add AVChannelLayoutKey for surround probing in characterization test [CRA-12] ([d471685](https://github.com/cracknco/kompressor/commit/d4716856be57c65a5abeb8906cf6495966af3019))
* **test:** add timeouts to concurrency test await calls [CRA-15] ([4b335da](https://github.com/cracknco/kompressor/commit/4b335daf4ecd2283bb0cbfbe97e48adcec46acd5))
* **test:** address review — harden fetch script + add fixtures .gitignore [CRA-71] ([583e9b2](https://github.com/cracknco/kompressor/commit/583e9b26a8ac00b0be7c95ba8caee8c27bcbf4c9))
* **test:** address review — verify SHA-256 on source_url fallback + drop unused LFS checkout [CRA-71] ([0be9ed7](https://github.com/cracknco/kompressor/commit/0be9ed724fb34f218dab7c35bcb0ddd93912abc7))
* **test:** address self-review — persist matrix + drop dead surround branches [CRA-12] ([d762a7c](https://github.com/cracknco/kompressor/commit/d762a7cfac92c784889f87a2563ed2633ac308bf))
* **test:** assert tkhd rotation preserved, not zeroed — matches Media3 behaviour [CRA-9] ([4358829](https://github.com/cracknco/kompressor/commit/4358829a4be9608d64436df4b4c58dc522d415c5))
* **test:** correct channel layout tag comments + trim splice whitespace [CRA-78] ([a9f67de](https://github.com/cracknco/kompressor/commit/a9f67de06cfc6516ba687064262257d17d281053))
* **test:** limit characterization sweep to mono/stereo on simulator [CRA-12] ([452038d](https://github.com/cracknco/kompressor/commit/452038d910340e364a3f6d35b65fe195b4c4ad15))
* **test:** skip planned fixtures without sha256 in fetch script [CRA-71] ([7bd7c29](https://github.com/cracknco/kompressor/commit/7bd7c29916d5ab4118991ff1c4d761ca68da30c3))
* update Joern installation in CI to specify installation directory ([40239f6](https://github.com/cracknco/kompressor/commit/40239f62bb6ab7819c285efa4f4b82f4010c6168))
* update Kotlin CPG generation to use a symlinked input directory ([af270ce](https://github.com/cracknco/kompressor/commit/af270ce9c605ccac810bc000c19992b684b4ce39))
* update lazy thread safety mode and add CompressionResult validation ([9bcfaf9](https://github.com/cracknco/kompressor/commit/9bcfaf926797219ccecb73e8d2d1ab3f7d055007))
* **video:** address PR [#56](https://github.com/cracknco/kompressor/issues/56) review nits ([ef5cbbc](https://github.com/cracknco/kompressor/commit/ef5cbbc6ab0ed31b54f174fed6d490ac7fc93450))
* **video:** address PR [#58](https://github.com/cracknco/kompressor/issues/58) review — FTL compile + content:// probes + iOS HDR10 preflight ([ddf967d](https://github.com/cracknco/kompressor/commit/ddf967d2e2ef71d513667c28f48b67a7b6823379))
* **video:** gate TRACK_TYPE_AUDIO on probed audio-track presence ([6ec2d70](https://github.com/cracknco/kompressor/commit/6ec2d708469db79b404eed88687fcdc746d09be5)), closes [#58](https://github.com/cracknco/kompressor/issues/58)

### Changed

* **android:** dedupe tight Mp4 muxer factory, simplify video probe plumbing ([bdba89f](https://github.com/cracknco/kompressor/commit/bdba89f6b9133ef75e3306145f73c17f75febcce))
* **audio:** collapse triple MediaExtractor open into single probe pass ([4fa9b41](https://github.com/cracknco/kompressor/commit/4fa9b41299e0e7dadfa2c3f4e63aad2228baf8b4))
* extract copyDecodedToEncoder helper + add AudioChannels.count ([2b4312f](https://github.com/cracknco/kompressor/commit/2b4312f875c3fd136f9a3eef57afed3443e9032d))
* **hdr:** cache MediaCodecList scan + dedupe stacked KDoc ([ce25dec](https://github.com/cracknco/kompressor/commit/ce25decf5af71b957bacd5353c29b03460b0b4bb))
* initialize Kompressor project and remove template files ([8b7ba8c](https://github.com/cracknco/kompressor/commit/8b7ba8c47f11fdfdd01d6aa57d0ff42cd8a0139e))
* optimize audio compression pipeline ([12520a2](https://github.com/cracknco/kompressor/commit/12520a2a25ccee33f6f9db53aebd852d250e516b))
* refactor CI/CD workflows and integrate Fastlane ([e7a93f9](https://github.com/cracknco/kompressor/commit/e7a93f902ba06aee5a101b76ebb7e73275c54aa0))
* rename `library` module to `kompressor` and update dependencies ([1ccba22](https://github.com/cracknco/kompressor/commit/1ccba22dd5181d4b0e04351b2082fc55e6f46930))
* simplify Android audio pipeline — remove Channel overengineering ([557f631](https://github.com/cracknco/kompressor/commit/557f6316f4522dcc88b4b30b35af00772af8d05a))
