# Next-Concept G3 Evaluation (Issue #127, gate G3)

**Contract**: `ncm-wave-cognition/1`, Sections 8, 12.3, 13 (H3, H4, H6), 14.2 and 15 (G3)
**Status**: protocol pre-registered in this commit, before any held-out result; results pending

This document is the pre-registration that contract Section 13 requires: the margins, the minimum coverage, the gate
rule, the selection procedures, every seed and the arms are committed here **before** the first commit that contains a
held-out result. Changing any value below after results exist requires a new protocol version and is reported.

## Question

Does the hierarchical `NextConceptPredictor` (control C) predict the concept of the **next** window better than the
no-spectral baseline A on the windows it answers, with enough leaf coverage (H3), is its abstention informative (H6),
and does coarse-to-fine beat a flat decision over the same scores (H4, informational)?

Gate G2 was **no-go** ([spectral-resonance-h2.md](spectral-resonance-h2.md)), so C runs **without resonance
features**: its only spectral feature is the phase-blind, unit-normalized DFT magnitude spectrum (ADR 0043). Concept
candidates are hypotheses; the report claims nothing beyond this fixture (contract Section 8.5).

## Pre-registered values

| Item | Value |
| --- | --- |
| H3 margin | `0.15` leaf accuracy: `acc(C) − acc(A)`, paired on the windows C covers at the leaf depth |
| H3 minimum coverage | `0.60`: C's pooled leaf coverage of the windows that have a next window |
| H6 margin | `0.05` leaf accuracy: `acc(C on covered) − acc(C forced top-1 on all windows)` |
| H4 margin (informational) | `0.00` leaf accuracy: `acc(C on covered) − acc(C-flat on its covered)` |
| Threshold-selection target | C's leaf coverage `≥ 0.70` on the threshold-selection split |
| Interval | 95% paired percentile bootstrap, `B = 2000` resamples; clusters are sequence slots |
| Repeats | 5 held-out test seeds |
| SNR levels | 20, 10 and 0 dB, pooled per repeat |
| Generator version | `ncm-concept-fixture-v1`, unchanged ([spectral-resonance-h2.md](spectral-resonance-h2.md#fixture-generator-ncm-concept-fixture-v1-contract-section-123)) |
| Training split seed | `0x1250_0001` (registered by #125 for reuse by #127) |
| Threshold-selection split seed | `0x1250_0002` (registered by #125 for reuse by #127) |
| Test split seeds (repeats 1 to 5) | `0x1270_1001`, `0x1270_1002`, `0x1270_1003`, `0x1270_1004`, `0x1270_1005` |
| Bootstrap seed | `0x1270_B007`, combined with the repeat number `r` as `0x1270_B007 + r` |
| N11 noise seed | `0x1270_0011`: 32 × 64 windows of unit-variance Gaussian noise (`java.util.Random.nextGaussian`) |
| Workload | medium (contract Section 14.1): `N = 256`, 32 sequences of 64 windows per split and SNR level |

### Gate rule

G3 is **go** if and only if, in **each** of the 5 repeats, pooled over the three SNR levels:

1. the lower bound of the 95% interval of `acc(C) − acc(A)` on C-covered windows is strictly greater than `0.15`;
2. C's leaf coverage is at least `0.60`;
3. the lower bound of the 95% interval of `acc(C covered) − acc(C forced top-1)` is strictly greater than `0.05`;
4. C leaves a larger fraction of the N11 noise windows uncovered at the leaf depth (abstention or coarse-only) than of
   that repeat's 20 dB windows.

Otherwise G3 is **no-go**: the result is published and #128 does not start (contract Section 15). H4 holds when the
lower bound of `acc(C) − acc(C-flat)` exceeds `0.00` in every repeat; it is informational and never decides the gate.

### Definitions

- **Next label.** The next-concept label of window `w` is the generator label of window `w + 1` of the same sequence;
  the last window of a sequence has none and is excluded from every accuracy and coverage.
- **Previous concept.** Every arm runs its sequences in stream order. The query for window `p` carries the arm's
  **own** prediction for window `p − 1` (the concept it answered at step `p − 2`), none at the start of a sequence or
  after an abstention. No arm reads a test label.
- **Coverage.** C covers a window at the leaf depth when it returns a leaf; a coarse-only answer and an abstention are
  not covered (contract Section 8.2).
- **Forced top-1.** The leaf with the highest next-window score in C's score table (ties to the lower ordinal),
  for every window, whatever C decided; abstentions before scoring count as errors.
- **Bootstrap.** A cluster is one sequence slot with its windows at all three SNR levels (their clean windows are
  shared). Each statistic is a ratio of sums over the drawn clusters; both arms of a difference use the same resample.
  Bounds are the order statistics 51 and 1,950 of the 2,000 sorted resample values.

## Training and selection (non-test data only)

1. **Model.** `ConceptModelTrainer` on the training split at 20, 10 and 0 dB pooled (96 labeled sequences): one unit
   magnitude centroid per concept, leaf transition counts, leaf marginals.
2. **Temperature.** From `{0.01, 0.02, 0.05, 0.1, 0.2, 0.5}`, the one with the lowest negative log-likelihood of the true
   current leaf (`currentScore`, no previous concept) on the training split; ties to the earlier value.
3. **C thresholds.** Per depth, `τ_conf ∈ {0, 0.6, 0.7, 0.8, 0.9}` and `τ_margin ∈ {0, 0.1, 0.2, 0.3}` (400
   combinations), each run in stream order on the threshold-selection split at all three SNR levels. Among the
   combinations whose leaf coverage is at least `0.70`, the highest covered leaf accuracy; ties to the higher coverage,
   then the earlier combination (loops: depth-0 confidence, depth-0 margin, depth-1 confidence, depth-1 margin). If none
   reaches `0.70`, no thresholds.
4. **C-flat thresholds.** `τ_conf ∈ {0.00, 0.05, …, 0.95}` and `τ_margin ∈ {0, 0.05, 0.1, 0.2}` over the unconditional
   next-leaf scores; the combination whose leaf coverage on the threshold-selection split is closest to C's coverage
   there, ties to the earlier combination.

## Arms

| Arm | Answer for the next window | Role |
| --- | --- | --- |
| A | The most frequent next leaf of the training transitions, ties to the lower ordinal; reads no test input | Gate control (H3) |
| A-oracle | The most frequent training successor of the **true current** label | Informational only: reads a test label, after the labels are opened |
| B | The leaf whose centroid is most similar to the current window (no transitions, no hierarchy, no previous concept) | Spectral-only baseline |
| C | `NextConceptPredictor` with the selected temperature and thresholds | System under test |
| C forced | C's top-ranked leaf on every window | H6 control |
| C-flat | C's next-leaf scores from the same predictor call, ranked flat over the leaves with the flat thresholds | H4 control: same features, scoring inputs and work |

## Metrics (contract Section 14.3)

Next-leaf accuracy per arm, per SNR level and pooled; C's leaf and depth-0 coverage, depth-0 accuracy, forced top-2
leaf accuracy; abstentions and coarse-only answers, abstention precision and recall against C's forced top-1 errors;
reliability bins (10 equal-width) and ECE for the forced top-1 leaf, the covered leaf and the depth-0 answer;
risk-coverage of the forced top-1 leaf at 10% to 100% coverage; comparisons, candidates and levels per window; N11
leaf non-coverage; the selected temperature and thresholds with their threshold-split coverage and accuracy.

## Checks

The verdict is trusted only when every check passes (exit status 0): `fixtures.p15-n9-n12` (P15 on clean centre-band
windows with the trained model and no thresholds, N9, N12; P16 and N10 are unit-tested in core), `splits.disjoint`,
`leakage.counterfactual` (repeat 1 re-answered with permuted labels: answers bit-identical, C's accuracy changes),
`determinism` (repeat 1 regenerated, re-answered and re-bootstrapped bit-identically) and `leakage.test-labels` (no
guarded label read before the scorer opened it).

## Command

```bash
./gradlew :monada-neuron-evaluation:runNextConceptG3
```

## Results

Pending: computed only after this pre-registration is committed.
