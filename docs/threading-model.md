# Threading Model

Kompressor's public API is **stateless and thread-safe**. This document is the
single source of truth for what that means in practice, which tests exercise
which guarantees, and where the platform boundaries lie.

Cross-referenced from the M5 architecture docs.

## Public API contract

Every public compressor interface guarantees:

- **Stateless instances.** No mutable fields hold cross-call state. An
  instance can be created once and reused indefinitely.
- **Coroutine-concurrent `compress()` safe.** Two or more coroutines in the
  same process calling `compress()` on the same instance do not interfere —
  provided each call writes to a **distinct output path**. Concurrent calls
  that share an output path are undefined (partial file, `EncodingFailed`,
  or one writer silently wins the race).

Inter-process / cross-subprocess scenarios are **out of scope** — mobile apps
do not spawn subprocesses to compress media. See [§ Test coverage matrix](#test-coverage-matrix).

Cancellation is via structured concurrency: cancel the calling coroutine
scope to abort. There is no blocking `cancel()` method on the instance
itself, which eliminates a whole class of cross-thread state races.

## Init lifecycle

### Android

`KompressorContext` ([`KompressorInitializer.kt`](../kompressor/src/androidMain/kotlin/co/crackn/kompressor/KompressorInitializer.kt))
captures the application `Context` via AndroidX App Startup. Init is
idempotent: backed by `AtomicReference<Context?>` with a
`compareAndSet(null, appCtx)` guard so exactly one initialisation wins,
re-init attempts are no-ops (no rebind, no throw).

### iOS

No init. `createKompressor()` returns a fresh `IosKompressor` instance each
call. AVFoundation is auto-initialised by the OS on first use.

## Per-platform concurrency details

### Android

| Component | Lifetime / scope | Concurrency notes |
| --- | --- | --- |
| `Media3 Transformer` | Per-`compress()` call | Built, run, and released inside the call. No static/shared state. |
| `MediaCodec` (encoder / decoder) | Per-`Transformer` instance | Per-process resource. Android does **not** enforce a cross-process lock on codec acquisition; the OS arbitrates hardware contention by queuing codec configuration, so two processes can each hold a codec concurrently as long as the device has enough instances (`CodecCapabilities.getMaxSupportedInstances()`). |
| `MediaMuxer` | Per-`compress()` call | Per-output-file. No shared state. |
| `BitmapFactory` / `Bitmap.compress` | Per-call | Stateless JNI entry points. |
| `MediaExtractor` / `MediaMetadataRetriever` (probe) | Per-`probe()` call | Released in `finally`; no cached state. |

### iOS

| Component | Lifetime / scope | Concurrency notes |
| --- | --- | --- |
| `AVAssetExportSession` | Per-`compress()` call | Fresh session per call. No shared state. |
| `AVAssetWriter` | Per-`compress()` call | Per-output-URL. No cross-process lock at the framework level. |
| `AVURLAsset` (probe) | Per-`probe()` call | No cached state beyond the call. |
| `UIImage` / Core Graphics (image) | Per-call | Core Graphics contexts are per-thread; Kompressor creates one per call inside `UIGraphicsBeginImageContextWithOptions` / `UIGraphicsEndImageContext`. |

## Test coverage matrix

| Guarantee | Android test | iOS test |
| --- | --- | --- |
| Intra-process, 4 parallel audio coroutines | [`androidDeviceTest/ConcurrentCompressionTest#fourParallelAudioCompressions_allSucceed`](../kompressor/src/androidDeviceTest/kotlin/co/crackn/kompressor/ConcurrentCompressionTest.kt) | [`iosTest/ConcurrentCompressionTest#fourParallelAudioCompressions_allSucceed`](../kompressor/src/iosTest/kotlin/co/crackn/kompressor/ConcurrentCompressionTest.kt) |
| Intra-process, 2 audio + 2 image coroutines | `androidDeviceTest/ConcurrentCompressionTest#mixedAudioAndImageCompressions_allSucceed` | `iosTest/ConcurrentCompressionTest#mixedAudioAndImageCompressions_allSucceed` |
| Intra-process, 16 parallel coroutines (stress) | `androidDeviceTest/ConcurrentCompressionTest#sixteenParallelCoroutines_allSucceed` | `iosTest/ConcurrentCompressionTest#sixteenParallelCoroutines_allSucceed` |

The intra-process coroutine coverage above is the contract Kompressor
promises; inter-process scenarios are not tested (see [§ Public API contract](#public-api-contract)).

## Adding a new compressor — thread-safety checklist

When implementing a new compressor (e.g. a hypothetical `HevcVideoCompressor`):

1. Do not hold mutable state on the instance. Keep `compress()` a pure
   function over its args.
2. Do not hold a file lock, named semaphore, or process-wide mutex inside
   `compress()`. Per-output-path serialisation is the caller's
   responsibility (and is already documented in the public KDoc).
3. Release all platform resources (`MediaCodec`, `Transformer`,
   `AVAssetExportSession`, etc.) inside a `finally` block — leaks on the
   success path are acceptable (GC cleans up); leaks on the error path
   cause cross-test flakiness on device runs.
4. Add a `Thread-safety:` KDoc section to the public interface that mirrors
   the wording on `ImageCompressor`, `VideoCompressor`, `AudioCompressor`.
5. Extend `ConcurrentCompressionTest` (both `androidDeviceTest` and
   `iosTest`) with a new 4-parallel variant for the new compressor.
6. If the new compressor allocates a temp file, make sure the path includes
   a `UUID` or the output file basename — never a static constant shared
   across calls.
