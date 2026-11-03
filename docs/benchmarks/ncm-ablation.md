# NCM Consolidated Ablation and Go/No-Go Report (Issue #130, gate G5)

**Contract**: `ncm-wave-cognition/2`, Sections 12 to 15
**Protocol**: [`ncm-ablation/1`](../experiments/ncm-ablation-v1.md), pre-registered in commit `ad0fb7c`, before any
held-out result of this protocol was produced or committed
**Run**: full protocol, every semantic check **PASS**. Quality figures from commit `ad0fb7c` (clean); the review fixes
before merge reproduced every quality figure bit for bit, and the cost table and the two checks below come from that
rerun
**Verdict**: **NO-GO**. The decision is binding for further investment (contract Section 15, gate G5); it is recorded in
[ADR 0050](../adr/0050-ncm-investment-decision.md)

## Verdict

No arm meets the registered G3 quality rule in any of the five repeats, so the pre-registered verdict is **NO-GO**:

| Arm | What it changes | G3 rule in every repeat | Why it fails |
| --- | --- | --- | --- |
| C | the system under test (DFT magnitudes + hierarchy) | fail (0/5) | H6: abstention adds only about 0.015 accuracy (margin 0.05); H3 below 0.15 in four of five repeats |
| C+coh | C plus phase coherence with the previous window | fail (0/5) | Same as C: coherence moves forced accuracy by at most ±0.002 |
| D-haar | Haar magnitudes instead of DFT magnitudes | fail (0/5) | H3 lower bounds 0.07 to 0.12; it never abstains (coverage 1.00), so H6 is 0 and N11 noise is always answered |
| D-haar+coh | control D: Haar magnitudes + coherence | fail (0/5) | H3 lower bounds 0.07 to 0.12 and H6 about 0; it meets the N11 direction only trivially (3 of 2,016 noise windows uncovered, none at 20 dB) |
| E | C plus validated feedback (H5) | negative (0/5) | Reproduces #129: `rate(E) − rate(C)` lower bound at most 0.005, margin 0.02 |

Because no D arm passes, E-D (feedback over D) is excluded with that reason, as the protocol pre-registered. No arm was
excluded by an earlier gate (G1h is go).

The numeric result does not depend on the host's memory: 128 medium executions through `WaveCognitionReasoningStage`
give identical reports and hypotheses with no memory port, an empty port, an `UNAVAILABLE` port and a recalling
port (`host.memory-independence`).

## What the ablations isolate

All intervals are 95% paired cluster bootstrap (`B = 2000`) over the same held-out windows, pooled over 20, 10 and
0 dB. Repeats 1 to 5 are listed in order.

| Component | Estimand | Repeats 1 to 5 (point) | Reading |
| --- | --- | --- | --- |
| Spectral input | acc(C covered) − acc(A on the same windows) (H3) | 0.173, 0.156, 0.182, 0.179, 0.146 | Spectral evidence helps, but the lower bound clears the 0.15 margin only in repeat 3 |
| Spectral input, no transitions or hierarchy | B on every window | 0.410, 0.414, 0.420, 0.403, 0.412 | B (nearest centroid) is about as accurate as C forced (0.386 to 0.408) |
| Hierarchy | acc(C) − acc(C-flat) (H4) | −0.002, −0.004, −0.005, −0.005, −0.006 | Coarse-to-fine adds nothing; the interval excludes 0 on the negative side in repeats 4 and 5 |
| Abstention | acc(C covered) − acc(C forced) (H6) | 0.015, 0.016, 0.016, 0.014, 0.018 | Informative but small, a third of the margin |
| Wavelet | forced(D-haar) − forced(C) | −0.032, −0.039, −0.026, −0.020, −0.018 | Haar magnitudes are worse than DFT magnitudes; every interval excludes 0 |
| Wavelet, with selection | rate(D-haar) − rate(C) | +0.053, +0.042, +0.065, +0.066, +0.068 | Higher only because D answers every window: its selected thresholds give coverage 1.00, so it trades abstention for coverage |
| Phase (coherence) | forced(C+coh) − forced(C) | +0.000, −0.001, +0.001, +0.001, +0.002 | Every interval contains 0: coherence carries no concept information here, consistent with G2 |
| Phase on Haar | forced(D-haar+coh) − forced(D-haar) | +0.003, −0.002, −0.003, −0.005, −0.001 | Point differences only; same reading |
| Feedback | rate(E) − rate(C) (H5) | −0.002, 0.009, 0.015, 0.010, 0.003 | Below the 0.02 margin in every repeat |

