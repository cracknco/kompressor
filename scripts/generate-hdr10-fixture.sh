#!/usr/bin/env bash
# generate-hdr10-fixture.sh — One-shot regenerator for the HDR10 Main10 P010 LFS fixture.
#
# The fixture (`fixtures/hdr/video/hdr10_p010.mp4`, 2s 1080p, four canonical BT.2020 primary
# patches) can't be produced off-device — it requires an Android HEVC Main10 encoder writing
# 10-bit P010 samples. This script drives `Hdr10Mp4Generator` through an instrumented JUnit
# entry point (`GenerateHdr10Fixture`), pulls the result to the repo, and prints the manifest
# metadata (size + SHA-256) so you can paste it into `fixtures/manifest.json`.
#
# Usage:
#   ./scripts/generate-hdr10-fixture.sh            # generate + replace the LFS fixture
#   ./scripts/generate-hdr10-fixture.sh --dry-run  # run on device, print metadata, don't overwrite
#   ./scripts/generate-hdr10-fixture.sh --help
#
# Prerequisites: adb, a connected device/emulator that advertises HEVC Main10 HDR10 encode
# (Pixel 6 / Tensor G1+ is the reference; API 29+ required for `HEVCProfileMain10HDR10`).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
FIXTURE_DIR="$PROJECT_ROOT/fixtures/hdr/video"
FIXTURE_NAME="hdr10_p010.mp4"
FIXTURE_PATH="$FIXTURE_DIR/$FIXTURE_NAME"
TEST_PACKAGE="co.crackn.kompressor.test"
TEST_RUNNER="$TEST_PACKAGE/androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS="co.crackn.kompressor.GenerateHdr10Fixture"
TEST_METHOD="writeHdr10P010FixtureToExternalFilesDir"
DEVICE_FILES_DIR="/storage/emulated/0/Android/data/co.crackn.kompressor.test/files"
DEVICE_FIXTURE="$DEVICE_FILES_DIR/$FIXTURE_NAME"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_ok()   { printf "${GREEN}[OK]${NC}    %s\n" "$1"; }
log_warn() { printf "${YELLOW}[WARN]${NC}  %s\n" "$1"; }
log_err()  { printf "${RED}[FAIL]${NC}  %s\n" "$1"; }
log_info() { printf "        %s\n" "$1"; }

usage() {
    cat <<EOF
Usage: $0 [--dry-run | --help]

Options:
  --dry-run   Generate on device + print metadata; do NOT overwrite the repo fixture.
  --help      Show this help message.
EOF
    exit 0
}

DRY_RUN=0
while [ $# -gt 0 ]; do
    case "$1" in
        --help) usage ;;
        --dry-run) DRY_RUN=1 ;;
        *) log_err "Unknown flag: $1"; usage ;;
    esac
    shift
done

check_deps() {
    local missing=()
    command -v adb >/dev/null 2>&1 || missing+=(adb)
    command -v shasum >/dev/null 2>&1 || command -v sha256sum >/dev/null 2>&1 || missing+=(shasum)
    if [ ${#missing[@]} -gt 0 ]; then
        log_err "Missing required tools: ${missing[*]}"
        exit 1
    fi
}

sha256_hex() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | cut -d' ' -f1
    else
        sha256sum "$1" | cut -d' ' -f1
    fi
}

ensure_device_connected() {
    if ! adb get-state >/dev/null 2>&1; then
        log_err "No adb device detected. Connect a device / start an emulator and retry."
        exit 1
    fi
    log_ok "adb device: $(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
}

build_and_install() {
    log_info "Building and installing the device-test APKs…"
    (
        cd "$PROJECT_ROOT"
        ./gradlew :kompressor:installAndroidDeviceTest >/dev/null
    )
    log_ok "Test APK installed."
}

run_generator() {
    log_info "Running $TEST_CLASS#$TEST_METHOD on device…"
    local output
    output="$(adb shell am instrument -w -r \
        -e class "$TEST_CLASS#$TEST_METHOD" \
        "$TEST_RUNNER" 2>&1)"
    if ! echo "$output" | grep -q "OK ("; then
        log_err "Instrumentation did not report OK — generator failed or was skipped."
        printf '%s\n' "$output"
        exit 1
    fi
    log_ok "Generator reported OK."
}

pull_and_report() {
    local local_tmp
    local_tmp="$(mktemp -t hdr10_p010.XXXXXX.mp4)"
    adb pull "$DEVICE_FIXTURE" "$local_tmp" >/dev/null
    local sz sha
    sz=$(wc -c <"$local_tmp" | tr -d ' ')
    sha=$(sha256_hex "$local_tmp")
    log_ok "Pulled fixture: $local_tmp ($sz bytes)"
    printf '        sha256: %s\n' "$sha"
    printf '        size:   %s bytes (%.2f MB)\n' "$sz" "$(awk "BEGIN {printf \"%f\", $sz/1048576}")"

    if [ "$DRY_RUN" -eq 1 ]; then
        log_warn "--dry-run: leaving repo fixture untouched. Device output at $local_tmp"
        return
    fi

    mkdir -p "$FIXTURE_DIR"
    mv "$local_tmp" "$FIXTURE_PATH"
    log_ok "Wrote $FIXTURE_PATH"
    log_info "Next: update fixtures/manifest.json (sha256=$sha, size=$sz) and commit via Git LFS."
}

main() {
    check_deps
    ensure_device_connected
    build_and_install
    run_generator
    pull_and_report
}

main "$@"
