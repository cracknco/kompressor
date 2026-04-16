# Audio Downmix Matrices

Reference for the multichannel downmix coefficients implemented in
`co.crackn.kompressor.audio.surroundDownmixMatrix` (Android) and consumed by Media3's
`ChannelMixingAudioProcessor`. iOS does not currently downmix in software — it relies on
AVFoundation's built-in renderer matrix when a 7.1/5.1 source is encoded to a lower channel
count.

This document is the source of truth for **what coefficients we ship** and **why each one
diverges from the strict ITU-R BS.775-3 reference**. The host-side test
`Bs775DownmixMatrixTest` enforces every claim here at build time: any drift between the
implementation and a coefficient listed below — divergent or not — fails CI.

## Channel layout

7.1 input follows the ISO/IEC 23001-8 `Mpeg7_1_C` order used by Media3's PCM pipeline:

| Index | Channel | Abbreviation |
|------:|---------|--------------|
| 0 | Front Left | FL |
| 1 | Front Right | FR |
| 2 | Front Center | FC |
| 3 | Low-Frequency Effects | LFE |
| 4 | Back Left | BL |
| 5 | Back Right | BR |
| 6 | Side Left | SL |
| 7 | Side Right | SR |

5.1 output drops `BL`/`BR` and renames `BL`/`BR` to `Ls`/`Rs`, consistent with Media3's
`AudioFormat.CHANNEL_OUT_5POINT1` mask. Stereo and mono outputs use the obvious 2/1 channel
counts.

## Tolerance

`Bs775DownmixMatrixTest` enforces **±0.01** on each coefficient against the reference values
in this document, except at positions explicitly listed under "Intentional divergences" —
those are pinned exactly. The ±0.01 envelope absorbs the floating-point spread between the
implementation's truncated `0.7071f` literal and the exact `1/√2 ≈ 0.70710678` used in the
reference (≈ 6.8 × 10⁻⁶ per coefficient).

---

## 7.1 → Stereo

### Implementation

```
L = 1.000·FL + 0.707·FC + 0.500·LFE + 0.707·BL + 0.707·SL
R = 1.000·FR + 0.707·FC + 0.500·LFE + 0.707·BR + 0.707·SR
```

### ITU-R BS.775-3 reference (chained 7.1 → 5.1 → 2.0)

BS.775-3 does not specify a single-step 7.1 → 2.0 formula. The reference here is derived by
applying §3.5 (7.1 → 5.1, each surround folded at 1/√2) followed by the §3 5.1 → 2.0
default (FC and the surround pair attenuated by 1/√2):

```
L_ref = 1.000·FL + 0.707·FC + 0.000·LFE + 0.500·BL + 0.500·SL
R_ref = 1.000·FR + 0.707·FC + 0.000·LFE + 0.500·BR + 0.500·SR
```

### Dolby reference (ATSC A/52 §7.8 / E-AC-3)

AC-3 / E-AC-3 publish stereo downmix coefficients via the bitstream metadata fields
`cmixlev` (center mix level), `surmixlev` (surround mix level) and `dmixmod` (downmix
mode). With default metadata (`cmixlev = 0.707`, `surmixlev = 0.707`, LFE excluded), the
Lo/Ro downmix from a 5.1 source matches BS.775-3 §3 exactly:

```
L_dolby = 1.000·FL + 0.707·FC + 0.000·LFE + 0.707·Ls
R_dolby = 1.000·FR + 0.707·FC + 0.000·LFE + 0.707·Rs
```

For 7.1 input, E-AC-3 chains the Annex E §6.1.12 7.1 → 5.1 fold (`Ls = (1/√2)·BL +
(1/√2)·SL`) before applying the formula above, giving:

```
L_dolby_chained = 1.000·FL + 0.707·FC + 0.000·LFE + 0.500·BL + 0.500·SL
```

i.e. identical to the BS.775-3 chained reference.

### Intentional divergences

| Position | Impl | Ref | Rationale |
|----------|-----:|----:|-----------|
| `L ← LFE` | 0.500 | 0.000 | LFE folded into stereo at ≈ −6 dB so devices without subwoofer reproduction (laptop speakers, headphones) preserve low-end energy. Matches Dolby Pro Logic II's "include LFE" mode and ATSC A/52's `lfemixlevcod` "+0 dB" option. |
| `R ← LFE` | 0.500 | 0.000 | Symmetric to L. |
| `L ← BL` | 0.707 | 0.500 | Treats each 7.1 surround as an independent BS.775 §3 surround channel (single-step downmix) instead of chaining through 5.1. Preserves surround energy when the 7.1 source has independent BL/SL content; over-attenuates by ≈ +3 dB on chained material. |
| `L ← SL` | 0.707 | 0.500 | Symmetric to BL. |
| `R ← BR` | 0.707 | 0.500 | Symmetric to BL→L. |
| `R ← SR` | 0.707 | 0.500 | Symmetric to SL→L. |

