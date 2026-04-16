#!/usr/bin/env bash
# check-unreleased-entry.test.sh
#
# Self-contained tests for check-unreleased-entry.sh. Builds a throwaway
# git repo in a tempdir, writes synthetic CHANGELOG.md revisions, and
# runs the script against the resulting refs.
#
# Includes a faithful repro of PR #83's pre-fix layout (new bullet on
# line 12, `## [Unreleased]` header on line 8) — the scenario that
# motivated CRA-79. That case must pass with the new implementation and
# would have failed under the old `git diff -U3 | sed | grep` pipeline.
#
# Run from repo root:
#   bash scripts/ci/check-unreleased-entry.test.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECKER="${SCRIPT_DIR}/check-unreleased-entry.sh"

if [ ! -x "${CHECKER}" ]; then
  echo "FAIL: ${CHECKER} missing or not executable" >&2
  exit 1
fi

pass_count=0
fail_count=0

# Run a single test case in an isolated git repo.
#   $1 test name
#   $2 expected exit code (0 = gate passes, 1 = gate fails)
#   $3 base CHANGELOG contents
#   $4 head CHANGELOG contents
run_case() {
  local name="$1" expected="$2" base_content="$3" head_content="$4"
  local tmp out actual
  tmp=$(mktemp -d)
  out=$(mktemp)

  (
    cd "${tmp}"
    git init -q -b main
    # Base revision. Write CHANGELOG.md only when content is non-empty,
    # so the "CHANGELOG introduced in this PR" scenario (empty base) is
    # faithful.
    if [ -n "${base_content}" ]; then
      printf '%s' "${base_content}" > CHANGELOG.md
      git add CHANGELOG.md
    fi
    git -c user.email=test@example.invalid -c user.name=test commit -q --allow-empty -m base
    # Head revision. Always `--allow-empty` so identical content still
    # produces a distinct commit (required for HEAD^ to point at base).
    printf '%s' "${head_content}" > CHANGELOG.md
    git add CHANGELOG.md
    git -c user.email=test@example.invalid -c user.name=test commit -q --allow-empty -m head
  ) >/dev/null 2>&1

  local base_sha head_sha
  base_sha=$(git -C "${tmp}" rev-parse HEAD^)
  head_sha=$(git -C "${tmp}" rev-parse HEAD)

  set +e
  (cd "${tmp}" && "${CHECKER}" "${base_sha}" "${head_sha}") >"${out}" 2>&1
  actual=$?
  set -e

  if [ "${actual}" -eq "${expected}" ]; then
    echo "PASS  ${name}"
    rm -rf "${tmp}" "${out}"
    return 0
  else
    echo "FAIL  ${name} (expected exit ${expected}, got ${actual})"
    sed 's/^/      /' "${out}"
    rm -rf "${tmp}" "${out}"
    return 1
  fi
}

# --- Case 1: PR #83 pre-fix layout ---------------------------------------
# Header on line 8, ### Added + blank on lines 10–11, new bullet on line 12.
# Under the old `-U3` pipeline this reported 0 added lines. The new script
# parses the file directly and must pass.
base_pr83=$(cat <<'EOF'
# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

* existing entry one
* existing entry two

## 1.0.0 (2026-04-15)

### Added

* initial release
EOF
)
head_pr83=$(cat <<'EOF'
# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

* **test:** inter-process concurrent compress() regression guard [CRA-14]
* existing entry one
* existing entry two

## 1.0.0 (2026-04-15)

### Added

* initial release
EOF
)
if run_case "pr-83-repro (bullet 4 lines below header via ### Added subsection)" 0 "${base_pr83}" "${head_pr83}"; then
  pass_count=$((pass_count + 1))
else
  fail_count=$((fail_count + 1))
fi

