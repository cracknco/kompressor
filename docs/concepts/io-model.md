# I/O model

> **Audience**: every consumer of Kompressor's `compress(...)` entry points.
> **Status**: stable from M10 (CRA-89 → CRA-97). Pre-M10 path-based overloads were removed in CRA-97.

Kompressor takes inputs from many places (filesystem, MediaStore URI, `PHAsset`, an `okio.Source`, raw bytes) and writes outputs to similarly many places. Rather than overload every compressor with N variants for each combination, Kompressor offers exactly **one** entry point per media kind:

```kotlin
// Audio + Video — the underlying pipelines emit progress ticks.
suspend fun compress(
    input: MediaSource,
    output: MediaDestination,
    config: XxxCompressionConfig = XxxCompressionConfig(),
    onProgress: suspend (CompressionProgress) -> Unit = {},
): Result<CompressionResult>

// Image — `BitmapFactory` (Android) / `UIImage` + Core Graphics (iOS) are
// synchronous single-step operations, so there is no `onProgress` parameter.
suspend fun compress(
    input: MediaSource,
    output: MediaDestination,
    config: ImageCompressionConfig = ImageCompressionConfig(),
): Result<CompressionResult>
```

Where you came from (a `Uri`, an `NSURL`, an `InputStream`, …) is encoded by which `MediaSource.of(...)` builder you used. The compressor doesn't know the difference — it dispatches off the sealed hierarchy.

This page is the canonical reference for that hierarchy: every variant, what it accepts, how it's resolved, who owns the lifecycle, and where the sharp edges are.

---

## The two sealed hierarchies

```kotlin
public sealed interface MediaSource {
    public interface Local : MediaSource {
        public data class FilePath(public val path: String) : Local
        public class Stream(
            public val source: okio.Source,
            public val sizeHint: Long? = null,
            public val closeOnFinish: Boolean = true,
        ) : Local
        public data class Bytes(public val bytes: ByteArray) : Local
    }
    public companion object
}

public sealed interface MediaDestination {
    public interface Local : MediaDestination {
        public data class FilePath(public val path: String) : Local
        public class Stream(
            public val sink: okio.Sink,
            public val closeOnFinish: Boolean = true,
        ) : Local
    }
    public companion object
}
```

Both hierarchies are `sealed interface` at the top level but **`Local` is a plain `interface`, not `sealed`**. Kotlin's sealed-hierarchy-per-module rule prohibits cross-module extension, and we need platform source sets (`androidMain`, `iosMain`) to add their own wrappers — `AndroidUriMediaSource`, `IosPHAssetMediaSource`, and so on. So `Local` is left open, and `commonMain` callers that exhaustive-match on `Local` must include an `else` branch for the platform wrappers. The canonical dispatch lives in `AndroidMediaDispatch.toAndroidInputPath` / `IosMediaDispatch.toIosInputPath`.

The companion objects on `MediaSource` / `MediaDestination` are reserved for platform-specific factories — that's how `MediaSource.of(uri: Uri)` (Android) and `MediaSource.of(asset: PHAsset)` (iOS) attach without bloating the common surface.

### Future `Remote` extension

Today only `Local` exists. A `Remote` sibling for streaming from URLs without a prior download is a deliberate non-goal for M10 — see the **Remote URL rejection** section below for why downloading first is the supported path. Consumers that exhaust-match on `MediaSource` today will need a new branch when a remote variant ships; given the sealed hierarchy, that's a compile error you'll catch on bump, not a runtime surprise.

---

## Source variants

### `MediaSource.Local.FilePath`

```kotlin
val src = MediaSource.Local.FilePath("/path/to/input.mp4")
```

The simplest form. The compressor passes the path straight through to the platform pipeline:

| Platform | Image                              | Audio / Video                |
|----------|------------------------------------|------------------------------|
| Android  | `BitmapFactory.decodeFile(path)`   | `Transformer` `MediaItem.fromUri(path)` |
| iOS      | `UIImage(contentsOfFile:)`         | `AVURLAsset(url: file://path)` |

