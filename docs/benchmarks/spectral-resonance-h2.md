# Spectral Resonance H2 Evaluation (Issue #125, gate G2)

**Contract**: `ncm-wave-cognition/1`, Sections 7, 12.3, 13 (H2), 14.2 and 15 (G2)
**Status**: protocol pre-registered; no held-out result exists yet

This document is the pre-registration that contract Section 13 requires: the margin, the gate rule, the fixture
generator parameters, every seed and the arms are committed here **before** the first commit that contains a
held-out result. Changing any value below after results exist requires a new protocol version and is reported.

## Question

Does the `aggregate` of `SpectralResonanceDynamics` separate same-concept from different-concept window pairs better
than the scalar control, `ScalarResonanceMetric` on the dominant-component `FrequencyState` projection (contract
Section 14.2), by at least the pre-registered margin?

A higher score is read as "more likely the same concept". This tests a measured relation between two patterns; it
claims nothing about whether correlation identifies a concept (contract Section 8.5).

## Pre-registered values

| Item | Value |
| --- | --- |
| H2 margin | `0.10` AUC (spectral aggregate minus scalar control) |
| Interval | 95% paired percentile bootstrap, `B = 2000` resamples |
| Repeats | 5 held-out test seeds |
| SNR levels | 20, 10 and 0 dB |
| Generator version | `ncm-concept-fixture-v1` |
| Training split seed | `0x1250_0001` (generated for disjointness and reuse by #127; not read by H2) |
| Threshold-selection split seed | `0x1250_0002` (generated for disjointness and reuse by #127; not read by H2) |
| Test split seeds (repeats 1 to 5) | `0x1250_1001`, `0x1250_1002`, `0x1250_1003`, `0x1250_1004`, `0x1250_1005` |
| Bootstrap seed | `0x1250_B007`, combined with the repeat number `r` as `0x1250_B007 + r` |

### Gate rule

G2 is **go** if and only if, in **each** of the 5 repeats, the lower bound of the 95% paired-bootstrap interval of
`AUC(aggregate) − AUC(scalar)`, computed on the pairs pooled over the three SNR levels, is strictly greater than
`0.10`. Otherwise G2 is **no-go**: spectral resonance stays a diagnostic only, and #127 may proceed without resonance
features (contract Section 15). Per-SNR results and the informational arms are reported but never decide the gate.

## Fixture generator `ncm-concept-fixture-v1` (contract Section 12.3)

- **Hierarchy**: depth 0 `LOW_BAND` (code 1, ordinal 0) and `HIGH_BAND` (code 2, ordinal 1); depth 1 `LOW_PURE`
  (11, ordinal 2) and `LOW_CHORD` (12, ordinal 3) under `LOW_BAND`, `HIGH_PURE` (21, ordinal 4) and `HIGH_CHORD`
  (22, ordinal 5) under `HIGH_BAND`.
- **Window**: `N = 256` samples, `sampleRate = 256` Hz, `SampleUnit.HERTZ`, `t_n = n / 256`.
- **Dominant tone**: `A · cos(2π f t + φ)` with amplitude `A` uniform in `[0.5, 2.0)`, phase `φ` uniform in
  `[0, 2π)`, frequency `f` uniform in `[4, 16)` Hz for `LOW_*` and `[64, 80)` Hz for `HIGH_*` (continuous, so
  spectral leakage is expected, contract Section 4.2).
- **Chord tone** (`*_CHORD` only): `r · A · cos(2π · 1.5 f · t + φ₂)` with `r` uniform in `[0.4, 0.8)` and `φ₂`
  uniform in `[0, 2π)`; at most 120 Hz. The generator rejects any tone at or above the 128 Hz Nyquist limit.
- **Noise**: additive Gaussian, variance `σ² = P / 10^(SNR/10)` with `P` the mean square of the clean window. Gaussian
  values come from Box-Muller over `java.util.Random.nextDouble()` with `StrictMath`, so a seed reproduces on every
  JDK. For one seed, the clean windows are identical at every SNR level; only the noise stream differs.
- **Sequences**: 32 sequences of 64 windows. The first concept of a sequence is uniform over the four leaves; later
  windows follow the fixed first-order transition table below (rows: current leaf; columns: next leaf, in the order
  `LOW_PURE`, `LOW_CHORD`, `HIGH_PURE`, `HIGH_CHORD`).

| Current | `LOW_PURE` | `LOW_CHORD` | `HIGH_PURE` | `HIGH_CHORD` |
| --- | ---: | ---: | ---: | ---: |
| `LOW_PURE` | 0.50 | 0.20 | 0.15 | 0.15 |
| `LOW_CHORD` | 0.20 | 0.50 | 0.15 | 0.15 |
| `HIGH_PURE` | 0.15 | 0.15 | 0.50 | 0.20 |
| `HIGH_CHORD` | 0.15 | 0.15 | 0.20 | 0.50 |

- **Ground truth**: the label of a window is the leaf concept that generated it. The labels of a split are held in a
  guard separate from the waves; the scorer opens it only after every arm has scored. A read before that fails the
  run (leakage check).
- **Splits**: training, threshold-selection and test splits use the disjoint seeds above; the run fails if two
  splits share a seed or an identical window.

## Pairs

For each repeat, the 2,048 test windows are shuffled with a stream derived from the test seed and paired
consecutively: 1,024 **disjoint** pairs, each window in exactly one pair. A pair is **positive** when both windows
have the same leaf label. The same pairing is used at every SNR level, so one pair slot contributes one pair per SNR
level (3,072 pooled pairs per repeat).

The bootstrap resamples **pair slots** with replacement (1,024 per resample, seeded as above); each drawn slot
contributes its pairs at all three SNR levels, and both arms are evaluated on the same resample (paired). The
interval bounds are the order statistics 51 and 1,950 of the 2,000 sorted resample values.

AUC is the Mann-Whitney statistic with midranks for ties: `P(score of a positive > score of a negative) +
0.5 · P(tie)`.

## Arms

| Arm | Score of a pair | Role |
| --- | --- | --- |
| `aggregate` | `SpectralResonanceDynamics.compare(p1, p2).aggregate()` | Gate (system under test) |
| `scalar` | `ScalarResonanceMetric.score(dominant(p1), dominant(p2))` | Gate (control, Section 14.2) |
| `coherence` | `compare(p1, p2).coherence()` | Informational |
| `magnitude` | `Σ m1_k · m2_k / (‖m1‖ · ‖m2‖)` over shared bands (phase ignored) | Informational |
| `aggregate.phase-randomized` | `aggregate` after replacing every non-real bin phase of both patterns with a seeded uniform phase | Informational control: isolates the phase effect |
| `aggregate.frequency-shifted` | `aggregate` with the second pattern's non-real bins `k` moved to `k + 32` (bins past 127 dropped) | Informational control: isolates the frequency effect |

`dominant(p)` is the largest-magnitude component as `FrequencyState(magnitude, locator, phase)`, ties to the lower
bin. Patterns come from `DftWaveDecomposer`, complete and untruncated.

## Command

```bash
./gradlew :monada-neuron-evaluation:runSpectralResonanceH2
```

## Results

Not yet computed. They are added in a later commit, together with the environment fields of contract Section 14.4
and the G2 verdict.