# --- Case 2: bullet added far below header -------------------------------
# Stretches the distance to simulate a long Unreleased section. The old
# -U1000 band-aid would cover this, but the parser-based approach has no
# such ceiling.
long_filler=$(for i in $(seq 1 1500); do printf '* filler entry %d\n' "$i"; done)
base_long=$(printf '## [Unreleased]\n\n### Added\n\n%s\n\n## 1.0.0\n' "${long_filler}")
head_long=$(printf '## [Unreleased]\n\n### Added\n\n%s\n* new bottom entry added far below header\n\n## 1.0.0\n' "${long_filler}")
if run_case "bullet added 1500+ lines below header (beyond -U1000 ceiling)" 0 "${base_long}" "${head_long}"; then
  pass_count=$((pass_count + 1))
else
  fail_count=$((fail_count + 1))
fi

# --- Case 3: no changes under Unreleased ---------------------------------
# Identical Unreleased sections must fail the gate.
base_same=$(cat <<'EOF'
## [Unreleased]

* existing entry

## 1.0.0
EOF
)
head_same="${base_same}"
if run_case "no changes under Unreleased (expected fail)" 1 "${base_same}" "${head_same}"; then
  pass_count=$((pass_count + 1))
else
  fail_count=$((fail_count + 1))
fi

# --- Case 4: only blank line added ---------------------------------------
# Adding whitespace-only lines must not satisfy the gate.
base_blank=$(cat <<'EOF'
## [Unreleased]

* existing entry

## 1.0.0
EOF
)
head_blank=$(cat <<'EOF'
## [Unreleased]



* existing entry

## 1.0.0
EOF
)
if run_case "only blank line added (expected fail)" 1 "${base_blank}" "${head_blank}"; then
  pass_count=$((pass_count + 1))
else
  fail_count=$((fail_count + 1))
fi

# --- Case 5: entry added to a different release (not Unreleased) ---------
# Backport-style edits to a past release must not pass the gate.
base_other=$(cat <<'EOF'
## [Unreleased]

* existing entry

## 1.0.0

* old entry
EOF
)
head_other=$(cat <<'EOF'
## [Unreleased]

* existing entry

## 1.0.0

* old entry
* backport entry — should NOT count
EOF
)
if run_case "entry added under a past release only (expected fail)" 1 "${base_other}" "${head_other}"; then
  pass_count=$((pass_count + 1))
else
  fail_count=$((fail_count + 1))
fi

# --- Case 6: header missing on head --------------------------------------
head_no_header=$(cat <<'EOF'
## 1.0.0

* some entry
EOF
)
if run_case "missing '## [Unreleased]' header on head (expected fail)" 1 "${base_same}" "${head_no_header}"; then
  pass_count=$((pass_count + 1))
else
  fail_count=$((fail_count + 1))
fi

# --- Case 7: new subsection + bullet (### Fixed) -------------------------
# Adds a subsection header AND a bullet under Unreleased.
base_newsub=$(cat <<'EOF'
## [Unreleased]

### Added

* existing entry

## 1.0.0
EOF
)
head_newsub=$(cat <<'EOF'
## [Unreleased]

### Added

* existing entry

### Fixed

* **video:** fix rotation metadata [CRA-9]

## 1.0.0
EOF
)
if run_case "new ### Fixed subsection with bullet" 0 "${base_newsub}" "${head_newsub}"; then
  pass_count=$((pass_count + 1))
else
  fail_count=$((fail_count + 1))
fi

# --- Case 8: CHANGELOG introduced for the first time ---------------------
# Base revision has no CHANGELOG.md at all; head introduces it with an
# Unreleased entry. Gate must pass.
base_none=""
head_new=$(cat <<'EOF'
## [Unreleased]

* first entry ever

## 1.0.0

* seed
EOF
)
if run_case "CHANGELOG introduced in this PR (no base revision)" 0 "${base_none}" "${head_new}"; then
  pass_count=$((pass_count + 1))
else
  fail_count=$((fail_count + 1))
fi

echo ""
echo "-----"
echo "Results: ${pass_count} passed, ${fail_count} failed"

if [ "${fail_count}" -gt 0 ]; then
  exit 1
fi