Per-SNR coverage and accuracy, risk-coverage curves, calibration and abstention precision and recall are in the
generated report. Summary of repeat 1 (the other repeats agree within ±0.02):

| Arm | Coverage | Covered acc | Forced acc | Top-2 | MRR | Root acc | ECE forced | Abstention precision / recall | N11 / 20 dB non-coverage |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| C | 0.756 | 0.413 | 0.397 | 0.694 | 0.636 | 0.694 | 0.021 | 0.651 / 0.263 | 0.183 / 0.249 |
| C+coh | 0.757 | 0.413 | 0.398 | 0.694 | 0.636 | 0.694 | 0.021 | 0.650 / 0.263 | 0.181 / 0.247 |
| D-haar | 1.000 | 0.365 | 0.365 | 0.694 | 0.620 | 0.694 | 0.045 | 0 / 0 | 0.000 / 0.000 |
| D-haar+coh | 1.000 | 0.368 | 0.368 | 0.694 | 0.621 | 0.694 | 0.042 | 0 / 0 | 0.002 / 0.000 |

Control A answers 0.24 on every window; A-oracle (reads the true current label, informational) reaches 0.49 to 0.51.
No arm detects out-of-distribution input usefully, but the arms fail the N11 condition in different ways:

- **C and C+coh** abstain **less** on pure noise (N11) than on clean 20 dB evidence: 0.18 against 0.23 to 0.28 in
  every repeat. Their confidence scores are higher on noise.
- **D-haar** never abstains on either: 0.000 against 0.000. It fails the strict inequality because it does not
  abstain at all.
- **D-haar+coh** satisfies the direction of the N11 condition in every repeat, but only trivially: it leaves 3 of
  2,016 noise windows uncovered (0.0015) against none at 20 dB. It is still NO-GO, because it fails H3 and H6.

### Selections (non-test data)

| Arm | Feature map | Temperature | Thresholds (confidence; margin) | Threshold-split coverage / accuracy |
| --- | --- | ---: | --- | --- |
| C | DFT (G3) | 0.02 | `[0, 0]`; `[0.3, 0.1]` | as G3 |
| C+coh | DFT + coherence, `w = 0.25` (18 candidates) | 0.02 | `[0, 0]`; `[0.3, 0.1]` | 0.751 / 0.412 |
| D-haar | Haar `L = 5` (48 candidates) | 0.01 | `[0, 0]`; `[0, 0]` | 1.000 / 0.360 |
| D-haar+coh | Haar `L = 5` + coherence, `w = 0.5` (144 candidates) | 0.01 | `[0, 0]`; `[0.3, 0]` | 1.000 / 0.360 |

For the Haar arms, no threshold point improves covered accuracy on the threshold-selection split at the required
coverage, so the rule picks the open thresholds. The protocol fixed this rule in advance; it is reported, not
corrected.

## Scale: tiny and large workloads

**Tiny** (`N = 8`, 2 concepts): C and D both predict the alternating next concept for each clean window, the reference
scorer is bit-identical to C, and a second pass is bit-identical (`workload.tiny`).

**Large** (`N = 4096`, 256 concepts at depth 3, 1,024 held-out windows at 20 dB, no thresholds; informational):

| Arm | Leaf acc | Depth-1 acc | Root acc | Top-5 | MRR | ECE forced | Comparisons per window |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| C | 0.178 | 0.464 | 0.604 | 0.597 | 0.374 | 0.196 | 256 |
| D-haar (`L = 12`) | 0.063 | 0.262 | 0.561 | 0.319 | 0.194 | 0.055 | 256 |

