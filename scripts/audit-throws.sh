#!/usr/bin/env bash
# Audits every `throw ` and `error(` site in the kompressor library Main source sets and
# classifies it against the typed-error taxonomy contract (CRA-21).
#
# A throw/error site is *allowed* only when it falls into one of these buckets:
#
#   TYPED     — throws a `VideoCompressionError`, `AudioCompressionError`, or
#               `ImageCompressionError` sub-type directly.
#   RETHROW   — rethrows an already-typed error / CancellationException caught upstream
#               (`throw ce`, `throw typed`, `throw e` inside a catch-typed block).
#   REMAPPED  — throws a `mapTo*Error(...)` / `classify*Error(...)` return value, i.e.
#               the value itself is guaranteed typed at the throw point.
#   INTERNAL  — non-public helper whose throw never escapes untyped because every call
#               site sits inside a typed-remap wrapper (e.g. `runPipelineWithTypedErrors`
#               in iOS pipelines, or the catch-Throwable wrapper in AndroidImageCompressor).
#
# The allowlist enumerates every line the current code-base is known to emit. Any *new*
# throw / error( line not on the allowlist fails the audit — forcing the contributor to
# either make the throw typed, route it through a remap, or extend the allowlist with
# a justification.
#
# Exit status: 0 = clean; 1 = unclassified hit(s).
#
# Usage: scripts/audit-throws.sh [--report PATH]

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC_ROOTS=(
  "kompressor/src/commonMain"
  "kompressor/src/androidMain"
  "kompressor/src/iosMain"
)

REPORT_PATH=""
if [ "${1-}" = "--report" ]; then
  REPORT_PATH="${2:?--report requires a path}"
fi

