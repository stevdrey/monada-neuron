# Next-Concept G3 Evaluation (Issue #127, gate G3)

**Contract**: `ncm-wave-cognition/1`, Sections 8, 12.3, 13 (H3, H4, H6), 14.2 and 15 (G3)
**Status**: protocol pre-registered in commit `9071893`, before any held-out result; results below (G3: **no-go**)

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
`h4.paired-inputs` (C-flat carries C's previous concept and score table on every window of repeat 1, and every window
where the two arms answer differently is re-scored identically by a fresh predictor call), `determinism` (repeat 1 regenerated, re-answered and re-bootstrapped bit-identically) and `leakage.test-labels` (no
guarded label read before the scorer opened it).

## Command

```bash
./gradlew :monada-neuron-evaluation:runNextConceptG3
```

## Environment

| Item | Value |
| --- | --- |
| Contract / generator | `ncm-wave-cognition/1`, `ncm-concept-fixture-v1` |
| JDK | OpenJDK 27 (Azul Zulu 27), JMH 1.37 for the cost table |
| JVM flags | `--add-modules=jdk.incubator.vector` (evaluation module default), G1, default heap (3,832 MB max) |
| OS / architecture | Linux 7.2 (Fedora 44), x86_64 |
| CPU | AMD Ryzen 7 7730U with Radeon Graphics, 16 available processors (the evaluation is single-threaded) |
| Neuron commit | `9071893` (clean tree; the pre-registration commit) |
| Run | about 20 s wall clock for the full protocol; two runs gave bit-identical reports (only the timestamp differs) |

## Checks

All six semantic checks pass, so the verdict below can be trusted:

| Check | Observed |
| --- | --- |
| `fixtures.p15-n9-n12` | P15 4/4 leaves predicted coarse then fine; N9 `UNKNOWN_CONCEPT`; N12 cyclic hierarchy rejected |
| `splits.disjoint` | 7 splits with distinct seeds; 14,336 clean windows, none shared across splits |
| `leakage.counterfactual` | repeat 1 re-answered with permuted labels: answers bit-identical, C's covered accuracy changed |
| `h4.paired-inputs` | C-flat shares C's previous concept and score table on 6,144/6,144 windows; 571/571 windows with different answers re-scored identically |
| `determinism` | repeat 1 regenerated, re-answered and re-bootstrapped bit-identically |
| `leakage.test-labels` | 21 guarded label sets (test, permuted and regenerated), 0 reads before the scorer opened them |

## Results

### Selection (non-test data)

| Item | Value |
| --- | --- |
| Temperature | `0.02` (training NLL 3,329; `0.01`: 3,466, `0.05`: 3,854, larger values worse) |
| C thresholds | `τ_conf = [0, 0]`, `τ_margin = [0.3, 0.1]` (depth 0, depth 1) |
| C on the threshold-selection split | leaf coverage 0.752, covered accuracy 0.413 |
| C-flat thresholds | `τ_conf = 0.40`, `τ_margin = 0`; coverage 0.683, covered accuracy 0.415 |
| Control A | always `LOW_PURE` |
| Snapshot | 6,400 bytes (6 centroids of 129 bins, counts) |

### Gate quantities (pooled over 20, 10 and 0 dB; 95% paired-bootstrap intervals)

| Repeat | C leaf coverage | H3: acc(C) − acc(A) | H6: acc(C covered) − acc(C forced) | N11 vs 20 dB non-coverage | Gate |
| ---: | --- | --- | --- | --- | --- |
| 1 | 0.756 | 0.173 [0.139, 0.209] | 0.015 [0.007, 0.024] | 0.183 vs 0.249 | fail |
| 2 | 0.773 | 0.156 [0.123, 0.187] | 0.016 [0.008, 0.023] | 0.183 vs 0.230 | fail |
| 3 | 0.747 | 0.182 [0.155, 0.211] | 0.016 [0.007, 0.024] | 0.183 vs 0.268 | fail |
| 4 | 0.753 | 0.179 [0.148, 0.210] | 0.014 [0.007, 0.021] | 0.183 vs 0.265 | fail |
| 5 | 0.744 | 0.146 [0.109, 0.184] | 0.018 [0.011, 0.025] | 0.183 vs 0.276 | fail |

Margins: H3 lower bound > 0.15 with coverage ≥ 0.60; H6 lower bound > 0.05 and N11 non-coverage above 20 dB.

**G3 verdict: NO-GO.** Coverage is met in every repeat, but the H3 lower bound exceeds 0.15 only in repeat 3 (0.155),
and H6 fails in every repeat on both conditions: abstaining adds only about 0.015 leaf accuracy, and C leaves fewer
pure-noise windows uncovered (18%) than 20 dB windows (23–28%). Under contract Section 15, #128 does not start; the
predictor stays a library-only experiment.

