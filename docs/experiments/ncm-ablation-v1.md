# NCM Consolidated Ablation Protocol (`ncm-ablation/1`)

## Status

| Field | Value |
| :--- | :--- |
| Protocol version | `ncm-ablation/1` |
| Issue | [#130](https://github.com/stevdrey/monada-neuron/issues/130) (NCM 10/10, Epic #120) |
| Contract | `ncm-wave-cognition/2`, Sections 12 to 15 ([contract](../specs/ncm-wave-cognition-v1.md)) |
| Gate served | G5: the consolidated go/no-go report whose decision is binding for further investment |
| Implementation | `monada-neuron-evaluation`, package `monada.neuron.evaluation.wave`: `NcmAblationEvaluation`, `NcmAblationRunner`, `FeatureConceptScorer`, `ConceptFeatureMap`, `TinyWorkload`, `LargeWorkloadEvaluation`, `LargeConceptFixtureGenerator`, `HostMemoryIndependence` |
| Results | [ncm-ablation.md](../benchmarks/ncm-ablation.md) |

This document pre-registers the protocol **before** any held-out result of it is committed (contract Section 13).
It fixes the arms, how each is built and selected, the workloads, the seeds, the checks and the verdict rule. Changing
any of them after results exist requires `ncm-ablation/2`, and the change is reported.

**Disclosure.** The harness was developed and tested only on a small protocol whose seeds are not the registered
ones (`NcmAblationEvaluationTest.SMALL`, seeds `0x7E57_…`). No run with the registered seeds below, including a
`--quick` smoke run, was made before this protocol was committed. Controls A to C-flat and E reuse the G3 and G5
harnesses, whose registered results were published by #127 and #129; this protocol re-runs them unchanged and checks
that the re-run reproduces them.

## Question

Does adding spectral decomposition and a hierarchy improve **concept** prediction over simpler alternatives, and at
what cost? Concretely, over the contract's tiny, medium and large workloads:

1. Does any available arm meet the registered G3 quality rule (H3 and H6) in every repeat?
2. What does each component contribute when it is ablated alone: spectral input (A vs B vs C), the hierarchy
   (each arm vs its flat control), the wavelet (C vs D-haar), the phase-sensitive coherence feature (with vs
   without coherence), and feedback (C vs E)?
3. Does numeric prediction depend on the host's memory (Resonance Store) composition?
4. What does one window cost end to end, in time, allocation and retained memory, at every scale?

## What this protocol does not claim

- The fixtures are synthetic tones with host-supplied labels (contract Section 12.3). A result says nothing about
  semantic understanding of real signals; external validation is a follow-up whatever the verdict.
- No arm is compared with an LLM, and none is enabled by default under any verdict.
- Timings are machine-specific diagnostics, never a gate.
- Prediction figures of the large workload are informational: they enter no gate.

## Arms

| Arm | What it is | Source |
| --- | --- | --- |
| A | Most frequent next leaf of the training split; reads no test input | G3 harness (#127), unchanged |
| A-oracle | Training transitions applied to the **true** current label; informational upper reference, never used by a gate | G3 harness |
| B | Flat nearest leaf centroid of the current window (spectral only, no hierarchy, no transitions) | G3 harness |
| C | Core `NextConceptPredictor` over phase-blind DFT magnitudes; no resonance feature (G2 no-go) | G3 harness |
| C-flat | C's own score tables ranked flat over the leaves, thresholds matched to C's coverage | G3 harness |
| E | C plus validated outcome feedback (`ConceptFeedbackPolicy`) | G5 harness (#129), unchanged |
| C+coh | C's DFT magnitudes plus a coherence feature; isolates the phase-sensitive feature without the wavelet | this protocol |
| D-haar | Haar magnitudes; isolates the wavelet without coherence | this protocol |
| D-haar+coh | **Control D** of contract Section 14.2: Haar magnitudes plus a coherence feature | this protocol |
| X-flat | For each of C+coh, D-haar and D-haar+coh: the arm's own score tables ranked flat over the leaves, as C-flat | this protocol |
| E-D | Feedback over D: run only if a D arm passes the G3 rule (see the verdict rule); otherwise reported as excluded with that reason | this protocol |

No arm is excluded by an earlier gate: G1h is go, so the Haar arms are in scope; G2 is no-go, so C carries no
resonance feature, and the coherence arms re-test the phase-sensitive quantity as a **feature** rather than as a pair
score.

### How the feature arms are built

The core predictor is DFT-only by design (`ConceptModelTrainer` rejects other bases; ADR 0043, ADR 0044), and this
issue changes no core type. The feature arms therefore run through a **reference scorer in the evaluation module**,
`FeatureConceptScorer`. It re-implements the documented training and scoring rules of the core
(`ConceptModelTrainer`, `NextConceptPredictor`) over any feature vector: unit centroids, sibling softmax at
temperature `T`, the Laplace-smoothed transition prior and mix, subtree masses, the per-depth descent with
`LOW_MARGIN` tested before `LOW_CONFIDENCE`, coarse-only answers and ties to the lower ordinal. Only the feature
changes between C and the feature arms.

Before any feature-arm figure is trusted, check `d.reference-equivalence` requires the scorer over DFT features,
trained on the same training split with C's temperature and thresholds, to reproduce the core predictor **bit for
bit** on every threshold-selection window: centroids, score tables, selections, abstention reasons and work.

Features (`ConceptFeatureMap`):

- **DFT magnitudes**: magnitude per bin `0..N/2`, scaled to unit norm (the core's feature).
- **Haar magnitudes**: the magnitude of every coefficient of a complete Haar pattern of depth `L`
  (approximation and every detail level, `N` slots), scaled to unit norm. The host constructs
  `new HaarWaveDecomposer(L)` explicitly; nothing selects wavelets by default (contract Section 14.2).
- **Coherence**: one extra dimension, `w · coherence(previous window, current window)` from
  `SpectralResonanceDynamics`, appended after the magnitudes are scaled to unit norm; the whole vector is then
  scaled to unit norm again. The previous window is the input samples of the preceding window of the same sequence,
  never a label; the value is `0` at a sequence start.

### Selection (non-test data only)

For each feature arm, in this order, with the G3 rules and grids:

1. Feature map and temperature: every candidate map of the arm (Haar depth `L ∈ {1, …, 8}`, coherence weight
   `w ∈ {0.25, 0.5, 1.0}`, as applicable) times every temperature of the G3 grid
   `{0.01, 0.02, 0.05, 0.1, 0.2, 0.5}`; the pair with the least current-leaf negative log-likelihood on the training
   split (no previous concept) wins, the first in grid order on ties. As in G3 this is in-sample.
2. Thresholds: the G3 four-way grid (confidence `{0, 0.6, 0.7, 0.8, 0.9}` × margin `{0, 0.1, 0.2, 0.3}` at depths
   0 and 1) on the threshold-selection split; the best covered accuracy among points with leaf coverage at least
   `0.70`, ties to the higher coverage; `NONE` when no point reaches `0.70`.
3. Flat thresholds: the G3 flat grid point whose coverage on the threshold-selection split is closest to the arm's.

C, C-flat and E keep the G3 and G5 selections unchanged.

## Workloads (contract Section 14.1)

| Workload | Definition | Purpose |
| --- | --- | --- |
| tiny | `N = 8` at 8 Hz, 2 concepts at depth 1 (`cos` at bins 1 and 3), training sequences alternate them; one window per query; fixed temperature `0.05`; D on Haar depth 3 | Exact fixtures: C and D must predict the alternating next concept; the DFT reference scorer must equal C; a second pass must be bit-identical (check `workload.tiny`) |
| medium | `ncm-concept-fixture-v1`, `N = 256`, 32 sequences × 64 windows per split and SNR level, SNR 20, 10 and 0 dB, 6 concepts at depth 2, 5 repeats | All quality evidence and every gate |
| large | `ncm-concept-fixture-large-v1` (below), `N = 4096`, 256 concepts at depth 3, 32 training and 16 held-out sequences × 64 windows (1,024 held-out windows), SNR 20 dB | Bounds, latency, allocation and memory; informational prediction figures |

**`ncm-concept-fixture-large-v1`.** 16 roots, 3 children each, 4 leaves each (`16 + 48 + 192`). 4,096 samples at
4,096 Hz. Leaf `(r, m, l)`: a dominant tone with frequency uniform in `[s, s + 6)` Hz, `s = 16 + 120r + 40m + 10l`,
amplitude in `[0.5, 2)`, phase in `[0, 2π)`, plus a depth-1 signature tone `0.3·A` at `16 + 120r + 40m + 36` Hz
shared by the four leaves of one depth-1 concept; every tone stays below 1,940 Hz. Noise as in the medium generator.
Sequences start at a uniform leaf; from leaf `i`: with probability 0.5 a uniform leaf of the same depth-1 concept, 0.3
the leaf `(i + 7) mod 192`, 0.2 a uniform leaf. On the large workload each model's temperature is chosen by training
negative log-likelihood; no thresholds are selected (there is no threshold-selection split at this size), so C and D
answer every window down to a leaf and the selective trade-off is reported as a risk-coverage curve. D uses Haar depth
`log2 4096 = 12`.

## Seeds

| Item | Value |
| --- | --- |
| Medium training, threshold, test, N11 and G3 bootstrap seeds | The G3 protocol, unchanged: `0x1250_0001`, `0x1250_0002`, `0x1270_1001..1005`, `0x1270_0011`, `0x1270_B007 + r` |
| G5 feedback, test and bootstrap seeds | The G5 protocol, unchanged: `0x1290_F001..5`, `0x1290_1001..5`, `0x1290_B005 + r` |
| Feature-arm bootstrap seed | `0x1300_B001 + r` for repeat `r`, `B = 2000`, clusters = sequence slots across SNR levels (as G3) |
| Large training and test seeds | `0x1300_0001`, `0x1300_1001` |

The feature arms answer the **same** medium test windows as C (G3's test seeds). Their N11 windows are drawn exactly
as G3 draws them.

## Metrics

Per arm and repeat, pooled over the SNR levels and per level: leaf coverage, accuracy on covered windows, forced
top-1 and top-2 accuracy, mean reciprocal rank of the true next leaf, depth-0 coverage and accuracy, expected
calibration error of the forced and covered scores, the risk-coverage curve, abstention precision and recall against
forced errors, N11 and 20 dB non-coverage, and comparisons per window. Paired 95% cluster-bootstrap intervals for:

- the registered estimands H3 (`acc(arm covered) − acc(A on the arm's covered windows)`) and H6
  (`acc(arm covered) − acc(arm forced)`);
- the hierarchy ablation (`acc(arm covered) − acc(arm-flat covered)`, the H4 estimand);
- the feature ablation against C on every window: `forced(arm) − forced(C)` and
  `rate(arm) − rate(C)` (covered and correct over all windows).

Resources, end to end with thread-allocated bytes (`EvaluationMetricsCollector`, 3 warm-up and 10 measured
iterations; `--quick`: 1 and 2): per workload, DFT decomposition plus prediction per window over one sequence,
prediction alone, D's Haar decomposition plus feature plus scorer, and one `NeuronRuntime` execution of eight windows
through `WaveCognitionReasoningStage` and hypothesis evaluation. The retained snapshot size is reported with each.
Kernel figures are cited from the JMH benchmarks, not re-measured.

## Verdict rule (pre-registered)

No margin is added: every arm is judged by the **registered** G3 rule of
[next-concept-g3.md](../benchmarks/next-concept-g3.md), as a candidate replacement for C. An arm passes in a repeat
when, pooled over the SNR levels:

1. the lower bound of the 95% interval of H3 exceeds `0.15`, and its leaf coverage is at least `0.60`;
2. the lower bound of the 95% interval of H6 exceeds `0.05`; and
3. its N11 non-coverage exceeds its 20 dB non-coverage.

- **GO** if C passes in every repeat.
- **CONDITIONAL GO** if C does not but a feature arm passes in every repeat. E-D (feedback over D, with the registered
  H5 rule) and a Haar-capable core model with its own decision record are then required follow-ups before any
  investment beyond the experiment.
- **NO-GO** otherwise.

H5 (control E) is reported with its registered rule and does not change the verdict: feedback that improves a
predictor which fails the quality rule does not make it pass. Under every verdict, NCM stays an experimental opt-in
(ADR 0044) and is not enabled in the default runtime.

## Checks (a failed check makes the verdict untrustworthy)

| Check | Rule |
| --- | --- |
| `workload.tiny` | Tiny fixtures as above |
| `g3.*`, `g5.*` | Every check of the G3 and G5 harnesses |
| `d.reference-equivalence` | Bit-identity of the reference scorer and the core predictor over DFT features |
| `c.reproduces-g3` | The ablation's C has G3's leaf answers per repeat and SNR level, G3's gate outcomes and G3's N11 non-coverage |
| `d.paired-inputs` | Each flat control shares its arm's previous concepts and predictions; differing windows re-score identically |
| `leakage.counterfactual` | Repeat 1 re-answered with permuted held-out labels: every arm bit-identical, D-haar's accuracy changes |
| `determinism` | Repeat 1 regenerated, re-answered and re-bootstrapped: bit-identical |
| `leakage.test-labels` | No guarded held-out label read before scoring |
| `host.memory-independence` | 128 medium windows, one execution each, chaining the stage's own selection: identical reports and hypotheses with no memory port, an empty port, an `UNAVAILABLE` port and a recalling port |
| `large.bounds` | No prediction above the comparison budget, no `BUDGET_EXHAUSTED`, distinct seeds, no early label read |

## Required report fields

Contract Section 14.4, all recorded by the runner: contract and protocol versions, generator versions and seeds,
split seeds, SNR levels, workload sizes, registered margins, every selected threshold and how it was chosen, JDK
vendor and version, OS, architecture, CPU model, available processors, heap and GC, measurement settings, the Neuron
commit (`+dirty` when uncommitted) and the determinism result.

## Commands

```bash
./gradlew :monada-neuron-evaluation:runNcmAblation
```

writes `monada-neuron-evaluation/build/reports/benchmarks/ncm-ablation.{md,json}`. `-PbenchmarkArgs="--quick"` runs
the smoke protocol (first repeat, 8 sequences, 200 resamples, a reduced large workload). Exit status 0 means every
check passed, whatever the verdict.

```bash
./gradlew :monada-neuron-evaluation:jmh -PjmhArgs="-prof gc NextConceptPredictorBenchmark HaarWaveDecomposerBenchmark DftWaveDecomposerBenchmark"
```

## Changelog

- `ncm-ablation/1` (#130): initial protocol.
