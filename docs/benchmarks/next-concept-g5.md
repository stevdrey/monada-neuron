# Next-Concept G5 Evaluation, Part 1 (Issue #129, hypothesis H5)

**Contract**: `ncm-wave-cognition/2`, Sections 10.2, 12.3, 13 (H5), 14.2 (control E) and 15 (G5)
**Status**: protocol pre-registered in this commit, before any held-out result is committed; results pending

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
(control **E**: C plus validated feedback, ADR 0045), improve C's next-concept predictions on **later held-out
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

## Results

Pending: recorded in a later commit.
