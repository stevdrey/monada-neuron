# ADR 0049: Bounded Outcome Feedback for Concept Model Snapshots

## Status

Accepted (issue #129; Epic #120; implements section 10.2 of `ncm-wave-cognition/2` under ADR 0034; extends the
`ConceptModel` of ADR 0043 and follows the cross-cycle rule of ADR 0021 and the caller-owned state of ADR 0030)

## Context

ADR 0043 made `ConceptModel` an immutable, caller-owned snapshot that only `ConceptModelTrainer` builds, and ADR 0044
lets a host run it inside a cycle. Contract section 10.2 reserves outcome-driven updates for #129, with four rules:

- an outcome produces an explicit artifact the caller carries;
- the caller applies it to its own snapshot and gets a new immutable snapshot;
- there is no hidden online training, no mutation of the producing cycle and no feedback derived from the predictor's
  own unvalidated output;
- every update is reversible by keeping the previous snapshot.

Issue #129 adds further requirements:

- attribution to particular predictions;
- capped update magnitude and evidence age;
- preservation of the coarse-to-fine hierarchy;
- deterministic replay;
- rejection of untrusted or ambiguous outcomes;
- proof that repeated feedback, cross-task misattribution and unverified outcomes cannot silently amplify confidence.

The existing feedback paths do not fit:

- ADR 0012/0021 adapt `Node` frequency state in place from an `ActionStatus`.
- ADR 0029/0030 credit routing cohorts through a ledger and reconciler.

A concept prediction is judged by what the next window turned out to be, not by an action status. The snapshot is
also replaced, never patched.

## Decision

The new types live in `monada.neuron.reasoning.concept`. The contract's DAG table names `monada.neuron.evolution`;
the code sits in the model's package instead, because the update must build a `ConceptModel` through its
package-private constructor and read its arrays without copies. Opening those to another package would make the
learned arrays a public mutation surface.

- **Snapshot identity.** `ConceptModel` gains:
  - `revision()`: `0` when trained, plus one per accepted batch;
  - the norm of each centroid's exemplar sum;
  - per-leaf `feedbackCount`;
  - `fingerprint()`, the lower-case hex SHA-256 of a canonical encoding in format `ncm-concept-model/1`: format name,
    hierarchy, frame, the exact bits of every learned value, temperature, thresholds at every depth and revision. It
    is computed once, lazily. As with ADR 0031's digest, it is an identity and equivalence check, not an
    authentication boundary.

  Trained models predict bit-identically to before. N13 and every ADR 0043 fixture are unchanged. Memory grows by
  `8 · (concepts + leaves)` bytes (6,480 B for the medium workload instead of 6,400 B).
- **Attribution.** `ConceptPredictionReceipt` is caller-built from a library prediction (`of(model, query,
  candidates)`) or a stage window (`of(model, window, WindowOutcome.Predicted)`). It records the snapshot
  fingerprint, the window's `sourceReference` and `windowOffset`, the previous concept of the query, and the selected
  code and depth. Neuron retains no receipt. `WaveCognitionReport` is unchanged.
- **Outcome artifact.** `ConceptOutcome` holds `(receipt, verification, verifiedCode, observedEvidence,
  verifiedPreviousCode, originCycleOrdinal, verifierId)`.
  - The host's verifier, never the predictor, sets `VERIFIED`, `UNVERIFIED` or `CONTRADICTED`.
  - Only `VERIFIED` carries a label and the labeled window's pattern.
  - `polarity(hierarchy)` reports the result as `POSITIVE` (verified leaf in the predicted subtree), `NEGATIVE` or
    `UNKNOWN`.
- **Gate.** `ConceptFeedbackPolicy(ConceptFeedbackLimits).apply(current, batch, currentCycleOrdinal)` is pure and
  stateless. Each outcome is checked in a fixed order, and the first failing check is reported as a
  `ConceptFeedbackRejection`:

  | Order | Check | Rejection |
  |---|---|---|
  | 1 | The batch is no larger than `maxOutcomesPerUpdate` (default 64, at most 4,096); otherwise the whole batch is rejected | `BATCH_LIMIT` |
  | 2 | The receipt fingerprint equals `current.fingerprint()`. Outcomes of another model, task, thresholds or revision fail here | `MODEL_MISMATCH` |
  | 3 | The verifier is in the sorted `trustedVerifiers` allow-list | `UNTRUSTED_VERIFIER` |
  | 4 | The outcome is not unverified | `UNVERIFIED` |
  | 5 | The outcome is not contradicted | `AMBIGUOUS` |
  | 6 | `0 ≤ current − origin ≤ maxEvidenceAge` (default 64 caller cycles) | `AGE_OUT_OF_RANGE` |
  | 7 | The predicted code is a concept, and the verified and verified-previous codes are leaves | `UNKNOWN_CONCEPT` |
  | 8 | The evidence has the snapshot's frame and pattern version | `INCOMPATIBLE_EVIDENCE` |
  | 9 | Grouped by `(sourceReference, windowOffset)` in a `TreeMap` (no hash order): repeats of one label are rejected after the first; different labels reject every outcome of the window | `DUPLICATE` / `AMBIGUOUS` |
  | 10 | The leaf's feedback count stays at most `maxFeedbackShare` × its training windows (default 1.0). This bounds how far feedback can move a leaf. A leaf with no training window never accepts feedback | `CONCEPT_CAP` |

