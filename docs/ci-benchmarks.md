# CI Benchmarks & Pipeline-Optimisation Decisions

This document captures the audit and decisions behind Kompressor's CI shape —
specifically the **compile-once-reuse** pass tracked as
[CRA-85](https://linear.app/crackn/issue/CRA-85). It exists so future
maintainers (and future-me) don't re-litigate the same trade-offs every time
the audit re-surfaces.

Cross-referenced from [`CLAUDE.md`](../CLAUDE.md) and
[`docs/adr/002-decline-level-3-supply-chain.md`](adr/002-decline-level-3-supply-chain.md).

## Pre-merge admin checklist (one-time setup)

The CI dedup in `release.yml` (see § 1 below) is safe in two layers:
in-CI enforcement via `verify-ios-sim-passed`, plus branch protection.
Both should be configured for defence-in-depth; in-CI alone is enough
to prevent a publish without iOS sim coverage, but branch protection
prevents the precondition job from ever needing to fire.

When operating this repo, an admin should verify that on `main`:

- [ ] Branch protection is enabled with **Require pull request before merging**.
- [ ] **Require status checks to pass before merging** is enabled, with
      `iOS simulator tests` (the `pr.yml` job name) listed as required.
- [ ] **Require branches to be up to date before merging** is enabled, so
      a PR can't be merged with a stale base SHA whose iOS sim run
      doesn't apply to the actual merge commit.
- [ ] **Do not allow bypassing the above settings** — even for admins —
      unless there's an explicit operational reason (in which case
      `verify-ios-sim-passed` is the seatbelt that catches the bypass
      at release time and refuses to publish).

The repo doesn't track these settings in source — they're configured
under *Settings → Branches → Branch protection rules* on the GitHub UI.
Restate the checklist in your release runbook so it doesn't drift.

## Origin audit (2026-04-17)

The original audit (against `main` commit `43e2876`, before ADR-002 landed
in PR #104) flagged five candidate sources of CI waste across `pr.yml`,
`release.yml`, and the device-test workflows. Items 1–3 and 5 were real;
item 4 was paper-only and turned out never to have existed in this repo
(see § N/A note below). The original estimate of "~45 min/week of
redundant compilation" was therefore an upper bound that overstated the
real waste:

1. **iOS-sim test duplicated** — `pr.yml:ios-simulator-tests` and
   `release.yml:test-native` both ran `iosSimulatorArm64Test` on the same
   merge commit. Cost ~6–10 min per release for zero additional signal.
2. **`commonMain` + `androidMain` compiled twice per PR** — `test` and the
   then-existing `android-device-tests-ftl` job each invoked Gradle from
   scratch with no shared `build/`.
3. **`merged-coverage` recompiled to report** — re-invoked
   `koverXmlReport -PkoverMergedGate=true` instead of merging the host
   `.xml` and device `.ec` reports directly via the JaCoCo CLI.
4. **(speculative)** `fastlane/Fastfile` *would* force
   `--no-configuration-cache` on publish — listed in the audit as a
   hypothetical concern, but no `fastlane/` directory has ever existed in
   this repo's git history. The item was an audit error, not a fixed
   regression. Carried forward in this list for traceability with the
   original CRA-85 ticket; see § N/A row in the table below.
5. **No cross-job `actions/cache`** — `~/.gradle/caches/modules-*`,
   `.kotlin/`, `~/.konan/` were not persisted across jobs.

## What's still in scope vs. what changed underneath

By the time CRA-85 was implemented (2026-04-27), the surrounding CI
architecture had moved on. The relevance of each audit item now:

| # | Audit item                                | Status       | Reason |
|---|-------------------------------------------|--------------|--------|
| 1 | iOS-sim test duplicated in `release.yml`  | **Fixed**    | `test-native` job replaced by `verify-ios-sim-passed` precondition check (this PR). See § 1 below. |
| 2 | Android device-test job recompiles        | **Obsolete** | Device-test PR job retired in [ADR-002](adr/002-decline-level-3-supply-chain.md); `leak-tests.yml` is now `workflow_dispatch`-only. |
| 3 | `merged-coverage` recompiles to report    | **Obsolete** | `merged-coverage` job retired in ADR-002. Only the host `koverVerify` (≥85%) gate remains. |
| 4 | Fastlane forces `--no-configuration-cache`| **N/A**      | No `fastlane/` directory exists. The flag survives only on the `release.yml:publish` step (see § Configuration-cache below). |
| 5 | No cross-job `actions/cache`              | **Reviewed — no change** | See § Gradle cache analysis below. |

## Change log (CRA-85)

### 1. Replaced `release.yml:test-native` with an in-CI precondition check

Previous shape: `release.yml` ran iOS sim tests on `macos-latest` before the
`release` and `publish` jobs (~6–10 min per release).

New shape: `release.yml` starts with a cheap `verify-ios-sim-passed` job
on `ubuntu-latest` that queries the GitHub check-runs API for the SHA
that triggered the workflow (`github.sha` — the merge commit pushed to
`main`) and asserts the `iOS simulator tests` check from `pr.yml`
already concluded as `success` on that exact commit. If the check is
missing, failed, cancelled, or timed out, the precondition job exits 1
with a `::error::` annotation and the `release` + `publish` jobs never
start.

```yaml
gh api "repos/${GH_REPO}/commits/${SHA}/check-runs" --paginate \
  --jq '[.check_runs[] | select(.name == "iOS simulator tests"
                              and .status == "completed")] |
        sort_by(.completed_at) | last | .conclusion // "missing"'
```

The `--paginate` + `select(.status == "completed")` + `sort_by(.completed_at) | last`
combination handles the realistic edge cases: a re-run of the iOS sim
test job leaves multiple check-runs on the same SHA; we want the latest
completed one, not whichever happens to come first in the API response.
The fallback `// "missing"` (jq alternative operator) yields a string
the bash equality check handles, rather than a null that would silently
pass through `[ "" != "success" ]`.

This converts the dedup's safety from an admin-side setting (branch
protection, invisible from the repo) into an in-CI invariant enforced
by the release pipeline itself. Branch protection remains the
*intended* canonical gate (see § Pre-merge admin checklist below) — the
precondition check is the seatbelt for the case where it's bypassed
(admin direct-push, force-push, or someone disabling the required-check
rule).

