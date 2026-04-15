# iOS device smoke tests — AWS Device Farm runbook

## What this is

PR gate that runs `Hdr10ExportTests`, `Surround51Tests`, `Surround71Tests`
(XCTest, Swift) on a real iPhone A15+ / iOS 17+ via AWS Device Farm metered.
Replaces Firebase Test Lab (deprecated for iOS since 2024).

Trigger: every PR to `main` unless only `**/*.md`, `docs/**`, `LICENSE`,
`.gitignore`, or issue/PR templates changed.

## AWS resources

All in `us-west-2`, account `437655433978` (Device Farm is single-region).

| Resource | ARN suffix |
|---|---|
| Device Farm project | `project:e5295e19-f8c6-40a7-b108-025f108e19fb` |
| Device pool `kompressor-ios-a15plus` | `devicepool:e5295e19-…/d57a8b94-…` |
| IAM role `kompressor-ci-devicefarm` | `role/kompressor-ci-devicefarm` |
| CloudFormation stack | `kompressor-ci-oidc` |
| AWS Budget | `kompressor-devicefarm` (alert → rachid@switchy.be) |

Device pool is dynamic (rules at `.aws/devicefarm-pool-rules.json`):
Apple + iPhone + iOS ≥ 17 + FLEET_TYPE=PUBLIC, `maxDevices=1`.

## GitHub repo config

**Variables** (Settings → Secrets and variables → Actions → Variables):
- `AWS_ROLE_ARN` = `arn:aws:iam::437655433978:role/kompressor-ci-devicefarm`
- `AWS_DEVICEFARM_PROJECT_ARN`
- `AWS_DEVICEFARM_POOL_ARN`
- `MOBILE_CI_BUDGET_MONTH` = `75`

**Secrets**:
- `IOS_CERT_P12` — base64 of Apple Development .p12
- `IOS_CERT_PASSWORD`
- `IOS_PROVISIONING_PROFILE` — base64 of development profile for `co.crackn.kompressor.smoketesthost`
- `APPLE_TEAM_ID`

## Cost model

- $0.17/device-minute metered.
- Typical PR run: ~5 min device time → **~$0.85/PR**.
- Budget cap enforced by `scripts/ci/check-devicefarm-budget.sh`:
  the `budget-guard` job calls Cost Explorer, skips the device job
  (not fails) when month-to-date Device Farm spend ≥ `$MOBILE_CI_BUDGET_MONTH`.
- AWS Budget `kompressor-devicefarm` emails `rachid@switchy.be` at 50/80/100%.

## Failure playbook

### `budget-guard` skipped the device job
Expected behaviour when the monthly cap is reached. Two options:
1. Let it recover next month (zero action — merges proceed).
2. Raise the cap: bump the `MOBILE_CI_BUDGET_MONTH` GitHub Variable
   and edit the AWS Budget in parallel.

### Upload stuck in `INITIALIZED` / `PROCESSING`
Device Farm's IPA validator is slow (30-60s typical, occasionally 2 min).
The workflow polls up to completion. If it actually hangs past the
`timeout-minutes: 45`, re-run the job; usually transient.

### Run result = `ERRORED` (not `PASSED`/`FAILED`)
Device Farm infrastructure problem, not our test. Re-run. If it
repeats, check the job's system artifacts for `DeviceFarm.log` —
common culprit is a mismatched signing identity (see below).

### Signing complaints (`CodeSign failed`, `No profiles matching`)
- Provisioning profile bundle ID must match `co.crackn.kompressor.smoketesthost`
  and `co.crackn.kompressor.smoketests`. A wildcard dev profile
  (`co.crackn.kompressor.*`) is the easiest match.
- Team ID in `APPLE_TEAM_ID` secret must match the profile's team.
- Certificate and profile must belong to the same Apple Developer team.
- `.p12` must include **both** the cert and its private key.

### Device Farm cert/profile rotation (annually)
1. Renew Apple Development certificate on developer.apple.com.
2. Export new `.p12` from Keychain, base64-encode, update
   `IOS_CERT_P12` and `IOS_CERT_PASSWORD` secrets.
3. Regenerate the provisioning profile referencing the new cert.
4. Download, base64, update `IOS_PROVISIONING_PROFILE`.
5. No AWS-side change needed — re-signing happens per run.

## Local validation

From the repo root on a Mac with Xcode 16:

```bash
./gradlew :kompressor:linkReleaseFrameworkIosArm64
cd iosDeviceSmokeTests && xcodegen generate
xcodebuild build-for-testing \
  -project KompressorDeviceSmokeTests.xcodeproj \
  -scheme SmokeTests \
  -destination 'generic/platform=iOS' \
  -derivedDataPath build \
  CODE_SIGNING_ALLOWED=NO
```

To dry-run the Device Farm upload without scheduling a paid run, use
`aws devicefarm create-upload` manually — the upload itself is free,
only `schedule-run` spends device-minutes.

## Related

- Ticket: [CRA-7](https://linear.app/crackn/issue/CRA-7)
- PR: [#77](https://github.com/cracknco/kompressor/pull/77)
- Workflow: `.github/workflows/ios-device-smoke.yml`
- Infra: `infra/aws-oidc-github.yml`
- Budget guard: `scripts/ci/check-devicefarm-budget.sh`
- Pool rules: `.aws/devicefarm-pool-rules.json`
