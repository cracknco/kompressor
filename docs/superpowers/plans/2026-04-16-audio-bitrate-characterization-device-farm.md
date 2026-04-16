# Audio bitrate characterization on Device Farm — implementation plan [CRA-78]

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port `AudioToolboxBitrateCharacterizationTest.kt` to Swift XCTest in `iosDeviceSmokeTests/`, add a new `CharacterizationTests` Xcode target that runs only that test, and wire a `workflow_dispatch`-triggered GitHub Actions workflow that runs it on AWS Device Farm (iPhone A15+) and publishes the generated `audio-bitrate-matrix.md` as a downloadable artifact.

**Architecture:** New Swift XCTest file mirrors the Kotlin sweep 1:1 (40 bitrates × 4 channel counts × 44.1 kHz). Uses existing `WavFixture` + `ObjCExceptionCatcher` to classify invalid combos as rejected cells rather than crashes. `xcodegen` spawns a second `.xctest` bundle from the same `iosDeviceSmokeTests/` tree. A cloned-and-trimmed copy of `ios-device-smoke.yml` triggers on `workflow_dispatch` only, schedules a Device Farm run with the new bundle, downloads `XCTAttachment` output as a GitHub Actions artifact.

**Tech Stack:** Swift 5.10, XCTest, AVFoundation (`AVAssetReader`/`AVAssetWriterInput`), CoreAudioTypes channel layouts, XcodeGen 2.x, AWS Device Farm XCTest runner, GitHub Actions.

---

## File Structure

Files created by this plan:

| Path | Responsibility |
|---|---|
| `iosDeviceSmokeTests/Tests/AudioBitrateCharacterizationTests.swift` | Swift port of the Kotlin sweep. Self-contained probe + matrix formatter + `XCTAttachment` emission. Does NOT `import Kompressor` — pure AVFoundation. |
| `.github/workflows/ios-audio-characterization.yml` | `workflow_dispatch` runner. Budget-guarded. Builds → signs → uploads to Device Farm → downloads artifacts → publishes matrix as GitHub Actions artifact. |

Files modified by this plan:

| Path | Change |
|---|---|
| `iosDeviceSmokeTests/project.yml` | Add new `CharacterizationTests` target. Exclude the new file from `SmokeTests.sources`. |
| `kompressor/src/iosTest/kotlin/co/crackn/kompressor/audio/AudioToolboxBitrateCharacterizationTest.kt` | Update KDoc pointer to reference the Swift port as the authoritative surround tool (no logic change). |
| `docs/ios-device-ci.md` | Add one row to the test mapping table for the characterization test. |

Files NOT modified by this plan (scope protection):