**Net effect**: stereo output is up to +3 dB louder than a strict BS.775-3 chained downmix
when both back- and side-surround channels carry signal, and includes LFE at −6 dB. We
accept this trade-off because most Kompressor outputs target consumer playback (laptop
speakers, headphones, stereo mobile bars) where strict spec compliance comes at the cost
of perceived loudness and bass impact.

---

## 7.1 → 5.1

### Implementation

```
FL_5.1  = 1.000·FL
FR_5.1  = 1.000·FR
FC_5.1  = 1.000·FC
LFE_5.1 = 1.000·LFE
Ls_5.1  = 1.000·BL + 0.707·SL
Rs_5.1  = 1.000·BR + 0.707·SR
```

### ITU-R BS.775-3 §3.5 reference

```
FL_ref  = 1.000·FL
FR_ref  = 1.000·FR
FC_ref  = 1.000·FC
LFE_ref = 1.000·LFE
Ls_ref  = 0.707·BL + 0.707·SL
Rs_ref  = 0.707·BR + 0.707·SR
```

### Dolby / E-AC-3 (Annex E §6.1.12)

E-AC-3 specifies the same 1/√2 fold for both BL and SL:

```
Ls_dolby = 0.707·BL + 0.707·SL
Rs_dolby = 0.707·BR + 0.707·SR
```

Identical to BS.775-3.

### Intentional divergences

| Position | Impl | Ref | Rationale |
|----------|-----:|----:|-----------|
| `Ls ← BL` | 1.000 | 0.707 | Back-left passes through at unit gain so 7.1 sources whose surround content lives entirely in BL/BR (common for older 7.1 mixes that don't use SL/SR) don't lose ≈ 3 dB of surround loudness on the 5.1 output. |
| `Rs ← BR` | 1.000 | 0.707 | Symmetric to BL→Ls. |

The side-surround coefficients (`Ls ← SL`, `Rs ← SR`) match the BS.775-3 1/√2 fold within
tolerance.

**Net effect**: when the 7.1 source uses BL/BR only, our 5.1 output carries the full
back-channel energy. When SL/SR also carry signal, the combined Ls level is
`1.0·BL + 0.707·SL`, peaking ≈ +1.4 dB above the BS.775 chained reference.

### A note on THX

The original CRA-13 DoD framed this as a "BS.775-3 vs Dolby/THX matrices" comparison.
We were unable to cite a public THX downmix specification: THX's multichannel rendering is
a proprietary certification regime rather than a published coefficient table, so there is
no standard to pin against. The Dolby / E-AC-3 coefficients above (from ATSC A/52, which is
public) are the only industry reference this doc cites alongside BS.775-3; any statement
about what a specific THX-certified renderer does would be unsourced. If a citable THX
coefficient table becomes available, extend the divergence tables above with a "THX ref"
column and add it to [`Bs775ReferenceFixture`] as an additional pinned matrix.

---

## 7.1 → Mono

`surroundDownmixMatrix(8, 1)` ships a coefficient set for completeness but the test does
**not** assert mono conformance against a BS.775 reference: BS.775-3 does not specify
7.1 → mono (or 5.1 → mono), and the chained interpretation gives different values
depending on whether the intermediate stereo step uses additive or constant-power
summation. The implementation matrix is pinned exactly by `SurroundChannelMixingTest`
instead. Documented for parity, not for spec conformance:

```
M = 0.707·FL + 0.707·FR + 1.000·FC + 0.707·LFE + 0.500·BL + 0.500·BR + 0.500·SL + 0.500·SR
```

---

## References

- **ITU-R BS.775-3** — *Multichannel stereophonic sound system with and without
  accompanying picture* (2012). The 7.1 → 5.1 §3.5 fold and 5.1 → 2.0 §3 default
  coefficients are the basis for the "reference" matrices in `Bs775ReferenceFixture`.
- **ATSC A/52:2018** — *Digital Audio Compression (AC-3, E-AC-3) Standard*. §7.8
  documents the AC-3 stereo downmix; Annex E §6.1.12 documents the E-AC-3 7.1 → 5.1
  fold. Used as the "Dolby reference" rows above.
- **Media3 1.10** — `androidx.media3.common.audio.ChannelMixingMatrix.createForConstantPower`
  ships the BS.775-equivalent coefficients for inputs 1..6 → {1, 2}; we hand-roll only the
  combinations Media3 doesn't cover (8 → {1, 2, 6}).
- **Implementation**: `kompressor/src/androidMain/kotlin/co/crackn/kompressor/audio/AudioProcessorPlan.kt`
- **Tests**: `Bs775DownmixMatrixTest` (BS.775-3 conformance), `SurroundChannelMixingTest`
  (impl-coefficient pinning), `SurroundAudioTest` (Android device round-trip),
  `Surround71RoundTripTest` (iOS device round-trip).
