#!/usr/bin/env bash
# Generates an SPDX 2.3 JSON document from the dependency-license-report output.
set -euo pipefail

REPORT="${1:-build/reports/dependency-license/licenses.json}"
OUTPUT="${2:-build/reports/dependency-license/kompressor-spdx.json}"

if [ ! -f "$REPORT" ]; then
  echo "::error::License report not found at $REPORT — run ./gradlew generateLicenseReport first."
  exit 1
fi

TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
# Prefer VERSION from the caller (CI passes the authoritative semantic-release version);
# fall back to grep for local runs where the env var isn't set.
VERSION="${VERSION:-$(grep '^version' kompressor/build.gradle.kts | head -1 | sed 's/.*"\(.*\)".*/\1/')}"

jq --arg ts "$TIMESTAMP" --arg ver "${VERSION:-0.0.0}" '{
  spdxVersion: "SPDX-2.3",
  dataLicense: "CC0-1.0",
  SPDXID: "SPDXRef-DOCUMENT",
  name: "kompressor-license-report",
  documentNamespace: ("https://github.com/cracknco/kompressor/spdx/" + $ver + "/" + $ts),
  creationInfo: {
    created: $ts,
    creators: ["Tool: gradle-license-report", "Organization: crackn.co"]
  },
  packages: [.dependencies[] | {
    name: .moduleName,
    SPDXID: ("SPDXRef-Package-" + (.moduleName | gsub("[^a-zA-Z0-9.-]"; "-"))),
    versionInfo: .moduleVersion,
    downloadLocation: ((.moduleUrls // [])[0] // "NOASSERTION"),
    filesAnalyzed: false,
    licenseConcluded: ((.moduleLicenses // [])[0].moduleLicense // "NOASSERTION"),
    licenseDeclared: ((.moduleLicenses // [])[0].moduleLicense // "NOASSERTION"),
    copyrightText: "NOASSERTION"
  }]
}' "$REPORT" > "$OUTPUT"

echo "SPDX report written to $OUTPUT ($(jq '.packages | length' "$OUTPUT") packages)"