Expected impact: **~6–10 min saved per release** (≈ 6–10 fewer
`macos-latest` runner-minutes per release, billed at the macOS multiplier
on private repos; OSS public repos pay nothing). One fewer `macos-latest`
job invocation per release.

Risk surface: a direct push to `main` that bypasses branch protection
(admin override, force-push) would, on its own, skip the iOS sim gate.
Two layers of mitigation now stand in for the removed test-native job:

1. **Branch protection on `main`** is the *intended* canonical gate (see
   § Pre-merge admin checklist below).
2. **In-CI precondition check** — `release.yml:verify-ios-sim-passed`
   queries the GitHub API for the iOS sim test conclusion on the merge
   SHA before letting `release` start. This is enforced *inside* the
   release pipeline, so it survives even if branch protection is ever
   relaxed by accident. Implemented in CRA-85.

### 2. Gradle cache analysis — no code change

The audit's "no `actions/cache` inter-jobs" framing conflated two separate
problems:

- **Cross-RUN reuse** (a PR re-running the same job, or `main` re-running
  `test`): `gradle/actions/setup-gradle@v6.1.0` already handles this. Each
  job has a stable cache key (job name + OS + lockfile hash) and the
  GitHub Actions cache survives across runs of the same key. This is
  optimal for the common case.

- **Cross-JOB-in-same-run reuse** (one job's `build/` reused by another in
  the same workflow run): would require `needs:` chains plus
  `upload-artifact`/`download-artifact`. That's exactly the pattern
  ADR-002 retired for the device-tests + merged-coverage chain — for an
  indie OSS library the maintenance overhead of the artifact-passing
  rigging exceeded the saving.

Stacking a manual `actions/cache` step for `~/.gradle/caches/modules-*`
on top of `setup-gradle`'s own cache management would produce
non-deterministic cache contents (the two layers race each other on save)
and is a well-known footgun.

The Konan toolchain (`~/.konan`, ~500 MB Kotlin/Native compiler) is
**not** managed by `setup-gradle`, so it's cached separately on every job
that touches a native target — currently only
`pr.yml:ios-simulator-tests`. This is already wired correctly.

