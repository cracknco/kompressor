# Contributing Fixtures

How to add, update, or remove test fixtures in the Kompressor fixture bank.

## Quick Start

```bash
# After cloning, fetch all fixtures
./scripts/fetch-fixtures.sh

# Verify checksums
./scripts/fetch-fixtures.sh --verify
```

## Adding a New Fixture

### 1. Choose a Source

Pick a file from a dataset listed in `docs/fixture-sources.md`. Ensure the
license permits redistribution for testing purposes.

### 2. Determine Storage Tier

| File size | Storage | Action |
|-----------|---------|--------|
| <= 2 MB   | Git LFS | Add to `fixtures/<category>/`, commit normally (`.gitattributes` handles LFS) |
| > 2 MB    | R2      | Upload to the R2 bucket under `<category>/<filename>` |

### 3. Compute SHA-256

```bash
shasum -a 256 path/to/file
```

### 4. Add Manifest Entry

Edit `fixtures/manifest.json` and add an entry:

```json
{
  "name": "my-new-fixture.mp4",
  "source_url": "https://example.com/original-source",
  "license": "CC BY 3.0",
  "sha256": "abc123...",
  "size": 1048576,
  "storage": "lfs",
  "category": "happy-path/video",
  "tags": ["h264", "main", "mp4", "720p", "30fps"],
  "test_cases": ["VideoCompressionTest.compressH264Main"]
}
```

Fields:

| Field | Required | Description |
|-------|----------|-------------|
| `name` | Yes | Filename (must be unique across all categories) |
| `source_url` | Yes | Original download URL (or `null` if internally generated) |
| `license` | Yes | License of the source material |
| `sha256` | Yes | SHA-256 hex digest |
| `size` | Yes | File size in bytes |
| `storage` | Yes | `"lfs"` or `"r2"` |
| `category` | Yes | Directory under `fixtures/` (e.g., `"happy-path/audio"`) |
| `tags` | Yes | Array of searchable tags (codec, format, resolution, edge case) |
| `test_cases` | Yes | Array of test methods that use this fixture |

### 5. Place the File

**LFS fixtures** (<=2 MB):
```bash
cp my-new-fixture.mp4 fixtures/happy-path/video/
git add fixtures/happy-path/video/my-new-fixture.mp4
```

**R2 fixtures** (>2 MB):
```bash
# Upload via AWS CLI (configured for R2)
aws s3 cp my-new-fixture.mp4 \
  s3://kompressor-fixtures/happy-path/video/my-new-fixture.mp4 \
  --endpoint-url "$R2_ENDPOINT"
```

### 6. Write the Test

Reference the fixture in your test:

```kotlin
// In androidDeviceTest or iosTest
val fixture = FixtureBank.resolve("happy-path/video/my-new-fixture.mp4")
val result = compressor.video.compress(fixture, config)
```

### 7. Submit PR

- Commit the manifest change + LFS file (if applicable)
- Ensure `./scripts/fetch-fixtures.sh --verify` passes
- CI will cache the fixtures directory automatically

## Updating a Fixture

1. Replace the file (LFS: commit new version; R2: re-upload)
2. Update `sha256` and `size` in `manifest.json`
3. Run `./scripts/fetch-fixtures.sh --verify`

## Removing a Fixture

1. Delete the file (LFS: `git rm`; R2: delete from bucket)
2. Remove the entry from `manifest.json`
3. Remove or update any tests that referenced it

## Category Structure

```
fixtures/
├── happy-path/       # Standard, well-formed files for golden-path testing
│   ├── image/
│   ├── audio/
│   └── video/
├── edge-cases/       # Valid but unusual: EXIF orientations, progressive JPEG, VBR, CMYK
│   ├── image/
│   ├── audio/
│   └── video/
├── adversarial/      # Malformed, truncated, or CVE-triggering files
│   ├── image/
│   ├── audio/
│   └── video/
├── legacy-codecs/    # Older formats: MPEG-2, DivX, WMA, BMP
│   ├── image/
│   ├── audio/
│   └── video/
├── hdr/              # HDR10, HDR10+, HLG, Dolby Vision, OpenEXR
│   ├── image/
│   └── video/
├── multi-channel/    # 5.1, 7.1, Atmos-compatible audio
│   └── audio/
├── high-resolution/  # 4K+, 100MP+ images
│   ├── image/
│   └── video/
└── manifest.json     # Source of truth for all fixtures
```

## CI Integration

The PR workflow caches `fixtures/` keyed on the manifest hash. On cache miss,
`fetch-fixtures.sh` runs automatically. No manual intervention needed for CI.

## Troubleshooting

**`git lfs pull` fails**: Ensure Git LFS is installed (`git lfs install`).
Check that `.gitattributes` tracks the file extension.

**R2 download fails**: Check your network. The fetch script retries 3 times
with a 2-second delay. If the R2 bucket is unreachable, it falls back to
`source_url` from the manifest.

**Checksum mismatch**: The file was corrupted or updated without a manifest
change. Re-download with `./scripts/fetch-fixtures.sh --clean && ./scripts/fetch-fixtures.sh`.
