#!/usr/bin/env bash
# Regenerate the auto-generated section of docs/format-support.md from
# `FormatSupportMatrix` (CRA-43).
#
# How it works: the JVM test `FormatSupportDocUpToDateTest` normally asserts that the
# committed file matches `renderFormatSupportMatrixTables()`. When invoked with
# `-PregenerateFormatSupportDoc=true`, the same test rewrites the file in place instead.
# That keeps renderer + assertions + file-write code in one place — there's exactly one
# Kotlin function that produces the canonical output, and it's the same one the verifier
# reads.
#
# Usage: ./scripts/regenerate-format-support-doc.sh
#
# After running, `git diff docs/format-support.md` shows what changed. CI's
# `FormatSupportDocUpToDateTest` + `format-support-check.yml` catches drift if you forget
# to commit the result.
set -euo pipefail

cd "$(dirname "$0")/.."

./gradlew \
  :kompressor:testAndroidHostTest \
  --tests 'co.crackn.kompressor.matrix.FormatSupportDocUpToDateTest' \
  -PregenerateFormatSupportDoc=true \
  --rerun-tasks

echo
echo "Regenerated docs/format-support.md. Review and commit with:"
echo "  git diff docs/format-support.md"
echo "  git add docs/format-support.md && git commit -m 'docs: regenerate format-support matrix [CRA-43]'"
