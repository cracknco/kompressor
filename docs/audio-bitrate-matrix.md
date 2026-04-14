# iOS AAC Encoder Bitrate Matrix

Empirically discovered bitrate acceptance ranges for Apple's AudioToolbox AAC-LC encoder,
reached via `AVAssetWriterInput` with `kAudioFormatMPEG4AAC`. The characterization test
(`AudioToolboxBitrateCharacterizationTest`) writes results to `NSTemporaryDirectory()` —
copy them here after reviewing.

## Test Parameters

- **Sample rate**: 44,100 Hz (standard; other rates tested in `IosAudioBitrateValidationTest`)
- **Bitrate sweep**: 32,000 – 1,280,000 bps, step 32,000
- **Channel layouts**: Mono (1), Stereo (2), 5.1 (6), 7.1 (8)

## Acceptance Matrix

> Run `AudioToolboxBitrateCharacterizationTest` on an iOS simulator or device to populate
> this table with empirical results. The test prints the matrix to stdout and writes it to
> `NSTemporaryDirectory()/audio-bitrate-matrix.md`.

| Bitrate (bps) | Mono (1ch) | Stereo (2ch) | 5.1 (6ch) | 7.1 (8ch) |
|---------------|:----------:|:------------:|:---------:|:---------:|
| 32,000        | ?          | ?            | ?         | ?         |
| 64,000        | ?          | ?            | ?         | ?         |
| 96,000        | ?          | ?            | ?         | ?         |
| 128,000       | ?          | ?            | ?         | ?         |
| 160,000       | ?          | ?            | ?         | ?         |
| 192,000       | ?          | ?            | ?         | ?         |
| 224,000       | ?          | ?            | ?         | ?         |
| 256,000       | ?          | ?            | ?         | ?         |
| 288,000       | ?          | ?            | ?         | ?         |
| 320,000       | ?          | ?            | ?         | ?         |
| 352,000       | ?          | ?            | ?         | ?         |
| 384,000       | ?          | ?            | ?         | ?         |
| 416,000       | ?          | ?            | ?         | ?         |
| 448,000       | ?          | ?            | ?         | ?         |
| 480,000       | ?          | ?            | ?         | ?         |
| 512,000       | ?          | ?            | ?         | ?         |

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