- **Update.** An accepted outcome counts as one more labeled window of its verified leaf, exactly as the trainer
  counts one:
  - one leaf marginal count;
  - one transition from the verified previous leaf, when one is given;
  - for a non-silent pattern, its unit feature added to the exemplar sum of the leaf and every ancestor, so inner
    concepts keep pooling their leaves.

  Each touched sum is restored once per batch as unit centroid × stored norm, then re-normalized. The predicted code
  never enters the update: positive and negative outcomes with the same verified label give bit-identical snapshots.
  A confirmation therefore adds no more confidence than an independently labeled window (the confirmation-bias
  guard). Nothing is ever subtracted (ADR 0029).
- **Result.** `ConceptModelUpdate` is one of two values:
  - `Updated(previous, next, applied, rejected, work)`, where `next` has revision + 1 and the same hierarchy, frame,
    temperature and thresholds;
  - `Unchanged(previous, rejected, work)`, which keeps the same instance.

  `Work` reports considered, applied, positive and negative outcomes, exemplars added and copied bytes. The previous
  snapshot is never modified, so rollback means keeping it. The next cycle runs only with the snapshot the caller
  passes to a new `WaveCognitionReasoningStage`.
- **Replay.** The same base, batches and ordinals give the same fingerprint. An update equals a retrain on the
  training windows plus the accepted outcomes: counts match exactly and centroids match within `1e-12` per component
  on the unit fixture (`ConceptFeedbackPolicyTest`). The only difference is rounding of the restored sums.
- **Cost.** Per batch of B outcomes, the gate costs O(B log B). The sums cost O(B · depth · bins). One copy of the
  learned arrays costs O(model) bytes, and the fingerprint is O(model). Retained size does not grow with feedback;
  only counts change.

## Alternatives Considered

### Replay-only updates (retrain from training data plus accepted outcomes)

This is exact, but every caller would have to keep the full training corpus and every past outcome, and each update
would cost O(corpus). Storing one norm per concept gives the same snapshot up to rounding at O(batch) work. Replay
stays the reference that the tests and the G5 control `C-replay` check against.

### Patch the snapshot in place

Patching would break ADR 0043's immutability, the contract's reversibility and the "no mutation of the producing
cycle" rule. Rejected.

### Reinforce or penalize the predicted concept

Adding mass to the predicted concept on a positive outcome, or removing it on a negative one, makes the update depend
on the predictor's own output. That is the self-reinforcing loop the contract forbids, and it lets repeated
confirmations amplify confidence. Rejected in favor of label-driven evidence.

### A routing-style ledger and reconciler (ADR 0029/0031)

A ledger would let late outcomes of an older revision be reconciled, but at the cost of a caller-owned O(outcomes)
ledger and rebuild machinery. The strict fingerprint match makes a replayed batch a `MODEL_MISMATCH` without any
retained state. The cost is that outcomes of a superseded revision are dropped, so callers batch per snapshot.
Deferred.

### Learn thresholds or temperature online

These parameters were chosen on non-test data (contract section 8.4). Changing them from feedback would be hidden
re-selection. They are copied unchanged.

## Consequences

- Feedback is opt-in and caller-driven. Memory-off and no-feedback runs are bit-identical to ADR 0043/0044 behavior.
- Late outcomes of an older revision are rejected (`MODEL_MISMATCH`) and never merged. A host that wants them must
  re-predict or replay.
- `CONCEPT_CAP` and `maxEvidenceAge` are safety bounds, not tuned hyperparameters. G5 registers the values it uses.
- Feedback can only add labeled evidence. It cannot correct a bad hierarchy, temperature or threshold.

## Follow-Up

- #129 H5 (control E, `docs/benchmarks/next-concept-g5.md`): does validated feedback improve later held-out windows
  without leakage?
- #130: control E reused with D, plus the consolidated G5 report.
- A ledger-based reconciliation of late outcomes, if a host needs it.