- `docs/audio-bitrate-matrix.md` — the splice markers and acceptance table are already in place (PR #71). The workflow produces the matrix; a human pastes empirical values in the follow-up PR described in [`docs/superpowers/specs/2026-04-16-audio-bitrate-characterization-device-farm-design.md`](../specs/2026-04-16-audio-bitrate-characterization-device-farm-design.md).
- `kompressor/src/iosMain/kotlin/co/crackn/kompressor/audio/IosAudioCompressor.kt` — cap updates happen after a successful characterization run, not now.
- `kompressor/src/iosTest/kotlin/co/crackn/kompressor/audio/IosAudioBitrateValidationTest.kt` — boundary-test updates also follow empirical data.

---

## Task 1: Swift port of the characterization sweep

**Files:**
- Create: `iosDeviceSmokeTests/Tests/AudioBitrateCharacterizationTests.swift`

### Why no TDD here

The test *is* the unit under construction. A test-for-the-test would (a) require mocking Apple's encoder, which defeats the whole point of empirical characterization, and (b) add zero safety — the real validation is running the sweep on hardware and eyeballing the matrix. Task 5 is the "validation" step: compile-check locally via xcodebuild, then run on Device Farm.

This is consistent with how `Surround51Tests.swift` / `Surround71Tests.swift` / `IosLargeInputStreamingTests.swift` were landed (no harness test preceded them — see their histories).

- [ ] **Step 1: Create the Swift characterization test file**

Write `iosDeviceSmokeTests/Tests/AudioBitrateCharacterizationTests.swift`:

```swift
/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

import AVFoundation
import CoreAudioTypes
import XCTest

/// Swift port of `AudioToolboxBitrateCharacterizationTest.kt` that runs on AWS
/// Device Farm. Sweeps the grid {channel count ∈ [1, 2, 6, 8]} × {bitrate ∈
/// 32,000…1,280,000 step 32,000} at 44.1 kHz and probes each cell with a
/// single-sample-buffer round-trip through `AVAssetWriterInput` backed by
/// `kAudioFormatMPEG4AAC`. The Kotlin sibling cannot probe surround on the
/// iOS Simulator because `AVAssetWriterInput.init` throws an uncatchable
/// `NSInvalidArgumentException` for 5.1/7.1 layouts there.
///
/// Discovery tool — always passes. Emits the matrix in two forms:
/// - stdout (via `print`) so it shows in Device Farm device logs
/// - `XCTAttachment` so Device Farm captures it as a FILE artifact that the
///   GitHub Actions workflow downloads and re-uploads for human pickup.
final class AudioBitrateCharacterizationTests: XCTestCase {

    // MARK: - Sweep parameters (must match Kotlin test)

    private static let sampleRate = 44_100
    private static let bitrateStart = 32_000
    private static let bitrateEnd = 1_280_000
    private static let bitrateStep = 32_000
    private static let channelCounts = [1, 2, 6, 8]
    private static let allChannels = [1, 2, 6, 8]

    // MARK: - Lifecycle

    private var testDir: URL!

    override func setUp() {
        super.setUp()
        testDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("audio-bitrate-char-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(
            at: testDir, withIntermediateDirectories: true
        )
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: testDir)
        super.tearDown()
    }

    // MARK: - Test

    func testSweepBitrateChannelGrid() throws {
        var results: [Pair: Bool] = [:]
        for channelCount in Self.channelCounts {
            let inputURL = try generateFixture(channelCount: channelCount)
            sweepBitrates(inputURL: inputURL, channelCount: channelCount, into: &results)
            try? FileManager.default.removeItem(at: inputURL)
        }

        let docTable = formatDocTable(results: results)
        let fullReport = formatMatrix(results: results)

        print(fullReport)

        // Splice-ready table (lands inside <!-- ACCEPTANCE_MATRIX --> markers).
        let tableAttachment = XCTAttachment(
            data: Data(docTable.utf8),
            uniformTypeIdentifier: "net.daringfireball.markdown"
        )
        tableAttachment.name = "audio-bitrate-matrix-table.md"
        tableAttachment.lifetime = .keepAlways
        add(tableAttachment)

        // Full report with legend, for human readability.
        let reportAttachment = XCTAttachment(
            data: Data(fullReport.utf8),
            uniformTypeIdentifier: "net.daringfireball.markdown"
        )
        reportAttachment.name = "audio-bitrate-matrix-full.md"
        reportAttachment.lifetime = .keepAlways
        add(reportAttachment)
    }

    // MARK: - Fixture

    private func generateFixture(channelCount: Int) throws -> URL {
        let wavData = WavFixture.generate(
            durationSec: 1,
            sampleRate: Self.sampleRate,
            channels: channelCount
        )
        let url = testDir.appendingPathComponent("fixture_\(channelCount)ch.wav")
        try wavData.write(to: url)
        return url
    }

    // MARK: - Sweep

    private func sweepBitrates(
        inputURL: URL,
        channelCount: Int,
        into results: inout [Pair: Bool]
    ) {
        for bitrate in stride(from: Self.bitrateStart,
                              through: Self.bitrateEnd,
                              by: Self.bitrateStep) {
            let outputURL = testDir.appendingPathComponent(
                "probe_\(channelCount)ch_\(bitrate)bps.m4a"
            )
            let accepted = probeEncoder(
                inputURL: inputURL,
                outputURL: outputURL,
                channelCount: channelCount,
                bitrate: bitrate
            )
            results[Pair(channelCount, bitrate)] = accepted
            try? FileManager.default.removeItem(at: outputURL)
        }
    }

    // MARK: - Probe

    /// Returns `true` if the encoder accepted exactly one sample buffer at
    /// (channelCount, bitrate, 44.1 kHz). Any NSException or thrown error
    /// counts as rejection.
    private func probeEncoder(
        inputURL: URL,
        outputURL: URL,
        channelCount: Int,
        bitrate: Int
    ) -> Bool {
        var accepted = false
        let error = ObjCExceptionCatcher.catchExceptionInBlock {
            accepted = self.probeEncoderInner(
                inputURL: inputURL,
                outputURL: outputURL,
                channelCount: channelCount,
                bitrate: bitrate
            )
        }
        if error != nil {
            return false
        }
        return accepted
    }

    private func probeEncoderInner(
        inputURL: URL,
        outputURL: URL,
        channelCount: Int,
        bitrate: Int
    ) -> Bool {
        let asset = AVURLAsset(url: inputURL)
        guard let track = asset.tracks(withMediaType: .audio).first else {
            return false
        }
        let reader: AVAssetReader
        do {
            reader = try AVAssetReader(asset: asset)
        } catch {
            return false
        }
        let readerOutput = AVAssetReaderTrackOutput(
            track: track,
            outputSettings: decodingSettings(channelCount: channelCount)
        )
        reader.add(readerOutput)

        let writer: AVAssetWriter
        do {
            writer = try AVAssetWriter(outputURL: outputURL, fileType: .m4a)
        } catch {
            return false
        }
        let writerInput = AVAssetWriterInput(
            mediaType: .audio,
            outputSettings: encodingSettings(
                channelCount: channelCount, bitrate: bitrate
            )
        )
        writerInput.expectsMediaDataInRealTime = false
        writer.add(writerInput)

        guard reader.startReading(), writer.startWriting() else {
            reader.cancelReading()
            writer.cancelWriting()
            return false
        }
        writer.startSession(atSourceTime: CMTime(value: 0, timescale: 1))

        guard let buffer = readerOutput.copyNextSampleBuffer() else {
            reader.cancelReading()
            writer.cancelWriting()
            return false
        }
        let didAppend = writerInput.append(buffer)
        writerInput.markAsFinished()
        reader.cancelReading()
        writer.cancelWriting()
        return didAppend
    }

    // MARK: - AV settings

    private func decodingSettings(channelCount: Int) -> [String: Any] {
        [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVLinearPCMBitDepthKey: 16,
            AVLinearPCMIsFloatKey: false,
            AVLinearPCMIsBigEndianKey: false,
            AVLinearPCMIsNonInterleaved: false,
            AVSampleRateKey: Self.sampleRate,
            AVNumberOfChannelsKey: channelCount,
        ]
    }

    private func encodingSettings(channelCount: Int, bitrate: Int) -> [String: Any] {
        [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVEncoderBitRateKey: bitrate,
            AVSampleRateKey: Self.sampleRate,
            AVNumberOfChannelsKey: channelCount,
            AVChannelLayoutKey: channelLayoutData(channelCount: channelCount),
        ]
    }

    /// Packs `(tag << 16) | channels` into 12 bytes little-endian, matching
    /// `AudioChannelLayout` struct layout (mChannelLayoutTag + mChannelBitmap +
    /// mNumberChannelDescriptions, latter two zeroed for predefined tags).
    ///
    /// Tag IDs: 100 = Mono, 101 = Stereo, 121 = MPEG_5_1_A, 128 = MPEG_7_1_C.
    private func channelLayoutData(channelCount: Int) -> Data {
        let tag: UInt32
        switch channelCount {
        case 1: tag = (100 << 16) | 1
        case 2: tag = (101 << 16) | 2
        case 6: tag = (121 << 16) | 6
        case 8: tag = (128 << 16) | 8
        default: fatalError("Unsupported channel count: \(channelCount)")
        }
        var bytes = [UInt8](repeating: 0, count: 12)
        bytes[0] = UInt8(tag & 0xFF)
        bytes[1] = UInt8((tag >> 8) & 0xFF)
        bytes[2] = UInt8((tag >> 16) & 0xFF)
        bytes[3] = UInt8((tag >> 24) & 0xFF)
        return Data(bytes)
    }

    // MARK: - Formatters

    /// Splice-ready markdown table. Exactly matches the columns between
    /// `<!-- ACCEPTANCE_MATRIX -->` markers in `docs/audio-bitrate-matrix.md`.
    private func formatDocTable(results: [Pair: Bool]) -> String {
        var out = ""
        out += "| Bitrate (bps) | Mono (1ch) | Stereo (2ch) | 5.1 (6ch) | 7.1 (8ch) |\n"
        out += "|---------------|:----------:|:------------:|:---------:|:---------:|\n"
        for bitrate in stride(from: Self.bitrateStart,
                              through: Self.bitrateEnd,
                              by: Self.bitrateStep) {
            let cells = Self.allChannels.map { ch -> String in
                if !Self.channelCounts.contains(ch) { return "?" }
                return (results[Pair(ch, bitrate)] == true) ? "Y" : "N"
            }
            out += "| \(formatWithCommas(bitrate)) | \(cells.joined(separator: " | ")) |\n"
        }
        return out
    }

    private func formatMatrix(results: [Pair: Bool]) -> String {
        var out = ""
        out += "# AudioToolbox AAC-LC Bitrate Acceptance Matrix\n\n"
        out += "Sample rate: \(Self.sampleRate) Hz\n\n"
        out += "| Bitrate (bps) |"
        for ch in Self.channelCounts { out += " \(ch)ch |" }
        out += "\n|---------------|"
        for _ in Self.channelCounts { out += ":---:|" }
        out += "\n"
        for bitrate in stride(from: Self.bitrateStart,
                              through: Self.bitrateEnd,
                              by: Self.bitrateStep) {
            out += "| \(bitrate) |"
            for ch in Self.channelCounts {
                let mark = (results[Pair(ch, bitrate)] == true) ? " Y " : " N "
                out += "\(mark)|"
            }
            out += "\n"
        }
        out += "\nLegend: Y = encoder accepted, N = encoder rejected\n"
        return out
    }

    private func formatWithCommas(_ value: Int) -> String {
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        formatter.groupingSeparator = ","
        formatter.groupingSize = 3
        return formatter.string(from: NSNumber(value: value)) ?? String(value)
    }

    // MARK: - Helpers

    private struct Pair: Hashable {
        let channels: Int
        let bitrate: Int
        init(_ channels: Int, _ bitrate: Int) {
            self.channels = channels
            self.bitrate = bitrate
        }
    }
}
```

- [ ] **Step 2: Commit the Swift test source**

```bash
git add iosDeviceSmokeTests/Tests/AudioBitrateCharacterizationTests.swift
git commit -m "test(ios): Swift port of AudioToolbox bitrate characterization [CRA-78]"
```

---

## Task 2: Add `CharacterizationTests` XCTest target in xcodegen

**Files:**
- Modify: `iosDeviceSmokeTests/project.yml`

- [ ] **Step 1: Read the current `project.yml`**

Run: `cat iosDeviceSmokeTests/project.yml`

Expected: shows the two top-level targets `SmokeTestHost` and `SmokeTests`. The `SmokeTests.sources` is `- path: Tests` (no excludes).

- [ ] **Step 2: Exclude the new test from `SmokeTests` and add `CharacterizationTests`**

Apply this diff to `iosDeviceSmokeTests/project.yml`:

```diff
   SmokeTests:
     type: bundle.unit-test
     platform: iOS
     sources:
-      - path: Tests
+      - path: Tests
+        excludes:
+          - "AudioBitrateCharacterizationTests.swift"
     info:
       path: Tests/Info.plist
     settings:
       base:
         PRODUCT_BUNDLE_IDENTIFIER: co.crackn.kompressor.devicetest.tests
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
+
+  CharacterizationTests:
+    type: bundle.unit-test
+    platform: iOS
+    sources:
+      - path: Tests/AudioBitrateCharacterizationTests.swift
+      - path: Tests/ObjCExceptionCatcher.h
+      - path: Tests/ObjCExceptionCatcher.m
+      - path: Tests/WavFixture.swift
+    info:
+      path: Tests/Info.plist
+    settings:
+      base:
+        PRODUCT_BUNDLE_IDENTIFIER: co.crackn.kompressor.devicetest.characterization
+        CODE_SIGNING_ALLOWED: "NO"
+        CODE_SIGNING_REQUIRED: "NO"
+        CODE_SIGN_IDENTITY: ""
+        TEST_HOST: "$(BUILT_PRODUCTS_DIR)/SmokeTestHost.app/SmokeTestHost"
+        BUNDLE_LOADER: "$(TEST_HOST)"
+        SWIFT_OBJC_BRIDGING_HEADER: Tests/BridgingHeader.h
+    dependencies:
+      - target: SmokeTestHost
```

Note: `CharacterizationTests` does NOT need `FRAMEWORK_SEARCH_PATHS` for
`Kompressor.framework` because its test file does not `import Kompressor`.
It only needs the bridging header for `ObjCExceptionCatcher`.

- [ ] **Step 3: Regenerate the Xcode project to verify xcodegen accepts the YAML**

Run:
```bash
cd iosDeviceSmokeTests && xcodegen generate && cd ..
```

Expected: `Created project at .../KompressorDeviceSmokeTests.xcodeproj` with no errors.

If xcodegen is not installed locally: `brew install xcodegen`.

- [ ] **Step 4: Verify both XCTest bundles are in the generated project**

Run:
```bash
grep -E "SmokeTests\.xctest|CharacterizationTests\.xctest" \
  iosDeviceSmokeTests/KompressorDeviceSmokeTests.xcodeproj/project.pbxproj | head
```

Expected: lines referencing both bundles appear.

- [ ] **Step 5: Clean up the generated project from git staging**

The `.xcodeproj` is regenerated at build time. Check `.gitignore`:

```bash
grep -E "xcodeproj|\.xcodeproj" .gitignore iosDeviceSmokeTests/.gitignore 2>/dev/null
```

Expected: `.xcodeproj` is git-ignored (look for any match; if not, xcodegen output is checked in — in which case you must also add that file to the commit). The smoke workflow regenerates it per run (`xcodegen generate`), so it should be ignored.

- [ ] **Step 6: Commit the project.yml edit**

```bash
git add iosDeviceSmokeTests/project.yml
git commit -m "build(ios): add CharacterizationTests xctest target [CRA-78]"
```

---

## Task 3: Local compile verification of the new target

**Files:**
- (no edits; validation step)

- [ ] **Step 1: Build the new target for an arm64 device (without signing)**

Run:
```bash
./gradlew :kompressor:linkReleaseFrameworkIosArm64
cd iosDeviceSmokeTests
xcodegen generate
xcodebuild build-for-testing \
  -project KompressorDeviceSmokeTests.xcodeproj \
  -scheme CharacterizationTests \
  -destination 'generic/platform=iOS' \
  -derivedDataPath build \
  CODE_SIGNING_ALLOWED=NO | xcbeautify --renderer terminal
cd ..
```

Expected: `** TEST BUILD SUCCEEDED **` in the output. No compile errors for `AudioBitrateCharacterizationTests.swift`.

If this is run on a dev machine without a macOS environment, skip this step — Task 5 covers the CI build. But prefer to run this locally: it catches Swift compile issues in seconds, whereas CI takes minutes.

- [ ] **Step 2: Sanity-check the compiled test bundle identifier**

Run:
```bash
defaults read "$(pwd)/iosDeviceSmokeTests/build/Build/Products/Debug-iphoneos/CharacterizationTests.xctest/Info.plist" CFBundleIdentifier
```

Expected: `co.crackn.kompressor.devicetest.characterization`

---

## Task 4: Add the Device Farm workflow

**Files:**
- Create: `.github/workflows/ios-audio-characterization.yml`

- [ ] **Step 1: Create the workflow file**

Write `.github/workflows/ios-audio-characterization.yml`:

```yaml
name: iOS audio bitrate characterization (AWS Device Farm)

# Manual-only trigger. This is a discovery tool that updates
# docs/audio-bitrate-matrix.md; it is not a regression gate. Each invocation
# costs ~$1.50 of Device Farm time.
on:
  workflow_dispatch:

concurrency:
  group: ${{ github.workflow }}
  cancel-in-progress: true

env:
  AWS_REGION: us-west-2
  MOBILE_CI_BUDGET_MONTH: ${{ vars.MOBILE_CI_BUDGET_MONTH || '75' }}

jobs:
  budget-guard:
    name: Budget guard
    runs-on: ubuntu-latest
    permissions:
      id-token: write
      contents: read
    outputs:
      should-run: ${{ steps.check.outputs.should-run }}
    steps:
      - uses: actions/checkout@de0fac2e4500dabe0009e67214ff5f5447ce83dd # v6
      - uses: aws-actions/configure-aws-credentials@7474bc4690e29a8392af63c5b98e7449536d5c3a # v4
        with:
          role-to-assume: ${{ vars.AWS_ROLE_ARN }}
          aws-region: us-east-1
      - id: check
        env:
          MOBILE_CI_BUDGET_MONTH: ${{ env.MOBILE_CI_BUDGET_MONTH }}
        run: |
          set +e
          bash scripts/ci/check-devicefarm-budget.sh
          code=$?
          if [[ $code -eq 0 ]]; then
            echo "should-run=true" >> "$GITHUB_OUTPUT"
          elif [[ $code -eq 78 ]]; then
            echo "::warning::Device Farm budget exhausted — skipping characterization."
            echo "should-run=false" >> "$GITHUB_OUTPUT"
          else
            echo "::error::Budget guard script errored ($code)"
            exit $code
          fi

  characterization:
    name: Audio bitrate characterization (iPhone A15+ iOS 17+)
    needs: budget-guard
    if: needs.budget-guard.outputs.should-run == 'true'
    runs-on: macos-latest
    timeout-minutes: 45
    permissions:
      id-token: write
      contents: read
    env:
      RUN_NAME: ios-audio-char-${{ github.run_id }}-${{ github.run_attempt }}
    steps:
      - uses: actions/checkout@de0fac2e4500dabe0009e67214ff5f5447ce83dd # v6
        with:
          lfs: true

      - uses: actions/setup-java@be666c2fcd27ec809703dec50e508c2fdc7f6654 # v5
        with:
          distribution: temurin
          java-version: 21

      - uses: gradle/actions/setup-gradle@50e97c2cd7a37755bbfafc9c5b7cafaece252f6e # v6.1.0

      - uses: actions/cache@27d5ce7f107fe9357f9df03efb73ab90386fccae # v5
        with:
          path: ~/.konan
          key: konan-${{ runner.os }}-${{ hashFiles('**/libs.versions.toml') }}
          restore-keys: |
            konan-${{ runner.os }}-

      - name: Build KMP framework (iosArm64)
        run: ./gradlew :kompressor:linkReleaseFrameworkIosArm64

      - name: Install XcodeGen and xcbeautify
        run: brew install xcodegen xcbeautify

      - name: Generate Xcode project
        working-directory: iosDeviceSmokeTests
        run: xcodegen generate

      - name: Import signing cert and provisioning profile
        env:
          IOS_CERT_P12: ${{ secrets.IOS_CERT_P12 }}
          IOS_CERT_PASSWORD: ${{ secrets.IOS_CERT_PASSWORD }}
          IOS_PROVISIONING_PROFILE: ${{ secrets.IOS_PROVISIONING_PROFILE }}
        run: |
          set -euo pipefail
          KEYCHAIN_PATH="$RUNNER_TEMP/ios-signing.keychain-db"
          KEYCHAIN_PASSWORD="$(openssl rand -hex 24)"
          security create-keychain -p "$KEYCHAIN_PASSWORD" "$KEYCHAIN_PATH"
          security set-keychain-settings -lut 21600 "$KEYCHAIN_PATH"
          security unlock-keychain -p "$KEYCHAIN_PASSWORD" "$KEYCHAIN_PATH"
          security list-keychains -d user -s "$KEYCHAIN_PATH" $(security list-keychains -d user | tr -d '"')

          printf '%s' "$IOS_CERT_P12" | base64 --decode > "$RUNNER_TEMP/cert.p12"
          security import "$RUNNER_TEMP/cert.p12" \
            -k "$KEYCHAIN_PATH" \
            -P "$IOS_CERT_PASSWORD" \
            -T /usr/bin/codesign \
            -T /usr/bin/security
          security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$KEYCHAIN_PASSWORD" "$KEYCHAIN_PATH"

          PROFILE_TMP="$RUNNER_TEMP/profile.mobileprovision"
          printf '%s' "$IOS_PROVISIONING_PROFILE" | base64 --decode > "$PROFILE_TMP"
          PROFILE_UUID=$(/usr/libexec/PlistBuddy -c 'Print :UUID' /dev/stdin \
            <<< "$(security cms -D -i "$PROFILE_TMP")")
          PROFILE_NAME=$(/usr/libexec/PlistBuddy -c 'Print :Name' /dev/stdin \
            <<< "$(security cms -D -i "$PROFILE_TMP")")
          PROFILE_APP_ID=$(/usr/libexec/PlistBuddy -c 'Print :Entitlements:application-identifier' /dev/stdin \
            <<< "$(security cms -D -i "$PROFILE_TMP")")
          echo "Profile: name='$PROFILE_NAME' uuid=$PROFILE_UUID app-id=$PROFILE_APP_ID"
          mkdir -p "$HOME/Library/MobileDevice/Provisioning Profiles"
          cp "$PROFILE_TMP" "$HOME/Library/MobileDevice/Provisioning Profiles/${PROFILE_UUID}.mobileprovision"
          echo "KEYCHAIN_PATH=$KEYCHAIN_PATH" >> "$GITHUB_ENV"
          echo "PROFILE_UUID=$PROFILE_UUID" >> "$GITHUB_ENV"

      - name: Build CharacterizationTests for testing (arm64 device, signed)
        working-directory: iosDeviceSmokeTests
        env:
          APPLE_TEAM_ID: ${{ secrets.APPLE_TEAM_ID }}
        run: |
          set -euo pipefail
          xcodebuild build-for-testing \
            -project KompressorDeviceSmokeTests.xcodeproj \
            -scheme CharacterizationTests \
            -destination 'generic/platform=iOS' \
            -derivedDataPath build \
            DEVELOPMENT_TEAM="$APPLE_TEAM_ID" \
            CODE_SIGN_STYLE=Manual \
            CODE_SIGN_IDENTITY="Apple Development" \
            PROVISIONING_PROFILE_SPECIFIER="$PROFILE_UUID" \
            OTHER_CODE_SIGN_FLAGS="--keychain $KEYCHAIN_PATH" \
            | xcbeautify --renderer github-actions

      - name: Package IPA and test bundle
        id: package
        working-directory: iosDeviceSmokeTests/build/Build/Products
        run: |
          set -euo pipefail
          mkdir -p Payload
          cp -R Debug-iphoneos/SmokeTestHost.app Payload/
          zip -qr "$GITHUB_WORKSPACE/SmokeTestHost.ipa" Payload
          rm -rf Payload

          cd Debug-iphoneos/SmokeTestHost.app/PlugIns
          zip -qr "$GITHUB_WORKSPACE/CharacterizationTests.xctest.zip" CharacterizationTests.xctest

          ls -lh "$GITHUB_WORKSPACE/SmokeTestHost.ipa" "$GITHUB_WORKSPACE/CharacterizationTests.xctest.zip"
          echo "ipa=$GITHUB_WORKSPACE/SmokeTestHost.ipa" >> "$GITHUB_OUTPUT"
          echo "tests=$GITHUB_WORKSPACE/CharacterizationTests.xctest.zip" >> "$GITHUB_OUTPUT"

      - uses: aws-actions/configure-aws-credentials@7474bc4690e29a8392af63c5b98e7449536d5c3a # v4
        with:
          role-to-assume: ${{ vars.AWS_ROLE_ARN }}
          aws-region: ${{ env.AWS_REGION }}

      - name: Upload app + test bundle to Device Farm
        id: upload
        env:
          PROJECT_ARN: ${{ vars.AWS_DEVICEFARM_PROJECT_ARN }}
          IPA: ${{ steps.package.outputs.ipa }}
          TESTS: ${{ steps.package.outputs.tests }}
        run: |
          set -euo pipefail
          upload() {
            local file="$1" type="$2" name="$3"
            echo "::group::Upload $name ($type, $(du -h "$file" | cut -f1))"
            local resp arn url
            resp=$(aws devicefarm create-upload \
              --region "$AWS_REGION" \
              --project-arn "$PROJECT_ARN" \
              --name "$name" \
              --type "$type" \
              --output json)
            arn=$(echo "$resp" | jq -r '.upload.arn')
            url=$(echo "$resp" | jq -r '.upload.url')
            echo "arn=$arn"
            curl -fsS -T "$file" -H "Content-Type: application/octet-stream" "$url"
            local attempts=0
            while :; do
              local getj status message
              getj=$(aws devicefarm get-upload --region "$AWS_REGION" --arn "$arn" --output json)
              status=$(echo "$getj" | jq -r '.upload.status')
              echo "  $name: $status"
              case "$status" in
                SUCCEEDED)
                  echo "::endgroup::"
                  printf '%s' "$arn" > "$RUNNER_TEMP/${name}.arn"
                  return 0
                  ;;
                FAILED|ERRORED)
                  message=$(echo "$getj" | jq -r '.upload.metadata, .upload.message // empty')
                  echo "::error::Upload $name status=$status: $message"
                  echo "full response:"; echo "$getj"
                  echo "::endgroup::"
                  return 1
                  ;;
                *)
                  attempts=$((attempts+1))
                  if (( attempts > 60 )); then
                    echo "::error::Upload $name stuck in $status after 5 min"
                    echo "::endgroup::"
                    return 1
                  fi
                  sleep 5
                  ;;
              esac
            done
          }
          upload "$IPA" IOS_APP "SmokeTestHost.ipa"
          upload "$TESTS" XCTEST_TEST_PACKAGE "CharacterizationTests.xctest.zip"
          echo "app-arn=$(cat "$RUNNER_TEMP/SmokeTestHost.ipa.arn")" >> "$GITHUB_OUTPUT"
          echo "test-arn=$(cat "$RUNNER_TEMP/CharacterizationTests.xctest.zip.arn")" >> "$GITHUB_OUTPUT"

      - name: Schedule Device Farm run
        id: run
        env:
          PROJECT_ARN: ${{ vars.AWS_DEVICEFARM_PROJECT_ARN }}
          POOL_ARN: ${{ vars.AWS_DEVICEFARM_POOL_ARN }}
          APP_ARN: ${{ steps.upload.outputs.app-arn }}
          TEST_ARN: ${{ steps.upload.outputs.test-arn }}
        run: |
          set -euo pipefail
          TEST_PAYLOAD=$(jq -nc --arg arn "$TEST_ARN" '{type:"XCTEST",testPackageArn:$arn}')
          RUN_ARN=$(aws devicefarm schedule-run \
            --region "$AWS_REGION" \
            --project-arn "$PROJECT_ARN" \
            --device-pool-arn "$POOL_ARN" \
            --name "$RUN_NAME" \
            --app-arn "$APP_ARN" \
            --test "$TEST_PAYLOAD" \
            --execution-configuration "jobTimeoutMinutes=30,videoCapture=false" \
            --query 'run.arn' --output text)
          echo "run-arn=$RUN_ARN" >> "$GITHUB_OUTPUT"
          echo "Scheduled run: $RUN_ARN"

      - name: Poll Device Farm run to completion
        id: poll
        env:
          RUN_ARN: ${{ steps.run.outputs.run-arn }}
        run: |
          set -euo pipefail
          while :; do
            RUN_JSON=$(aws devicefarm get-run --region "$AWS_REGION" --arn "$RUN_ARN" --output json)
            STATUS=$(echo "$RUN_JSON" | jq -r '.run.status')
            RESULT=$(echo "$RUN_JSON" | jq -r '.run.result')
            echo "run status=$STATUS result=$RESULT"
            if [[ "$STATUS" == "COMPLETED" ]]; then
              echo "result=$RESULT" >> "$GITHUB_OUTPUT"
              break
            fi
            sleep 30
          done

      - name: Download Device Farm artifacts
        if: always() && steps.run.outputs.run-arn != ''
        env:
          RUN_ARN: ${{ steps.run.outputs.run-arn }}
        run: |
          set -euo pipefail
          mkdir -p artifacts/ios-device
          JOBS=$(aws devicefarm list-jobs --region "$AWS_REGION" --arn "$RUN_ARN" --query 'jobs[].arn' --output text)
          for JOB_ARN in $JOBS; do
            for TYPE in FILE LOG SCREENSHOT; do
              aws devicefarm list-artifacts --region "$AWS_REGION" --arn "$JOB_ARN" --type "$TYPE" \
                --query 'artifacts[].[name,extension,url]' --output text \
              | while IFS=$'\t' read -r name ext url; do
                  [[ -z "$url" ]] && continue
                  safe=$(echo "${name}.${ext}" | tr '/ ' '__')
                  curl -fsS -o "artifacts/ios-device/${safe}" "$url" || true
                done
            done
          done
          echo "::group::Downloaded artifacts"
          ls -la artifacts/ios-device/ || true
          echo "::endgroup::"

      - name: Extract bitrate matrix from artifacts
        id: matrix
        if: always() && steps.run.outputs.run-arn != ''
        run: |
          set -euo pipefail
          mkdir -p artifacts/audio-bitrate-matrix
          # XCTAttachment artifacts land as "audio-bitrate-matrix-table.md" /
          # "audio-bitrate-matrix-full.md", with the Device Farm safe-name
          # transform applied. Glob both underscored and hyphenated spellings.
          shopt -s nullglob
          found=0
          for f in artifacts/ios-device/*audio?bitrate?matrix*; do
            cp "$f" artifacts/audio-bitrate-matrix/
            echo "Matrix artifact: $f"
            found=1
          done
          if [[ "$found" -eq 0 ]]; then
            echo "::warning::No matrix artifacts found. XCTAttachment may not have been captured — check Device Farm LOG artifact for test output."
          fi

      - name: Upload bitrate matrix
        if: always()
        uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7
        with:
          name: audio-bitrate-matrix-${{ github.run_id }}
          path: artifacts/audio-bitrate-matrix/
          if-no-files-found: warn

      - name: Upload all Device Farm artifacts (logs + screenshots + files)
        if: always()
        uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7
        with:
          name: devicefarm-raw-${{ github.run_id }}
          path: artifacts/ios-device/

      - name: Fail job if Device Farm run did not pass
        if: steps.poll.outputs.result != 'PASSED'
        env:
          DF_RESULT: ${{ steps.poll.outputs.result }}
        run: |
          echo "::error::Device Farm run result=$DF_RESULT"
          exit 1

      - name: Cleanup signing keychain
        if: always()
        run: |
          if [[ -n "${KEYCHAIN_PATH:-}" && -f "$KEYCHAIN_PATH" ]]; then
            security delete-keychain "$KEYCHAIN_PATH" || true
          fi
```

Diff vs existing `ios-device-smoke.yml`:
- `on:` is `workflow_dispatch` only (no PR gate).
- Test bundle is `CharacterizationTests.xctest.zip`, built from `-scheme CharacterizationTests`.
- `jobTimeoutMinutes` bumped from 15 → 30 (sweep is ~130s/channel × 4 channels, plus boot/upload overhead).
- New `Extract bitrate matrix from artifacts` step globs the XCTAttachment files and stages them separately.
- Two artifact uploads: `audio-bitrate-matrix-<run-id>` (just the matrix) and `devicefarm-raw-<run-id>` (everything, for debugging).

- [ ] **Step 2: Validate the workflow YAML syntax**

Run:
```bash
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/ios-audio-characterization.yml'))" && echo "YAML OK"
```

Expected: `YAML OK`.

- [ ] **Step 3: Shellcheck-style sanity on embedded run scripts**

Visually verify:
- All `run:` blocks start with `set -euo pipefail` (matching existing workflow style) — except trivial ones.
- All env vars are quoted where needed.
- Heredocs use the same quoting as the reference workflow.

No automated check required — the workflow is a near-clone of the already-vetted `ios-device-smoke.yml`.

- [ ] **Step 4: Commit the workflow**

```bash
git add .github/workflows/ios-audio-characterization.yml
git commit -m "ci(audio): workflow_dispatch runner for AudioToolbox bitrate characterization [CRA-78]"
```

---

## Task 5: Update the Kotlin test's docstring to point at the Swift port

**Files:**
- Modify: `kompressor/src/iosTest/kotlin/co/crackn/kompressor/audio/AudioToolboxBitrateCharacterizationTest.kt:50-67`

The Kotlin test stays functionally unchanged (still runs mono/stereo on simulator). Only update the header KDoc to tell future readers where the surround characterization actually lives.

- [ ] **Step 1: Apply the docstring update**

Replace the existing docstring at `AudioToolboxBitrateCharacterizationTest.kt:50-67`:

```kotlin
/**
 * Characterization test that empirically discovers which bitrate / channel-count combinations
 * Apple's AudioToolbox AAC-LC encoder accepts via [AVAssetWriterInput]. Sweeps the grid
 * {channels × bitrate: 32k–1280k, step 32k} at 44.1 kHz.
 * Surround (6, 8) is gated to hardware runs — see [CHANNEL_COUNTS] for why.
 *
 * This is a **discovery tool**, not a regression gate — it always passes. Results are printed
 * to stdout. When the `KOMPRESSOR_DOCS_DIR` environment variable is set, the matrix is also
 * written to `$KOMPRESSOR_DOCS_DIR/audio-bitrate-matrix.md`; otherwise it falls back to a
 * UUID-suffixed file in `NSTemporaryDirectory()`.
 *
 * Hardware run with surround + repo write-back:
 * ```
 * KOMPRESSOR_DOCS_DIR=/path/to/kompressor/docs \
 *   xcodebuild test -scheme kompressor -destination 'platform=iOS,name=<device>' \
 *   -only-testing:AudioToolboxBitrateCharacterizationTest
 * ```
 */
```

with this updated version:

```kotlin
/**
 * Characterization test that empirically discovers which bitrate / channel-count combinations
 * Apple's AudioToolbox AAC-LC encoder accepts via [AVAssetWriterInput]. Sweeps the grid
 * {channels × bitrate: 32k–1280k, step 32k} at 44.1 kHz.
 *
 * This Kotlin test covers the **mono (1) + stereo (2)** cells on the iOS Simulator lane of the
 * regular unit-test CI. Surround layouts (5.1 / 7.1) cannot be probed here because the
 * Simulator's AAC encoder throws an uncatchable `NSInvalidArgumentException` on 6/8-channel
 * `AVAssetWriterInput` configs — see [CHANNEL_COUNTS] below. The authoritative surround sweep
 * runs on real A15+ hardware via AWS Device Farm; the Swift sibling lives at
 * `iosDeviceSmokeTests/Tests/AudioBitrateCharacterizationTests.swift` and is triggered manually
 * by the `ios-audio-characterization` GitHub Actions workflow (see
 * `.github/workflows/ios-audio-characterization.yml`).
 *
 * This is a **discovery tool**, not a regression gate — it always passes. Results are printed
 * to stdout. When the `KOMPRESSOR_DOCS_DIR` environment variable is set, the matrix is also
 * written to `$KOMPRESSOR_DOCS_DIR/audio-bitrate-matrix.md`; otherwise it falls back to a
 * UUID-suffixed file in `NSTemporaryDirectory()`.
 *
 * Hardware run (simulator fallback — prefer Device Farm workflow for a complete matrix):
 * ```
 * KOMPRESSOR_DOCS_DIR=/path/to/kompressor/docs \
 *   xcodebuild test -scheme kompressor -destination 'platform=iOS,name=<device>' \
 *   -only-testing:AudioToolboxBitrateCharacterizationTest
 * ```
 */
```

- [ ] **Step 2: Verify the edit didn't break Kotlin parsing**

Run:
```bash
./gradlew :kompressor:detektIosSimulatorArm64 --no-configuration-cache 2>&1 | tail -20
```

Expected: detekt completes without errors. If detekt is not available in this env, fall back to:

```bash
./gradlew :kompressor:compileTestKotlinIosSimulatorArm64 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add kompressor/src/iosTest/kotlin/co/crackn/kompressor/audio/AudioToolboxBitrateCharacterizationTest.kt
git commit -m "docs(audio): point characterization KDoc at Swift Device Farm sibling [CRA-78]"
```

---

## Task 6: Add the Swift test to the device-CI documentation

**Files:**
- Modify: `docs/ios-device-ci.md:85-91` (the "Test mapping" table)

- [ ] **Step 1: Read the current mapping table**

Run:
```bash
sed -n '84,96p' docs/ios-device-ci.md
```

Expected: the "Test mapping" section header and its 4-row table.

- [ ] **Step 2: Append the characterization row and a note about its trigger cadence**

Apply this edit to `docs/ios-device-ci.md`:

Replace:
```markdown
| Swift XCTest (Device Farm) | Kotlin iosTest (simulator) | Behaviour |
|---|---|---|
| `Hdr10ExportTests` | `Hdr10ExportRoundTripTest` | Kotlin test skips on sim via `runDeviceOnly()` |
| `Surround51Tests` | `Surround51RoundTripTest` | Same |
| `Surround71Tests` | `Surround71RoundTripTest` | Same |
| `IosLargeInputStreamingTests` | — (Swift-only) | Generates a ~200 MB 1080p/60s fixture on device and asserts `task_info` peak stays ≤ 300 MB while `IosVideoCompressor` streams it. No Kotlin sibling: peak-memory sampling and `task_info(TASK_VM_INFO)` are expressed naturally in Swift, and the DoD only requires the device-side assertion |

The Kotlin tests contain detailed assertions (progress monotonicity, channel
count preservation, compression result validation). The Swift wrappers are thin
smoke tests that verify the compression completes and produces output on real
hardware.
```

with:
```markdown
| Swift XCTest (Device Farm) | Kotlin iosTest (simulator) | Behaviour |
|---|---|---|
| `Hdr10ExportTests` | `Hdr10ExportRoundTripTest` | Kotlin test skips on sim via `runDeviceOnly()` |
| `Surround51Tests` | `Surround51RoundTripTest` | Same |
| `Surround71Tests` | `Surround71RoundTripTest` | Same |
| `IosLargeInputStreamingTests` | — (Swift-only) | Generates a ~200 MB 1080p/60s fixture on device and asserts `task_info` peak stays ≤ 300 MB while `IosVideoCompressor` streams it. No Kotlin sibling: peak-memory sampling and `task_info(TASK_VM_INFO)` are expressed naturally in Swift, and the DoD only requires the device-side assertion |
| `AudioBitrateCharacterizationTests` (own `CharacterizationTests` bundle) | `AudioToolboxBitrateCharacterizationTest` | Kotlin test sweeps mono/stereo on sim; Swift port runs the full sweep (mono + stereo + 5.1 + 7.1) on A15+ hardware. Not in the per-PR smoke run — triggered manually via `gh workflow run ios-audio-characterization.yml` because matrix output is stable once characterized |

The Kotlin tests contain detailed assertions (progress monotonicity, channel
count preservation, compression result validation). The Swift wrappers are thin
smoke tests that verify the compression completes and produces output on real
hardware — with the exception of `AudioBitrateCharacterizationTests`, which is a
discovery tool that emits a markdown matrix as an `XCTAttachment` (captured as a
Device Farm `FILE` artifact and re-uploaded by the workflow as a GitHub Actions
artifact `audio-bitrate-matrix-<run-id>`).
```

- [ ] **Step 3: Sanity-check markdown renders correctly**

Run:
```bash
grep -n "AudioBitrateCharacterizationTests" docs/ios-device-ci.md
```

Expected: at least one line matches (the new row + the paragraph reference).

- [ ] **Step 4: Commit**

```bash
git add docs/ios-device-ci.md
git commit -m "docs(ci): document characterization test in iOS device CI mapping [CRA-78]"
```

---

## Task 7: End-to-end dry-run check

**Files:**
- (no edits; pre-PR validation)

- [ ] **Step 1: Confirm all commits are in place**

Run:
```bash
git log --oneline origin/main..HEAD
```

Expected: six commits:
1. docs(spec): design for audio bitrate characterization on Device Farm
2. test(ios): Swift port of AudioToolbox bitrate characterization
3. build(ios): add CharacterizationTests xctest target
4. ci(audio): workflow_dispatch runner for AudioToolbox bitrate characterization
5. docs(audio): point characterization KDoc at Swift Device Farm sibling
6. docs(ci): document characterization test in iOS device CI mapping

Plus the pre-existing design-doc commit from the brainstorming step. Total = 6 or 7 depending on whether the design doc is on this branch.

- [ ] **Step 2: Verify the diff touches only the planned files**

Run:
```bash
git diff --name-only origin/main..HEAD
```

Expected set (exact):
```
.github/workflows/ios-audio-characterization.yml
docs/ios-device-ci.md
docs/superpowers/plans/2026-04-16-audio-bitrate-characterization-device-farm.md
docs/superpowers/specs/2026-04-16-audio-bitrate-characterization-device-farm-design.md
iosDeviceSmokeTests/Tests/AudioBitrateCharacterizationTests.swift
iosDeviceSmokeTests/project.yml
kompressor/src/iosTest/kotlin/co/crackn/kompressor/audio/AudioToolboxBitrateCharacterizationTest.kt
```

No other files should be modified. If anything extra appears (e.g., `Kompressor.framework` binaries, build outputs, generated `.xcodeproj`), unstage and verify `.gitignore` coverage before proceeding.

- [ ] **Step 3: Push and open PR**

```bash
git push -u origin cyruscrackn/cra-78-m1-follow-up-run-audiotoolbox-bitrate-characterization-on
gh pr create --title "test(audio): Swift port + Device Farm workflow for AAC bitrate characterization [CRA-78]" --body "$(cat <<'EOF'
## Summary

- Adds `AudioBitrateCharacterizationTests.swift` — a Swift port of `AudioToolboxBitrateCharacterizationTest.kt` that runs on real iPhone A15+ hardware via AWS Device Farm, closing the surround-characterization gap that the simulator cannot cover.
- Adds `.github/workflows/ios-audio-characterization.yml` — a `workflow_dispatch`-only runner that builds, signs, uploads, schedules the Device Farm run, and publishes the generated bitrate matrix as a GitHub Actions artifact.
- Adds a second XCTest bundle `CharacterizationTests` to `iosDeviceSmokeTests/project.yml` so Device Farm runs only the characterization test, not the full smoke suite.

The Kotlin test keeps running on the simulator unit-test lane as a cheap mono/stereo regression net — disposition confirmed during brainstorming as "keep as-is".

This PR is the **wiring** — empirical surround caps and consequent `iosAacMaxBitrate`/`iosAacMinBitrate` updates are a separate follow-up PR triggered by the first successful characterization run.

Part of [CRA-78](https://linear.app/crackn/issue/CRA-78).

## Test plan

- [ ] `xcodebuild build-for-testing -scheme CharacterizationTests` succeeds locally (or in CI macos runner)
- [ ] `gh workflow run ios-audio-characterization.yml` kicks off cleanly
- [ ] Device Farm run completes `PASSED` (characterization always passes — it's discovery, not assertion)
- [ ] Downloaded artifact `audio-bitrate-matrix-<run-id>` contains a markdown table with Y/N for all four channel-count columns (no `?`)
- [ ] Device Farm run cost stays under ~$1.50
- [ ] Budget-guard correctly skips when `MOBILE_CI_BUDGET_MONTH` is exhausted
EOF
)"
```

Expected: a PR URL is returned. The PR should show six files changed (the seven listed above minus the design/plan docs already committed in earlier sessions if applicable).

---

## Post-landing follow-up (NOT part of this plan)

Once the workflow is triggered and produces a matrix:

1. Paste the `audio-bitrate-matrix-table.md` content between the `<!-- ACCEPTANCE_MATRIX -->` markers in `docs/audio-bitrate-matrix.md`.
2. From the matrix, compute the empirical surround caps. If they diverge from the current linear extrapolation (`160 kbps/ch × N` at 44.1 kHz → 960k for 5.1, 1280k for 7.1):
   - Update `iosAacMaxBitrate` / `iosAacMinBitrate` in `kompressor/src/iosMain/kotlin/co/crackn/kompressor/audio/IosAudioCompressor.kt`.
   - Update the corresponding rows in the "Current Validation Table" section of `docs/audio-bitrate-matrix.md`.
3. Align boundary assertions in `kompressor/src/iosTest/kotlin/co/crackn/kompressor/audio/IosAudioBitrateValidationTest.kt` with the new caps.
4. Add a CHANGELOG entry under `## [Unreleased]` covering the empirical caps (since these edits touch `kompressor/src/**`, the changelog gate applies).

These steps complete DoD items 2, 3, 4 of [CRA-78](https://linear.app/crackn/issue/CRA-78).