Expected cache-hit rate on a warm cache (steady-state main):

| Job                            | Setup-gradle cache | Konan cache |
|--------------------------------|--------------------|-------------|
| `ktlint` / `detekt` / `dokka-build` | hit (~80–90%) | n/a (no native) |
| `test`                         | hit (~80–90%)      | n/a |
| `ios-simulator-tests`          | hit (~80–90%)      | hit (after first run on key) |

Cold cache (cache eviction or `libs.versions.toml` bump invalidating the
key): every job pays full download cost — typically 60–90 s for the
dependency cache, ~3 min for Konan first install. We accept this; it's
infrequent.

### 3. Configuration-cache on publish

`release.yml:publish` retains `--no-configuration-cache` on the
`publishToMavenCentral` task. CRA-85 re-validated against
`com.vanniktech.maven.publish` 0.36.0 (see [`kompressor/build.gradle.kts`](../kompressor/build.gradle.kts)
plugin alias) and the underlying issue persists: the plugin's signing
configuration uses APIs that aren't compatible with Gradle's
configuration-cache serialisation. Removing the flag would fail the
publish task at runtime, gating Maven Central releases.

The flag is **scoped to that single Gradle invocation** —
`gradle.properties` keeps `org.gradle.configuration-cache=true` for every
other task in the build. Net effect on release pipeline: ~2–3 s lost on
the publish task, which is dwarfed by the upload itself.

Revisit when:

- Vanniktech plugin documents config-cache support in its release notes,
  **or**
- A Gradle `--configuration-cache --warning-mode=all` run on the publish
  task succeeds without serialisation errors against the version pinned
  in `libs.versions.toml`.

### 4. Stretch — remote build cache POC

**Decision: no-go for v1.0.**

A remote build cache (S3 / Develocity / Gradle Cloud) is the only path
to genuine cross-job-in-same-run task-output reuse. Considered against
the indie-OSS-lib operational target set in
[ADR-002](adr/002-decline-level-3-supply-chain.md):

- **Develocity / Gradle Cloud**: free tier covers OSS public repos but
  introduces an external dependency on Gradle Inc. infra, a TOS to
  monitor, and a dashboard surface that adds zero value to consumers
  picking the library on Maven Central.
- **Self-hosted (S3 + `org.gradle.build-cache.directory`)**: requires
  AWS account, S3 bucket lifecycle config, IAM rotation — same
  bus-factor / maintenance-surface trap that ADR-002 declined to take
  on.

Estimated saving: ~30–40% off cold-start jobs. Real saving on Kompressor's
PR pipeline (~5 min) at current shape: ~1.5–2 min. Below the threshold
that justifies the operational cost.

Revisit if the maintainer headcount grows past one (per ADR-002
reversal criterion #2) or if pipeline duration becomes a measured
contributor adoption blocker.

## Estimated impact summary

| Pipeline       | Before CRA-85 | After CRA-85 | Saving |
|----------------|---------------|--------------|--------|
| PR (full)      | ~10–15 min    | unchanged    | 0 (PR pipeline already optimised; ADR-002 retired the heavy device-test path) |
| Release        | ~14–20 min    | ~7–10 min    | **~6–10 min** (test-native removed; `verify-ios-sim-passed` adds back ~10 s on `ubuntu-latest`) |

Cache hit-rate is not exposed as a structured metric — the per-job
markdown summary that `gradle/actions/setup-gradle` writes to the
GitHub Actions Job Summary is the only signal. Any quantification
beyond "warm vs cold" would be theatre.

## How to re-measure

If you want concrete numbers before changing the pipeline shape:

1. Pick a representative recent PR that triggered all of `pr.yml`'s jobs.
2. Note each job's duration from the GitHub Actions run summary.
3. For release: for a baseline, look at the most recent successful
   release **before** CRA-85 (it had a `test-native` job that ran iOS
   sim tests on `macos-latest`); for the current shape, look at any
   release **after** CRA-85 (`verify-ios-sim-passed` ~10 s on
   `ubuntu-latest`, then `release` + `publish`).
4. Cross-check against the table in § Estimated impact summary.

Avoid micro-benchmarking individual cache hits — the variance from
GitHub-hosted runner load alone exceeds any single-job optimisation
signal.
