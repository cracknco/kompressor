# Public API Inventory

This document is the source of truth for the stability tier of every public
Kompressor symbol. It is curated alongside the committed ABI baseline
(`kompressor/api/kompressor.api`) and the S/E/I review checklist in
[`docs/api-stability.md`](api-stability.md).

Three tiers exist:

- **Stable** — covered by the SemVer 2.0.0 contract from 1.0.0 onward.
  Source- and binary-breaking changes require a MAJOR release.
- **Experimental** — annotated `@ExperimentalKompressorApi`. Opt-in is
  required at the call site. These APIs may change or be removed in any
  MINOR release without a MAJOR bump. Consumers see a WARNING-level
  opt-in requirement from the Kotlin compiler.
- **Internal** — declared `internal`. Invisible to consumers, no
  stability guarantee.

Only the Experimental inventory is maintained here explicitly. Every
other public symbol is Stable by default; `internal` symbols never leak
into this file.

## Experimental symbols

> Pre-1.0 incubating surface. Gated by
> `@ExperimentalKompressorApi`. Opting in is an explicit acknowledgement
> that consumer code may have to change across MINOR releases while
> these APIs are stabilised.

### `co.crackn.kompressor.image`

| Symbol | Kind | Rationale for experimental status |
|--------|------|-----------------------------------|
| `ImageFormat.HEIC` | Enum entry | iOS 11+ only; Android has no stable `Bitmap.CompressFormat.HEIC` in this release. Output contract may change once Android adds encoder support. |
| `ImageFormat.AVIF` | Enum entry | Android API 34+, iOS 16+. Platform coverage still expanding; the fallback error shape is under review. |

### `co.crackn.kompressor.video`

| Symbol | Kind | Rationale for experimental status |
|--------|------|-----------------------------------|
| `DynamicRange.HDR10` | Enum entry | BT.2020 + PQ requires HEVC Main10 encoder. Tonemapping / HDR metadata preservation contract across devices is still being tuned. |
| `VideoPresets.HDR10_1080P` | `VideoCompressionConfig` preset | Uses `DynamicRange.HDR10`; inherits the same stability caveats. |

### `co.crackn.kompressor.audio`

| Symbol | Kind | Rationale for experimental status |
|--------|------|-----------------------------------|
| `AudioChannels.FIVE_POINT_ONE` | Enum entry | BS.775-3 downmix matrices + per-device encoder coverage are still being validated. See [`docs/audio-downmix.md`](audio-downmix.md). |
| `AudioChannels.SEVEN_POINT_ONE` | Enum entry | Same as 5.1 — surround output stability depends on in-progress encoder characterisation work. |
| `AudioCompressionConfig.audioTrackIndex` | Property | Multi-track selection semantics (Android MP4-muxer stream-copy restrictions, probe ordering across containers) are still being stabilised. |

### `co.crackn.kompressor`

| Symbol | Kind | Rationale for experimental status |
|--------|------|-----------------------------------|
| `SourceMediaInfo.audioTrackCount` | Property | Paired with `AudioCompressionConfig.audioTrackIndex`; the track-enumeration probe contract moves in lockstep. |

## How to opt in

**Per call site** (recommended for consumer code — keeps the opt-in
explicit to anyone reading the call):

```kotlin
@OptIn(ExperimentalKompressorApi::class)
fun compressWithSurround() {
    val config = AudioCompressionConfig(channels = AudioChannels.FIVE_POINT_ONE)
    // ...
}
```

**Per module** (appropriate for test modules that exercise the full
experimental surface — used in `kompressor/build.gradle.kts` for
`commonTest` / `androidHostTest` / `androidDeviceTest` / iOS test
targets):

```kotlin
// kompressor/build.gradle.kts
sourceSets {
    matching { it.name.endsWith("Test") }.configureEach {
        languageSettings.optIn("co.crackn.kompressor.ExperimentalKompressorApi")
    }
}
```

## How this list stays in sync

- New public API gated by `@ExperimentalKompressorApi` → add a row here
  **in the same PR** that introduces the symbol.
- Stabilising an experimental symbol (removing the annotation) → delete
  its row here; bump the CHANGELOG under `Changed` with a "stabilised"
  note; no MAJOR bump required because removing an opt-in requirement
  is additive.
- Renaming or removing an experimental symbol → update/delete its row;
  CHANGELOG entry under `Changed (experimental)` so opt-in consumers
  see the breakage.

Reviewers must confirm the inventory delta matches the ABI dump delta
when a PR modifies `kompressor/api/kompressor.api`. See the S/E/I
checklist in [`docs/api-stability.md`](api-stability.md#pr-review-checklist-for-abi-diffs--s--e--i).
