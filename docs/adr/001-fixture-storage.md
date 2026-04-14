# ADR-001: Test Fixture Storage Strategy

## Status

Accepted (2026-04-15)

## Context

Kompressor's test suite currently generates all media fixtures on-the-fly
(e.g., `Bitmap.createBitmap` for images, `MediaCodec` for video, `WavGenerator`
for audio). This approach:

- **Misses real-world edge cases**: synthetic 1x1 white bitmaps don't cover
  progressive JPEG, EXIF orientations, VBR MP3, CMYK color spaces, or HDR content.
- **Is non-deterministic across platforms**: `MediaCodec` encoder output varies by
  device/driver; generated fixtures can't be golden-tested cross-platform.
- **Wastes CI time**: each run regenerates identical content.

CRA-71 mandates a centralized fixture bank of real, versioned files. We need to
decide **where and how** to store them.

## Options Considered

### Option A: Git LFS Only

Store all fixtures in Git LFS. Every clone gets pointer files; `git lfs pull`
materializes them.

- **Pros**: Simple tooling, single `git clone` workflow, no external infra.
- **Cons**: GitHub LFS has a 2 GB storage + 2 GB/month bandwidth limit on free
  tier ($5/50 GB pack beyond). UHD video fixtures alone could exceed 2 GB.
  Large binary diffs bloat LFS storage even when content is append-only.

### Option B: External Object Storage Only (S3/R2)

Store all fixtures in a Cloudflare R2 (or S3-compatible) bucket. A fetch script
downloads them at build time.

- **Pros**: Unlimited storage, R2 has zero egress fees, no Git repo bloat.
- **Cons**: Requires bucket provisioning, IAM setup, and a fetch script. Offline
  development is harder. CI needs network access to external storage.

### Option C: Hybrid (LFS for small, R2 for large) — **CHOSEN**

- Files **<= 2 MB**: committed via Git LFS. Covers most audio samples, small
  images, short video clips, and metadata test files.
- Files **> 2 MB**: stored in Cloudflare R2, fetched by `scripts/fetch-fixtures.sh`.
  Covers UHD video, high-resolution images, multi-channel audio.
- **`fixtures/manifest.json`** is the single source of truth. It lists every
  fixture with `sha256`, `size`, `storage` (`"lfs"` or `"r2"`), and `source_url`.
- The fetch script: downloads R2-hosted fixtures if absent, verifies SHA-256 for
  both LFS and R2 files, and fails fast on mismatch.

## Decision

**Option C: Hybrid LFS + R2**.

This keeps the common case simple (small fixtures "just work" after clone + LFS
pull) while avoiding LFS quota pressure from large media files. R2's zero-egress
pricing makes CI bandwidth free.

### Storage Rules

| Size | Storage | Tracked By |
|------|---------|------------|
| <= 2 MB | Git LFS (`.gitattributes`) | `git lfs pull` |
| > 2 MB | Cloudflare R2 bucket | `scripts/fetch-fixtures.sh` |

### Manifest Contract

Every fixture MUST have an entry in `fixtures/manifest.json`:

```json
{
  "name": "kodak-01.png",
  "source_url": "https://r0k.us/graphics/kodak/kodak/kodim01.png",
  "license": "Unrestricted",
  "sha256": "abc123...",
  "size": 786432,
  "storage": "lfs",
  "category": "happy-path/image",
  "tags": ["jpeg", "baseline", "768x512"],
  "test_cases": ["ImageCompressionTest.compressJpegBaseline"]
}
```

### CI Integration

1. `actions/cache` caches `fixtures/` directory keyed on `manifest.json` hash.
2. On cache miss, CI runs `scripts/fetch-fixtures.sh` which:
   - Calls `git lfs pull` for LFS-tracked fixtures.
   - Downloads R2-hosted fixtures via `curl`.
   - Verifies all SHA-256 checksums.
3. Tests read fixtures from `fixtures/<category>/<file>`.

## Consequences

- **Positive**: Real-world test coverage, deterministic across platforms,
  no CI regeneration cost, scalable storage.
- **Negative**: Contributors must run `scripts/fetch-fixtures.sh` after clone
  (documented in `docs/contributing-fixtures.md` and repo README).
- **Neutral**: LFS requires one-time `git lfs install` (standard for media repos).

## References

- CRA-71: Fixture bank centralisee
- CRA-5: VBR MP3 / FLAC / CMYK fixtures (will migrate to this bank)
- CRA-6: HDR10 P010 generator (output stored in bank)
