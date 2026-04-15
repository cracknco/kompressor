# Contributing to Kompressor

Thanks for your interest in Kompressor! This guide will get you from zero to a passing build and a mergeable PR.

---

## Prerequisites

| Tool | Minimum version | Notes |
|------|----------------|-------|
| **JDK** | 21 (Temurin recommended) | `java -version` to check |
| **Android SDK** | API 24+ (compile SDK 36) | Install via Android Studio or `sdkmanager` |
| **Xcode** | 16+ (iOS deployment target 16.0) | Required only for iOS development |
| **Kotlin** | 2.3.20 | Managed by Gradle — no manual install |
| **Git LFS** | Latest | Required for test fixtures (`git lfs install`) |

> **Tip:** on macOS you can install JDK 21 with `brew install --cask temurin@21`.

---

## Local Setup

```bash
# 1. Clone
git clone https://github.com/cracknco/kompressor.git
cd kompressor

# 2. Fetch test fixtures (Git LFS + R2)
./scripts/fetch-fixtures.sh

# 3. Build everything
./gradlew build

# 4. Run all host tests
./gradlew allTests
```

If the build succeeds, you're ready to contribute.

---

## Essential Commands

```bash
# Full build
./gradlew build

# Tests
./gradlew allTests                   # All platforms
./gradlew testAndroidHostTest        # Android unit tests (host JVM — no device)
./gradlew iosSimulatorArm64Test      # iOS tests (requires macOS + Xcode)

# Single test class
./gradlew testAndroidHostTest --tests "co.crackn.kompressor.SomeTest"

# Lint & format
./gradlew ktlintCheck                # Check formatting
./gradlew ktlintFormat               # Auto-fix formatting
./gradlew detekt                     # Static analysis

# Coverage
./gradlew koverXmlReport             # Generate coverage report
./gradlew koverVerify                # Quality gate — minimum 85%
```

---

## Test Layers

Kompressor has four distinct test layers. Knowing which to use avoids running
tests in the wrong environment:

| Source set | Runs on | What it tests | When to use |
|------------|---------|---------------|-------------|
| `commonTest` | All targets | Pure logic: config validation, error types, `Kompressor.probe` contract | Shared behavior with no platform I/O |
| `androidHostTest` | Host JVM | Android-flavored unit tests without a device: error mapping, processor planning, encoder settings | Logic that imports Android types but doesn't need `MediaCodec` |
| `androidDeviceTest` | Emulator / device | End-to-end golden + property tests for the actual transcoder | Media3 `Transformer`, codec behavior, AAC passthrough |
| `iosTest` | iOS simulator | End-to-end tests for `IosVideoCompressor` / `IosAudioCompressor` | `AVAssetExportSession` / `AVAssetWriter` paths |

> **CI note:** `androidDeviceTest` does NOT run in PR CI (requires KVM emulator).
> `iosTest` runs on `macos-latest` in both PR and release workflows.

---

## Commit Conventions

We use [Conventional Commits](https://www.conventionalcommits.org/) — this
drives [semantic-release](https://github.com/semantic-release/semantic-release)
for automated versioning.

### Format

```
<type>(<scope>): <short description> [<ticket>]
```

### Types

| Type | When | Bumps |
|------|------|-------|
| `feat` | New feature or capability | MINOR |
| `fix` | Bug fix | PATCH |
| `docs` | Documentation only | — |
| `test` | Adding or updating tests | — |
| `refactor` | Code change that neither fixes a bug nor adds a feature | — |
| `chore` | Build, CI, deps, tooling | — |
| `perf` | Performance improvement | PATCH |

### Scope

Use the module or area: `image`, `video`, `audio`, `test`, `ci`, `sample`.

### Examples

```
feat(audio): add AAC passthrough fast path [CRA-42]
fix(video): handle rotated tkhd metadata correctly [CRA-9]
docs: add semver versioning policy [CRA-25]
test(video): add tkhd rotation metadata assertion [CRA-9]
chore(deps): update gradle/actions action to v4.4.4
```

---

## Pull Request Process

### 1. Branch naming

```
feat/CRA-<number>--<short-kebab-description>
fix/CRA-<number>--<short-kebab-description>
chore/CRA-<number>--<short-kebab-description>
```

### 2. Before opening a PR

- [ ] `./gradlew build` passes with zero warnings (`allWarningsAsErrors` is on)
- [ ] `./gradlew ktlintCheck` passes
- [ ] `./gradlew detekt` passes
- [ ] `./gradlew koverVerify` passes (≥ 85% coverage)
- [ ] New public API has KDoc (Detekt enforces `UndocumentedPublicClass/Function/Property`)
- [ ] Tests cover every new behavior

### 3. PR template

```markdown
## Summary
<2-3 sentences>

## Changes
<bullet list by file>

## Testing
<what was added, how to verify>

## Linear
Closes CRA-<number>
```

### 4. Review & merge

- At least **1 approving review** required.
- All CI checks must be green.
- Squash-merge is preferred for single-concern PRs; merge commit for multi-commit PRs where history matters.

---

## Developer Certificate of Origin (DCO)

We use the [DCO](https://developercertificate.org/) instead of a CLA. By
contributing, you certify that you wrote the code (or have the right to submit
it) under the project's Apache 2.0 license.

### How to sign off

Add a `Signed-off-by` line to every commit:

```bash
git commit -s -m "feat(audio): add resampling support [CRA-99]"
```

This appends:

```
Signed-off-by: Your Name <your.email@example.com>
```

> **Tip:** configure `git commit -s` as your default by adding an alias:
> ```bash
> git config --global alias.cs "commit -s"
> ```

If you forgot to sign off, amend the last commit:

```bash
git commit --amend -s --no-edit
```

---

## Adding Test Fixtures

Test fixtures are managed via a centralized fixture bank with Git LFS (≤ 2 MB)
and Cloudflare R2 (> 2 MB) storage.

See **[docs/contributing-fixtures.md](docs/contributing-fixtures.md)** for the
full workflow: choosing a source, computing checksums, editing the manifest, and
submitting the PR.

---

## Code Style

- **Ktlint** enforces formatting: max line length 120, 4-space indent.
- **Detekt** enforces static analysis: max method length 30, cyclomatic complexity 15.
- All public API must have KDoc.
- All warnings are errors — the build fails on any warning.
- Run `./gradlew ktlintFormat` before committing to auto-fix most formatting issues.

---

## Getting Help

- Open an issue on [GitHub](https://github.com/cracknco/kompressor/issues) for bugs or feature requests.
- Check existing [docs/](docs/) for architecture decisions and fixture management.

---

## License

By contributing, you agree that your contributions will be licensed under the
[Apache License 2.0](LICENSE).
