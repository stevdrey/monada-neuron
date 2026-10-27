# Spectral Resonance H2 Evaluation (Issue #125, gate G2)

**Contract**: `ncm-wave-cognition/1`, Sections 7, 12.3, 13 (H2), 14.2 and 15 (G2)
**Status**: protocol pre-registered in commit `054f804`, before any held-out result; results below (G2: **no-go**)

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

## Environment

| Item | Value |
| --- | --- |
| Contract / generator | `ncm-wave-cognition/1`, `ncm-concept-fixture-v1` |
| JDK | OpenJDK 27 (Azul Zulu 27), JMH 1.37 for the cost table |
| JVM flags | `--add-modules=jdk.incubator.vector` (evaluation module default), G1, default heap (3,832 MB max) |
| OS / architecture | Linux 7.2 (Fedora 44), x86_64 |
| CPU | AMD Ryzen 7 7730U with Radeon Graphics, 16 available processors (the evaluation is single-threaded) |
| Neuron commit | `5c56e14` (clean tree; the harness commit, after the pre-registration commit `054f804`) |
| Run | about 15 s wall clock for the full protocol; a rerun gave bit-identical numbers |

## Checks

All five semantic checks pass, so the verdict below can be trusted:

| Check | Observed |
| --- | --- |
| `fixtures.p10-p12-n6` | P10 aggregate and coherence 1; P11 interference −1 and aggregate 0; P12 interference `6.1e-17`, aggregate 0.5 and coherence 1; N6 `SAMPLE_RATE` and `BASIS`; tolerance `1e-12` |
| `splits.disjoint` | 7 splits with distinct seeds; 14,336 windows at 20 dB, none shared across splits |
| `leakage.counterfactual` | repeat 1 re-scored with permuted labels: scores bit-identical, AUC changed (not vacuous) |
| `determinism` | repeat 1 bootstrap re-run with the same seed is bit-identical |
| `leakage.test-labels` | 18 guarded label sets, 0 reads before the scorer opened them |

## Results

### Gate arms (pooled over 20, 10 and 0 dB; 95% paired-bootstrap intervals)

| Repeat | Same-concept pairs | `aggregate` | `scalar` | `aggregate − scalar` | Lower bound > 0.10? |
| ---: | ---: | --- | --- | --- | --- |
| 1 | 264 / 1,024 | 0.514 [0.474, 0.558] | 0.701 [0.663, 0.739] | −0.187 [−0.241, −0.129] | no |
| 2 | 249 / 1,024 | 0.506 [0.463, 0.548] | 0.719 [0.679, 0.756] | −0.213 [−0.270, −0.158] | no |
| 3 | 248 / 1,024 | 0.494 [0.451, 0.537] | 0.732 [0.695, 0.768] | −0.239 [−0.294, −0.180] | no |
| 4 | 247 / 1,024 | 0.502 [0.457, 0.544] | 0.707 [0.666, 0.746] | −0.205 [−0.263, −0.146] | no |
| 5 | 270 / 1,024 | 0.541 [0.499, 0.580] | 0.749 [0.714, 0.782] | −0.208 [−0.257, −0.158] | no |

**G2 verdict: NO-GO.** In every repeat the spectral aggregate is at chance and *below* the scalar control, with the
whole interval of the difference below zero. H2 is refuted. Under contract Section 15, spectral resonance stays a
diagnostic only, and #127 proceeds without resonance features.

### Informational arms (pooled AUC; per SNR level for repeat 1)

These arms were pre-registered as informational and do not change the verdict.

| Arm | Pooled AUC, repeats 1–5 | Repeat 1 at 20 / 10 / 0 dB |
| --- | --- | --- |
| `coherence` | 0.571, 0.563, 0.578, 0.577, 0.580 | 0.596 / 0.590 / 0.559 |
| `magnitude` (phase ignored) | 0.648, 0.641, 0.635, 0.644, 0.647 | 0.810 / 0.744 / 0.625 |
| `aggregate.phase-randomized` | 0.499, 0.491, 0.514, 0.494, 0.514 | 0.454 / 0.531 / 0.510 |
| `aggregate.frequency-shifted` | 0.526, 0.492, 0.503, 0.497, 0.512 | 0.545 / 0.532 / 0.512 |

The full per-repeat, per-SNR tables (including the per-SNR intervals of the gate difference) are written by the
runner to `build/reports/benchmarks/spectral-resonance-h2.{md,json}`.

### Reading the results

