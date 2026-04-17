# iOS AAC Encoder Bitrate Matrix

Empirically discovered bitrate acceptance ranges for Apple's AudioToolbox AAC-LC encoder,
reached via `AVAssetWriterInput` with `kAudioFormatMPEG4AAC`. The characterization test
(`AudioToolboxBitrateCharacterizationTest`) prints results to stdout and, when the
`KOMPRESSOR_DOCS_DIR` environment variable is set, writes directly to this file.

## Test Parameters

- **Sample rate**: 44,100 Hz (standard; other rates tested in `IosAudioBitrateValidationTest`)
- **Bitrate sweep**: 32,000 – 1,280,000 bps, step 32,000
- **Channel layouts**: Mono (1), Stereo (2), 5.1 (6), 7.1 (8)

## Acceptance Matrix

> Empirical results below are from **AWS Device Farm run [24536970778](https://github.com/cracknco/kompressor/actions/runs/24536970778)**
> (2026-04-16, iPhone 13 / A15 Bionic, iOS 18.x, Xcode 16). Produced by the Swift
> characterization test `iosDeviceSmokeTests/Tests/AudioBitrateCharacterizationTests.swift`
> running on real hardware. The Kotlin sibling (`AudioToolboxBitrateCharacterizationTest`)
> stays gated to the `[1, 2]` simulator-safe channel range — surround on the Simulator
> throws `NSInvalidArgumentException` from `AVAssetWriterInput.init` (K/N can't catch),
> so the full `[1, 2, 6, 8]` grid must run on device.
>
> To re-run on a different device tier: dispatch `ios-audio-characterization.yml`
> (branch `main`); the resulting `audio-bitrate-matrix-full-<run-id>` artifact carries
> a splice-ready table. Or run `AudioToolboxBitrateCharacterizationTest` locally with
> `KOMPRESSOR_DOCS_DIR` set to the repo's `docs/` directory (mono/stereo only —
> simulator rejects surround).

<!-- ACCEPTANCE_MATRIX -->
| Bitrate (bps) | Mono (1ch) | Stereo (2ch) | 5.1 (6ch) | 7.1 (8ch) |
|---------------|:----------:|:------------:|:---------:|:---------:|
| 32,000        | Y          | N            | N         | N         |
| 64,000        | Y          | Y            | N         | N         |
| 96,000        | Y          | Y            | N         | N         |
| 128,000       | Y          | Y            | N         | N         |
| 160,000       | Y          | Y            | N         | N         |
| 192,000       | Y          | Y            | N         | N         |
| 224,000       | Y          | Y            | N         | N         |
| 256,000       | Y          | Y            | N         | N         |
| 288,000       | N          | Y            | N         | N         |
| 320,000       | N          | Y            | N         | N         |
| 352,000       | N          | N            | N         | N         |
| 384,000       | N          | N            | N         | N         |
| 416,000       | N          | N            | N         | N         |
| 448,000       | N          | N            | N         | N         |
| 480,000       | N          | N            | N         | N         |
| 512,000       | N          | N            | N         | N         |
| 544,000       | N          | N            | N         | N         |
| 576,000       | N          | N            | N         | N         |
| 608,000       | N          | N            | N         | N         |
| 640,000       | N          | N            | N         | N         |
| 672,000       | N          | N            | N         | N         |
| 704,000       | N          | N            | N         | N         |
| 736,000       | N          | N            | N         | N         |
| 768,000       | N          | N            | N         | N         |
| 800,000       | N          | N            | N         | N         |
| 832,000       | N          | N            | N         | N         |
| 864,000       | N          | N            | N         | N         |
| 896,000       | N          | N            | N         | N         |
| 928,000       | N          | N            | N         | N         |
| 960,000       | N          | N            | N         | N         |
| 992,000       | N          | N            | N         | N         |
| 1,024,000     | N          | N            | N         | N         |
| 1,056,000     | N          | N            | N         | N         |
| 1,088,000     | N          | N            | N         | N         |
| 1,120,000     | N          | N            | N         | N         |
| 1,152,000     | N          | N            | N         | N         |
| 1,184,000     | N          | N            | N         | N         |
| 1,216,000     | N          | N            | N         | N         |
| 1,248,000     | N          | N            | N         | N         |
| 1,280,000     | N          | N            | N         | N         |
<!-- /ACCEPTANCE_MATRIX -->

## Current Validation Table

Derived from the characterization test results and encoded in
`IosAudioCompressor.kt` → `iosAacMaxBitrate()` / `iosAacMinBitrate()`.

### Maximum bitrate (per sample-rate tier)

| Sample Rate   | Mono (1ch)                   | Stereo (2ch) | 5.1 (6ch)     | 7.1 (8ch)     |
|---------------|:----------------------------:|:------------:|:-------------:|:-------------:|
| ≤ 24,000 Hz   | 64 kbps                      | 128 kbps     | — (rejected) | — (rejected) |
| ≤ 32,000 Hz   | 96 kbps                      | 192 kbps     | — (rejected) | — (rejected) |
| ≤ 44,100 Hz   | **256 kbps** (empirical)\*   | 320 kbps     | — (rejected) | — (rejected) |
| > 44,100 Hz   | 192 kbps                     | 384 kbps     | — (rejected) | — (rejected) |

\* The 256 kbps mono cap is only empirically confirmed at exactly 44.1 kHz. The
[`iosAacMaxBitrate`](../kompressor/src/iosMain/kotlin/co/crackn/kompressor/audio/IosAudioCompressor.kt)
override pins the guard to `sampleRate == 44_100`; 22.05 / 32 kHz mono retain the
linear per-channel caps from the rows above. A follow-up Device Farm sweep is needed
before extending the override to other rates.

### Minimum bitrate (per sample-rate tier)

| Sample Rate   | Mono (1ch) | Stereo (2ch) | 5.1 (6ch)      | 7.1 (8ch)      |
|---------------|:----------:|:------------:|:--------------:|:--------------:|
| ≤ 24,000 Hz   | 16 kbps    | 32 kbps      | — (rejected)  | — (rejected)  |
| ≤ 32,000 Hz   | 24 kbps    | 48 kbps      | — (rejected)  | — (rejected)  |
| > 32,000 Hz   | 32 kbps    | 64 kbps      | — (rejected)  | — (rejected)  |

> **Note on surround**: Device Farm run 24536970778 (iPhone 13 / A15 / iOS 18) confirmed
> that AudioToolbox's AAC-LC encoder rejects 5.1 and 7.1 output at every tested bitrate
> (32k–1280k). Mono and stereo matched the simulator baseline exactly, ruling out a test
> setup issue. `iosAacMaxBitrate` / `iosAacMinBitrate` return 0 for surround, and
> `checkSupportedIosBitrate` surfaces `UnsupportedConfiguration` before the hardware
> encoder probe is reached. Surround AAC encoding remains supported on Android. [CRA-82]
>
> **Note on mono headroom (empirically confirmed, 44.1 kHz)**: Mono accepts up to
> 256 kbps at 44.1 kHz — 60% more than the linear per-channel model predicts
> (160 kbps/ch × 1). Stereo matches the linear model exactly (160 kbps/ch × 2 =
> 320 kbps). The implementation encodes mono-at-exactly-44.1 kHz as a per-(rate, ch)
> override (guard: `sampleRate == 44_100`); other cells still use the linear model.
>
> **Sample-rate coverage caveat**: the Device Farm sweep covered a single sample rate
> (44.1 kHz). Caps for 22.05 / 32 / 48 kHz are **estimates** derived from the linear
> per-channel model + property-test shrinks, not direct hardware sweeps. They are
> validated against simulator results but have not been confirmed on device for the
> non-44.1 kHz rates. A follow-up Device Farm sweep is needed before shipping any
> caller-facing guarantee that the non-44.1 rate caps are tight.
