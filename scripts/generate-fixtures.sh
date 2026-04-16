#!/usr/bin/env bash
# generate-fixtures.sh — Regenerate the M1 edge-case audio/image fixtures.
#
# Usage:
#   ./scripts/generate-fixtures.sh              # Regenerate all three fixtures
#   ./scripts/generate-fixtures.sh --verify     # Compare committed files' SHA-256 against the
#                                               # expected values listed below
#
# Fixtures produced:
#   - vbr_v0.mp3           : 1 s 440 Hz sine, LAME -V 0 VBR (Xing VBR header)
#   - with_cover_art.flac  : 1 s 440 Hz sine, FLAC with embedded PICTURE block
#   - cmyk.jpg             : 32x32 solid-colour JPEG in CMYK colorspace (Adobe APP14 + Nf=4)
#
# Outputs are copied into:
#   - kompressor/src/androidDeviceTest/resources/   (Android device tests — classpath)
#   - fixtures/edge-cases/audio|image/              (centralised fixture bank — manifest.json)
#
# iOS note: CMYK JPEG bytes are inlined in `kompressor/src/iosTest/.../CmykJpegFixture.kt`
# (parallel to `MinimalPngFixtures`), not read from disk — the Kotlin/Native test binary's
# NSBundle layout is not guaranteed to expose `iosTest/resources/` predictably across KGP
# versions. If you regenerate `cmyk.jpg`, refresh the `BYTES` literal in that file too. The
# checksum anchor below will fail until the inlined bytes match.
#
# Reproducibility anchor — the committed fixtures have these SHA-256 checksums. Regeneration
# is deterministic (sine input + `-strip` metadata) when tool versions match; different LAME /
# FLAC / ImageMagick versions may produce different bytes (encoder version strings are embedded).
# Refresh manifest.json + this script when upgrading fixtures.
#
#   vbr_v0.mp3           fcec20379448c9b659111642cae0a26eeba77894075a9f0481498d460143a788
#   with_cover_art.flac  fe029839cedfffc0e0f94bf202cacdc80f4dffb9a34828f192dde3f1e767c4b5
#   cmyk.jpg             c0df869fc3db6a2045b661fdf6f3877583466b1add913672294079d115fb3b68

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Checksum-anchor table — parallel arrays rather than an associative array so this script
# stays compatible with the bash 3.2 shipped on macOS.
FIXTURE_NAMES=(
    "vbr_v0.mp3"
    "with_cover_art.flac"
    "cmyk.jpg"
)
FIXTURE_SHAS=(
    "fcec20379448c9b659111642cae0a26eeba77894075a9f0481498d460143a788"
    "fe029839cedfffc0e0f94bf202cacdc80f4dffb9a34828f192dde3f1e767c4b5"
    "c0df869fc3db6a2045b661fdf6f3877583466b1add913672294079d115fb3b68"
)
FIXTURE_CATEGORIES=(
    "audio"
    "audio"
    "image"
)

ANDROID_RES="$PROJECT_ROOT/kompressor/src/androidDeviceTest/resources"
BANK_DIR="$PROJECT_ROOT/fixtures/edge-cases"

sha256_of() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | cut -d' ' -f1
    else
        sha256sum "$1" | cut -d' ' -f1
    fi
}

check_deps() {
    local missing=()
    command -v ffmpeg   >/dev/null 2>&1 || missing+=(ffmpeg)
    command -v lame     >/dev/null 2>&1 || missing+=(lame)
    command -v flac     >/dev/null 2>&1 || missing+=(flac)
    command -v magick   >/dev/null 2>&1 || missing+=(imagemagick)
    if [ "${#missing[@]}" -gt 0 ]; then
        echo "Missing required tools: ${missing[*]}" >&2
        echo "Install on macOS with: brew install ${missing[*]}" >&2
        exit 1
    fi
}

verify() {
    local all_ok=1
    for i in "${!FIXTURE_NAMES[@]}"; do
        local name="${FIXTURE_NAMES[$i]}"
        local expected="${FIXTURE_SHAS[$i]}"
        local category="${FIXTURE_CATEGORIES[$i]}"
        local committed="$BANK_DIR/$category/$name"
        if [ ! -f "$committed" ]; then
            echo "MISSING  $name (expected at $committed)"
            all_ok=0
            continue
        fi
        local actual
        actual="$(sha256_of "$committed")"
        if [ "$actual" = "$expected" ]; then
            echo "OK       $name"
        else
            echo "MISMATCH $name"
            echo "         expected $expected"
            echo "         got      $actual"
            all_ok=0
        fi
    done
    [ "$all_ok" -eq 1 ]
}

generate() {
    check_deps

    local tmp
    tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' EXIT

    echo "=> Generating 1 s 440 Hz sine source WAV (deterministic input)"
    ffmpeg -hide_banner -loglevel error -y \
        -f lavfi -i "sine=frequency=440:sample_rate=44100:duration=1" \
        -ac 1 -sample_fmt s16 \
        "$tmp/source.wav"

    echo "=> Encoding VBR MP3 (LAME -V 0)"
    lame --silent -V 0 "$tmp/source.wav" "$tmp/vbr_v0.mp3"

    echo "=> Building deterministic cover art (stripped PNG)"
    magick -size 32x32 xc:red -strip -define png:exclude-chunks=all "$tmp/cover.png"

    echo "=> Encoding FLAC with embedded PICTURE block"
    flac --silent --force --picture="$tmp/cover.png" \
        -o "$tmp/with_cover_art.flac" "$tmp/source.wav"

    echo "=> Encoding CMYK JPEG (Adobe APP14 marker, Nf=4)"
    magick -size 32x32 xc:"cmyk(30%,60%,90%,10%)" \
        -colorspace CMYK \
        -sampling-factor 4:2:0 \
        -quality 85 \
        -strip \
        "$tmp/cmyk.jpg"

    echo ""
    echo "=> Installing fixtures"
    mkdir -p "$ANDROID_RES" "$BANK_DIR/audio" "$BANK_DIR/image"

    # Android device tests consume all three via classpath resource loading.
    install -m 0644 "$tmp/vbr_v0.mp3"          "$ANDROID_RES/vbr_v0.mp3"
    install -m 0644 "$tmp/with_cover_art.flac" "$ANDROID_RES/with_cover_art.flac"
    install -m 0644 "$tmp/cmyk.jpg"            "$ANDROID_RES/cmyk.jpg"

    # Centralised fixture bank (discoverable via fixtures/manifest.json).
    install -m 0644 "$tmp/vbr_v0.mp3"          "$BANK_DIR/audio/vbr_v0.mp3"
    install -m 0644 "$tmp/with_cover_art.flac" "$BANK_DIR/audio/with_cover_art.flac"
    install -m 0644 "$tmp/cmyk.jpg"            "$BANK_DIR/image/cmyk.jpg"

    echo ""
    echo "=> Resulting SHA-256 (compare against the anchor table at the top of this script):"
    sha256_of "$BANK_DIR/audio/vbr_v0.mp3"          | xargs -I{} echo "  vbr_v0.mp3           {}"
    sha256_of "$BANK_DIR/audio/with_cover_art.flac" | xargs -I{} echo "  with_cover_art.flac  {}"
    sha256_of "$BANK_DIR/image/cmyk.jpg"            | xargs -I{} echo "  cmyk.jpg             {}"
}

main() {
    case "${1:-}" in
        --verify) verify ;;
        "")       generate ;;
        *)
            echo "Usage: $0 [--verify]" >&2
            exit 2
            ;;
    esac
}

main "$@"