Control A 0.004, control B 0.137, over 1,008 windows with a successor. At this scale C beats A and B, and the
hierarchy gives usable coarse answers (root 0.60, depth 1 0.46) where the leaf is weak (0.18). C is poorly calibrated
here (ECE 0.20). Haar features are again clearly worse. Every prediction stayed within the comparison budget
(at most 256 of 256) and none was `BUDGET_EXHAUSTED` (`large.bounds`).

### Control coverage by workload

Every gate is decided on the medium workload, where every control runs. Tiny and large run subsets, as the protocol
records:

| Control | tiny (`N = 8`) | medium (`N = 256`) | large (`N = 4096`) |
| --- | --- | --- | --- |
| A | no: one window per query, nothing to compare a transition baseline against | yes | yes |
| A-oracle | no: as A | yes (informational) | no: informational only, and it reads a test label |
| B | no: exact fixtures check C and D only | yes | yes |
| C | yes | yes | yes, without thresholds (descent to a leaf) |
| C-forced | no: no thresholds, so it equals C | yes | yes: the forced accuracy column |
| C-flat, X-flat (thresholded flat controls) | no: no threshold selection | yes | no: no threshold-selection split; the forced accuracy is the unthresholded flat argmax |
| C+coh | no: one window per query, no previous window for coherence | yes | no: not in the pre-registered large protocol |
| D-haar | yes (Haar `L = 3`) | yes (`L` selected, 5) | yes (`L = 12`), without thresholds |
| D-haar+coh (control D) | no: as C+coh | yes | no: not in the pre-registered large protocol |
| E (feedback) | no: no feedback stream | yes (G5 harness) | no: the G5 harness and its FEEDBACK split exist for the medium fixture only |
| E-D | no | excluded: no D arm passes the G3 rule | no |
| DFT reference scorer (equivalence) | yes | yes | no |

Coherence and feedback are **not** validated at the large scale. These results say nothing about them at 256 concepts
or 4,096 samples.

## Cost

End to end, `EvaluationMetricsCollector`, 3 warm-up and 10 measured iterations, thread-allocated bytes, median. One
iteration is one sequence (64 windows), or one runtime execution of eight windows for the stage rows. These are
machine-specific diagnostics, not gates.

| Scenario | Per window | Per iteration | Allocated per window | Snapshot |
| --- | ---: | ---: | ---: | ---: |
| tiny: DFT + C | 2.5 µs | 5.0 µs (2 windows) | 2,012 B | 176 B |
| medium: DFT + C | 49.9 µs | 3.19 ms per sequence | 26,802 B | 6,480 B |
| medium: C prediction only | 4.4 µs | 0.28 ms | 3,029 B | 6,480 B |
| medium: Haar + feature + D | 5.8 µs | 0.37 ms | 9,785 B | 12,496 B |
| medium: runtime execution, 8 windows | 50.1 µs | 0.40 ms | 27,368 B | 6,480 B |
| large: DFT + C | 11.03 ms | 706 ms per sequence | 413,790 B | 4,498,432 B |
| large: C prediction only | 0.59 ms | 37.6 ms | 36,737 B | 4,498,432 B |
| large: Haar + feature + D | 0.89 ms | 56.8 ms | 157,525 B | 8,687,104 B |
| large: runtime execution, 8 windows | 11.31 ms | 90.5 ms | 412,901 B | 4,498,432 B |

The prediction rows call the core predictor and the reference scorer directly, without the evaluation's decision
records, so they carry no harness copies: 36,737 B per large prediction against the 34,683 B JMH measures for the bare
predictor ([next-concept-g3.md](next-concept-g3.md)). The reference O(N²) DFT dominates: about 91% of the medium
window and 95% of the large one. Prediction at 256 concepts is linear in concepts × bins (0.59 ms). D is cheaper end to
end because the Haar transform is O(N), but its snapshot is twice as large (`concepts × N` features instead of
`concepts × (N/2 + 1)`). Both runtime executions ended `PROCESSED` within the default `WaveCognitionLimits`. For kernel-level figures see
[wave-decomposer-reference.md](wave-decomposer-reference.md) and [next-concept-g3.md](next-concept-g3.md).

