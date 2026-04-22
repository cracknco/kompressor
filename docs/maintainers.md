# Maintainers — runbooks

Short, actionable runbooks for maintainers. These complement the user-facing
docs (`docs/guides/`, `docs/reference/`) and the architectural docs
(`docs/adr/`, `docs/threading-model.md`).

## Debugging leaks

CRA-50 ships two leak-detection gates: **LeakCanary** on Android
(`androidDeviceTest`) and an **in-process `WeakReference` + `GC.collect`
checker** on iOS (`iosSimulatorArm64Test`, part of the `iOS simulator
tests` job on every PR). A red leak gate means a retained native
resource in the `MediaCodec` / Media3 `Transformer` / `AVAssetWriter` /
`AVAssetExportSession` path — the exact class of bug that produces
consumer OOMs after N compressions.

Cross-ref: the lifecycle contract for every native handle lives in
[`docs/threading-model.md`](threading-model.md). If a leak is real (not
a false positive), the fix almost always lives in the per-call release
chain documented there.

### Android — LeakCanary

**Local reproduction** (emulator or physical device):

```bash
# Boot an emulator first: `emulator -avd Pixel_6_API_34` or plug in a device.
./gradlew :kompressor:connectedAndroidDeviceTest \
  --tests "co.crackn.kompressor.CompressionLeakTest"
```

Each of `imageCompression_50Iterations_hasNoLeaks`,
`audioCompression_50Iterations_hasNoLeaks`, and
`videoCompression_50Iterations_hasNoLeaks` instantiates a compressor 50
times, hands it to `AppWatcher.objectWatcher.expectWeaklyReachable(...)`,
and calls `LeakAssertions.assertNoLeaks()` after the loop. A retained
compressor (or anything it transitively references) fails the test and
prints the full retention chain.

**CI**: Android device tests are manual-dispatch only per
[ADR-002](adr/002-decline-level-3-supply-chain.md). Trigger via
**Actions → Leak tests (on-demand) → Run workflow**. Do this before
every release that touches `kompressor/src/androidMain/` and whenever a
PR reworks the `MediaCodec` / Media3 Transformer / `BitmapFactory`
pipeline.

**Interpreting a leak report**

1. `androidTests/` HTML report lists the retained class and which
   watched reference (`expectWeaklyReachable` label) it was registered
   under — that's the iteration index.
2. The LeakCanary log entry shows the GC root + chain. The chain
   usually looks like:
   `Thread → Handler → Media3 ExoPlayerImpl → MediaCodec → compressor`.
3. Walk backwards from the compressor. The owner along the chain that
   should have released the compressor is the leak site — often a
   listener or a `Handler`-posted runnable captured in a callback
   lambda that outlives the `suspend` body.
4. Fix, then re-run. LeakCanary does not suppress transient retained
   instances, so a passing run is a hard signal.

**Common false-positive sources** (and why they are real bugs anyway):

- Static `ExoPlayer` instances held by Media3 internal caches —
  Transformer 1.10 releases its internal pool on `.release()`. If the
  chain shows `RenderersFactory` retained, the fix is to audit our
  `Transformer.release()` call site — not to suppress the detection.
- A `kotlinx.coroutines` Job still alive past `compress()` return —
  indicates we leaked a launched child without cancelling it. See
  `docs/threading-model.md § Cancellation`.

### iOS — in-process leak checker (`WeakReference` + `GC.collect`)

**Local reproduction** (macOS with Xcode installed):

```bash
./gradlew :kompressor:iosSimulatorArm64Test --tests '*IosCompressionLeakTest*'
```

