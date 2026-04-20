# Maintainers — runbooks

Short, actionable runbooks for maintainers. These complement the user-facing
docs (`docs/guides/`, `docs/reference/`) and the architectural docs
(`docs/adr/`, `docs/threading-model.md`).

## Debugging leaks

CRA-50 ships two leak-detection gates: **LeakCanary** on Android
(`androidDeviceTest`) and **xctrace Leaks** on iOS (PR CI). A red leak
gate means a retained native resource in the `MediaCodec` / Media3
`Transformer` / `AVAssetWriter` / `AVAssetExportSession` path — the
exact class of bug that produces consumer OOMs after N compressions.

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

### iOS — xctrace Leaks

**Local reproduction** (macOS with Xcode installed):

```bash
./scripts/ios-leak-test.sh
```

The script builds `test.kexe`, boots (or reuses) an iPhone simulator,
runs the test binary scoped to `co.crackn.kompressor.IosCompressionLeakTest.*`
under `xctrace record --template Leaks`, exports the `leaks` table to
XML, and counts the rows. Non-zero = exit 1. Artefacts land under
`kompressor/build/reports/leaks/`:

- `IosCompressionLeakTest.trace` — open in Instruments.app for the
  interactive allocation graph.
- `leaks.xml` — textual row-by-row leak listing. Start here to get a
  count + leaked-object class names.

**CI**: runs on every PR as the `iOS leak tests` job in
`.github/workflows/pr.yml`. The trace artefact is uploaded even on
green runs so a reviewer can inspect the allocation baseline.

**Interpreting a trace**

1. Open `IosCompressionLeakTest.trace` in Instruments. The top pane is
   the per-second leak-count timeline — look for a monotonically rising
   line, which signals a per-iteration leak (vs. a one-shot leak at
   init).
2. Select a leak row. The **Responsible Library** column tells you
   whether the leak is ours (`Kompressor.framework` / the test binary)
   or an OS framework (`AVFoundation.framework` — usually not our bug
   unless we misuse the API).
3. Use **Call Tree → Invert Call Tree** to surface the allocation
   backtrace. Our frames are namespaced with `co.crackn.kompressor`.
4. Cross-check with
   [`docs/threading-model.md § iOS`](threading-model.md): every
   `AVAssetWriter` / `AVAssetReader` / `AVAssetExportSession` needs its
   teardown call in a `finally` / `defer` path.

**Common false-positive sources**:

- First-launch AVFoundation initialisation allocates a handful of one-
  shot singletons (codec tables, track readers). These appear as
  leaks on the first iteration only and do not grow — Instruments'
  baseline delta mode hides them. If the count is stable across all
  50 iterations, the gate is green.
- CoreFoundation object-graph retain cycles from `block`-captured
  `self` references in Obj-C callbacks. Our iOS code is pure
  Kotlin/Native, so this shouldn't happen — but if it does, the
  retain chain in Instruments will name the offending block.

### When the gate is flaky

A leak that appears once and disappears on retry is rarely a true
flake — it is almost always a GC / finaliser timing artefact where the
test asserts *before* the JVM or ObjC runtime has collected a
still-reachable-but-about-to-be-collected reference. LeakCanary
already runs multiple GC passes before failing, and xctrace triggers
its leak pass at process exit (when no runtime references remain).
If you see a flake:

1. Re-run **once**. A genuinely flaky leak (timing-dependent) will
   reproduce at >50 % rate on a loop.
2. If it doesn't reproduce, capture the trace / LeakCanary log and
   file a `test-flake` Linear issue — do **not** disable or relax the
   assertion.
3. If it reproduces intermittently, treat it as a real leak and
   investigate. Intermittent native leaks are the hardest consumer-
   visible bugs to diagnose.

## Other runbooks

This file grows as runbooks are added. Current sections:

- [Debugging leaks](#debugging-leaks)