# ── Allowlist ────────────────────────────────────────────────────────
# Tab-separated: <bucket> <path> <line-pattern>. The path is matched exactly against
# the repo-relative grep hit; the pattern is matched as a substring on the trimmed
# source line. Sorted by file for readability.
ALLOWLIST=(
  $'RETHROW\tkompressor/src/commonMain/kotlin/co/crackn/kompressor/SuspendRunCatching.kt\tthrow ce'

  $'RETHROW\tkompressor/src/androidMain/kotlin/co/crackn/kompressor/DeletingOutputOnFailure.kt\tthrow t'
  $'RETHROW\tkompressor/src/androidMain/kotlin/co/crackn/kompressor/Media3ExportRunner.kt\tthrow ce'
  $'RETHROW\tkompressor/src/androidMain/kotlin/co/crackn/kompressor/audio/AudioTrackExtraction.kt\tthrow t'
  $'INTERNAL\tkompressor/src/androidMain/kotlin/co/crackn/kompressor/audio/AudioTrackExtraction.kt\terror("No audio track at index'
  $'TYPED\tkompressor/src/androidMain/kotlin/co/crackn/kompressor/audio/AudioTrackExtraction.kt\tthrow AudioCompressionError.UnsupportedSourceFormat('
  $'TYPED\tkompressor/src/androidMain/kotlin/co/crackn/kompressor/audio/AudioProcessorPlan.kt\tthrow AudioCompressionError.UnsupportedConfiguration('
  $'TYPED\tkompressor/src/androidMain/kotlin/co/crackn/kompressor/audio/AndroidAudioCompressor.kt\tthrow AudioCompressionError.IoFailed("Input file is empty'
  $'REMAPPED\tkompressor/src/androidMain/kotlin/co/crackn/kompressor/audio/AndroidAudioCompressor.kt\tthrow e.toAudioCompressionError(description)'
  $'TYPED\tkompressor/src/androidMain/kotlin/co/crackn/kompressor/audio/AndroidAudioCompressor.kt\tthrow AudioCompressionError.UnsupportedSourceFormat('
  $'TYPED\tkompressor/src/androidMain/kotlin/co/crackn/kompressor/audio/AndroidAudioCompressor.kt\tthrow AudioCompressionError.IoFailed('
  $'RETHROW\tkompressor/src/androidMain/kotlin/co/crackn/kompressor/audio/AndroidAudioCompressor.kt\tthrow ce'
  $'TYPED\tkompressor/src/androidMain/kotlin/co/crackn/kompressor/audio/AndroidAudioCompressor.kt\tthrow AudioCompressionError.IoFailed("Audio input not found:'
  $'TYPED\tkompressor/src/androidMain/kotlin/co/crackn/kompressor/audio/AndroidAudioCompressor.kt\tthrow AudioCompressionError.IoFailed("Permission denied probing audio input:'
  $'RETHROW\tkompressor/src/androidMain/kotlin/co/crackn/kompressor/image/AndroidImageCompressor.kt\tthrow e'
  $'REMAPPED\tkompressor/src/androidMain/kotlin/co/crackn/kompressor/image/AndroidImageCompressor.kt\tthrow classifyAndroidImageError(inputPath, e)'
  $'TYPED\tkompressor/src/androidMain/kotlin/co/crackn/kompressor/image/AndroidImageCompressor.kt\tthrow ImageCompressionError.EncodingFailed('
  $'TYPED\tkompressor/src/androidMain/kotlin/co/crackn/kompressor/image/AndroidImageCompressor.kt\tthrow ImageCompressionError.IoFailed("Input file not found:'
  $'TYPED\tkompressor/src/androidMain/kotlin/co/crackn/kompressor/image/AndroidImageCompressor.kt\tthrow ImageCompressionError.DecodingFailed("Cannot decode image dimensions:'
  $'TYPED\tkompressor/src/androidMain/kotlin/co/crackn/kompressor/image/AndroidImageCompressor.kt\tthrow ImageCompressionError.DecodingFailed("Failed to decode image:'
  $'TYPED\tkompressor/src/androidMain/kotlin/co/crackn/kompressor/image/AndroidImageCompressor.kt\tthrow ImageCompressionError.IoFailed("ContentResolver returned null input stream'
  $'TYPED\tkompressor/src/androidMain/kotlin/co/crackn/kompressor/video/AndroidVideoCompressor.kt\tthrow VideoCompressionError.UnsupportedSourceFormat('
  $'REMAPPED\tkompressor/src/androidMain/kotlin/co/crackn/kompressor/video/AndroidVideoCompressor.kt\tthrow e.toVideoCompressionError(description)'

  $'RETHROW\tkompressor/src/iosMain/kotlin/co/crackn/kompressor/DeletingOutputOnFailure.ios.kt\tthrow t'
  $'RETHROW\tkompressor/src/iosMain/kotlin/co/crackn/kompressor/IosAVWriterUtils.kt\tthrow writerFailureException(writer, "AVAssetWriter failed while waiting")'
  $'RETHROW\tkompressor/src/iosMain/kotlin/co/crackn/kompressor/IosAVWriterUtils.kt\tthrow CancellationException("AVAssetWriter cancelled")'
  $'RETHROW\tkompressor/src/iosMain/kotlin/co/crackn/kompressor/IosAVWriterUtils.kt\tthrow writerFailureException(writer, "AVAssetWriter not completed")'
  $'TYPED\tkompressor/src/iosMain/kotlin/co/crackn/kompressor/IosFileUtils.kt\tthrow AVNSErrorException('
  $'INTERNAL\tkompressor/src/iosMain/kotlin/co/crackn/kompressor/IosFileUtils.kt\terror("Cannot read file size:'
  $'TYPED\tkompressor/src/iosMain/kotlin/co/crackn/kompressor/audio/IosAudioCompressor.kt\tthrow AudioCompressionError.IoFailed("Input file is empty'
  $'RETHROW\tkompressor/src/iosMain/kotlin/co/crackn/kompressor/audio/IosAudioCompressor.kt\tthrow ce'
  $'RETHROW\tkompressor/src/iosMain/kotlin/co/crackn/kompressor/audio/IosAudioCompressor.kt\tthrow typed'
  $'REMAPPED\tkompressor/src/iosMain/kotlin/co/crackn/kompressor/audio/IosAudioCompressor.kt\tthrow mapToAudioError(t)'
  $'TYPED\tkompressor/src/iosMain/kotlin/co/crackn/kompressor/audio/IosAudioCompressor.kt\tthrow AudioCompressionError.UnsupportedSourceFormat('
  $'TYPED\tkompressor/src/iosMain/kotlin/co/crackn/kompressor/audio/IosAudioCompressor.kt\tthrow AudioCompressionError.UnsupportedConfiguration('
  $'TYPED\tkompressor/src/iosMain/kotlin/co/crackn/kompressor/audio/IosAudioCompressor.kt\tthrow AudioCompressionError.UnsupportedBitrate('
  $'RETHROW\tkompressor/src/iosMain/kotlin/co/crackn/kompressor/audio/IosAudioCompressor.kt\tthrow e'
  $'TYPED\tkompressor/src/iosMain/kotlin/co/crackn/kompressor/audio/IosAudioCompressor.kt\tthrow co.crackn.kompressor.AVNSErrorException(err, "AVAssetReader failed to start")'
  $'INTERNAL\tkompressor/src/iosMain/kotlin/co/crackn/kompressor/audio/IosAudioCompressor.kt\terror("AVAssetReader failed to start: unknown")'
  $'TYPED\tkompressor/src/iosMain/kotlin/co/crackn/kompressor/audio/IosAudioCompressor.kt\tthrow co.crackn.kompressor.AVNSErrorException(err, "AVAssetWriter failed to start")'
  $'INTERNAL\tkompressor/src/iosMain/kotlin/co/crackn/kompressor/audio/IosAudioCompressor.kt\terror("AVAssetWriter failed to start: unknown")'
  $'TYPED\tkompressor/src/iosMain/kotlin/co/crackn/kompressor/audio/IosAudioCompressor.kt\tthrow co.crackn.kompressor.AVNSErrorException(err, "AVAssetReader failed")'
  $'INTERNAL\tkompressor/src/iosMain/kotlin/co/crackn/kompressor/audio/IosAudioCompressor.kt\terror("AVAssetReader failed: unknown")'
  $'INTERNAL\tkompressor/src/iosMain/kotlin/co/crackn/kompressor/audio/IosAudioCompressor.kt\terror("Failed to create AVAssetWriter for:'
  $'INTERNAL\tkompressor/src/iosMain/kotlin/co/crackn/kompressor/audio/IosAudioCompressor.kt\terror("AVAssetExportSession not available for input")'
  $'RETHROW\tkompressor/src/iosMain/kotlin/co/crackn/kompressor/image/IosImageCompressor.kt\tthrow e'
  $'TYPED\tkompressor/src/iosMain/kotlin/co/crackn/kompressor/image/IosImageCompressor.kt\tthrow ImageCompressionError.DecodingFailed('
  $'TYPED\tkompressor/src/iosMain/kotlin/co/crackn/kompressor/image/IosImageCompressor.kt\tthrow ImageCompressionError.Unknown('
  $'TYPED\tkompressor/src/iosMain/kotlin/co/crackn/kompressor/image/IosImageCompressor.kt\tthrow ImageCompressionError.IoFailed("Input file not found:'
  $'TYPED\tkompressor/src/iosMain/kotlin/co/crackn/kompressor/image/IosImageCompressor.kt\tthrow ImageCompressionError.DecodingFailed("Failed to decode image:'
  $'TYPED\tkompressor/src/iosMain/kotlin/co/crackn/kompressor/image/IosImageCompressor.kt\tthrow ImageCompressionError.EncodingFailed("Failed to draw image into context")'
  $'TYPED\tkompressor/src/iosMain/kotlin/co/crackn/kompressor/image/IosImageCompressor.kt\tthrow ImageCompressionError.EncodingFailed("UIImageJPEGRepresentation returned nil")'
  $'TYPED\tkompressor/src/iosMain/kotlin/co/crackn/kompressor/image/IosImageCompressor.kt\tthrow ImageCompressionError.EncodingFailed('
  $'RETHROW\tkompressor/src/iosMain/kotlin/co/crackn/kompressor/video/IosVideoCompressor.kt\tthrow ce'
  $'RETHROW\tkompressor/src/iosMain/kotlin/co/crackn/kompressor/video/IosVideoCompressor.kt\tthrow typed'
  $'REMAPPED\tkompressor/src/iosMain/kotlin/co/crackn/kompressor/video/IosVideoCompressor.kt\tthrow mapToVideoError(t)'
  $'TYPED\tkompressor/src/iosMain/kotlin/co/crackn/kompressor/video/IosVideoCompressor.kt\tthrow VideoCompressionError.UnsupportedSourceFormat('
  $'RETHROW\tkompressor/src/iosMain/kotlin/co/crackn/kompressor/video/IosVideoCompressor.kt\tthrow e'
  $'INTERNAL\tkompressor/src/iosMain/kotlin/co/crackn/kompressor/video/IosVideoCompressor.kt\terror("Failed to create AVAssetWriter for:'
  $'TYPED\tkompressor/src/iosMain/kotlin/co/crackn/kompressor/video/IosVideoCompressor.kt\tthrow co.crackn.kompressor.AVNSErrorException(err, "AVAssetReader failed to start")'
  $'INTERNAL\tkompressor/src/iosMain/kotlin/co/crackn/kompressor/video/IosVideoCompressor.kt\terror("AVAssetReader failed to start: unknown")'
  $'TYPED\tkompressor/src/iosMain/kotlin/co/crackn/kompressor/video/IosVideoCompressor.kt\tthrow co.crackn.kompressor.AVNSErrorException(err, "AVAssetWriter failed to start")'
  $'INTERNAL\tkompressor/src/iosMain/kotlin/co/crackn/kompressor/video/IosVideoCompressor.kt\terror("AVAssetWriter failed to start: unknown")'
  $'TYPED\tkompressor/src/iosMain/kotlin/co/crackn/kompressor/video/IosVideoCompressor.kt\tthrow co.crackn.kompressor.AVNSErrorException(err, "AVAssetReader failed")'
  $'INTERNAL\tkompressor/src/iosMain/kotlin/co/crackn/kompressor/video/IosVideoCompressor.kt\terror("AVAssetReader failed: unknown")'
  $'INTERNAL\tkompressor/src/iosMain/kotlin/co/crackn/kompressor/video/IosVideoCompressor.kt\terror("AVAssetExportSession not available for input")'
)

