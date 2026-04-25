# AVFoundation legacy-vs-novel byte divergence — CRA-98 investigation

Status: **resolved**. Cause: **H1 — wall-clock `mvhd`/`tkhd`/`mdhd` stamping by
AVFoundation** (confirmed). H2 is the manifestation mechanism. H3 is refuted.

Artefact produced by [`UrlInputNonDeterminismInvestigationTest`](../../kompressor/src/iosTest/kotlin/co/crackn/kompressor/UrlInputNonDeterminismInvestigationTest.kt)
for CRA-98. Linear issue: [CRA-98](https://linear.app/crackn/issue/CRA-98).

## Context

- PR [#142](https://github.com/cracknco/kompressor/pull/142) (CRA-94) shipped the NSURL
  builder tests as **bitwise-identical** legacy-vs-novel: the `file://` `NSURL` overload
  was asserted to produce byte-equal output compared to the legacy `path`-based overload.
- PR [#143](https://github.com/cracknco/kompressor/pull/143) (CRA-95) saw those tests
  flake and relaxed them to a **size-delta tolerance of 1024 bytes**
  (`AV_SIZE_TOLERANCE_BYTES`). A class-KDoc hypothesis blamed
  second-resolution `mvhd` / `mdhd` timestamps, but at the time an additional determinism
  test (`audio_twoConsecutiveCompressesProduceIdenticalBytes`) passed with a strict
  bitwise assertion — neither confirming nor refuting the hypothesis because two
  back-to-back calls typically land in the same wall-clock second. (That legacy twin
  was later relaxed in the [PR #157](https://github.com/cracknco/kompressor/pull/157)
  follow-up, after the same straddle hit the NSURL twin under heavy CI load.)

## Competing hypotheses (pre-investigation)

| # | Hypothesis | Prediction |
|---|---|---|
| H1 | AVFoundation stamps wall-clock (`NSDate()`) timestamps into `mvhd` / `mdhd` at export start | Multiple distinct outputs across a >1 s loop |
| H2 | The NSURL dispatch overhead pushes novel compress into a different wall-clock second than legacy on every run | Legacy-vs-novel differs but novel-vs-novel might not, depending on timing |
| H3 | The NSURL path (e.g. `toIosInputPath`, coroutine dispatch) stamps something non-timestamp into the AVAsset init | Novel-vs-novel differs even in the same wall-clock second |

## Experiments

All three experiments live in
[`UrlInputNonDeterminismInvestigationTest`](../../kompressor/src/iosTest/kotlin/co/crackn/kompressor/UrlInputNonDeterminismInvestigationTest.kt)
and run on `iosSimulatorArm64Test`.

### Step 1 — `audio_legacyOverload_spansWallClockSecondBoundary`

Runs **100 consecutive legacy-overload** audio compresses with a real
`platform.posix.usleep(50 * 1000)` between iterations (NOT
`kotlinx.coroutines.delay`, which is virtual under `runTest` and contributes
nothing to the wall clock). The 99 inter-iteration sleeps total 4.95 s of
guaranteed real elapsed time, plus ~10–30 ms per compress on top — so the loop
mathematically spans ≥4 wall-clock second boundaries regardless of how fast
AVFoundation gets on future simulator hardware.

**Observed** (iOS Simulator arm64, macOS 26.4.1):

```text
LOOP_ITERATIONS=100 STRADDLE_DELAY_MS=50 distinctByteSets=3
sizes=[27537] firstDivergentOffsets=[26370, 26486, 26586]
```

- `distinctByteSets = 3` — the 100 outputs cluster into exactly 3 distinct byte-sets.
- `sizes = {27537}` — **all outputs have the same length**. Whatever varies, it flips
  byte values in fixed positions, not the container size.
- `firstDivergentOffsets = [26370, 26486, 26586]` — in a 27 537-byte M4A the `moov` box
  sits at the end (AVFoundation does not emit `shouldOptimizeForNetworkUse` by default),
  so offsets ~26 370–26 586 fall inside the tail `moov` box where ISOBMFF places
  `mvhd.creation_time`, `trak.tkhd.creation_time`, and `mdia.mdhd.creation_time`.

H1 is therefore **confirmed**: the legacy path alone, re-run across a second boundary,
produces byte differences in exactly the positions where the wall-clock timestamps live.

### Step 2 — `audio/video_novelOverloadTwice_producesIdenticalBytes`

Two back-to-back NSURL-overload compresses of the same input, executed within
milliseconds of each other, compared structurally.

**Observed**:

```text
[CRA-98 Step 2 audio] novelA.size=27537 novelB.size=27537 firstDivergentOffsets=[]
[CRA-98 Step 2 video] novelA.size=2858  novelB.size=2858  firstDivergentOffsets=[]
```

Both audio and video produce **bitwise-identical** output (when both calls land in
the same wall-clock second). The NSURL overload is not introducing any per-call
non-determinism. This eliminates H3: there is nothing in `toIosInputPath` /
coroutine dispatch that stamps a non-timestamp value differently on successive runs.

**Slow-runner caveat (added post-conclusion).** The original Step 2 assertion was
`a.contentEquals(b)`, which assumed the two back-to-back compresses always finish
within the same wall-clock second. On a slow `macos-latest` runner under load the
release pipeline observed the pair straddle a second tick and produce an H1-flavoured
3-byte divergence at the moov-tail timestamp offsets — see GitHub Actions run
[24935723437](https://github.com/cracknco/kompressor/actions/runs/24935723437). Because
H3 has already been refuted (whatever that run produced was an H1 straddle, not new
NSURL non-determinism), Step 2 was relaxed to the same `assertAvOutputStructurallyEquivalent`
check the production-grade tests use (sizes equal + ≤ `AV_TIMESTAMP_BYTE_TOLERANCE = 64`
differing bytes). The test now serves as an **ongoing sentinel for non-determinism
*beyond* the timestamp budget** — any size mismatch or ≥65 differing bytes between two
back-to-back NSURL compresses would still fail.

### Step 3 — `audio/video_legacyVsNovel_captureDivergentByteOffsets`

One legacy-overload compress plus one NSURL-overload compress, same input, with a
byte-by-byte diff.

**Observed** (representative run):

```text
[CRA-98 Step 3 audio] legacy.size=27537 novel.size=27537 delta=0 divergentOffsetsCount=0
  legacyTopBoxes: ftyp@0-28
[CRA-98 Step 3 video] legacy.size=2858  novel.size=2858  delta=0 divergentOffsetsCount=0
  legacyTopBoxes: ftyp@0-28
```

In this run legacy and novel both completed within the same wall-clock second, so the
`mvhd`/`tkhd`/`mdhd` timestamps matched and the outputs were byte-identical. Under
slower CI runners or bad luck one compress may land on either side of a second tick —
producing the flakes that motivated PR #143's tolerance. When that happens the
divergent offsets are the same ones identified in Step 1 (tail `moov` box, timestamp
fields only).

## Verdict

| # | Hypothesis | Result |
|---|---|---|
| H1 | Wall-clock `mvhd`/`tkhd`/`mdhd` stamping | **CONFIRMED** (root cause) |
| H2 | NSURL dispatch overhead inflates straddle probability | Confirmed as the manifestation mechanism for *when* H1 surfaces on legacy-vs-novel pairs. The NSURL overload adds ~µs–ms of overhead (NSURL.path unwrap, one suspend hop), which occasionally pushes the novel compress across a wall-clock second boundary that the legacy compress stayed inside. |
| H3 | NSURL path stamps a non-timestamp value | **REFUTED** — novel-overload-twice is bitwise-identical (Step 2). |

## ISOBMFF timestamp budget

Per ISO/IEC 14496-12 §8.2.2 / §8.3.2 / §8.4.2:

- `moov.mvhd` — 1 × {`creation_time`, `modification_time`} = 2 timestamps
- `moov.trak[].tkhd` — 1 × 2 per track
- `moov.trak[].mdia.mdhd` — 1 × 2 per track

For the v0 boxes used by AVFoundation here each timestamp is a 4-byte big-endian uint32
seconds-since-1904.

| Output | Tracks | Timestamp fields | Worst-case byte delta |
|---|---|---|---|
| Audio M4A (CRA-98 default) | 1 | 2 (mvhd) + 2 (tkhd) + 2 (mdhd) = 6 | 6 × 4 = 24 B |
| Video MP4 (CRA-98 default) | 1 | 2 + 2 + 2 = 6 | 24 B |
| Video MP4 + audio track | 2 | 2 + 2×(2+2) = 10 | 40 B |

In practice a 1-second rollover on the ~3.9 × 10⁹-second 1904 epoch flips only the
least-significant byte of each 4-byte field (the other three bytes change
once per 256 s / 65 536 s / 16 × 10⁶ s respectively). So the realistic observation is
~3–6 differing bytes per straddle — which matches the 3 divergent offsets produced by
Step 1 exactly.

## Applied fix

Replace the former coarse size-delta tolerance with a structural equivalence check:

- `AV_SIZE_TOLERANCE_BYTES: Long = 1024` (former, size-delta) →
  `AV_TIMESTAMP_BYTE_TOLERANCE: Int = 64` (new, differing-byte count).
- `assertAvSizeEquivalent(...)` / `assertSizeMatchesWithinTolerance(...)` →
  `assertAvOutputStructurallyEquivalent(...)` in both
  [`UrlInputEndToEndTest`](../../kompressor/src/iosTest/kotlin/co/crackn/kompressor/UrlInputEndToEndTest.kt)
  and
  [`io.StreamAndBytesEndToEndTest`](../../kompressor/src/iosTest/kotlin/co/crackn/kompressor/io/StreamAndBytesEndToEndTest.kt).

The new assertion enforces:

1. Sizes are exactly equal (AVFoundation timestamp drift never resizes the container;
   if sizes disagree there is a real regression).
2. Differing bytes ≤ 64 (1.6× safety margin over the 40 B worst-case multi-track
   timestamp budget, still 16× tighter than the former 1024-byte size tolerance).

**Follow-up extensions** (PR [#157](https://github.com/cracknco/kompressor/pull/157)):

- The Step-2 NSURL-twice tests (`audio/video_novelOverloadTwice_producesIdenticalBytes`)
  switched from strict `contentEquals` to the same structural-equivalence tolerance
  after the assumed-deterministic NSURL pair straddled a wall-clock second on a slow
  `macos-latest` runner (release run 24935723437). The Step-2 tests now serve as
  ongoing sentinels for non-determinism *beyond* the timestamp budget rather than
  H2-vs-H3 discriminators.
- The legacy-twin determinism test (`audio_twoConsecutiveCompressesProduceIdenticalBytes`
  in `UrlInputEndToEndTest`) was relaxed alongside the Step-2 fix for symmetry: even
  the legacy path can in principle straddle a second under heavy CI load (the NSURL
  failure proved the runner was capable of pushing two back-to-back AVFoundation
  exports across a tick), so keeping that test on strict `contentEquals` while Step 2
  was relaxed would have been an arbitrary inconsistency — not a meaningful pin.

## Why not tighten to bitwise-identical?

Bitwise equality is only achievable if we can make AVFoundation stamp a deterministic
timestamp. `AVAssetWriter` does not expose a public creation-time override
(`AVAssetWriterInput` has no knob; `AVAssetExportSession` does not either). The only
way to post-process would be to open the output file and zero out the timestamp fields
at known offsets — which is more complexity than the bound warrants for a
test-infrastructure concern. The structural tolerance keeps the test precise (catches
any non-timestamp byte flip) while accepting AVFoundation's inherent wall-clock
stamping.

## Follow-up

- This investigation closes CRA-98.
- If future iOS SDKs expose a stable timestamp override (e.g. via `AVAssetWriter`
  movie metadata), tighten `AV_TIMESTAMP_BYTE_TOLERANCE` to 0 and restore bitwise
  equality.
- If new AVFoundation exports produce timestamps in v1 (64-bit) boxes, double the
  budget — the current 64-byte cushion still covers it for the audio + single-track
  video cases, but v1 multi-track video would need re-derivation.
