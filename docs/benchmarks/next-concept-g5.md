# Next-Concept G5 Evaluation, Part 1 (Issue #129, hypothesis H5)

**Contract**: `ncm-wave-cognition/2`, Sections 10.2, 12.3, 13 (H5), 14.2 (control E) and 15 (G5)
**Status**: protocol pre-registered in commit `9343e9b`, before any held-out result was committed; results below (H5:
**negative**)

This document is the pre-registration that contract Section 13 requires for H5. It commits the margin, the minimum
coverage, the gate rule, the feedback procedure, the feedback bounds, every seed and the arms **before** the first
commit that contains a held-out result. Changing any value below after results exist requires a new protocol version,
and the change is reported.

**Disclosure.** While the harness was being developed (commit `708b43a`), one `--quick` smoke run was executed on the
first registered repeat: 8 sequences, 200 resamples, feedback seed `0x1290_F001` and test seed `0x1290_1001`. Its
output was seen before this commit. The H5 margin (`0.02`) and the minimum coverage (`0.60`) had been fixed with the
issue owner beforehand, and no value below was changed after that run. The smoke run's numbers are not results of this
protocol and are not recorded.

## Question

Does validated outcome feedback, applied by `ConceptFeedbackPolicy` to a caller-owned `ConceptModel` snapshot
(control **E**: C plus validated feedback, ADR 0049), improve C's next-concept predictions on **later held-out
windows**, without label leakage (H5)?

In this repository, "later" has a fixed meaning:
- feedback comes from a disjoint FEEDBACK split, streamed before the test split is answered;
- the snapshot is frozen when the feedback ends;
- the held-out TEST split is answered afterwards with the frozen snapshot;
- feedback never reads a test label.

