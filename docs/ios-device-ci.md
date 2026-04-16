# iOS device CI — Firebase Test Lab

## Platform choice

**Firebase Test Lab (FTL)** for iOS physical devices.

We already use FTL for Android device tests (Pixel 6 API 33 in `pr.yml`), so
the GCP project (`kompressor-library`), service account, and results bucket
(`test-lab-kompressor-ci`) are shared. No new infrastructure.

### Why real hardware is required

| Feature | Simulator behaviour | Device behaviour |
|---|---|---|
| HDR10 HEVC (Main10) | `AVAssetWriterInput.init` throws uncatchable `NSInvalidArgumentException` | Encodes via VideoToolbox A10+ HW encoder |
| 5.1 surround AAC | VTAACEncoder gate failure — sim has no multi-channel encoder | Hardware AAC encoder handles 6 channels |
| 7.1 surround AAC | Same VTAACEncoder gate failure | Hardware AAC encoder handles 8 channels |

### Alternatives considered

| Provider | Verdict |
|---|---|
| **Firebase Test Lab** | **Chosen.** Already set up, shared infra, generous free tier. |
| AWS Device Farm | Viable but would require separate setup + credentials. |
| BrowserStack / Sauce Labs | More expensive, no existing integration. |
| Self-hosted macOS + USB device | Fragile, requires physical maintenance. |

## Cost

| Item | Estimate |
|---|---|
| Free tier | ~5 physical device-tests/day (FTL iOS) |
| Our usage per PR | 1 test run × 3 tests × ~3-5 min = 1 device-session |
| Normal PR volume | 5-10 PRs/day → within free tier |
| Overage rate | ~$5/device-hour |
| Monthly budget cap | **$25** (`MOBILE_CI_BUDGET_MONTH` repo variable, default) |

### Budget alerting

`MOBILE_CI_BUDGET_MONTH` is a GitHub repository variable (Settings → Variables →
Actions). The workflow reads it with a fallback to `$25`. A notice annotation
logs the cap on every run. For production alerting, configure
a [GCP budget alert](https://cloud.google.com/billing/docs/how-to/budgets) on
the `kompressor-library` project with the same threshold.

## SLA

| Metric | Target |
|---|---|
| Availability | Best-effort (FTL iOS device pool). No contractual SLA from Google. |
| Failure mode | **Hard fail** — PR is blocked if FTL fails. Use repository admin override for confirmed FTL outages. |
| Timeout | 15 min per test run (FTL default). Job-level: 45 min (includes build + upload). |

## Device selection

| Property | Value |
|---|---|
| Model | Dynamic — prefers iPhone SE 3 (`iphonese3`), falls back to 14 Pro / 16 Pro / 11 Pro |
| iOS version | Latest available (currently 18.4) |
| Chip | A15 Bionic (iPhone SE 3) — A10+ required for HEVC Main10 |
| Minimum required | A10+ (iPhone 7+) for HEVC Main10 hardware encoding |

## Workflow

File: `.github/workflows/ios-device-smoke.yml`

```
PR opened
  └─ macos-latest runner
       ├─ Build KMP framework (iosArm64)
       ├─ Install XcodeGen + generate .xcodeproj
       ├─ xcodebuild build-for-testing (arm64)
       ├─ Zip .xctestrun + test products
       ├─ gcloud firebase test ios run (hard-fail)
       └─ Upload JUnit XML as artifact
```

## Test mapping

| Swift XCTest (FTL) | Kotlin iosTest (simulator) | Behaviour |
|---|---|---|
| `Hdr10ExportTests` | `Hdr10ExportRoundTripTest` | Kotlin test skips on sim via `runDeviceOnly()` |
| `Surround51Tests` | `Surround51RoundTripTest` | Same |
| `Surround71Tests` | `Surround71RoundTripTest` | Same |

The Kotlin tests contain detailed assertions (progress monotonicity, channel
count preservation, compression result validation). The Swift wrappers are thin
smoke tests that verify the compression completes and produces output on real
hardware.
