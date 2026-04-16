# iOS device CI — AWS Device Farm

## Platform choice

**AWS Device Farm** for iOS physical devices.

Android device tests use Firebase Test Lab (Pixel 6 API 33 in `pr.yml`), but
iOS is hosted on AWS Device Farm because FTL's iOS device pool is thin on
recent iPhone generations, whereas Device Farm exposes iPhone 14/15/16-class
hardware with iOS 17+ reliably. Credentials are short-lived OIDC via
`aws-actions/configure-aws-credentials`; no long-lived IAM user is checked in.

### Why real hardware is required

| Feature | Simulator behaviour | Device behaviour |
|---|---|---|
| HDR10 HEVC (Main10) | `AVAssetWriterInput.init` throws uncatchable `NSInvalidArgumentException` | Encodes via VideoToolbox A10+ HW encoder |
| 5.1 surround AAC | VTAACEncoder gate failure — sim has no multi-channel encoder | Hardware AAC encoder handles 6 channels |
| 7.1 surround AAC | Same VTAACEncoder gate failure | Hardware AAC encoder handles 8 channels |

### Alternatives considered

| Provider | Verdict |
|---|---|
| **AWS Device Farm** | **Chosen.** Current iPhone models on iOS 17+, OIDC auth, pay-per-minute. |
| Firebase Test Lab | Already used for Android, but iOS device pool leans old (iPhone 8 / SE2 era). |
| BrowserStack / Sauce Labs | More expensive, no existing integration. |
| Self-hosted macOS + USB device | Fragile, requires physical maintenance. |

## Cost

| Item | Estimate |
|---|---|
| Billing model | $0.17/device-minute on-demand (private-device slots if configured) |
| Our usage per PR | 1 test run × 3-4 tests × ~3-5 min = 1 device-session (~$0.60-$1.00) |
| Normal PR volume | 5-10 PRs/day |
| Monthly budget cap | **$75** (`MOBILE_CI_BUDGET_MONTH` repo variable, default) — enforced by `scripts/ci/check-devicefarm-budget.sh` before the job runs |

### Budget alerting

`MOBILE_CI_BUDGET_MONTH` is a GitHub repository variable (Settings → Variables →
Actions). The workflow reads it with a fallback to `$75`. Before every
device-test job the `budget-guard` stage invokes
`scripts/ci/check-devicefarm-budget.sh`, which queries AWS Cost Explorer for
month-to-date Device Farm spend and short-circuits the run with a warning
annotation if the cap is exceeded (exit 78). A notice annotation logs the cap
on every successful run. For production alerting, configure an
[AWS Budgets alert](https://docs.aws.amazon.com/cost-management/latest/userguide/budgets-managing-costs.html)
on the Device Farm service with the same threshold.

## SLA

| Metric | Target |
|---|---|
| Availability | Best-effort (AWS Device Farm on-demand pool). No contractual SLA from AWS. |
| Failure mode | **Hard fail** — PR is blocked if the Device Farm run fails. Use repository admin override for confirmed AWS outages. |
| Timeout | 30 min per Device Farm run (`schedule-run --execution-configuration jobTimeoutMinutes`). Job-level: 45 min (includes build + upload). |

## Device selection

| Property | Value |
|---|---|
| Model pool | Dynamic — prefers current-generation iPhone (iPhone 14 Pro / 15 Pro / 16 Pro), falls back to iPhone SE 3 |
| iOS version | 17+ (A15+ required for HEVC Main10 + 5.1/7.1 hardware encoders) |
| Chip | A15 Bionic or newer |
| Minimum required | A15+ for HEVC Main10 + multi-channel AAC. A10+ is the raw HEVC Main10 floor, but Device Farm's modern pool starts at A15. |

## Workflow

File: `.github/workflows/ios-device-smoke.yml`

```
PR opened
  └─ Budget guard (Cost Explorer) — skips run if over MOBILE_CI_BUDGET_MONTH
  └─ macos-latest runner (if budget ok)
       ├─ Build KMP framework (iosArm64)
       ├─ Install XcodeGen + generate .xcodeproj
       ├─ xcodebuild build-for-testing (arm64)
       ├─ Package .xctestrun + test products into an .ipa + tests zip
       ├─ aws devicefarm create-upload (app + test spec) → schedule-run (hard-fail)
       └─ Upload JUnit XML + artifacts on completion
```

## Test mapping

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