# ── Collect current hits ─────────────────────────────────────────────
# Use `grep -n` rather than ripgrep so the script works identically in CI images that
# do not ship rg. `|| true` because grep exits 1 when there are zero matches (possible
# on a pathological repo state; we still want to emit the empty report).
HITS="$(cd "$REPO_ROOT" && grep -nE 'throw |error\(' -r "${SRC_ROOTS[@]}" --include='*.kt' || true)"

# ── Classify ─────────────────────────────────────────────────────────
typed_count=0
rethrow_count=0
remapped_count=0
internal_count=0
unclassified_count=0
declare -a unclassified_lines=()
declare -a typed_lines=()
declare -a rethrow_lines=()
declare -a remapped_lines=()
declare -a internal_lines=()

while IFS= read -r hit; do
  [ -z "$hit" ] && continue
  # grep -n output: <path>:<lineno>:<content>
  path="${hit%%:*}"
  rest="${hit#*:}"
  lineno="${rest%%:*}"
  content="${rest#*:}"

  # Trim leading/trailing whitespace for matching.
  trimmed="$(printf '%s' "$content" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')"

  # Skip comment-only lines.
  case "$trimmed" in
    "//"*|"*"*|"/*"*) continue ;;
  esac

  # Skip KDoc @throws declarations — those are contract documentation, not throw sites.
  case "$trimmed" in
    *"@throws"*) continue ;;
  esac

  matched_bucket=""
  for entry in "${ALLOWLIST[@]}"; do
    IFS=$'\t' read -r bucket allow_path allow_pattern <<<"$entry"
    if [ "$path" = "$allow_path" ] && [[ "$trimmed" == *"$allow_pattern"* ]]; then
      matched_bucket="$bucket"
      break
    fi
  done

  case "$matched_bucket" in
    TYPED)
      typed_count=$((typed_count + 1))
      typed_lines+=("$path:$lineno  $trimmed")
      ;;
    RETHROW)
      rethrow_count=$((rethrow_count + 1))
      rethrow_lines+=("$path:$lineno  $trimmed")
      ;;
    REMAPPED)
      remapped_count=$((remapped_count + 1))
      remapped_lines+=("$path:$lineno  $trimmed")
      ;;
    INTERNAL)
      internal_count=$((internal_count + 1))
      internal_lines+=("$path:$lineno  $trimmed")
      ;;
    *)
      unclassified_count=$((unclassified_count + 1))
      unclassified_lines+=("$path:$lineno  $trimmed")
      ;;
  esac
