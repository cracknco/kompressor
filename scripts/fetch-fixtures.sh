#!/usr/bin/env bash
# fetch-fixtures.sh — Download and verify Kompressor test fixtures.
#
# Usage:
#   ./scripts/fetch-fixtures.sh          # Fetch all missing fixtures
#   ./scripts/fetch-fixtures.sh --verify # Verify checksums only (no downloads)
#   ./scripts/fetch-fixtures.sh --clean  # Remove all downloaded fixtures
#
# See docs/adr/001-fixture-storage.md for the storage strategy.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
MANIFEST="$PROJECT_ROOT/fixtures/manifest.json"
FIXTURES_DIR="$PROJECT_ROOT/fixtures"

# R2 bucket base URL (public read). Set KOMPRESSOR_R2_BUCKET to override.
R2_BUCKET="${KOMPRESSOR_R2_BUCKET:-https://fixtures.kompressor.crackn.co}"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_ok()   { printf "${GREEN}[OK]${NC}    %s\n" "$1"; }
log_warn() { printf "${YELLOW}[WARN]${NC}  %s\n" "$1"; }
log_err()  { printf "${RED}[FAIL]${NC}  %s\n" "$1"; }
log_info() { printf "        %s\n" "$1"; }

usage() {
    echo "Usage: $0 [--verify | --clean | --help]"
    echo ""
    echo "Options:"
    echo "  --verify   Verify SHA-256 checksums of existing fixtures (no downloads)"
    echo "  --clean    Remove all downloaded R2 fixtures (keeps LFS-tracked files)"
    echo "  --help     Show this help message"
    exit 0
}