No materialization to disk, no temp file. Use this when you already have a path on local storage.

The path **must be absolute**. Relative paths are interpreted against the platform's CWD, which is undefined for Android library code and points at the iOS app bundle root on iOS — almost never what you want.

### `MediaSource.Local.Stream`

```kotlin
val src = MediaSource.Local.Stream(
    source = okio.buffer(file.source()),
    sizeHint = file.length(),
    closeOnFinish = true,
)
```

A streamable [`okio.Source`][okio]. For **video and audio**, Kompressor materializes the stream to a private temp file before invoking Media3 / AVFoundation (both APIs require seekable file inputs — there is no way to feed a streaming reader into either pipeline). For **images**, the stream is decoded directly without a temp file.

The `sizeHint` is optional and only used for progress estimation during the `MATERIALIZING_INPUT` phase. Pass `null` when the total byte count is unknown — fraction will stay at `0` for that phase, but the rest of the pipeline still works. Negative values are rejected at construction time with `IllegalArgumentException`.

`Stream` is a plain `class`, **not a `data class`**. Two `okio.Source` handles are never "equal" even if they momentarily hold the same bytes (one is consumed, the other isn't), and a `copy()` would silently share a stateful resource between instances.

### `MediaSource.Local.Bytes`

```kotlin
val src = MediaSource.Local.Bytes(byteArray)
```

In-memory buffer. Safe for **images up to ~50 MB**. For **video and audio**, prefer `FilePath` or `Stream` — wrapping a 500 MB video in `Bytes` forces the whole clip into the JVM heap and OOMs on mid-range Android devices (heap limit ≈ 256 MB). Kompressor emits a `WARN` log when `Bytes` is used for video or audio and proceeds with materialization to a temp file.

The referenced `bytes` array **must not be mutated** during the `compress()` call — Kompressor keeps a reference (no defensive copy) for zero-copy decode where possible.

`Bytes` is a `data class` but with hand-rolled `equals` / `hashCode` that compare by content (`ByteArray.contentEquals` / `contentHashCode`) — Kotlin's default array equality is identity-based, which is the wrong semantic for a value type wrapping bytes. `toString()` deliberately omits content (just `Bytes(size=N)`) to avoid leaking PII in logs.

### Platform-specific builders

#### Android

```kotlin
import co.crackn.kompressor.io.MediaSource

// content:// from PickVisualMedia, Storage Access Framework, …
val src = MediaSource.of(uri)
```

Accepts schemes `file` and `content`. Internally wraps the `Uri` in `AndroidUriMediaSource`. The dispatcher (`AndroidMediaDispatch.toAndroidInputPath`) calls `ContentResolver.openInputStream(uri)` for `content://` and unwraps the path for `file://`.

`PickVisualMedia` returns `content://media/external/images/...` — pass that straight to `MediaSource.of(uri)`. No `getRealPathFromUri` hack required.

Remote schemes `http` / `https` are **rejected with `IllegalArgumentException`** (see [Remote URL rejection](#remote-url-rejection)).

#### iOS

```kotlin
import co.crackn.kompressor.io.MediaSource

// file:// from PHPickerViewController, UIDocumentPickerViewController, …
val src = MediaSource.of(nsUrl)

// PHAsset from PHPickerViewController
val src = MediaSource.of(phAsset, allowNetworkAccess = true)

// In-memory NSData (image-only without temp file; video/audio materialize)
val src = MediaSource.of(nsData)

// Streaming from NSInputStream
val src = MediaSource.of(nsInputStream, closeOnFinish = true)
```

`MediaSource.of(NSURL)` accepts only `file://` schemes; `http`/`https` are rejected with the same cross-platform message as the Android sibling.

`MediaSource.of(PHAsset, allowNetworkAccess: Boolean)` is documented in detail under [PHAsset iCloud handling](#phasset-icloud-handling).

`MediaSource.of(NSData)` is a thin wrapper over `IosDataMediaSource`; for images it routes through `CGImageSourceCreateWithData` (zero-copy when the `NSData` is mmap-backed).

`MediaSource.of(NSInputStream)` adapts the stream to an `okio.Source` via `asOkioSource()` and routes through the common `Stream` pipeline.

---

## Destination variants

### `MediaDestination.Local.FilePath`

```kotlin
val dst = MediaDestination.Local.FilePath("/path/to/output.mp4")
```

The simplest form. Intermediate directories must already exist — Kompressor does **not** call `mkdirs` for you. The output file will be created (or overwritten) atomically: Kompressor writes to a sibling temp path first and renames on success, so a failed compression never leaves a half-written file at the requested path. Cleanup of the temp file on failure is best-effort.

### `MediaDestination.Local.Stream`

```kotlin
val dst = MediaDestination.Local.Stream(
    sink = okio.buffer(file.sink()),
    closeOnFinish = true,
)
```

A writable [`okio.Sink`][okio]. For **images**, Kompressor streams encoded bytes into the sink directly. For **video and audio**, Media3's `Transformer` and `AVAssetWriter` both require file outputs, so Kompressor writes to a private temp file first then copies it into the sink — **double I/O cost** vs. `FilePath`. Use `FilePath` when you can; reach for `Stream` when the output destination is a network upload, an HTTP body, an encryption pipeline, etc.

Like `MediaSource.Local.Stream`, `MediaDestination.Local.Stream` is a plain `class` (identity equality), not a `data class`.

### Platform-specific destination builders

#### Android

```kotlin
import co.crackn.kompressor.io.MediaDestination

// content:// — file URIs, SAF documents, MediaStore output handles
val dst = MediaDestination.of(uri)

// java.io.OutputStream — HTTP body, encryption pipeline, …
val dst = MediaDestination.of(outputStream, closeOnFinish = true)
```

When the `Uri`'s authority is `MediaStore.AUTHORITY` (`"media"`), Kompressor automatically applies the **`IS_PENDING` flag pattern** required for scoped storage on Android 10+. See [MediaStore strategy](#mediastore-strategy-android) for the contract.

For other `content://` URIs (SAF documents, custom providers), Kompressor uses `ContentResolver.openOutputStream(uri)` directly with no `IS_PENDING` dance.

#### iOS

```kotlin
import co.crackn.kompressor.io.MediaDestination

// file:// destination
val dst = MediaDestination.of(nsUrl)

// NSOutputStream (uploads, encryption pipelines, …)
val dst = MediaDestination.of(nsOutputStream, closeOnFinish = true)
```

`MediaDestination.of(NSURL)` accepts only `file://`. `MediaDestination.of(NSOutputStream)` adapts via `asOkioSink()` and routes through the common `Stream` pipeline.

There is **no `PHPhotoLibrary` write integration** in M10 — saving compressed media into the photo library is out of scope. Write to a `file://` destination first then call `PHPhotoLibrary.shared().performChanges` from your app code.

---

## Memory invariants

Kompressor's internal pipelines are designed to keep RSS bounded regardless of source size — but **the source variant you pick determines whether that's actually true**.

| Variant   | Memory behavior (image)                          | Memory behavior (audio / video)                |
|-----------|--------------------------------------------------|------------------------------------------------|
| `FilePath`| Decoded directly from disk; bounded by frame size| Streamed by Media3/AVFoundation; bounded       |
| `Stream`  | Decoded directly; bounded by frame size          | **Materialized to temp file**; transient 2× disk|
| `Bytes`   | Bytes held in RAM + decoded frame                | **Held in RAM until materialized**; OOM risk   |

**Rules of thumb**:

1. For video / audio sources larger than ~50 MB, **always** use `FilePath` or `Stream`. `Bytes` will fault on mid-range devices.
2. For images, `Bytes` is fine up to ~50 MB. Beyond that the decoded frame plus the encoded buffer plus Android's `Bitmap` doubling can push past the heap limit.
3. `Stream` for video / audio buys you composability with okio's pipeline (e.g. encrypted-at-rest streams) but pays a transient ~2× disk-space cost during materialization. Plan for the temp file on devices with tight storage.
4. `MediaDestination.Local.Stream` for video / audio likewise costs you a temp file plus a copy — only choose it when `FilePath` isn't an option.

The temp directory used for materialization is platform-private:

- **Android**: `context.cacheDir / "kompressor"` (subject to OS eviction under storage pressure)
- **iOS**: `NSTemporaryDirectory() + "kompressor-..."`

Temp files are deleted on success and best-effort on failure (a process kill mid-compression can leak them; the OS reclaims the cache directory eventually).

---

## `closeOnFinish` contract

Every stream variant accepts a `closeOnFinish: Boolean = true` parameter:

```kotlin
MediaSource.Local.Stream(source, closeOnFinish = false)
MediaDestination.Local.Stream(sink, closeOnFinish = false)
MediaSource.of(nsInputStream, closeOnFinish = false)
MediaDestination.of(outputStream, closeOnFinish = false)
```

When `true` (default), Kompressor calls `source.close()` / `sink.close()` at the end of compression — **regardless of success or failure, and regardless of whether the close itself throws**. Close failures are logged at `WARN` and swallowed; they do not promote a successful compression to a failure.

Pass `closeOnFinish = false` when:

- The stream lifecycle is externally managed (you opened it, you own its closing).
- The stream is shared with a parallel pipeline that hasn't finished consuming it (e.g. an uploader teeing the same source).
- You wrap the stream in your own `use {}` block at the call site.

The default is `true` because the common case is "open the stream, hand it to compress, walk away" — defaulting to leak-prone explicit close was the wrong call. The override exists because there are real cases where double-close raises `IOException` or detaches a still-needed file descriptor.

---

## Remote URL rejection

Both `MediaSource.of(...)` and `MediaDestination.of(...)` builders reject `http` and `https` schemes with a typed `IllegalArgumentException`:

```
Remote URLs not supported. Download the content locally first.
```

```
Remote URLs not supported. Write locally first then upload.
```

The rejection messages are **cross-platform invariants** — bit-identical strings between Android and iOS so a consumer switching platforms sees identical text. The constants live in `MediaSourceRejections` (commonMain); a typo fix on one side lands for every platform simultaneously.

**Why no remote support?**

1. **Backpressure**: Media3 `Transformer` and `AVAssetExportSession` both require seekable inputs. Streaming from HTTP without a backing file means re-requesting byte ranges, which most CDNs don't support reliably.
2. **Cancellation**: A cancelled compression should drop the in-flight HTTP request immediately. Wiring that through every codec path on both platforms is a non-trivial integration that consumers can do once at their network layer (Ktor, OkHttp, URLSession) instead of M times in our pipelines.
3. **Auth**: Bearer tokens, signed URLs with TTL, mTLS — none of these belong in a media compression library's surface.

The supported pattern: download the bytes via your network layer, then pass the resulting `okio.Source` (or path / Uri / NSURL of the cached file) to Kompressor. Ktor's `HttpResponse.bodyAsChannel().toInputStream()` adapts cleanly to `okio.source()`.

---

## MediaStore strategy (Android)

When you pass a `Uri` whose authority is `MediaStore.AUTHORITY` (`"media"`) to `MediaDestination.of(uri)`, Kompressor automatically handles the `IS_PENDING` flag dance required for scoped storage on Android 10+ (API 29+):

1. **Before write**: `contentResolver.update(uri, ContentValues().apply { put(IS_PENDING, 1) }, null, null)` — marks the entry as pending so the gallery does not surface a partial file.
2. **Write**: `contentResolver.openOutputStream(uri)` and stream compressed bytes through.
3. **After write success**: `contentResolver.update(uri, ContentValues().apply { put(IS_PENDING, 0) }, null, null)` — clears the flag, the file becomes visible.
4. **On failure**: best-effort delete via `contentResolver.delete(uri, null, null)` so a half-written entry does not linger pending forever.

The full strategy lives in `MediaStoreOutputStrategy.kt`. Custom `ContentProvider` implementations that mimic the MediaStore authority but do **not** implement the `IS_PENDING` contract get a `WARN` log on the final clear step — Kompressor still returns success because the bytes were written correctly; only the pending-flag clear failed.

For non-MediaStore `content://` URIs (SAF documents, custom providers), no `IS_PENDING` dance is attempted; `ContentResolver.openOutputStream(uri)` is used directly.

---

## PHAsset iCloud handling

```kotlin
val src = MediaSource.of(asset, allowNetworkAccess = true)  // default
val src = MediaSource.of(asset, allowNetworkAccess = false)
```

PhotoKit assets can be stored in iCloud and not locally cached. Kompressor resolves the asset at compression time via:

- `PHImageManager.requestAVAssetForVideo` — video and audio
- `PHImageManager.requestImageDataAndOrientationForAsset` — images

If the asset is iCloud-only, behavior depends on `allowNetworkAccess`:

| `allowNetworkAccess` | Behavior on iCloud-only asset                                                   |
|----------------------|---------------------------------------------------------------------------------|
| `true` (default)     | Permits iCloud download. `compress()` blocks until the download completes (seconds to minutes). Progress is reported via the `MATERIALIZING_INPUT` phase. |
| `false`              | Fails fast with `XxxCompressionError.SourceNotFound` (image / audio / video).   |

**When to use `false`**:

- **Background tasks** that must not initiate network traffic.
- **Airplane mode / metered network** awareness — you've already detected the user is offline and want a typed error instead of a hung call.
- **Bandwidth-sensitive contexts** — large 4K HDR assets in iCloud can be hundreds of MB.

**When to use `true`** (the default):

- Foreground compression triggered by user picker action — the user expects "it just works" and is already prepared to wait.

iCloud-resolution failures unrelated to availability (network error mid-download, asset corruption) surface as `XxxCompressionError.IoFailed` so they're distinguishable from a typed "iCloud-only and you said no".

---

## Progression phases

```kotlin
public data class CompressionProgress(
    public val phase: Phase,
    public val fraction: Float,
)

public enum class Phase {
    MATERIALIZING_INPUT,  // Stream/Bytes → temp file; PHAsset iCloud download
    COMPRESSING,          // Active decode + re-encode
    FINALIZING_OUTPUT,    // Muxer flush, MediaStore IS_PENDING clear, sink copy
}
```

**Invariants** (enforced at producer construction time):

- `fraction` is always in `[0.0, 1.0]` and never `NaN`. Out-of-range values throw `IllegalArgumentException` at the producer — invalid emissions cannot reach UI code.
- `fraction` resets to `0` at every phase transition. It tracks progress *within* the current phase, not globally.
- Phases are emitted in monotonic order: `MATERIALIZING_INPUT` → `COMPRESSING` → `FINALIZING_OUTPUT`. A consumer can rely on never seeing a phase repeat after the next one fires.
- `FINALIZING_OUTPUT(1.0)` is the canonical terminal emission. Every successful compression ends there.

**Phase emission by source kind**:

| Source                                               | Phases emitted                                          |
|------------------------------------------------------|---------------------------------------------------------|
| `FilePath`                                           | `COMPRESSING` → `FINALIZING_OUTPUT`                     |
| Native URI (`Uri`, `NSURL`)                          | `COMPRESSING` → `FINALIZING_OUTPUT`                     |
| `Stream`, `Bytes` (video/audio)                      | `MATERIALIZING_INPUT` → `COMPRESSING` → `FINALIZING_OUTPUT` |
| `Stream`, `Bytes` (image)                            | `COMPRESSING` → `FINALIZING_OUTPUT` (no temp file)      |
| `PHAsset` (locally cached)                           | `COMPRESSING` → `FINALIZING_OUTPUT`                     |
| `PHAsset` (iCloud-only, `allowNetworkAccess=true`)   | `MATERIALIZING_INPUT` → `COMPRESSING` → `FINALIZING_OUTPUT` |

**Mapping to a global progress bar**:

`fraction` is per-phase. To drive a single 0–100 progress bar, weight the phases:

```kotlin
val global = when (progress.phase) {
    Phase.MATERIALIZING_INPUT -> progress.fraction * 0.10f
    Phase.COMPRESSING         -> 0.10f + progress.fraction * 0.85f
    Phase.FINALIZING_OUTPUT   -> 0.95f + progress.fraction * 0.05f
}
```

The 10% / 85% / 5% split is a sane default for typical stream-backed sources. For `FilePath` inputs (no materialization phase), a 95% / 5% compress / finalize split fits better.

**Phase-aware UI**:

Use `progress.phase` to swap the progress label:

| Phase                  | Label suggestion                       |
|------------------------|----------------------------------------|
| `MATERIALIZING_INPUT`  | "Preparing source…" / "Downloading from iCloud…" |
| `COMPRESSING`          | "Compressing…"                         |
| `FINALIZING_OUTPUT`    | "Finishing…"                           |

The Kompressor sample app exposes `progress.phase` directly in `AudioCompressState` / `VideoCompressState` for exactly this pattern.

---

## Migration guide (from path-based overloads)

Pre-CRA-97, every compressor exposed two overloads:

```kotlin
// REMOVED — do not reintroduce.
suspend fun compress(
    inputPath: String,
    outputPath: String,
    config: XxxCompressionConfig = XxxCompressionConfig(),
    onProgress: suspend (Float) -> Unit = {},
): Result<CompressionResult>

// CURRENT — only entry point.
suspend fun compress(
    input: MediaSource,
    output: MediaDestination,
    config: XxxCompressionConfig = XxxCompressionConfig(),
    onProgress: suspend (CompressionProgress) -> Unit = {},
): Result<CompressionResult>
```

The path-based overload was retired in CRA-97 with no `@Deprecated` shim. The migration is mechanical:

### Plain file paths

```kotlin
// Before
kompressor.video.compress(inputPath, outputPath, config) { fraction ->
    progressBar.setProgress(fraction)
}

// After
kompressor.video.compress(
    input = MediaSource.Local.FilePath(inputPath),
    output = MediaDestination.Local.FilePath(outputPath),
    config = config,
) { progress ->
    progressBar.setProgress(progress.fraction)
}
```

### Android: `PickVisualMedia` → `MediaSource.of(uri)`

```kotlin
// Before — required getRealPathFromUri / file copy hack
val realPath = ContentResolverHelper.getRealPath(context, uri)
kompressor.image.compress(realPath, outputPath, config)

// After
kompressor.image.compress(
    input = MediaSource.of(uri),
    output = MediaDestination.Local.FilePath(outputPath),
    config = config,
)
```

`MediaSource.of(uri)` handles `content://` natively via `ContentResolver.openInputStream` — no helper required.

### Android: MediaStore output

```kotlin
// Before — manual IS_PENDING dance, often forgotten on Android 10+
val pendingValues = ContentValues().apply { put(IS_PENDING, 1) }
val outputUri = contentResolver.insert(collection, pendingValues)
val outputStream = contentResolver.openOutputStream(outputUri!!)
// … compress to a temp file, copy bytes into outputStream, clear IS_PENDING …

// After — Kompressor handles IS_PENDING automatically
val outputUri = contentResolver.insert(collection, ContentValues(/* no IS_PENDING needed */))
kompressor.video.compress(
    input = MediaSource.of(inputUri),
    output = MediaDestination.of(outputUri!!),
    config = config,
)
```

### iOS: `PHPickerViewController` → `MediaSource.of(phAsset)`

```kotlin
// Before — manual PHImageManager.requestAVAssetForVideo + file export to temp path
PHImageManager.defaultManager().requestAVAssetForVideo(asset, options) { avAsset, _, _ ->
    val urlAsset = avAsset as? AVURLAsset ?: return@requestAVAssetForVideo
    val tempPath = exportToTemp(urlAsset.URL)
    kompressor.video.compress(tempPath, outputPath, config)
}

// After
kompressor.video.compress(
    input = MediaSource.of(phAsset),
    output = MediaDestination.Local.FilePath(outputPath),
    config = config,
)
```

iCloud download (when needed) is handled automatically; toggle `allowNetworkAccess = false` to fail fast on iCloud-only assets.

### Ktor / okio streams

```kotlin
// Before — manual download to temp file
val tempFile = withContext(Dispatchers.IO) {
    val dst = File(cacheDir, "tmp.mp4")
    httpClient.get(url).bodyAsChannel().copyTo(dst.sink().buffer())
    dst
}
kompressor.video.compress(tempFile.absolutePath, outputPath, config)

// After — pass the okio.Source directly. Issue a single HEAD-or-GET to read both
// the body channel and the Content-Length header off the same response.
val response = httpClient.get(url)
kompressor.video.compress(
    input = MediaSource.Local.Stream(
        source = response.bodyAsChannel().toInputStream().source(),
        sizeHint = response.contentLength(),
        closeOnFinish = true,
    ),
    output = MediaDestination.Local.FilePath(outputPath),
    config = config,
)
```

For video / audio, Kompressor still materializes the stream to a temp file internally — the win isn't bypassing the temp file, it's not having to write the temp-file management yourself, and getting accurate `MATERIALIZING_INPUT` progress for free.

### Progress callback signature

The `onProgress: suspend (Float) -> Unit` parameter became `suspend (CompressionProgress) -> Unit`. Extract `.fraction` for the float, and use `.phase` if you want phase-aware UI:

```kotlin
onProgress = { progress ->
    progressBar.setProgress(progress.fraction)
    statusLabel.text = when (progress.phase) {
        Phase.MATERIALIZING_INPUT -> "Preparing…"
        Phase.COMPRESSING         -> "Compressing…"
        Phase.FINALIZING_OUTPUT   -> "Finishing…"
    }
}
```

If you ignore the parameter (`onProgress = {}` or omitted entirely), nothing changes.

---

## Reference

- `kompressor/src/commonMain/kotlin/co/crackn/kompressor/io/MediaSource.kt` — sealed hierarchy
- `kompressor/src/commonMain/kotlin/co/crackn/kompressor/io/MediaDestination.kt` — sealed hierarchy
- `kompressor/src/commonMain/kotlin/co/crackn/kompressor/io/CompressionProgress.kt` — phase enum + invariants
- `kompressor/src/androidMain/kotlin/co/crackn/kompressor/io/AndroidMediaSources.kt` — `MediaSource.of(Uri)`
- `kompressor/src/androidMain/kotlin/co/crackn/kompressor/io/AndroidMediaDestinations.kt` — `MediaDestination.of(Uri)`, `MediaDestination.of(OutputStream)`
- `kompressor/src/androidMain/kotlin/co/crackn/kompressor/io/MediaStoreOutputStrategy.kt` — `IS_PENDING` flow
- `kompressor/src/iosMain/kotlin/co/crackn/kompressor/io/IosMediaSources.kt` — `MediaSource.of(NSURL/PHAsset/NSData/NSInputStream)`
- `kompressor/src/iosMain/kotlin/co/crackn/kompressor/io/IosMediaDestinations.kt` — `MediaDestination.of(NSURL/NSOutputStream)`
- `kompressor/src/iosMain/kotlin/co/crackn/kompressor/io/PHAssetResolver.kt` — iCloud + local resolution

[okio]: https://square.github.io/okio/
