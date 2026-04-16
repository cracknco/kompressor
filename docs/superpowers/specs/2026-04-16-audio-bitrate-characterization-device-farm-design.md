# Audio bitrate characterization on Device Farm — design [CRA-78]

## Goal

Unblock DoD items 1–4 of [CRA-78](https://linear.app/crackn/issue/CRA-78): probe
AudioToolbox's AAC-LC encoder for empirical bitrate acceptance in 5.1 (6ch) and
7.1 (8ch) layouts on real A15+ hardware, write the matrix into
`docs/audio-bitrate-matrix.md`, and update `iosAacMaxBitrate` /
`iosAacMinBitrate` + boundary tests if the current linear per-channel caps
don't hold.

## Approach

The existing Kotlin characterization test
(`kompressor/src/iosTest/kotlin/co/crackn/kompressor/audio/AudioToolboxBitrateCharacterizationTest.kt`)
runs in the Kotlin/Native `iosTest` target — which ships to the simulator unit-test
CI lane, **not** to the Device Farm Swift XCTest bundle
(`iosDeviceSmokeTests/Tests/`). iOS Simulator's AAC encoder rejects surround
layouts with an uncatchable `NSInvalidArgumentException`, so the Kotlin test
gates `CHANNEL_COUNTS = [1, 2]` on simulator.

Device Farm is already wired up for real-device XCTest runs (see
`.github/workflows/ios-device-smoke.yml`, `docs/ci/aws-device-farm.md`) — but the
characterization test has no Swift counterpart there.

**Bridge the gap with a Swift port**, triggered manually via
`workflow_dispatch`. The port is a characterization/discovery tool, not a
regression gate, so a one-shot trigger fits better than the existing per-PR
smoke-test path.

## Architecture

### Components added

| File | Purpose |
|---|---|
| `iosDeviceSmokeTests/Tests/AudioBitrateCharacterizationTests.swift` | Swift port of the Kotlin sweep. Uses existing `WavFixture` and `ObjCExceptionCatcher`. Emits the matrix as an `XCTAttachment`. |
| `.github/workflows/ios-audio-characterization.yml` | Manual-trigger workflow. Reuses the signing + Device Farm upload/schedule/poll pattern from `ios-device-smoke.yml`. Uploads the XCTAttachment as a GitHub Actions artifact. |
| `iosDeviceSmokeTests/project.yml` (edit) | New `CharacterizationTests` XCTest target so Device Farm runs only the characterization test, not the full smoke suite. |

### Components unchanged

- `AudioToolboxBitrateCharacterizationTest.kt` — stays as-is. Continues to probe
  mono/stereo on simulator as a cheap sanity guardrail (disposition 1 from
  brainstorming). Surround columns stay `CHANNEL_COUNTS = [1, 2]` gated.
- `docs/audio-bitrate-matrix.md` — the splice markers and format are already in
  place (PR #71). Swift port reuses the exact same `formatDocTable` layout so
  the workflow artifact drops in byte-identical.
- `IosAudioCompressor.kt`, `IosAudioBitrateValidationTest.kt` — updated in a
  follow-up commit after a successful characterization run, using empirical
  values. Not in scope for the CI wiring itself.

### Data flow

```
[human] -> workflow_dispatch trigger
              |
              v
         budget-guard (Cost Explorer)
              |
              v
         build KMP framework (iosArm64) + sign + xcodegen
              |
              v
         xcodebuild build-for-testing -scheme CharacterizationTests
              |
              v
         package CharacterizationTests.xctest.zip
              |
              v
         aws devicefarm upload + schedule-run (pool: kompressor-ios-a15plus)
              |
              v
         Device Farm runs sweepBitrateChannelGrid on iPhone A15+
              |                                                        |
              v                                                        v
         stdout: matrix markdown              XCTAttachment("audio-bitrate-matrix.md")
                                                                       |
                                                                       v
                                                              Device Farm FILE artifact
                                                                       |
                                                                       v
                                                        workflow downloads + uploads
                                                        as GitHub Actions artifact
                                                                       |
                                                                       v
                                          [human] downloads, splices into
                                          docs/audio-bitrate-matrix.md, updates
                                          caps in IosAudioCompressor.kt + tests,
                                          opens follow-up PR
```

### Why not auto-commit?

Matrix drift could theoretically trigger an auto-PR, but:
1. A characterization run is one-shot by nature — after the first successful
   surround sweep, caps stabilize and re-running is pure cost.
2. Cap updates in `IosAudioCompressor.kt` and boundary assertions in
   `IosAudioBitrateValidationTest.kt` require human judgment (pick the
   conservative edge, decide whether to alter the sample-rate tiers).
3. Auto-commit from CI adds signing complexity (bot identity, branch perms) we
   don't need for a once-a-quarter run.

## Component details

### `AudioBitrateCharacterizationTests.swift`

Mirrors `AudioToolboxBitrateCharacterizationTest.kt` 1:1:

- `SAMPLE_RATE = 44_100`, `BITRATE_START…END = 32_000…1_280_000` step `32_000`,
  `CHANNEL_COUNTS = [1, 2, 6, 8]` (all four — we're on device).
- `probeEncoder(…)` wraps `AVAssetReader`/`AVAssetWriterInput.appendSampleBuffer`
  logic. Wrapped in `ObjCExceptionCatcher.catchExceptionInBlock` so any
  `NSInvalidArgumentException` is classified as "N" (rejected), not a crash.
- `channelLayoutData(Int) -> Data` — same `(tag << 16) | channels` packing as
  Kotlin, mapping 1/2/6/8 to `Mono/Stereo/MPEG_5_1_A/MPEG_7_1_C` tags (100, 101,
  121, 128). Bridging header already imports `CoreAudioTypes`.
- `formatDocTable(results)` — produces the exact splice-ready markdown block
  (same column headers, same `formatWithCommas` row labels) that lands between
  `<!-- ACCEPTANCE_MATRIX -->` markers in `audio-bitrate-matrix.md`.
- Output:
  1. `print(markdown)` — ends up in Device Farm device log
  2. `XCTContext.runActivity { activity in activity.add(XCTAttachment(string: markdown, uniformTypeIdentifier: "net.daringfireball.markdown")) }` — captured as Device Farm `FILE` artifact.
- Always passes. No `XCTAssert` on acceptance — this is discovery, not a gate.

### Xcode target topology

```yaml
# iosDeviceSmokeTests/project.yml (additions)
targets:
  SmokeTests:                       # existing
    sources:
      - path: Tests
        excludes:
          - AudioBitrateCharacterizationTests.swift

  CharacterizationTests:            # new
    type: bundle.unit-test
    platform: iOS
    sources:
      - path: Tests/AudioBitrateCharacterizationTests.swift
      - path: Tests/WavFixture.swift
      - path: Tests/ObjCExceptionCatcher.h
      - path: Tests/ObjCExceptionCatcher.m
      - path: Tests/Info.plist
    settings:                       # identical to SmokeTests.settings:
      base:                         #   PRODUCT_BUNDLE_IDENTIFIER
        PRODUCT_BUNDLE_IDENTIFIER: co.crackn.kompressor.devicetest.characterization
        CODE_SIGNING_ALLOWED: "NO"
        CODE_SIGNING_REQUIRED: "NO"
        CODE_SIGN_IDENTITY: ""
        TEST_HOST: "$(BUILT_PRODUCTS_DIR)/SmokeTestHost.app/SmokeTestHost"
        BUNDLE_LOADER: "$(TEST_HOST)"
        SWIFT_OBJC_BRIDGING_HEADER: Tests/BridgingHeader.h
        FRAMEWORK_SEARCH_PATHS:
          - "$(inherited)"
          - "$(SRCROOT)/../kompressor/build/bin/iosArm64/releaseFramework"
    dependencies:
      - target: SmokeTestHost
```

Shared assets (`WavFixture`, `ObjCExceptionCatcher`) are compiled into both
targets' bundles (each target gets its own copy of the object files). No file
moves, no framework extraction — the cost is a few KB of duplicated code in the
CharacterizationTests bundle, which is irrelevant for a CI-only artifact.

### `.github/workflows/ios-audio-characterization.yml`

Near-clone of `ios-device-smoke.yml` with three diffs:

1. `on:` trigger is `workflow_dispatch` only (no PR gate).
2. Build step uses `-scheme CharacterizationTests` and packages
   `CharacterizationTests.xctest.zip`.
3. After downloading Device Farm artifacts, the workflow globs
   `artifacts/ios-device/*audio-bitrate-matrix*` and re-uploads as a dedicated
   GitHub Actions artifact named `audio-bitrate-matrix-<run-id>` so it's easy
   to find in the UI.

Budget guard, signing, OIDC, device pool selection — all identical to the
existing workflow. Same `AWS_DEVICEFARM_PROJECT_ARN` and `AWS_DEVICEFARM_POOL_ARN`
variables.

## Error handling

- **ObjC exception from `AVAssetWriterInput.init`** — caught via
  `ObjCExceptionCatcher`. Cell recorded as "N".
- **Reader/writer `startWriting` returns false** — cell recorded as "N". Same
  as Kotlin.
- **Device Farm run status `ERRORED`** — workflow fails hard, same as existing
  smoke workflow. Human investigates via downloaded artifacts.
- **Missing `XCTAttachment` in Device Farm artifacts** — workflow logs the
  `ls` of `artifacts/ios-device/` and fails. Common cause: XCTest bundle ran
  but attachment wasn't captured because the test threw unexpectedly.
- **Budget exhausted** — `budget-guard` skips the job with a warning
  annotation, same as existing pattern.

## Testing

### Local validation

```bash
./gradlew :kompressor:linkReleaseFrameworkIosArm64
cd iosDeviceSmokeTests && xcodegen generate
xcodebuild build-for-testing \
  -project KompressorDeviceSmokeTests.xcodeproj \
  -scheme CharacterizationTests \
  -destination 'generic/platform=iOS' \
  -derivedDataPath build \
  CODE_SIGNING_ALLOWED=NO
```

This verifies the new target compiles. Can't actually run the sweep without a
device or Device Farm, but compile success is the gate.

### CI validation

Kick off the workflow via `gh workflow run ios-audio-characterization.yml` (or
GitHub UI). A full run takes ~5-8 min:
- ~2 min build + sign
- ~3 min Device Farm queue/boot
- ~2 min sweep (40 bitrates × 4 channel counts × ~0.8s probe = 128s)

Expected result: green workflow, `audio-bitrate-matrix-<run-id>` artifact
downloadable, matrix contains Y/N for all 6ch/8ch cells (no `?`).

## Follow-up work (separate PR, after first successful run)

Once the matrix has empirical 6ch/8ch data:

1. Paste the matrix into `docs/audio-bitrate-matrix.md` between the splice
   markers (or re-run the Kotlin sim test locally with `KOMPRESSOR_DOCS_DIR`
   pointing at the device-farm-produced matrix — easier to just paste).
2. Compute empirical surround caps. If they deviate from the current linear
   extrapolation (`160 kbps/ch × N`), update `iosAacMaxBitrate` /
   `iosAacMinBitrate` in `kompressor/src/iosMain/kotlin/co/crackn/kompressor/audio/IosAudioCompressor.kt`.
3. Align boundary assertions in
   `kompressor/src/iosTest/kotlin/co/crackn/kompressor/audio/IosAudioBitrateValidationTest.kt`
   with the new caps.
4. Update the "Current Validation Table" section of
   `docs/audio-bitrate-matrix.md` with the final sample-rate-tier caps.

## Out of scope

- Porting other Kotlin iosTest characterization tooling to Swift. No other
  Kotlin test has the simulator-vs-device gap that this one does — everything
  else either runs on simulator (unit tests) or is already mirrored in Swift
  (HDR10, Surround round-trips).
- Auto-commit / auto-PR of the matrix update. Human review of empirical
  encoder caps is non-negotiable.
- Running characterization per PR. Cost-prohibitive (~$1.50/run) for static
  output; `workflow_dispatch` + artifact is the right cadence.

## References

- Parent issue: [CRA-12](https://linear.app/crackn/issue/CRA-12)
- This issue: [CRA-78](https://linear.app/crackn/issue/CRA-78)
- Prep PR (merged): [#71](https://github.com/cracknco/kompressor/pull/71)
- Device Farm runbook: `docs/ci/aws-device-farm.md`
- iOS device CI overview: `docs/ios-device-ci.md`
- Kotlin test: `kompressor/src/iosTest/kotlin/co/crackn/kompressor/audio/AudioToolboxBitrateCharacterizationTest.kt`
- Matrix doc: `docs/audio-bitrate-matrix.md`
