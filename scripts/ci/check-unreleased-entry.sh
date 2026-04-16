#!/usr/bin/env bash
# check-unreleased-entry.sh
#
# Verifies that a PR adds at least one new non-blank line under the
# `## [Unreleased]` section of CHANGELOG.md.
#
# Design
# ------
# The previous implementation relied on `git diff` output and a sed range
# anchored on `## [Unreleased]`. Any bullet further from the header than
# the unified-diff context window (default 3 lines) fell outside the
# rendered hunk, sed never opened its range, and the gate reported zero
# added lines — a false failure. Widening context (`-U1000`) is only a
# heuristic: a sufficiently large Unreleased section still slips out of
# context.
#
# This script sidesteps the diff renderer entirely: it extracts the
# Unreleased section from the base and head revisions of CHANGELOG.md
# directly (via `git show <ref>:<path>`), then diffs the two extracted
# sections. The result is independent of hunk geometry.
#
# Usage
# -----
#   scripts/ci/check-unreleased-entry.sh <base-ref> <head-ref> [changelog-path]
#
# Exit codes:
#   0 — at least one new non-blank line under `## [Unreleased]`
#   1 — no new entries, or CHANGELOG missing from the head revision

set -euo pipefail

base_ref="${1:?base ref required (e.g. origin/main or \$BASE_SHA)}"
head_ref="${2:?head ref required (e.g. HEAD or \$HEAD_SHA)}"
changelog_path="${3:-CHANGELOG.md}"

# Prints the `## [Unreleased]` section on stdin: every line from the
# header (exclusive) up to the next `## `-prefixed heading (exclusive).
# The terminator intentionally matches *any* H2, not just `## […]`, because
# semantic-release emits release headings without brackets (e.g.
# `## 1.0.0 (2026-04-15)`) alongside the Keep-a-Changelog `## [Unreleased]`
# header. If the header is missing, prints nothing.
extract_unreleased() {
  awk '
    /^## \[Unreleased\]/ { in_section = 1; next }
    /^## /               { in_section = 0 }
    in_section
  '
}

# `git show <ref>:<path>` fails if the file does not exist at that ref;
# treat that as "empty section" so a PR that introduces CHANGELOG.md for
# the first time is still evaluated on its head contents.
base_section=$(git show "${base_ref}:${changelog_path}" 2>/dev/null | extract_unreleased || true)
head_section=$(git show "${head_ref}:${changelog_path}" 2>/dev/null | extract_unreleased || true)

if [ -z "${head_section}" ]; then
  echo "::error::${changelog_path} at ${head_ref} has no '## [Unreleased]' section."
  echo "::error::Add an entry under '## [Unreleased]', or add the 'skip-changelog' label to bypass (docs-only, ci-only changes)."
  exit 1
fi

# Count lines present in the head section but absent from the base
# section, ignoring pure-whitespace additions so an empty line cannot
# satisfy the gate.
#
# `diff` exits 1 when the inputs differ — the normal case here — which
# would otherwise trip `set -e`/`pipefail`. `{ diff …; true; }` absorbs
# the non-zero status while still surfacing real errors (exit 2+).
added=$({ diff <(printf '%s\n' "${base_section}") <(printf '%s\n' "${head_section}") || [ $? -eq 1 ]; } \
  | awk '/^> [^[:space:]]/ { n++ } END { print n + 0 }')

if [ "${added}" -gt 0 ]; then
  echo "Changelog gate passed — ${added} line(s) added under ## [Unreleased]."
  exit 0
fi

echo "::error::No new entries detected under '## [Unreleased]' in ${changelog_path}."
echo "::error::Add an entry, or add the 'skip-changelog' label to bypass (docs-only, ci-only changes)."
exit 1
