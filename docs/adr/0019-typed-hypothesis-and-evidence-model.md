# ADR 0019: Typed Hypothesis and Evidence Model

## Status

Accepted

## Context

The cognitive flow names `Hypothesis -> Evaluation`, but `REASONING` only exchanged `List<Signal>`
with the next stage. Neuron had no first-class hypothesis, evidence, or provenance representation,
and the cycle had no way to hand typed upstream data to a later stage: typed stage results were only
collected in `CognitiveCycleResult.stageResults()`. Overloading `Signal` (ADR 0005), using metadata
maps, or coupling to an LLM or memory type would violate the architecture boundaries.

## Decision

Hypotheses are **ephemeral, cycle-local cognitive artifacts** owned by Neuron reasoning. They are
not long-term memory and are never persisted by Neuron; useful experience belongs to the Resonance
Store boundary (ADR 0001).

The `monada.neuron.reasoning` package defines immutable records and one sealed hierarchy:

- `Hypothesis(sequence, Proposition, List<Evidence>)`. Identity within a cycle is `sequence`, the
  contiguous index assigned by `HypothesisSet`; equality is structural. No UUID is allocated.
- `Proposition(domain, code)`: an opaque modality-neutral symbolic statement. Equal propositions are
  equivalent claims, and one set holds at most one hypothesis per proposition.
- `Evidence` (sealed): `SignalEvidence` references a cycle-local signal occurrence sequence from
  `CognitiveContext`, which the cycle validates against the accepted occurrences; `MemoryReferenceEvidence` carries the opaque `String` that
  `ResonanceMemoryResult.reference()` defines. Both contracts share one deliberate bound,
  `ResonanceMemoryResult.MAX_REFERENCE_LENGTH` (128, versus 35 characters for current `rs-`
  adapter references), so evidence never retains large external payloads. Each has an `EvidenceRelation` (`SUPPORTS`, `CONTRADICTS`, `NEUTRAL`) and a finite
  weight in `(0, 1]`. Evidence never retains signals, graphs, or provider objects.
- `HypothesisLimits(maxCandidates, maxEvidencePerCandidate)`: explicit capacities, separate from
  `CognitiveBudget` so existing budget call sites are unaffected.
- `HypothesisSet`: immutable, validated snapshot with O(1) lookup by sequence.
  `HypothesisSetBuilder` is the sequential accumulator; capacity exhaustion is reported through
  `OptionalInt`/`boolean` rather than exceptions, and an equivalent proposition merges evidence
  into the existing candidate deterministically. Ordering is insertion order; nothing depends on
  hash iteration.
- `ReasoningCognitiveStageResult` is a typed `REASONING` result carrying the `HypothesisSet` next
  to the normal `outputSignals()`. Signal admission trims only the signals; hypotheses are
  independent of them.

`CognitiveStage` gains a `default` overload of `execute` that also receives the cycle-normalized
`Optional<CognitiveStageResult>` of the preceding executed stage. The default delegates to the
existing method, so current stages are unchanged. `DeterministicCognitiveCycle` passes the previous
stage's normalized result (empty for the first stage). `EVALUATION` reads hypotheses with
`ReasoningCognitiveStageResult.hypothesesOf(previousResult)`.

An action-outcome evidence variant is deferred: `ActionOutcome` exposes no identifier and `ACTION`
runs after `REASONING`, so no resolvable same-cycle reference exists. It can be added to the sealed
hierarchy once an outcome-reference lifecycle is decided.

A result may declare `retainsTypedHandOff()`. When the previous result retains a typed artifact
(non-empty hypotheses), the cycle runs the next non-Aeon stage even with zero signals instead of
ending with `NO_SIGNALS`, but only if that stage opts in through
`CognitiveStage.acceptsTypedOnlyHandOff()`; stages that require signals (such as `ACTION`) keep
the default and the cycle still ends with `NO_SIGNALS`. The cycle also calls `validateProvenance(context)` after each stage so
signal evidence pointing at an occurrence the context never accepted fails the cycle.

`Signal` is unchanged. A prior/confidence input is intentionally omitted until an evaluation
consumer defines its semantics. Scoring, top-K selection, and hypothesis generation are follow-ups.

## Alternatives Considered

### Typed slot in `CognitiveContext`
Rejected: it turns the trace/budget context into a data bus and mixes provenance with working state.

### Evaluation stage holding a reference to the reasoning stage
Rejected: hidden mutable coupling between stages makes replay and isolated testing fragile.

### Limits as fields of `CognitiveBudget`
Rejected: breaks every existing constructor call and conflates signal budgets with candidate
capacity.

### Carry hypotheses inside `Signal`
Rejected by ADR 0005 and the issue: `Signal` stays a compact transport primitive.

## Consequences

- Size is bounded by `maxCandidates * maxEvidencePerCandidate` evidence records. Two different
  quantities are reported separately (Java 27, four evidence entries per hypothesis):
  - **Transient allocation**: `HypothesisAllocationTest` measures thread-allocated bytes while
    building (builder growth plus the final snapshot), about 500 B per hypothesis at 10, 100, and
    1,000 hypotheses. This is not retained size.
  - **Retained footprint (modeled)**: `HypothesisFootprintModel` estimates only the final
    immutable graph on a 64-bit HotSpot JVM with compressed references (12 B header, 4 B
    references, 8 B alignment): about 242, 237, and 236 B per hypothesis at 10, 100, and 1,000
    hypotheses (2,424; 23,664; 236,064 B in total). It is an estimate, not a measurement.
  Both grow linearly without object explosion. A primitive/contiguous layout is deferred until a
  benchmark justifies it.
- Evidence sequences are only meaningful within their cycle; snapshots must not outlive it.
- Any future stage can consume typed upstream results without changing `Signal` or the context.
- Follow-ups: evaluation scoring/top-K, optional hypothesis trace events, and a compact layout if
  measurements demand it.