## Checks

Every check passed (23 in total). The 6 G3 and 8 G5 checks re-ran unchanged. The ablation adds the following:

| Check | Result |
| --- | --- |
| `workload.tiny` | C 2/2 and D 2/2; reference bit-identical to C; second pass bit-identical |
| `d.reference-equivalence` | centroids bit-identical; 6,144/6,144 threshold-selection predictions bit-identical |
| `c.reproduces-g3` | 15/15 repeat × SNR splits equal to the G3 harness; 5/5 gate outcomes; N11 0.1825 equal |
| `d.paired-inputs` | flat controls share 18,432/18,432 predictions; 571/571 differing windows re-score identically |
| `leakage.counterfactual` | permuted held-out labels: every arm bit-identical; D-haar accuracy changes |
| `determinism` | repeat 1 regenerated, re-answered and re-bootstrapped: bit-identical |
| `leakage.test-labels` | 21 guarded label sets, 0 early reads |
| `host.memory-independence` | 128 executions in sequences of 64: previous concepts, reports and hypotheses identical across four memory compositions |
| `large.bounds` | at most 256 comparisons per prediction (budget 256), 0 `BUDGET_EXHAUSTED`, distinct seeds and 0 of 1,024 held-out clean windows shared with training, 0 early reads |

## Environment

| Field | Value |
| --- | --- |
| JDK | Azul Zulu 27 (build 27+35), OpenJDK 64-Bit Server VM |
| OS / architecture | Linux, amd64 |
| CPU | AMD Ryzen 7 7730U with Radeon Graphics, 16 available processors |
| Heap / GC | max 3,832 MB, G1 |
| Measurements | 3 warm-up and 10 measured iterations per scenario; quality is one deterministic pass |
| Wall clock | 2 min 13 s for the full protocol |
| Generators | `ncm-concept-fixture-v1` (medium), `ncm-concept-fixture-large-v1` (large) |
| Seeds | as registered in the protocol |

## Limitations

- The fixtures are synthetic tones with host-supplied labels. A no-go here does not show that hierarchical
  prediction cannot help on real signals. It shows that this pipeline gives no measured benefit over simple
  baselines on the contract's own fixtures, which were designed to favor it.
- The medium hierarchy is small (2 categories, 4 leaves). The large workload suggests coarse answers matter more
  with 256 concepts, but its figures are informational: one seed, no threshold selection, one SNR level.
- Haar depth, coherence weight and temperature were selected by in-sample training likelihood (the G3 rule), which
  favors sharp temperatures. A held-out selection might choose differently. The protocol fixed the rule in advance.
- Timings come from one laptop CPU with frequency scaling.

## Follow-up proposals

None is started by this issue:

1. **Keep NCM experimental and opt-in.** No default integration and no further optimization of the reference
   pipeline (ADR 0050).
2. **External validation before any new investment.** Re-open the question only with a pre-registered evaluation on
   real, externally labeled signals, where a simple baseline (B, or C-flat) is the bar to beat.
3. **Out-of-distribution detection** is the clearest functional gap. C and C+coh abstain less on noise than on
   clean evidence. The Haar arms barely abstain at all (D-haar+coh on 3 of 2,016 noise windows). Any future work
   should start there, under its own hypothesis and gate.
4. **A Haar-capable core model is not warranted.** D is worse than C on every measured axis except transform cost.

## Commands

```bash
./gradlew :monada-neuron-evaluation:runNcmAblation
```

writes `monada-neuron-evaluation/build/reports/benchmarks/ncm-ablation.{md,json}` (every figure above, plus per-SNR
tables, risk-coverage curves and the full metadata).