**H4 (informational): not supported.** `acc(C) − acc(C-flat)` is −0.002 to −0.006; no lower bound exceeds 0. The
interval contains 0 in repeats 1 to 3 and lies below 0 in repeats 4 and 5 (upper bounds −0.0004 and −0.0025). C-flat
answers fewer windows (coverage 0.67–0.69 against C's 0.74–0.77), so the comparison is at the coverage the
pre-registered selection reached, not exactly equal coverage.

**Correction (2026-10-28).** The first published H4 run let C-flat feed back its own answers as the previous concept,
so after the arms disagreed its score tables diverged from C's. That deviated from the registered definition above
("C's next-leaf scores from the same predictor call"). C-flat now decides over C's recorded score table of every
window, checked by `h4.paired-inputs`. The superseded H4 values were −0.005 to −0.009 with every interval below 0
(C-flat coverage 0.66–0.69, threshold-split coverage 0.674). The gate, H3, H6, C's numbers and every other arm are
unchanged.

### Accuracy per arm (pooled; next leaf)

| Repeat | C covered | A on C-covered (H3) | A (all windows) | A-oracle (info) | B | C forced top-1 | C-flat covered | C depth-0 accuracy |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 0.413 | 0.240 | 0.242 | 0.493 | 0.410 | 0.397 | 0.415 | 0.694 |
| 2 | 0.424 | 0.268 | 0.267 | 0.499 | 0.414 | 0.408 | 0.428 | 0.703 |
| 3 | 0.420 | 0.237 | 0.237 | 0.512 | 0.420 | 0.404 | 0.424 | 0.705 |
| 4 | 0.406 | 0.227 | 0.233 | 0.497 | 0.403 | 0.392 | 0.411 | 0.700 |
| 5 | 0.404 | 0.257 | 0.255 | 0.488 | 0.412 | 0.386 | 0.410 | 0.684 |

C covered minus A on C-covered is the H3 point estimate. A (all windows) is A over every window that has a next
window. Calibration: forced top-1 leaf ECE 0.012–0.034, covered leaf
0.011–0.032, depth 0 0.023–0.037; almost every score lies in `[0.3, 0.5)`. Per SNR level, C's covered accuracy is
0.42–0.45 at 20 and 10 dB and 0.36–0.38 at 0 dB. Abstention precision against C's forced errors is 0.64–0.67 and
recall 0.25–0.28; almost every uncovered window is coarse-only (1,360–1,547 per repeat), abstentions are 16–33.
The full per-repeat, per-SNR tables, reliability bins and risk-coverage curves are written by the runner to
`build/reports/benchmarks/next-concept-g3.{md,json}`.

### Reading the results

- **Where the accuracy comes from.** C's next-leaf accuracy (about 0.41) sits between A (about 0.25) and the
  informational A-oracle (about 0.50), which knows the true current leaf; the transition table caps next-leaf
  accuracy near 0.5. C's depth-0 accuracy (about 0.70) equals its forced top-2 leaf accuracy: the band is identified
  almost perfectly (the band's next-window mass is 0.70 under the table), but pure and chord leaves are barely
  separated by a magnitude centroid, because tone frequencies vary continuously over each band and a chord tone often
  falls inside the pure band.
- **Why abstention is uninformative.** Scores barely vary (almost all in `[0.3, 0.5)`), so thresholds sort windows weakly;
  the selected depth-1 margin mostly turns leaf answers into coarse-only ones. A likely explanation for N11, not tested
  here: a noise window's flat magnitude spectrum is about equally far from every centroid, so its posterior falls
  back to the transition prior, which is confident enough to answer.
- **Hierarchy.** At the coverage the pre-registered selection reached, the flat decision over the same score tables
  is at least as accurate (by 0.002 to 0.006): on this fixture the hierarchy helps identify the band but its per-depth
  thresholds do not improve leaf selection.
- **Not a claim about concepts.** Every number is a property of `ncm-concept-fixture-v1` and of this phase-blind
  centroid model without resonance features. A feature that separates pure from chord windows (for example a
  frequency-ratio feature) would need a new protocol version with its own pre-registered margins.

## Cost of one prediction (JMH)

```bash
./gradlew :monada-neuron-evaluation:jmh -PjmhArgs="-prof gc NextConceptPredictorBenchmark"
```

`NextConceptPredictorBenchmark` (`monada.neuron.evaluation.jmh`): average time, 1 fork, 3 warm-up and 5 measurement
iterations of 1 s, `-prof gc`. The model is trained once per trial on two seeded random windows per leaf; the query
carries a previous leaf. The measured call is `predict` end to end: checks, feature, one comparison per concept, the
transition mix, the descent and the result values.

| Workload | Concepts / leaves / levels | Bins | Time (µs/op) | Allocation (B/op) | Snapshot |
| --- | ---: | ---: | ---: | ---: | ---: |
| `MEDIUM` | 6 / 4 / 2 | 129 | 3.50 ± 0.49 | 3,032 | 6.4 kB |
| `LARGE` | 256 / 192 / 3 | 2,049 | 522 ± 22 | 34,683 | about 4.5 MB |

- **Time** is dominated by the comparisons, O(concepts · bins): 256 × 2,049 ≈ 525 k multiply-adds at `LARGE`, about
  1 ns each, plus the O(leaves²) transition mix (36,864 terms). At the medium workload one prediction costs about a
  twelfth of one DFT decomposition (44 µs, [wave-decomposer-reference.md](wave-decomposer-reference.md)).
- **Allocation** is O(concepts + bins) per call: the feature and score arrays, the prior row, the candidate records
  and lists. Nothing scales with the transition table.
- In the G3 run every prediction made 6 comparisons, scored 4 candidates and visited 2 levels (1.99 to 2.00 on
  average; abstentions stop at depth 0). No optimization is made or justified: G3 is no-go.

## Limitations

- One fixture family with continuous tone frequencies, one machine, one JMH fork. The verdict is binding for G3 under
  this protocol and says nothing about other encoders or features.
- Windows inside one sequence share its transition process; the intervals assume sequence slots are independent.
- C-flat's coverage could not be matched exactly to C's on the registered grid (0.683 against 0.752 on the
  threshold-selection split), so H4 compares at slightly different coverages.
- C runs without resonance features (G2 no-go) and ignores phase entirely.
- The temperature is chosen, as pre-registered, by the NLL of the current leaf on the training windows the centroids
  were fitted on. That in-sample choice favours low temperatures and sharp scores, so the reliability bins and ECE
  above are pessimistic about calibration on new data. The verdict does not depend on it: H3, H4 and H6 compare
  accuracies, and the thresholds were chosen on the separate threshold-selection split. A held-out temperature
  choice belongs to a new protocol, not to this one.
