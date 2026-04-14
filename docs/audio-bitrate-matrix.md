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

> Run `AudioToolboxBitrateCharacterizationTest` on an iOS simulator or device to populate
> this table with empirical results. Set `KOMPRESSOR_DOCS_DIR` to the repo's `docs/`
> directory to auto-update this file; otherwise check `NSTemporaryDirectory()` for output.

<!-- ACCEPTANCE_MATRIX -->
| Bitrate (bps) | Mono (1ch) | Stereo (2ch) | 5.1 (6ch) | 7.1 (8ch) |
|---------------|:----------:|:------------:|:---------:|:---------:|
| 32,000        | Y          | N            | ?         | ?         |
| 64,000        | Y          | Y            | ?         | ?         |
| 96,000        | Y          | Y            | ?         | ?         |
| 128,000       | Y          | Y            | ?         | ?         |
| 160,000       | Y          | Y            | ?         | ?         |
| 192,000       | Y          | Y            | ?         | ?         |
| 224,000       | Y          | Y            | ?         | ?         |
| 256,000       | Y          | Y            | ?         | ?         |
| 288,000       | N          | Y            | ?         | ?         |
| 320,000       | N          | Y            | ?         | ?         |
| 352,000       | N          | N            | ?         | ?         |
| 384,000       | N          | N            | ?         | ?         |
| 416,000       | N          | N            | ?         | ?         |
| 448,000       | N          | N            | ?         | ?         |
| 480,000       | N          | N            | ?         | ?         |
| 512,000       | N          | N            | ?         | ?         |
| 544,000       | N          | N            | ?         | ?         |
| 576,000       | N          | N            | ?         | ?         |
| 608,000       | N          | N            | ?         | ?         |
| 640,000       | N          | N            | ?         | ?         |
| 672,000       | N          | N            | ?         | ?         |
| 704,000       | N          | N            | ?         | ?         |
| 736,000       | N          | N            | ?         | ?         |
| 768,000       | N          | N            | ?         | ?         |
| 800,000       | N          | N            | ?         | ?         |
| 832,000       | N          | N            | ?         | ?         |
| 864,000       | N          | N            | ?         | ?         |
| 896,000       | N          | N            | ?         | ?         |
| 928,000       | N          | N            | ?         | ?         |
| 960,000       | N          | N            | ?         | ?         |
| 992,000       | N          | N            | ?         | ?         |
| 1,024,000     | N          | N            | ?         | ?         |
| 1,056,000     | N          | N            | ?         | ?         |
| 1,088,000     | N          | N            | ?         | ?         |
| 1,120,000     | N          | N            | ?         | ?         |
| 1,152,000     | N          | N            | ?         | ?         |
| 1,184,000     | N          | N            | ?         | ?         |
| 1,216,000     | N          | N            | ?         | ?         |
| 1,248,000     | N          | N            | ?         | ?         |
| 1,280,000     | N          | N            | ?         | ?         |
<!-- /ACCEPTANCE_MATRIX -->

## Current Validation Table

Derived from the characterization test results and encoded in
`IosAudioCompressor.kt` → `iosAacMaxBitrate()` / `iosAacMinBitrate()`.

### Maximum bitrate (per sample-rate tier)

| Sample Rate   | Mono (1ch) | Stereo (2ch) | 5.1 (6ch) | 7.1 (8ch) |
|---------------|:----------:|:------------:|:---------:|:---------:|
| ≤ 24,000 Hz   | 64 kbps    | 128 kbps     | 384 kbps  | 512 kbps  |
| ≤ 32,000 Hz   | 96 kbps    | 192 kbps     | 576 kbps  | 768 kbps  |
| ≤ 44,100 Hz   | 160 kbps   | 320 kbps     | 960 kbps  | 1280 kbps |
| > 44,100 Hz   | 192 kbps   | 384 kbps     | 1152 kbps | 1536 kbps |

### Minimum bitrate (per sample-rate tier)

| Sample Rate   | Mono (1ch) | Stereo (2ch) | 5.1 (6ch) | 7.1 (8ch) |
|---------------|:----------:|:------------:|:---------:|:---------:|
| ≤ 24,000 Hz   | 16 kbps    | 32 kbps      | 96 kbps   | 128 kbps  |
| ≤ 32,000 Hz   | 24 kbps    | 48 kbps      | 144 kbps  | 192 kbps  |
| > 32,000 Hz   | 32 kbps    | 64 kbps      | 192 kbps  | 256 kbps  |

> **Note**: Surround (5.1/7.1) values above use linear per-channel scaling as a starting
> point. Run the characterization test to discover whether AudioToolbox imposes nonlinear
> total-bitrate caps for multichannel layouts. Update this document and
> `iosAacMaxBitrate()`/`iosAacMinBitrate()` with the empirical findings.
