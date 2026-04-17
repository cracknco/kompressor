# Supply chain & SBOM

Kompressor publishes a CycloneDX 1.5 Software Bill of Materials (SBOM) with
every release so downstream consumers can answer "am I exposed to CVE-X?" in
under five minutes.

This document covers:

- Where SBOMs live, how they're generated, and how to verify them.
- How to ingest them into **Dependency-Track** (optional, for consumers who
  want continuous vulnerability tracking).

> **Regulatory context:** US Executive Order 14028 ("Improving the Nation's
> Cybersecurity") and the EU Cyber Resilience Act both require software
> producers to provide a machine-readable SBOM for every release. Kompressor
> satisfies both with CycloneDX 1.5 JSON.

---

## Where to find the SBOM

Every GitHub Release ships a CycloneDX 1.5 JSON SBOM as a release asset:

```
https://github.com/cracknco/kompressor/releases/download/vX.Y.Z/kompressor-X.Y.Z.sbom.json
```

The file:

- Is scoped to the **published library's runtime classpath** (Android
  `androidRuntimeClasspath`) — the same dependency set Maven Central publishes
  via the `.aar` / POM. iOS dependencies are Apple system frameworks
  (`AVFoundation`, `CoreImage`, …) shipped with the OS and have no Maven
  coordinate, so they are intentionally out of scope for the SBOM.
- Is versioned and pinned to the exact release; there is no "latest" SBOM.
- Lives alongside the SPDX license report (`kompressor-spdx.json`), generated
  by the same release workflow. SPDX focuses on licenses; CycloneDX focuses on
  components and vulnerabilities — the two complement each other.

---

## Generating an SBOM locally

The Gradle task that produces the SBOM:

```bash
./gradlew :kompressor:cyclonedxBom --no-configuration-cache
```

This writes **`kompressor/build/reports/bom.json`** (CycloneDX 1.5). The root
task `./gradlew :cyclonedxBom` produces a repo-wide aggregate at
`build/reports/bom.json` that includes the `:sample` app and is useful for
contributors auditing the full build graph — but it is **not** the asset we
publish. Release consumers always want the per-module (`:kompressor`) SBOM.

---

## Verifying with syft

The published SBOM is reproducible from the Maven Central artifact using
[`syft`](https://github.com/anchore/syft):

```bash
# Install syft
brew install syft   # or: curl -sSfL https://raw.githubusercontent.com/anchore/syft/main/install.sh | sh

# Generate an SBOM from the Maven-Central artifact directly
syft packages maven-central:co.crackn.kompressor:kompressor:X.Y.Z \
    -o cyclonedx-json=kompressor-X.Y.Z.syft.sbom.json

# Diff against the published asset
gh release download vX.Y.Z --repo cracknco/kompressor \
    --pattern kompressor-X.Y.Z.sbom.json

diff \
    <(jq -S 'del(.metadata.timestamp, .metadata.tools)' kompressor-X.Y.Z.sbom.json) \
    <(jq -S 'del(.metadata.timestamp, .metadata.tools)' kompressor-X.Y.Z.syft.sbom.json)
```

The two SBOMs should match modulo:

- `metadata.timestamp` — different per invocation on both sides.
- `metadata.tools` — syft vs. the CycloneDX Gradle plugin.

The published SBOM **omits** the random `serialNumber` field (CycloneDX spec
marks it optional) precisely to keep this byte-level comparison tractable.

---

## Dependency-Track integration (optional)

[Dependency-Track](https://dependencytrack.org/) is the reference platform for
continuous component analysis. If your organisation already runs one, ingesting
Kompressor's SBOM on every release takes ~3 minutes to wire up.

### 1. Prerequisites

- A running Dependency-Track server (Docker image: `dependencytrack/apiserver`).
- A **project** in Dependency-Track representing your app (the *consumer* of
  Kompressor, not Kompressor itself).
- An API key with the `BOM_UPLOAD` permission, stored as the secret
  `DTRACK_API_KEY` in your CI.

### 2. Upload on release

Add a job to your CI that fires when a new Kompressor version is pinned
(e.g. after bumping `co.crackn.kompressor:kompressor` in your `libs.versions.toml`):

```yaml
- name: Download Kompressor SBOM
  env:
    KOMPRESSOR_VERSION: "1.2.3"
  run: |
    curl -fsSL \
      "https://github.com/cracknco/kompressor/releases/download/v${KOMPRESSOR_VERSION}/kompressor-${KOMPRESSOR_VERSION}.sbom.json" \
      -o kompressor.sbom.json

- name: Merge into your project's SBOM
  # Your app's SBOM is the root; Kompressor's is a child component. Use `cyclonedx-cli merge`
  # (https://github.com/CycloneDX/cyclonedx-cli) or ingest both separately into Dependency-Track.
  run: |
    cyclonedx-cli merge \
      --input-files build/reports/bom.json kompressor.sbom.json \
      --output-file merged.sbom.json

- name: Upload to Dependency-Track
  env:
    DTRACK_API_KEY: ${{ secrets.DTRACK_API_KEY }}
  run: |
    curl -fsSL -X POST \
      -H "X-Api-Key: ${DTRACK_API_KEY}" \
      -H "Content-Type: multipart/form-data" \
      -F "project=${DTRACK_PROJECT_UUID}" \
      -F "bom=@merged.sbom.json" \
      https://your-dtrack.example.com/api/v1/bom
```

Dependency-Track will then:

- Run the SBOM against its vulnerability feeds (NVD, GitHub Advisory, OSS
  Index) on every upload.
- Fire webhooks (Slack, email, Jira) when a new CVE affects any component.
- Keep a historical trail of every SBOM you uploaded, so you can answer "which
  of my builds included `log4j-core:2.14.1`?" on demand.

### 3. Picking the SBOM granularity

Dependency-Track can ingest Kompressor's SBOM two ways:

| Strategy | When to pick it |
|---|---|
| **As a child component of your app SBOM** (merge with `cyclonedx-cli merge`) | You want a single pane of glass — all transitive deps show up under one project. |
| **As its own project in Dependency-Track** | You're a platform team that ships multiple apps using Kompressor and wants to track the library independently of any consumer. |

Either strategy is valid; pick the one that matches how your SOC already slices
ownership.

---

## SBOM field reference

The published CycloneDX 1.5 JSON contains, per spec:

| Field | Description |
|---|---|
| `specVersion` | Pinned to `"1.5"` explicitly (plugin default is 1.6). |
| `metadata.component` | The Kompressor library itself, with group/name/version. |
| `metadata.component.externalReferences` | VCS URL (`github.com/cracknco/kompressor`). |
| `metadata.tools` | CycloneDX Gradle plugin version used to generate the BOM. |
| `components[]` | Every runtime dependency, with `purl`, version, licenses, and SHA-1/256/384/512/MD5 hashes. |
| `dependencies[]` | The resolved dependency graph edges (who depends on whom). |

The random `serialNumber` field is **intentionally omitted** — see the syft
verification section above for why.

---

## Questions?

- Found a discrepancy between the published SBOM and syft's output? Open an
  issue — this is a supply-chain correctness bug.
- Need a CycloneDX 1.6 variant, or the XML format, or a license-text-included
  SBOM? Open an issue; the current defaults are deliberate (size / diff-ability)
  but easy to flip per-release if there's demand.