Control D does not exist yet (#130), so E is C plus feedback.

Gate G3 was **no-go** ([next-concept-g3.md](next-concept-g3.md)), so C is not proven better than baseline A. H5 asks
only whether feedback improves C. It makes no claim about C versus A.

## Pre-registered values

| Item | Value |
| --- | --- |
| H5 margin | `0.02`: `rate(E) − rate(C)`, paired on the same test windows |
| H5 minimum coverage | `0.60`: E's pooled leaf coverage of the test windows that have a next window |
| Rate | covered-and-correct next leaf over all test windows with a next window: an abstention or a coarse-only answer counts as not correct, so coverage and accuracy are both inside one paired quantity |
| Interval | 95% paired percentile cluster bootstrap, `B = 2000` resamples. Clusters are sequence slots across SNR levels, as in G3 |
| Repeats | 5, each with its own feedback seed and test seed |
| SNR levels | 20, 10 and 0 dB, pooled per repeat |
| Generator version | `ncm-concept-fixture-v1`, unchanged. The FEEDBACK role only changes the provenance string |
| Training split seed | `0x1250_0001` (G3's) |
| Threshold-selection split seed | `0x1250_0002` (G3's) |
| Feedback split seeds (repeats 1 to 5) | `0x1290_F001`, `0x1290_F002`, `0x1290_F003`, `0x1290_F004`, `0x1290_F005` |
| Test split seeds (repeats 1 to 5) | `0x1290_1001`, `0x1290_1002`, `0x1290_1003`, `0x1290_1004`, `0x1290_1005` |
| Bootstrap seed | `0x1290_B005`, combined with the repeat number `r` as `0x1290_B005 + r` |
| Workload | medium (contract Section 14.1): `N = 256`, 32 sequences of 64 windows per split and SNR level |
| Feedback bounds | one outcome per update. Maximum evidence age 64 cycles. Maximum feedback share `1.0` (a leaf may absorb at most as many feedback windows as it had training windows). One trusted verifier, `ncm-g5.fixture-verifier` |

### Gate rule

H5 **holds** if and only if, in **each** of the 5 repeats, pooled over the three SNR levels:

1. the lower bound of the 95% interval of `rate(E) − rate(C)` is strictly greater than `0.02`; and
2. E's leaf coverage is at least `0.60`.

Otherwise H5 is **negative**. Either outcome is acceptable evidence for the first part of G5 (contract Section 15).
#130 consolidates G5 with every control. The verdict is trusted only when every check below passes.

### Definitions

- **C.** C is trained and thresholded exactly as in G3, by the same code, with the same seeds and grids:
  - the temperature minimizes the current-leaf NLL on the training split;
  - the per-depth thresholds maximize covered accuracy at coverage `≥ 0.70` on the threshold-selection split.

  Its snapshot is the starting point of every feedback stream.
- **Feedback stream (E).** The FEEDBACK splits of a repeat are processed in SNR-level order, then sequence order, then
  window order. At each window `w` that has a successor in its sequence:
  - the current snapshot predicts, carrying its own earlier selection as previous concept, as C does;
  - an answered prediction (leaf or coarse-only) gets a `ConceptPredictionReceipt`;
  - the verifier reports the label and pattern of window `w + 1` as a `VERIFIED` `ConceptOutcome`, with the label of
    `w` as the verified previous leaf;
  - the policy applies the outcome at cycle ordinal `step + 1`, and its result is the snapshot of the next step.

  An abstention gets no receipt and no feedback, because attribution needs a prediction. The final snapshot is frozen.
- **Next label, previous concept, coverage.** These are defined as in G3. No arm reads a test label at inference.

## Arms

| Arm | Snapshot answering the test split | Role |
| --- | --- | --- |
| C | C's trained snapshot | no-feedback control; H5 baseline |
| E | after the whole feedback stream | system under test (control E) |
| E-shuffled | after a stream whose FEEDBACK labels are permuted with a seeded permutation | wrong feedback through a trusted verifier: improvement must not come from the update mechanics |
| E-unverified | after a stream whose outcomes are all `UNVERIFIED` | memory-off control: must be C's exact snapshot (checked) |
| C-replay | C retrained on the training split plus the full FEEDBACK split, with C's temperature and thresholds | "more labeled data" without the feedback gate (informational) |
| E at 25% and 50% | after that fraction of the feedback stream | drift curve (informational) |

## Checks

The registered check list:
- `splits.disjoint`: training, threshold-selection, every FEEDBACK and every TEST split have distinct seeds and share
  no clean window.
- `leakage.feedback-provenance`: every applied outcome's receipt and evidence come from a FEEDBACK split, none from a
  TEST split. A test label feeding an update fails the run.
- `feedback.unverified-abstains`: E-unverified applies nothing and keeps C's exact snapshot in every repeat.
- `feedback.duplicate-rejected`: repeat 1's first applied outcome, sent twice, is applied once (`DUPLICATE`). Replayed
  onto its result, it is rejected (`MODEL_MISMATCH`).
- `feedback.replay-deterministic`: re-streaming repeat 1's feedback gives the same fingerprint.
- `leakage.counterfactual`: with permuted test labels, E's snapshot and every arm's answers are bit-identical, and E's
  rate changes.
- `determinism`: regenerating repeat 1 gives bit-identical answers, fingerprints and bootstrap values.
- `leakage.test-labels`: no guarded test label is read before the scorer opens it.

The verdict is trusted only when every check passes (exit status 0).

## Command

```bash
./gradlew :monada-neuron-evaluation:runNextConceptG5
./gradlew :monada-neuron-evaluation:jmh -PjmhArgs="ConceptFeedbackBenchmark -prof gc"
```

The runner writes `build/reports/benchmarks/next-concept-g5.{md,json}`.

## Environment

| Item | Value |
| --- | --- |
| Contract / generator | `ncm-wave-cognition/2`, `ncm-concept-fixture-v1` |
| JDK | OpenJDK 27 (Azul Zulu 27), JMH 1.37 for the cost table |
| JVM flags | evaluation module default, G1, default heap (3,832 MB max) |
| OS / architecture | Linux 7.2 (Fedora 44), x86_64 |
| CPU | AMD Ryzen 7 7730U with Radeon Graphics, 16 available processors (the evaluation is single-threaded) |
| Pre-registration commit | `9343e9b`: margin, coverage, seeds, bounds and arms committed before any held-out result |
| Execution commit (results below) | `bce881b` with uncommitted documentation edits only (`+dirty`: contract Section 10.2 text; no source change) |
| Run | about 34 s wall clock for the full protocol; two runs gave bit-identical checks and results (only the timestamp differs) |

## Checks

All eight semantic checks pass, so the verdict below can be trusted:

| Check | Observed |
| --- | --- |
| `splits.disjoint` | 12 splits (training, threshold selection, 5 feedback and 5 test) with distinct seeds; 24,576 clean windows, none shared |
| `leakage.feedback-provenance` | 88,596 receipt and evidence references of applied outcomes (E and E-shuffled), 0 outside FEEDBACK splits, 0 from TEST splits |
| `feedback.unverified-abstains` | 30,127 `UNVERIFIED` outcomes, 0 applied; C's exact snapshot kept in 5/5 repeats |
| `feedback.duplicate-rejected` | repeat 1's first applied outcome sent twice is applied once (`DUPLICATE`); replayed onto its result, `MODEL_MISMATCH` |
| `feedback.replay-deterministic` | repeat 1's feedback re-streamed from C: revision 5,691, identical fingerprint |
| `leakage.counterfactual` | repeat 1 with permuted held-out labels: E snapshot and answers bit-identical, E rate changed |
| `determinism` | repeat 1 regenerated, re-streamed, re-answered and re-bootstrapped bit-identically |
| `leakage.test-labels` | 21 guarded label sets (test, permuted and regenerated), 0 reads before the scorer opened them |

## Results

### Selection (non-test data, identical to G3)

Temperature `0.02`; thresholds `τ_conf = [0, 0]`, `τ_margin = [0.3, 0.1]`; C snapshot fingerprint
`6d6bf888…e3d563`, 6,480 retained bytes (the G3 model plus 80 bytes of feedback bookkeeping, ADR 0049).

### Gate quantities (pooled over 20, 10 and 0 dB; 95% paired-bootstrap intervals)

| Repeat | `rate(E) − rate(C)` | E leaf coverage | Gate (lower bound > 0.02, coverage ≥ 0.60) |
| --- | --- | --- | --- |
| 1 | −0.0023 [−0.0155, 0.0121] | 0.785 [0.770, 0.799] | fail |
| 2 | 0.0089 [−0.0035, 0.0225] | 0.768 [0.752, 0.783] | fail |
| 3 | 0.0149 [0.0050, 0.0246] | 0.804 [0.791, 0.818] | fail |
| 4 | 0.0104 [−0.0007, 0.0227] | 0.774 [0.764, 0.785] | fail |
| 5 | 0.0031 [−0.0063, 0.0129] | 0.753 [0.741, 0.765] | fail |

**H5 verdict: NEGATIVE.** No repeat reaches the registered margin: the largest lower bound is `0.0050` (repeat 3),
and four of five intervals include zero. Coverage is not the reason (E covers 0.75 to 0.80 of the windows). The first
part of G5 is therefore reported negative (contract Section 15); #130 consolidates G5 with every control.

### Controls

| Repeat | rate C | rate E | rate E-shuffled | rate C-replay | E at 25% | E at 50% | `rate(E) − rate(C-replay)` |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 0.3256 | 0.3232 | 0.0565 | 0.3188 | 0.3411 | 0.3401 | 0.0045 [0.0020, 0.0071] |
| 2 | 0.2991 | 0.3080 | 0.0577 | 0.3008 | 0.3077 | 0.3171 | 0.0073 [0.0033, 0.0114] |
| 3 | 0.3135 | 0.3284 | 0.0463 | 0.3262 | 0.3310 | 0.3423 | 0.0021 [−0.0010, 0.0055] |
| 4 | 0.3082 | 0.3186 | 0.0327 | 0.3158 | 0.3191 | 0.3266 | 0.0028 [0.0007, 0.0050] |
| 5 | 0.3170 | 0.3201 | 0.0589 | 0.3234 | 0.3297 | 0.3317 | −0.0033 [−0.0078, 0.0012] |

| Repeat | C coverage / covered accuracy | E coverage / covered accuracy | E outcomes sent / applied / `CONCEPT_CAP` | E positive / negative |
| --- | --- | --- | --- | --- |
| 1 | 0.761 / 0.428 | 0.785 / 0.412 | 6,024 / 5,691 / 333 | 2,777 / 2,914 |
| 2 | 0.733 / 0.408 | 0.768 / 0.401 | 6,018 / 5,793 / 225 | 2,860 / 2,933 |
| 3 | 0.748 / 0.419 | 0.804 / 0.408 | 6,018 / 5,916 / 102 | 2,727 / 3,189 |
| 4 | 0.750 / 0.411 | 0.774 / 0.412 | 6,025 / 5,679 / 346 | 2,768 / 2,911 |
| 5 | 0.753 / 0.421 | 0.753 / 0.425 | 6,017 / 5,851 / 166 | 2,783 / 3,068 |

### Reading the results

- **Feedback is safe and bounded, but not useful enough.** Validated feedback moves the rate by `+0.007` on average
  (−0.002 to +0.015), below the registered margin. It mostly raises leaf coverage (by about 0.03) while covered
  accuracy stays level or falls slightly, so E answers more windows at about C's accuracy.
- **The improvement is "more labeled data", not the gate.** C-replay, which retrains C on the same FEEDBACK labels
  without receipts or the gate, lands within `0.008` of E in every repeat. The incremental update reproduces a retrain,
  as ADR 0049 intends. On this stationary fixture, the extra labels add little to a model already trained on 6,144
  windows.
- **Wrong feedback is not hidden.** E-shuffled loses 0.24 to 0.28 of rate, mostly through coverage (0.07 to 0.14):
  permuted labels blur the centroids until C abstains. Feedback through a trusted verifier is only as good as the
  verifier. The policy bounds how far a leaf moves (`CONCEPT_CAP`, 2 to 6% of E's outcomes), but it cannot tell a
  wrong label from a right one.
- **The drift curve is not monotone.** In every repeat, E after 25% or 50% of the stream rates higher than E after all
  of it (by up to 0.018). This is informational and not pre-registered, so it does not change the verdict. A
  shorter stream or a smaller feedback share would be a new protocol.
- **No leakage and no amplification.** Every applied outcome came from a FEEDBACK split. `UNVERIFIED` outcomes changed
  nothing, a duplicate was applied once, a replayed batch was rejected, and two runs were bit-identical.

## Cost of one update (JMH)

```bash
./gradlew :monada-neuron-evaluation:jmh -PjmhArgs="ConceptFeedbackBenchmark -prof gc"
```

Settings: 1 fork, 3 warm-up and 5 measurement iterations of 1 s, average time, `-prof gc`. Workloads are those of
`NextConceptPredictorBenchmark`: MEDIUM is 6 concepts with 129 bins, and LARGE is the contract's largest bounds, 256
concepts with 2,049 bins (a 4.2 MB snapshot). Every operation includes the new snapshot's fingerprint, because the
next update's gate needs it.

| Operation | MEDIUM time | MEDIUM allocation | LARGE time | LARGE allocation |
| --- | ---: | ---: | ---: | ---: |
| `applyOne` (1 outcome) | 7.2 ± 0.2 µs | 16.9 kB | 4.09 ± 1.00 ms | 4.53 MB |
| `applyBatch` (64 outcomes) | 34.4 ± 4.5 µs | 23.0 kB | 4.69 ± 0.71 ms | 4.53 MB |
| `fingerprint` alone | 5.5 ± 0.4 µs | 8.9 kB | 3.57 ± 1.11 ms | 10.3 kB |

Retained state does not grow with feedback: the snapshot stays 6,480 B on the medium workload across 5,700 updates,
and only counts change. Each update copies the learned arrays once, so allocation is the snapshot size per batch.
The SHA-256 fingerprint dominates the time (about 77% of `applyOne` on MEDIUM and 87% on LARGE). At the largest bounds
a streaming caller pays about 4 ms per update, while a batch of 64 costs about 73 µs per outcome. Batching per snapshot
is the efficient pattern, and ADR 0049 already requires it for outcomes of one revision.

## Limitations

- One stationary fixture family, one machine, one JMH fork. The fixture has no distribution shift, so feedback can only
  add labeled data from the same process. A shifted workload is a separate protocol.
- Windows inside one sequence share its transition process; the intervals assume sequence slots are independent.
- Feedback is attributed only to answered windows. Abstentions get no receipt, so the feedback stream over-represents
  windows C already answers.
- The verifier is the generator's own labels, so "validated" means correct by construction for E. E-shuffled shows
  what a wrong trusted verifier does.
- The C snapshot and its selection are G3's, so the G3 limitations (in-sample temperature, no resonance features)
  carry over.