done <<<"$HITS"

# ── Emit report ──────────────────────────────────────────────────────
emit_report() {
  echo "== Kompressor throw-site audit =="
  echo ""
  printf "TYPED        %4d  — direct throw of *CompressionError subtype\n" "$typed_count"
  printf "RETHROW      %4d  — rethrow of already-typed / CancellationException\n" "$rethrow_count"
  printf "REMAPPED     %4d  — throw of mapTo*Error(...) / classify*Error(...) result\n" "$remapped_count"
  printf "INTERNAL     %4d  — non-public helper; remapped by typed-error wrapper\n" "$internal_count"
  printf "UNCLASSIFIED %4d  — FAIL if non-zero (see below)\n" "$unclassified_count"
  echo ""

  if [ "${#unclassified_lines[@]}" -gt 0 ]; then
    echo "== Unclassified throw/error sites =="
    echo "Each must become a typed throw, be wrapped with a mapping, or be added to the"
    echo "allowlist in scripts/audit-throws.sh with a clear bucket and justification."
    echo ""
    for line in "${unclassified_lines[@]}"; do
      echo "  $line"
    done
    echo ""
  fi

  echo "== Typed throws =="
  for line in "${typed_lines[@]}"; do echo "  $line"; done
  echo ""
  echo "== Rethrows =="
  for line in "${rethrow_lines[@]}"; do echo "  $line"; done
  echo ""
  echo "== Remapped throws =="
  for line in "${remapped_lines[@]}"; do echo "  $line"; done
  echo ""
  echo "== Internal (remapped by typed-error wrapper) =="
  for line in "${internal_lines[@]}"; do echo "  $line"; done
  echo ""
}

if [ -n "$REPORT_PATH" ]; then
  emit_report | tee "$REPORT_PATH"
else
  emit_report
fi

if [ "$unclassified_count" -gt 0 ]; then
  echo "::error::$unclassified_count unclassified throw/error site(s)."
  exit 1
fi

echo "Audit passed: every throw/error site falls in the typed-error taxonomy."
