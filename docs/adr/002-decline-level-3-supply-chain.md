# ADR-002: Decline Level-3 Supply-Chain Scope for v1.0

## Status

Accepted (2026-04-18)

## Context

Kompressor is a Kotlin Multiplatform wrapper library for Android/iOS media
compression, published to Maven Central and **maintained by a single developer**.
The pre-v1.0 scoping pass (M1–M9) progressively folded in requirements that match
the standard of a **supply-chain-regulated project** (Kubernetes / cosign /
OpenTelemetry style): SLSA L2 provenance, CycloneDX SBOM published as a release
asset, OpenSSF Scorecard & CII Best Practices badges, bit-identical reproducible
builds, Gradle `verification-metadata.xml` hash-pinning, multi-tool secret
scanning, hardware GPG key custody + rotation runbooks, etc.

This ADR formalises the decision to **decline that level** and reset the project
to the standard of a **pragmatic indie OSS library** — FileKit-grade.

## Benchmark — what successful indie KMP libs actually do

Cross-check of five widely-adopted, small-team / solo-maintained KMP libraries:

| Practice | FileKit | Kermit | Turbine | Coil-KMP | Koin | **Kompressor (target)** |
|---|---|---|---|---|---|---|
| GPG-signed Maven Central artifacts | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ (keep) |
| Semantic versioning + CHANGELOG | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ (keep) |
| Dokka API docs | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ (keep) |
| Binary compat validator (`apiCheck`) | ⚪ | ✅ | ✅ | ✅ | ✅ | ✅ (keep) |
| LICENSE + SECURITY.md + CoC | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ (keep) |
| Gitleaks or similar single secret scanner | ✅ | ⚪ | ⚪ | ⚪ | ⚪ | ✅ (keep) |
| SPDX header per `.kt` file | ⚪ | ⚪ | ⚪ | ⚪ | ⚪ | ✅ (keep — cheap convention) |
| **CycloneDX / SPDX SBOM as release asset** | ❌ | ❌ | ❌ | ❌ | ❌ | **❌ (decline)** |
| **SLSA L2+ provenance / sigstore** | ❌ | ❌ | ❌ | ❌ | ❌ | **❌ (decline)** |
| **Cosign keyless signing** | ❌ | ❌ | ❌ | ❌ | ❌ | **❌ (decline)** |
| **OpenSSF Scorecard / CII badge** | ❌ | ❌ | ❌ | ❌ | ❌ | **❌ (decline)** |
| **Transitive license allowlist (enforced)** | ❌ | ❌ | ❌ | ❌ | ❌ | **❌ (decline)** |
| **Gradle `verification-metadata.xml` strict** | ❌ | ❌ | ❌ | ❌ | ❌ | **❌ (decline)** |
| **Bit-identical reproducible builds** | ❌ | ❌ | ❌ | ❌ | ❌ | **❌ (decline)** |
| **Multi-tool secret scanning (trufflehog + GG + Gitleaks)** | ❌ | ❌ | ❌ | ❌ | ❌ | **❌ (decline)** |
| **GPG custody + hardware-key rotation plan** | ❌ | ❌ | ❌ | ❌ | ❌ | **❌ (decline)** |

The nine "decline" items are what characterise a **regulated supply-chain
project**. None of the listed indie KMP libs apply them. Our users (mobile app
developers picking a KMP compression lib on Maven Central) do not ask for them
either — they ask for "does it compile, does it work, is the API stable."

## Decision

**Kompressor v1.0 ships at the indie OSS-library level, not the regulated
supply-chain level.** Every item in the bottom nine rows of the table above is
**out of scope** and will remain so unless a concrete enterprise-adoption signal
justifies revisiting.

### Out of scope (declined)

- SLSA provenance attestations (`actions/attest-build-provenance`,
  `slsa-framework/slsa-github-generator`, in-toto).
