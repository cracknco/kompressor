#!/usr/bin/env bash
# Validates the dependency-license-report JSON against the project allowlist.
# Allowlist (SPDX): Apache-2.0, MIT, BSD-*, ISC, EPL-2.0, MPL-2.0
set -euo pipefail

REPORT="${1:-build/reports/dependency-license/licenses.json}"

if [ ! -f "$REPORT" ]; then
  echo "::error::License report not found at $REPORT — run ./gradlew generateLicenseReport first."
  exit 1
fi

is_allowed() {
  local license="$1"
  case "$license" in
    *Apache*2*)              return 0 ;;
    *MIT*)                   return 0 ;;
    *BSD*)                   return 0 ;;
    *ISC*)                   return 0 ;;
    *Eclipse*Public*|*EPL*)  return 0 ;;
    *Mozilla*Public*2*|*MPL*2*) return 0 ;;
    *)                       return 1 ;;
  esac
}

blocked=0
while IFS=$'\t' read -r module version license; do
  if [ -z "$license" ]; then
    echo "BLOCKED  $module:$version — no license declared"
    blocked=$((blocked + 1))
  elif ! is_allowed "$license"; then
    echo "BLOCKED  $module:$version — $license"
    blocked=$((blocked + 1))
  fi
done < <(jq -r '.dependencies[] | [.moduleName, .moduleVersion, (.moduleLicenses[]?.moduleLicense // "")] | @tsv' "$REPORT")

total=$(jq '.dependencies | length' "$REPORT")
echo ""
echo "Scanned $total dependencies: $blocked blocked."

if [ "$blocked" -gt 0 ]; then
  echo "::error::$blocked dependency license(s) are not on the allowlist."
  exit 1
fi

echo "All dependency licenses are on the allowlist."