The three tests run 50 compression iterations each (image / audio /
video) inside the normal `iosSimulatorArm64Test` binary. Every
compressor instance is wrapped in a `kotlin.native.ref.WeakReference`
before use; once the iteration's stack frame pops, three
`kotlin.native.runtime.GC.collect()` passes (interleaved with
`yield()` so `AVAssetExportSession` / `AVAssetWriter` completion
handlers queued on `dispatch_async` can drain between sweeps) force
the mark-and-sweep and clear any ref whose target is unreachable. Any
non-null ref after those three passes fails the test with the leaked
instance count and index — that's the "CI red" signal. Pass count is
exposed as the `GC_PASSES` constant in `IosCompressionLeakTest.kt`;
change it in one place if the empirical minimum ever drifts.

This is the iOS sibling of Android's `CompressionLeakTest`, which uses
LeakCanary's `AppWatcher.expectWeaklyReachable` + `assertNoLeaks` —
same logical contract (retention zeroed after 50 iterations), same
catch surface (retained compressor → dangling Media3 / AVFoundation
native handle → consumer OOM after N compressions).

**CI**: runs on every PR as part of the `iOS simulator tests` job in
`.github/workflows/pr.yml` (same gradle task that runs every other
`iosTest/`). No dedicated workflow, no script, no external tool —
the check is just one more `@Test` among the rest.

**Why not `xctrace record --template Leaks`?** — That was the previous
approach (up to commit `a7dc45c`). It depended on Instruments'
memory-recording services (`VMUTaskMemoryScanner`, `libmalloc`,
`vmmap`) which are initialised asynchronously after `xcrun simctl
boot`. On `macos-15-arm64` GitHub runners the init race produced
recurrent `"Allocations: This device is lacking a required recording
service"` flakes (see CRA-91 review thread on PR #136). Replacing
xctrace with an in-process check removes the service dependency, and
the flake class, entirely.

**Interpreting a failure**

The test throws with a message like:

```
Leaked 1/50 IosImageCompressor instances after 3 GC passes.
First leaked indices: #42.
```

There is no automatic retention-chain capture (Kotlin/Native's runtime
doesn't ship a Shark-equivalent). To debug:

1. Re-run the failing test locally with the simulator attached to
   Xcode. Instruments.app → "Leaks" template → attach to the test
   binary → reproduce the leak. The Responsible Library + Invert Call
   Tree flow is identical to the xctrace workflow it replaced.
2. Cross-check with [`docs/threading-model.md § iOS`](threading-model.md):
   every `AVAssetWriter` / `AVAssetReader` / `AVAssetExportSession`
   needs its teardown call in a `finally` path, and every K/N closure
   capturing the compressor needs to exit scope before the 50-loop
   ends.

**Common false-positive sources**:

- A lambda captured inside `AVFoundation` completion handler holding
  an implicit `this` reference — K/N closures promote captured locals
  to heap cells, which the GC won't collect while the completion
  handler registration lives on in CF/ObjC internals. Fix is to null
  out the closure reference inside the completion handler before
  returning.
- Singleton test fixtures that lazily retain the compressor via
  callback registration. Rare, but the symptom is "all 50 iterations
  leak" (a constant, not accumulating). Audit `TestConstants` /
  `testutil/` for global state holding compressor-typed slots.

### When the gate is flaky

An in-process leak checker cannot flake by design — no external
services, no IPC, no timing race with a runner image. If this test
fails, it is catching a real retention. Do NOT re-run blindly.

1. Reproduce locally on any macOS with Xcode installed:
   `./gradlew :kompressor:iosSimulatorArm64Test --tests '*IosCompressionLeakTest*'`.
2. If it reproduces locally → real leak, investigate the compressor
   you're touching. The message names the modality + iteration index
   that retained.
3. If it does NOT reproduce locally (suggesting runner-specific state) →
   capture the full Gradle test report (`kompressor/build/reports/tests/iosSimulatorArm64Test/`)
   and file a `test-flake` Linear issue with the runner image version
   (`macos-*-arm64/<build>`). Still do NOT disable the test.

The Android side via LeakCanary follows the same no-retry policy.

## Other runbooks

This file grows as runbooks are added. Current sections:

- [Debugging leaks](#debugging-leaks)
