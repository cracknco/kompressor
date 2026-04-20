#!/usr/bin/env bash
# Regenerate docs/error-handling.md from the three `…CompressionError.kt` sealed
# hierarchies (CRA-44 follow-up, PR #132).
#
# How it works: the JVM test `ErrorTaxonomyDocUpToDateTest` normally asserts that
# the committed file matches `ErrorTaxonomyRenderer.render(repoRoot)`. When invoked
# with `-PregenerateErrorTaxonomyDoc=true`, the same test rewrites the file in place
# instead. Renderer + assertions + file-write code live in one androidHostTest — the
# verifier and the regenerator read the same Kotlin function.
#
# Usage: ./scripts/regenerate-error-taxonomy.sh
#
# After running, `git diff docs/error-handling.md` shows what changed. CI's
# `ErrorTaxonomyDocUpToDateTest` (part of the required `Tests & coverage (host)` job)
# catches drift if you forget to commit the result.
set -euo pipefail

cd "$(dirname "$0")/.."

./gradlew \
  :kompressor:testAndroidHostTest \
  --tests 'co.crackn.kompressor.errortaxonomy.ErrorTaxonomyDocUpToDateTest' \
  -PregenerateErrorTaxonomyDoc=true \
  --rerun-tasks

echo
echo "Regenerated docs/error-handling.md. Review and commit with:"
echo "  git diff docs/error-handling.md"
echo "  git add docs/error-handling.md && git commit -m 'docs: regenerate error taxonomy [CRA-44]'"