check_deps() {
    local missing=()
    command -v jq    >/dev/null 2>&1 || missing+=(jq)
    command -v curl  >/dev/null 2>&1 || missing+=(curl)
    command -v shasum >/dev/null 2>&1 || {
        command -v sha256sum >/dev/null 2>&1 || missing+=(shasum)
    }

    if [ ${#missing[@]} -gt 0 ]; then
        log_err "Missing required tools: ${missing[*]}"
        echo "Install them with: brew install ${missing[*]}"
        exit 1
    fi
}

sha256_check() {
    local file="$1"
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$file" | cut -d' ' -f1
    else
        sha256sum "$file" | cut -d' ' -f1
    fi
}

parse_manifest() {
    if [ ! -f "$MANIFEST" ]; then
        log_err "Manifest not found: $MANIFEST"
        exit 1
    fi

    jq -c '.fixtures[]' "$MANIFEST"
}

fetch_fixture() {
    local name="$1" source_url="$2" sha256="$3" size="$4" storage="$5" category="$6"
    local target_dir="$FIXTURES_DIR/$category"
    local target_file="$target_dir/$name"

    mkdir -p "$target_dir"

    # Fixtures with empty sha256 are planned but not yet available — skip gracefully.
    if [ -z "$sha256" ] || [ "$sha256" = "" ]; then
        if [ -f "$target_file" ]; then
            log_ok "$name (cached, no checksum to verify)"
        else
            log_warn "$name (planned — no sha256 yet, skipping)"
        fi
        return 0
    fi

    if [ -f "$target_file" ]; then
        local actual_sha256
        actual_sha256="$(sha256_check "$target_file")"
        if [ "$actual_sha256" = "$sha256" ]; then
            log_ok "$name (cached, checksum verified)"
            return 0
        else
            log_warn "$name (checksum mismatch, re-downloading)"
            rm -f "$target_file"
        fi
    fi

    if [ "$storage" = "lfs" ]; then
        if command -v git-lfs >/dev/null 2>&1; then
            log_info "LFS fixture $name — run 'git lfs pull' to fetch"
        fi
        return 0
    fi

    if [ "$storage" = "r2" ]; then
        local url="$R2_BUCKET/$category/$name"
        log_info "Downloading $name from R2..."

        if curl -fsSL --retry 3 --retry-delay 2 -o "$target_file" "$url"; then
            local actual_sha256
            actual_sha256="$(sha256_check "$target_file")"
            if [ "$actual_sha256" != "$sha256" ]; then
                log_err "$name (SHA-256 mismatch after download)"
                log_info "Expected: $sha256"
                log_info "Got:      $actual_sha256"
                rm -f "$target_file"
                return 1
            fi
            log_ok "$name (downloaded, checksum verified)"
        else
            log_err "$name (download failed from $url)"

            if [ -n "$source_url" ] && [ "$source_url" != "null" ]; then
                log_info "Trying source URL: $source_url"
                if curl -fsSL --retry 3 --retry-delay 2 -o "$target_file" "$source_url"; then
                    local actual_sha256
                    actual_sha256="$(sha256_check "$target_file")"
                    if [ "$actual_sha256" != "$sha256" ]; then
                        log_err "$name (SHA-256 mismatch from source URL)"
                        log_info "Expected: $sha256"
                        log_info "Got:      $actual_sha256"
                        rm -f "$target_file"
                        return 1
                    fi
                    log_ok "$name (downloaded from source, checksum verified)"
                else
                    log_err "$name (all download attempts failed)"
                    return 1
                fi
            else
                return 1
            fi
        fi
    fi
}

verify_fixtures() {
    local total=0 passed=0 failed=0 skipped=0

    while IFS= read -r fixture; do
        local name sha256 category
        name="$(echo "$fixture" | jq -r '.name')"
        sha256="$(echo "$fixture" | jq -r '.sha256')"
        category="$(echo "$fixture" | jq -r '.category')"
        local target_file="$FIXTURES_DIR/$category/$name"

        total=$((total + 1))

        if [ ! -f "$target_file" ]; then
            log_warn "$name (not present)"
            skipped=$((skipped + 1))
            continue
        fi

        if [ -z "$sha256" ] || [ "$sha256" = "" ]; then
            log_warn "$name (no checksum in manifest)"
            skipped=$((skipped + 1))
            continue
        fi

        local actual_sha256
        actual_sha256="$(sha256_check "$target_file")"
        if [ "$actual_sha256" = "$sha256" ]; then
            log_ok "$name"
            passed=$((passed + 1))
        else
            log_err "$name (expected $sha256, got $actual_sha256)"
            failed=$((failed + 1))
        fi
    done < <(parse_manifest)

    echo ""
    echo "Verification: $passed passed, $failed failed, $skipped skipped (of $total total)"
    [ "$failed" -eq 0 ]
}

clean_fixtures() {
    local count=0
    while IFS= read -r fixture; do
        local name storage category
        name="$(echo "$fixture" | jq -r '.name')"
        storage="$(echo "$fixture" | jq -r '.storage')"
        category="$(echo "$fixture" | jq -r '.category')"
        local target_file="$FIXTURES_DIR/$category/$name"

        if [ "$storage" = "r2" ] && [ -f "$target_file" ]; then
            rm -f "$target_file"
            log_info "Removed $name"
            count=$((count + 1))
        fi
    done < <(parse_manifest)

    echo "Cleaned $count R2 fixtures (LFS files untouched)"
}

fetch_all() {
    local total=0 fetched=0 failed=0

    # Pull LFS files first
    if command -v git-lfs >/dev/null 2>&1; then
        log_info "Pulling Git LFS files..."
        (cd "$PROJECT_ROOT" && git lfs pull) || log_warn "git lfs pull failed — LFS fixtures may be missing"
    fi

    while IFS= read -r fixture; do
        local name source_url sha256 size storage category
        name="$(echo "$fixture" | jq -r '.name')"
        source_url="$(echo "$fixture" | jq -r '.source_url')"
        sha256="$(echo "$fixture" | jq -r '.sha256')"
        size="$(echo "$fixture" | jq -r '.size')"
        storage="$(echo "$fixture" | jq -r '.storage')"
        category="$(echo "$fixture" | jq -r '.category')"

        total=$((total + 1))

        if fetch_fixture "$name" "$source_url" "$sha256" "$size" "$storage" "$category"; then
            fetched=$((fetched + 1))
        else
            failed=$((failed + 1))
        fi
    done < <(parse_manifest)

    echo ""
    echo "Fixtures: $fetched ready, $failed failed (of $total total)"
    [ "$failed" -eq 0 ]
}

main() {
    check_deps

    case "${1:-}" in
        --verify) verify_fixtures ;;
        --clean)  clean_fixtures ;;
        --help)   usage ;;
        "")       fetch_all ;;
        *)        log_err "Unknown option: $1"; usage ;;
    esac
}

main "$@"
