# Audio Downmix Matrices

Reference for the multichannel downmix coefficients implemented in
`co.crackn.kompressor.audio.surroundDownmixMatrix` (Android) and consumed by Media3's
`ChannelMixingAudioProcessor`. iOS does not currently downmix in software — it relies on
AVFoundation's built-in renderer matrix when a 7.1/5.1 source is encoded to a lower channel
count.

## Channel layout

7.1 input follows the ISO/IEC 23001-8 `Mpeg7_1_C` order used by Media3's PCM pipeline:
`FL, FR, FC, LFE, BL, BR, SL, SR`. 5.1 output drops `SL`/`SR` and renames `BL`/`BR` to
`Ls`/`Rs`, consistent with Media3's `AudioFormat.CHANNEL_OUT_5POINT1` mask.

## 7.1 → Stereo

```
L = 1.000·FL + 0.707·FC + 0.500·LFE + 0.707·BL + 0.707·SL
R = 1.000·FR + 0.707·FC + 0.500·LFE + 0.707·BR + 0.707·SR
```

Derived from ITU-R BS.775-3 with two deliberate changes for consumer playback:
LFE folded in at −6 dB (so laptop speakers / headphones keep low-end energy), and each
7.1 surround treated as an independent BS.775 surround channel (preserves energy when
BL/SL carry distinct content; over-attenuates by ~3 dB on chained material).

## 7.1 → 5.1

```
FL_5.1  = 1.000·FL            Ls_5.1  = 1.000·BL + 0.707·SL
FR_5.1  = 1.000·FR            Rs_5.1  = 1.000·BR + 0.707·SR
FC_5.1  = 1.000·FC            LFE_5.1 = 1.000·LFE
```

Back channels pass through at unit gain so older 7.1 mixes whose surround lives entirely
in BL/BR don't lose ~3 dB on the 5.1 output.

## 7.1 → Mono

```
M = 0.707·FL + 0.707·FR + 1.000·FC + 0.707·LFE + 0.500·BL + 0.500·BR + 0.500·SL + 0.500·SR
```

## References

- **Implementation**: `kompressor/src/androidMain/kotlin/co/crackn/kompressor/audio/AudioProcessorPlan.kt`
- **Media3 1.10** `ChannelMixingMatrix.createForConstantPower` ships the standard
  coefficients for inputs 1..6 → {1, 2}; we hand-roll only the 8 → {1, 2, 6} combinations
  Media3 doesn't cover.