- **Why the aggregate fails here.** The aggregate is the normalized *real* part of the cross-spectrum, so it weights
  each shared band by `cos Δφ`. The fixture draws every tone phase uniformly in `[0, 2π)`, independently per window
  (a pre-registered choice), so `cos Δφ` averages to zero over same-concept pairs as well as different-concept pairs.
  The aggregate sits near 0.5 for both, and the phase-randomized control is indistinguishable from it. This is the
  intended semantics of section 7.3 (a phase-sensitive similarity), not a defect: the aggregate measures whether two
  windows add constructively, and windows with unrelated phases do not.
- **Frequency-shifted control.** Moving the second pattern by 32 bins leaves the aggregate near 0.5 too, as
  expected when nothing in phase is shared.
- **What does carry concept information.** The scalar control (0.70–0.75) uses the dominant bin's frequency ratio.
  The phase-blind `magnitude` cosine reaches 0.80–0.81 at 20 dB but falls under the scalar control at 0 dB, where
  noise spreads energy over every bin. Coherence is weakly informative (0.54–0.62 per SNR level). These are observations on one
  fixture, not tested hypotheses. Making a phase-blind spectral similarity the system under test would need a new
  protocol version with its own pre-registered margin; this report does not do that.
- **Not a claim about concepts.** Every number is a property of `ncm-concept-fixture-v1`. Correlation between two
  spectra does not identify a concept, and a host whose encoder phase-locks its windows would see a different
  aggregate.

## Cost of one comparison (JMH)

```bash
./gradlew :monada-neuron-evaluation:jmh -PjmhArgs="-prof gc SpectralResonanceDynamicsBenchmark"
```

`SpectralResonanceDynamicsBenchmark` (`monada.neuron.evaluation.jmh`): average time, 1 fork, 3 warm-up and 5
measurement iterations of 1 s, `-prof gc`. Patterns come from seeded windows (`SplittableRandom(0x5EED_0125)`,
`[-10, 10]`, `sampleRate = N`) and are built once per trial. The measured call is `compare` end to end: the
compatibility check, the peak and norm scans, the merge, and the result (band records, list copy and validation).
`SPARSE_DENSE` keeps every tenth bin of one pattern; `SPARSE_DISJOINT` keeps bins `k ≡ 0` and `k ≡ 5 (mod 10)`, so
no band is shared.

| N | Mixture | Components (first / second) | Shared bands | Time (µs/op) | Allocation (B/op) |
| ---: | --- | ---: | ---: | ---: | ---: |
| 8 | `DENSE` | 5 / 5 | 5 | 0.217 ± 0.001 | 832 |
| 256 | `DENSE` | 129 / 129 | 129 | 5.15 ± 0.13 | 18,192 |
| 4,096 | `DENSE` | 2,049 / 2,049 | 2,049 | 86.7 ± 1.5 | 286,993 |
| 8 | `SPARSE_DENSE` | 1 / 5 | 1 | 0.047 ± 0.001 | 192 |
| 256 | `SPARSE_DENSE` | 13 / 129 | 13 | 1.04 ± 0.01 | 1,952 |
| 4,096 | `SPARSE_DENSE` | 205 / 2,049 | 205 | 17.2 ± 0.5 | 28,832 |
| 8 | `SPARSE_DISJOINT` | 1 / 0 | 0 | 0.012 ± 0.001 | 56 |
| 256 | `SPARSE_DISJOINT` | 13 / 13 | 0 | 0.085 ± 0.005 | 120 |
| 4,096 | `SPARSE_DISJOINT` | 205 / 205 | 0 | 1.11 ± 0.01 | 888 |

- **Time** is linear in the components and dominated by the shared bands: about 42 ns per shared band at
  `N = 4096` (one `StrictMath.cos`, and one `StrictMath.sin` when both phases are defined), and about 2.7 ns per
  component when no band is shared (the two scans and the merge only). At the medium workload (`N = 256`) one dense
  comparison costs about a ninth of one DFT decomposition (44 µs, [wave-decomposer-reference.md](wave-decomposer-reference.md)).
- **Allocation** is O(shared bands) and deterministic: about 140 bytes per shared band. One `BandContribution`
  record is about 56 bytes, plus its slots in the band list and its immutable copy. The rest is the JDK's fdlibm
  argument-reduction buffer inside `StrictMath.cos` and `sin`, as measured for the DFT. With no shared band the
  result is constant size plus the empty list.
- No optimization was made or is justified by these numbers. A later primitive result layout or SIMD backend would
  report its own end-to-end figures against this reference (ADR 0041).

## Limitations

- Pairs share no window, but windows inside one sequence share its transition process, and one pair slot
  contributes correlated pairs at the three SNR levels (resampled together as one cluster). The intervals assume
  slots are independent.
- One fixture family, one machine and one JMH fork. The verdict is binding for G2 under this protocol and claims
  nothing for other encoders.
