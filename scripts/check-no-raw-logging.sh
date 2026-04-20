#!/usr/bin/env bash
# Copyright 2025 crackn.co
# SPDX-License-Identifier: Apache-2.0
#
# CI gate enforcing CRA-47: the Kompressor library must never emit diagnostics
# through the raw platform logging APIs. Every library-side log has to go
# through a KompressorLogger instance (see docs/logging.md and
# docs/adr/003-logger-contract.md). A raw call bypasses the consumer's opt-in
# NoOpLogger / custom sink contract and pollutes Logcat / OS logs without the
# consumer's consent.
#
# The only files permitted to touch the raw APIs are the PlatformLogger
# actuals — those are the adapter layer that consumer-owned loggers are
# routed *through*, not around.
#
# This script is fast (a grep over ~30 files), so it runs on every PR.
# Detekt's `ForbiddenImport` rule catches the same class of issue at build
# time; this script is the belt-and-suspenders gate that also flags NSLog
# (not importable in Kotlin so Detekt can't see it) and bare `println`.
#
# Uses only POSIX-available tools (GNU grep, awk, sed) so no ripgrep install
# step is required on CI runners. Bash 3.2-compatible — no `mapfile` /
# `readarray` — so the script runs on the default macOS shell as well.

set -euo pipefail

# Search root — every kompressor/src/*Main source set is library code and
# subject to the policy. Tests, sample, and tooling scripts are not.
SEARCH_ROOTS=(
  "kompressor/src/commonMain"
  "kompressor/src/androidMain"
  "kompressor/src/iosMain"
)

# Allowlist: the two PlatformLogger actuals are the single source of truth
# for adapting raw APIs onto the KompressorLogger contract.
ALLOWED_PATTERN='kompressor/src/(androidMain/kotlin/co/crackn/kompressor/logging/PlatformLogger\.android\.kt|iosMain/kotlin/co/crackn/kompressor/logging/PlatformLogger\.ios\.kt)'

FINDINGS_FILE="$(mktemp)"
trap 'rm -f "$FINDINGS_FILE"' EXIT

# report_hits PATTERN DESCRIPTION
# grep -rEn: recursive, extended regex, with line numbers. --include='*.kt'
# limits to Kotlin sources. Non-zero exit on "no match" is normal — `|| true`
# keeps `set -e` from aborting the whole run before we've checked every pattern.
report_hits() {
  local pattern="$1"
  local description="$2"
  grep -rEn --include='*.kt' -- "$pattern" "${SEARCH_ROOTS[@]}" 2>/dev/null \
    | grep -Ev "^$ALLOWED_PATTERN:" \
    | awk -v desc="$description" '{ print "[" desc "] " $0 }' \
    >> "$FINDINGS_FILE" || true
}

# ── Forbidden patterns ──────────────────────────────────────────────────────
# `println(` — bare Kotlin stdlib print. Always forbidden in library code.
# GNU/BSD grep ERE: `[[:<:]]` / `\<` / `\b` word boundaries differ across
# implementations, so we use explicit character class anchors: either start of
# line or a non-identifier character before the token.
report_hits '(^|[^a-zA-Z0-9_])println[[:space:]]*\(' "println"

# `print(` — bare Kotlin stdlib print (no newline). The previous rule already
# covers `println`; to avoid double-flagging it here, require the token
# starts with `print` followed immediately by `(` or whitespace-then-`(`, and
# ensure the preceding character is not part of the `println` token by
# anchoring on non-letter before `p`.
report_hits '(^|[^a-zA-Z0-9_])print[[:space:]]*\(' "print"

# Fully-qualified `android.util.Log.v/d/i/w/e(` — direct Logcat write.
report_hits 'android\.util\.Log\.[vdiwef][[:space:]]*\(' "android.util.Log"

# Short-form `Log.v/d/i/w/e(` via import. Matching on the import line catches
# any file that pulled in `android.util.Log` regardless of how they use it.
report_hits '^import[[:space:]]+android\.util\.Log([[:space:]]|$)' "android.util.Log import"

# `NSLog(` — iOS unified log call. Kotlin/Native can't import a macro, so
# Detekt's ForbiddenImport doesn't cover it. This is the only rule that
# actually catches raw NSLog use.
report_hits '(^|[^a-zA-Z0-9_])NSLog[[:space:]]*\(' "NSLog"

# ── Report ──────────────────────────────────────────────────────────────────
if [[ ! -s "$FINDINGS_FILE" ]]; then
  echo "✓ No raw logging calls in kompressor/src (PlatformLogger actuals allowlisted)"
  exit 0
fi

echo "✗ Raw logging calls found in library code (CRA-47 contract violation):"
echo
sed 's/^/  /' "$FINDINGS_FILE"
echo
echo "Every library-side diagnostic must route through KompressorLogger."
echo "See docs/logging.md and docs/adr/003-logger-contract.md."
echo "If you're implementing a new PlatformLogger target, extend ALLOWED_PATTERN"
echo "in scripts/check-no-raw-logging.sh."
exit 1
