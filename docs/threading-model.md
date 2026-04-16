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
- **Process-concurrent `compress()` safe.** The same guarantee extends across
  OS processes: two processes running `createKompressor().image.compress(...)`
  on distinct output paths do not block or corrupt each other. Kompressor
  holds no cross-process lock — no named semaphores, no file locks on shared
  paths, no `/tmp/*` sentinels.

Cancellation is via structured concurrency: cancel the calling coroutine
scope to abort. There is no blocking `cancel()` method on the instance
itself, which eliminates a whole class of cross-thread state races.

## Init lifecycle

### Android

`KompressorContext` ([`KompressorInitializer.kt`](../kompressor/src/androidMain/kotlin/co/crackn/kompressor/KompressorInitializer.kt))
captures the application `Context` via AndroidX App Startup. Init is
idempotent:

- Backed by `AtomicReference<Context?>` + `AtomicInteger` init counter.
- `compareAndSet(null, appCtx)` ensures exactly one initialisation wins even
  under 32-thread thundering-herd contention (covered by
  `KompressorInitializerConcurrencyTest`).
- Re-initialisation attempts are no-ops; they do not rebind or throw.

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
| Idempotent Android init under 32-thread contention | [`androidHostTest/KompressorInitializerConcurrencyTest`](../kompressor/src/androidHostTest/kotlin/co/crackn/kompressor/KompressorInitializerConcurrencyTest.kt) | n/a (iOS has no init) |
| **Inter-process, 4 processes × 4 coroutines (16 parallel) — real subprocesses** | [`androidHostTest/ConcurrentCompressInterProcessTest`](../kompressor/src/androidHostTest/kotlin/co/crackn/kompressor/ConcurrentCompressInterProcessTest.kt) | ⚠ not covered — see next section |

## iOS inter-process coverage — known gap

The ticket DoD (CRA-14) asked for NSTask-based inter-process coverage on iOS.
In practice:

- `NSTask` is **not available on iOS**. It lives in the macOS Foundation SDK
  and is not exposed to iOS apps or to Kotlin/Native's iOS targets.
- `posix_spawn` is technically callable from Kotlin/Native on the simulator,
  but a meaningful child must itself run `AVAssetWriter` — which requires
  compiling and signing a separate Kotlin/Native executable target, bundled
  alongside the simulator test binary and invoked by path.

That build infrastructure is non-trivial and out of scope for CRA-14.

**The gap is intentional, not accidental.** The Android inter-process test
covers the class of regression the ticket cares about: a global lock
introduced in Kompressor's shared commonMain code (e.g. a static
`FileLock`, a named `Semaphore`, a rendezvous file). Such a lock would show
up in any cross-process test, Android or iOS, because the culprit lives in
shared code. The Android-side coverage is therefore sufficient as a
regression guard for the shared layer.

What the iOS gap genuinely misses is an iOS-specific regression introduced
only in `iosMain` platform code (e.g. a static lock around `AVAssetWriter`
in `IosVideoCompressor`). We lift the intra-process bar to 16 concurrent
coroutines as a partial mitigation — a coroutine-dispatcher-level lock at
that scale would be visible — but a true iOS inter-process test remains
future work.

**Follow-up:** a subsequent PR will add a dedicated Kotlin/Native executable
target (`iosSimulatorArm64CompressWorker`), invoke it via `posix_spawn`
from the iOS simulator test, and close this gap.

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
