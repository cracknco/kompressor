#!/usr/bin/env bash
# Copyright 2025 crackn.co
# SPDX-License-Identifier: Apache-2.0
#
# CRA-50 — iOS leak-detection gate.
#
# Runs `IosCompressionLeakTest` (50 compressions per modality) under Instruments'
# `Leaks` instrument via `xctrace record --template Leaks`. Fails (exit 1) when
# the Leaks instrument reports *any* leaked allocation; passes when the trace
# contains zero rows in the `leaks` table. That's the "leak détecté = CI rouge"
# gate called out in the DoD.
#
# Why this script and not a gradle task:
#   - `xctrace record --launch -- <binary>` needs a Mach-O executable as the
#     subject. Kotlin/Native's `iosSimulatorArm64Test` gradle task normally
#     drives the test binary through `xcrun simctl spawn` internally; we need
#     to invoke that path explicitly so xctrace can attach to the *right*
#     process (the test binary itself, not the gradle JVM wrapper).
#   - The assertion ("0 leaks") is an external post-condition, not something
#     Kotlin/Native can assert from inside the test binary — the heap analysis
#     has to run after the process exits.
#
# Requires: `xctrace` (from Xcode), `xcrun simctl`, a booted iOS simulator
# runtime. On the `macos-latest` GitHub runner all three are pre-installed.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

TRACE_DIR="kompressor/build/reports/leaks"
TRACE_PATH="$TRACE_DIR/IosCompressionLeakTest.trace"
LEAKS_EXPORT="$TRACE_DIR/leaks.xml"
mkdir -p "$TRACE_DIR"
rm -rf "$TRACE_PATH" "$LEAKS_EXPORT"

echo "==> Building iOS simulator test binary"
./gradlew :kompressor:linkDebugTestIosSimulatorArm64

# Exclude the DWARF copy inside `test.kexe.dSYM/` — `-name test.kexe` matches both
# basenames. The real Mach-O lives directly under `debugTest/`; filter with `-not -path`.
BINARY=$(find kompressor/build/bin/iosSimulatorArm64/debugTest \
  -name "test.kexe" -not -path "*.dSYM/*" | head -n 1 || true)
if [[ -z "$BINARY" ]]; then
  echo "::error title=iOS leak test::Could not locate test.kexe after linkDebugTestIosSimulatorArm64"
  exit 2
fi
echo "    Binary: $BINARY"

# Pick an already-booted simulator if one exists (faster on repeated local runs);
# otherwise boot the latest iPhone runtime and tear it down at the end.
BOOTED_DEVICE=$(xcrun simctl list devices booted -j 2>/dev/null \
  | python3 -c "import json,sys;d=json.load(sys.stdin)['devices'];\
print(next((x['udid'] for v in d.values() for x in v if x['state']=='Booted'),''))" \
  || true)

BOOTED_HERE=0
if [[ -z "$BOOTED_DEVICE" ]]; then
  DEVICE_UDID=$(xcrun simctl list devices available -j \
    | python3 -c "import json,sys;d=json.load(sys.stdin)['devices'];\
print(next((x['udid'] for k,v in sorted(d.items(),reverse=True) \
  if 'iOS' in k for x in v if 'iPhone' in x['name']),''))")
  if [[ -z "$DEVICE_UDID" ]]; then
    echo "::error title=iOS leak test::No available iPhone simulator runtime. Install one with \`xcodebuild -downloadPlatform iOS\`."
    exit 2
  fi
  echo "==> Booting simulator $DEVICE_UDID"
  xcrun simctl boot "$DEVICE_UDID"
  BOOTED_HERE=1
else
  DEVICE_UDID="$BOOTED_DEVICE"
  echo "==> Reusing booted simulator $DEVICE_UDID"
fi

cleanup() {
  if [[ "$BOOTED_HERE" -eq 1 ]]; then
    echo "==> Shutting down simulator $DEVICE_UDID"
    xcrun simctl shutdown "$DEVICE_UDID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

# Kotlin/Native's test runner accepts `--ktest_filter=<glob>` for filtering.
# We scope to the leak test class so the trace captures 50-iteration loops
# rather than the entire test suite (which would run for 10+ minutes and
# obscure the leak signal with unrelated fixture churn).
KTEST_FILTER="co.crackn.kompressor.IosCompressionLeakTest.*"

echo "==> Recording Leaks trace"
# `--launch -- xcrun simctl spawn …` runs the test binary inside the simulator
# runtime while xctrace attaches the Allocations + Leaks instruments. The
# trailing `--` keeps downstream args (filter, -standalone output flag) from
# being interpreted by xctrace itself.
xctrace record \
  --template 'Leaks' \
  --output "$TRACE_PATH" \
  --launch -- xcrun simctl spawn "$DEVICE_UDID" "$BINARY" --ktest_filter="$KTEST_FILTER"

echo "==> Exporting leaks table"
# The Leaks template produces a `leaks` schema on the main run; each row is one
# leaked allocation. Exporting to XML makes counting deterministic without a
# bespoke `.trace` parser.
xctrace export \
  --input "$TRACE_PATH" \
  --xpath '/trace-toc/run[@number="1"]/data/table[@schema="leaks"]' \
  > "$LEAKS_EXPORT"

# Each leaked allocation maps to exactly one <row>. Counting rows is robust
# across xctrace versions because the schema prefix is fixed.
LEAK_COUNT=$(grep -c '<row>' "$LEAKS_EXPORT" || true)
echo "==> Leak count: $LEAK_COUNT"

if [[ "$LEAK_COUNT" -gt 0 ]]; then
  echo "::error title=iOS leak detection::$LEAK_COUNT leak(s) detected by xctrace. See $LEAKS_EXPORT and $TRACE_PATH for details. Runbook: docs/maintainers.md#debugging-leaks."
  # Surface the first ~200 lines of the leak report in CI logs so a reviewer
  # has a starting point without having to download the full trace artifact.
  head -200 "$LEAKS_EXPORT" || true
  exit 1
fi

echo "OK: 0 leaks detected across $KTEST_FILTER"
