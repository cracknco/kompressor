# CI Benchmarks & Pipeline-Optimisation Decisions

This document captures the audit and decisions behind Kompressor's CI shape —
specifically the **compile-once-reuse** pass tracked as
[CRA-85](https://linear.app/crackn/issue/CRA-85). It exists so future
maintainers (and future-me) don't re-litigate the same trade-offs every time
the audit re-surfaces.

Cross-referenced from [`CLAUDE.md`](../CLAUDE.md) and
[`docs/adr/002-decline-level-3-supply-chain.md`](adr/002-decline-level-3-supply-chain.md).

## Origin audit (2026-04-17)

The original audit (against `main` commit `43e2876`, before ADR-002 landed
in PR #104) flagged five sources of CI waste totalling ~45 min/week of
redundant compilation across `pr.yml`, `release.yml`, and the
device-test workflows:

1. **iOS-sim test duplicated** — `pr.yml:ios-simulator-tests` and
   `release.yml:test-native` both ran `iosSimulatorArm64Test` on the same
   merge commit. Cost ~6–10 min per release for zero additional signal.
2. **`commonMain` + `androidMain` compiled twice per PR** — `test` and the
   then-existing `android-device-tests-ftl` job each invoked Gradle from
   scratch with no shared `build/`.
3. **`merged-coverage` recompiled to report** — re-invoked
   `koverXmlReport -PkoverMergedGate=true` instead of merging the host
   `.xml` and device `.ec` reports directly via the JaCoCo CLI.
4. **`fastlane/Fastfile` forced `--no-configuration-cache` on publish** —
   blocked configuration-cache reuse on releases.
5. **No cross-job `actions/cache`** — `~/.gradle/caches/modules-*`,
   `.kotlin/`, `~/.konan/` were not persisted across jobs.

## What's still in scope vs. what changed underneath

By the time CRA-85 was implemented (2026-04-27), the surrounding CI
architecture had moved on. The relevance of each audit item now:

| # | Audit item                                | Status       | Reason |
|---|-------------------------------------------|--------------|--------|
| 1 | iOS-sim test duplicated in `release.yml`  | **Fixed**    | `test-native` job removed (this PR). |
| 2 | Android device-test job recompiles        | **Obsolete** | Device-test PR job retired in [ADR-002](adr/002-decline-level-3-supply-chain.md); `leak-tests.yml` is now `workflow_dispatch`-only. |
| 3 | `merged-coverage` recompiles to report    | **Obsolete** | `merged-coverage` job retired in ADR-002. Only the host `koverVerify` (≥85%) gate remains. |
| 4 | Fastlane forces `--no-configuration-cache`| **N/A**      | No `fastlane/` directory exists. The flag survives only on the `release.yml:publish` step (see § Configuration-cache below). |
| 5 | No cross-job `actions/cache`              | **Reviewed — no change** | See § Gradle cache analysis below. |

## Change log (CRA-85)

### 1. Removed `release.yml:test-native`

Previous shape: `release.yml` ran iOS sim tests on `macos-latest` before the
`release` and `publish` jobs.

New shape: `release.yml` starts directly from the `release` job. Branch
protection on `main` + required PR + the required
`pr.yml:ios-simulator-tests` status check together guarantee that the SHA
which lands on `main` (and triggers `release.yml`) has already passed iOS
sim tests at PR-merge time. Re-running them on the same SHA was pure
cycle burn.

Expected impact: **~6–10 min saved per release**, ~1 fewer `macos-latest`
runner-minute bill per release.

Risk surface: an admin direct-push to `main` (bypass branch protection)
would skip the iOS sim gate entirely. Mitigation: branch protection is
the canonical gate; if it's ever relaxed, restore this job rather than
treat `release.yml` as the safety net.

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
| Release        | ~14–20 min    | ~7–10 min    | **~6–10 min** (test-native removed) |

Cache hit-rate is not directly observable in `gradle/actions/setup-gradle`
without enabling Develocity or parsing the action's job summary; any
quantification beyond "warm vs cold" would be theatre.

## How to re-measure

If you want concrete numbers before changing the pipeline shape:

1. Pick a representative recent PR that triggered all of `pr.yml`'s jobs.
2. Note each job's duration from the GitHub Actions run summary.
3. For release: pick the most recent successful `release.yml` run and
   note `test-native` (now removed) + `release` + `publish` durations.
4. Cross-check against the table in § Estimated impact summary.

Avoid micro-benchmarking individual cache hits — the variance from
GitHub-hosted runner load alone exceeds any single-job optimisation
signal.