- Cosign keyless signing or Sigstore OIDC token exchange.
- SBOM publication (CycloneDX or SPDX) as a release asset.
- OpenSSF Scorecard workflow + badge.
- CII / OpenSSF Best Practices badge.
- Gradle `verification-metadata.xml` hash-pinning strict mode.
- Build reproducibility gates (`SOURCE_DATE_EPOCH`, double-build byte diff).
- Multi-tool secret scanning (Trufflehog / GitGuardian in complement of
  Gitleaks).
- Hardware GPG key custody (Yubikey / subkeys) + rotation runbooks.
- Transitive dependency license allowlist enforced on every PR.

### In scope (kept)

- `signAllPublications()` — standard GPG Maven Central signing (required by
  Sonatype).
- `cycjimmy/semantic-release-action` + `CHANGELOG.md` auto-maintenance.
- Gitleaks as sole secret-scanning tool.
- SPDX `Apache-2.0` header per `.kt` file (20-line bash gate — convention, not
  compliance).
- Kotlin Binary Compatibility Validator (`apiCheck` + committed
  `kompressor/api/kompressor.api` baseline).
- Dokka API docs.
- `LICENSE`, `SECURITY.md`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`.
- Renovate dependency management.

## Consequences

### Positive

- **Lower maintenance surface**: retired ~500 lines of YAML / Gradle / bash
  (CRA-28 cleanup PR). Fewer CI jobs to babysit, fewer external secrets to
  rotate, fewer stale docs to keep in sync with plugin version drift.
- **Faster PR feedback**: the `dependency-licenses` job is removed from
  `license-check.yml`; release pipeline drops two jobs (SPDX + SBOM) so each
  published tag is one less chance of a supply-chain-job flake.
- **Scope legibility**: the v1.0 promise is "stable public API, hardware
  compression, KMP". Enterprise-conformance signalling is not part of that
  promise — which is honest with consumers.

### Negative / accepted tradeoffs

- **No SBOM means a Fortune-500 consumer cannot auto-ingest Kompressor into
  their Dependency-Track without generating the SBOM themselves** (they can
  still run `syft packages maven-central:co.crackn.kompressor:kompressor:X.Y.Z`
  locally — it just isn't pre-shipped).
- **No SLSA provenance means air-gapped / attestation-gated build systems
  cannot verify the chain from source to Maven artifact via GitHub's OIDC.**
- **No allowlist gate means a new transitive dependency with a restrictive
  licence (GPL, LGPL-without-classpath-exception, AGPL) could slip in
  unnoticed.** Mitigation: Renovate surfaces dependency adds in PRs; maintainer
  review is the gate.

### Reversal criteria (when to revisit)

Revisit this decision only if **all** of the following become true:

1. A concrete enterprise consumer (name + contract) explicitly asks for SBOM or
   SLSA attestation as a pre-adoption gate.
2. The maintainer headcount grows to ≥ 2 (i.e. bus-factor-2 is resolved organically,
   not forced).
3. There is bandwidth to own the supply-chain surface **continuously** — not as a
   one-off ticket, but as permanent maintenance including plugin-version drift and
   CI-flake triage.

Absent any of those three, leave this decision standing.

## References

- **CRA-28** — cleanup PR retiring the merged level-3 artifacts (SBOM
  pipeline + transitive license allowlist).
- **CRA-30** — this ADR (the decline documentation).
- **CRA-37, CRA-38, CRA-52, CRA-57, CRA-65, CRA-69** — Linear tickets covering
  out-of-scope level-3 items (Scorecard, CII, hash-pinning, extended secret
  scanning, bit-identical builds, GPG custody). All kept as placeholders rather
  than cancelled, so the CRA numbers remain available for future recycling.
- [FileKit](https://github.com/vinceglb/FileKit), [Kermit](https://github.com/touchlab/Kermit),
  [Turbine](https://github.com/cashapp/turbine), [Coil-KMP](https://github.com/coil-kt/coil),
  [Koin](https://github.com/InsertKoinIO/koin) — indie KMP benchmark references.
