# ADR 0021: Cross-Cycle Outcome Feedback Handoff

## Status

Accepted

## Context

ADR 0011 gave `ACTION` a typed `ActionOutcome` and ADR 0012 gave `ADAPTATION` a bounded
`AdaptationPolicy`, but nothing connected them. The canonical order is
`PERCEPTION -> MEMORY_RECALL -> REASONING -> EVALUATION -> ADAPTATION -> ACTION`, so the outcome of an
action does not exist yet when its own cycle adapts. Feeding it back inside the same cycle would need a
backward `ACTION -> ADAPTATION` edge or a reordering of the stages, and both would change the lifecycle
that ADR 0002, ADR 0011, and ADR 0019 rely on.

The cycle and the Monad are intentionally stateless across cycles (ADR 0009: one fresh bounded
`CognitiveContext` per cycle; `PrimaryMonad` is "not a global mutable context, history store"). A
learning loop therefore needs a place for the feedback to live between two cycles that does not turn
Neuron into a feedback store, which would duplicate the ownership of persisted experience that belongs
to Monada Resonance Store (ADR 0001).

Two further facts shaped the design. `ActionOutcome` exposes no identifier (ADR 0019 deferred an
outcome-reference variant), and `Hypothesis.sequence` is meaningful only inside its cycle. Finally,
`Node.history` was unbounded, so repeated adaptation across cycles grew memory without limit.

## Decision

**Lifecycle.** Feedback crosses cycles only through a value the caller holds:

```text
Cycle N:    ... EVALUATION -> ADAPTATION (feedback given to cycle N, if any) -> ACTION
Caller:     OutcomeFeedbackPolicy.derive(cycleResult, targetNodeIds, ordinal) -> Optional<OutcomeFeedback>
            the caller carries the artifact forward or drops it
Cycle N+1:  FeedbackAdaptationCognitiveStage(policy, targets, priorFeedback) occupies ADAPTATION
```

The canonical stage order is unchanged and no backward edge exists. `CognitiveCycle`,
`DeterministicCognitiveCycle`, and `PrimaryMonad` are not modified: the cycle is cheap to construct, so
the caller builds the cycle for N+1 with the stage that already holds the prior artifact. Neuron adds no
queue, session store, singleton, background service, or self-starting loop; nothing in Neuron executes
another cycle.

**Artifact.** `OutcomeFeedback(monadId, originCycleOrdinal, sourceStatus, producedObservationCount,
admittedObservationCount, disposition, entries, attributions)` is an immutable record. `entries` is an ordered list of
`FeedbackEntry(targetNodeId, targetSignal, score)`; `attributions` is an ordered list of
`HypothesisAttribution(proposition, evaluationScore)`. The record validates finite values, scores in
`[-1, 1]` that are never zero, at most `MAX_ENTRIES = 64` entries and `MAX_ATTRIBUTIONS = 16`
attributions, unique targets, and that the sign of every score matches the explicit
`FeedbackDisposition` (`REINFORCE` positive, `PENALIZE` negative, `NEUTRAL` with no entries and nothing
else). `producedObservationCount` must be zero for `REJECTED`, `UNAVAILABLE`, `TIMED_OUT`, and `FAILED`, as
`ActionResult` carries no observations for them, `admittedObservationCount` cannot exceed it (the difference
is provenance about the cycle budget, never reward), and `attributions` must name each `Proposition` once with
evaluation scores that never increase along the list, the order `EVALUATION` selects in, so a hand-built or
deserialized artifact cannot claim provenance or a rank no valid cycle could have produced. It holds typed
enums, counters, and stable identifiers only: no provider payload, exception,
Signal of the originating cycle, or `HypothesisSet`. The only Signal an artifact can carry is an optional
`FeedbackEntry.targetSignal`, the expected Signal an adaptation rule may need; the reference policy leaves it
unset. `originCycleOrdinal` is assigned by the caller,
because the cycle has no identity (ADR 0009) and Neuron must not invent a global counter.

**Attribution.** Targets are Node UUIDs supplied by the caller in the order that should receive credit.
Hypotheses selected by `EVALUATION` are attributed by `Proposition(domain, code)`, which is structural and
stable across cycles, together with the evaluation score (ADR 0020), in rank order. The cycle-local
`Hypothesis.sequence` is never stored. `ActionOutcome` still has no identifier; the artifact carries the
status and observation count instead of referencing the outcome object.

**Derivation policy.** `OutcomeFeedbackPolicy.derive(CognitiveCycleResult, List<UUID>, long)` is pure and
deterministic and returns empty when the cycle produced no action result.
`NoOpOutcomeFeedbackPolicy` always returns empty and is the control path; it takes the same
`OutcomeFeedbackConfig` as the reference policy and validates the ordinal and the same leading targets (up to
`maxEntries`) exactly like it, so swapping the control for the reference policy changes only whether feedback
is derived, never whether a workload runs. `NoOpOutcomeFeedbackPolicy.INSTANCE` uses the default configuration
and matches the reference policy only in that case.
`DeterministicOutcomeFeedbackPolicy` (configured by `OutcomeFeedbackConfig`) uses these semantics:

| `ActionStatus` | Disposition | Score per entry (default) | Rationale |
| --- | --- | ---: | --- |
| `SUCCEEDED` | `REINFORCE` | +1.0 | The requested effect was observed. |
| `PARTIALLY_COMPLETED` | `REINFORCE` | +0.5 | Part of the effect was observed. |
| `REJECTED` | `PENALIZE` | -0.25 | The capability refused the request; a weak signal against the choice. |
| `FAILED` | `PENALIZE` | -1.0 | The capability reported an execution failure. |
| `UNAVAILABLE` | `NEUTRAL` | none | Environmental; says nothing about the targets. |
| `TIMED_OUT` | `NEUTRAL` | none | Environmental; says nothing about the targets. |
| no action result | no artifact | none | The cycle ended before `ACTION`. |

**The cycle budget never redefines the action's status.** The status is what the capability reported.
`ActionResult.withAdmittedObservationPrefix` used to downgrade a `SUCCEEDED` result to `PARTIALLY_COMPLETED`
when the cycle's global signal budget admitted only a prefix of its observations, which made a budget limit
indistinguishable from a partial success and halved the reward of a fully successful action. It now keeps the
status, and `ActionCognitiveStageResult(outcome, producedObservationCount, observationAdmission)` records what
the cycle did with the output (`ObservationAdmission.COMPLETE` or `TRUNCATED`). Feedback is derived from the
reported status; the produced and admitted counts travel in the artifact for audit only:

| Capability reported | Cycle budget | Status kept | Admission | Feedback |
| --- | --- | --- | --- | ---: |
| `SUCCEEDED` | admits all | `SUCCEEDED` | `COMPLETE` | +1.0 |
| `SUCCEEDED` | truncates | `SUCCEEDED` | `TRUNCATED` | +1.0 |
| `PARTIALLY_COMPLETED` | admits all | `PARTIALLY_COMPLETED` | `COMPLETE` | +0.5 |
| `PARTIALLY_COMPLETED` | truncates | `PARTIALLY_COMPLETED` | `TRUNCATED` | +0.5 |

Truncation still ends the cycle with `CONTEXT_BUDGET_EXHAUSTED`; the action's outcome and the cycle's termination
describe different things. This amends ADR 0011, which described the downgrade.

Neutral outcomes produce an explicit `NEUTRAL` artifact with no entries instead of a fabricated reward
or penalty. Only the first `maxEntries` supplied targets are considered, in the supplied order, and a
repeated target among them is an `IllegalArgumentException`. With no considered target the artifact is
also neutral. Scalar scores carry no target Signal, so the baseline adaptation policy scales amplitude and
energy (ADR 0012); `FeedbackEntry` can carry a `targetSignal` for other policies.

**Consumption.** `FeedbackAdaptationCognitiveStage` occupies `ADAPTATION`. It indexes its target Nodes by
UUID once (two target Nodes with the same UUID are ambiguous and rejected at construction, not silently
ignored) (a `HashMap`, O(1) per entry; entry count is bounded by 64, and processing order is the
artifact's entry order, never the map's), applies the configured `AdaptationPolicy` to every entry whose
target it knows, records the usual `NodeAdapted` event for each, and passes the cycle's Signals through so
`ACTION` still runs. An entry whose target is unknown to the stage is ineligible: it is counted and
skipped, not an error, because a later cycle may legitimately be configured with different targets.
Neutral feedback applies nothing. `validate(monad)` rejects feedback whose `monadId` differs from the
executing Monad with an `IllegalArgumentException`; the cycle calls it before creating its context, so an
artifact from Monad A can never adapt Nodes through a cycle of Monad B and nothing is mutated. `AdaptationCognitiveStage` is untouched, so a cycle without feedback
behaves exactly as before. Reusing one stage instance for several cycles reapplies the same artifact; that
is the caller's explicit choice.

**Trace.** `CognitiveTraceEvent.FeedbackConsumed(originCycleOrdinal, sourceStatus, disposition,
entryCount, appliedCount, ineligibleCount)` is recorded once per consuming stage, within the existing trace
budget, after the stage's `NodeAdapted` events (which keep the artifact's entry order): the counters are only
known once every entry was visited. Derivation happens after the producing cycle has completed, when its `CognitiveContext` can no
longer record events, so there is no derivation event; the artifact itself is the record of derivation and
carries the ordinal that ties the later `FeedbackConsumed` event to its origin. The event holds counters
and enums, not entries, so the trace does not become persistence.

**Bounded Node history.** `Node.history` becomes a lazily allocated ring buffer of the most recent
`historyLimit` states (`Node.DEFAULT_HISTORY_LIMIT = 256`, `Node.Builder.historyLimit(int >= 0)`, zero
disables history). Append is O(1) amortised, never shifts elements, grows by doubling up to the limit, and
overwrites the oldest state afterwards, so retained memory per Node is at most `limit` references plus
the states. `getHistory()` still returns a read-only list ordered oldest to newest, but it is now a
snapshot taken at call time (O(size)) instead of a live view; `getHistorySize()` returns the retained count in
O(1) for callers that only need the size. This amends the unbounded-history behavior
of ADR 0012 and keeps adaptation across many cycles bounded.

## Alternatives Considered

### Add a backward `ACTION -> ADAPTATION` edge in one cycle

Rejected: the outcome does not exist when `ADAPTATION` runs, so the cycle would have to run `ACTION`
before `ADAPTATION` or loop, destroying the order every stage and ADR relies on.

### Reorder `ACTION` before `ADAPTATION`

Rejected: it makes same-cycle feedback convenient but changes the lifecycle for every stage and every
ADR that fixes the canonical order, solely for convenience, and the control path would no longer be the
existing reference cycle.

### Keep a feedback queue, session store, or history inside `PrimaryMonad` or the cycle

Rejected: it creates hidden global state, makes the cycle non-replayable from its explicit inputs, and
duplicates the persisted experience that belongs to Monada Resonance Store. A caller-held value keeps
ownership and lifetime visible.

### Persist feedback or write it into the Resonance Store from Neuron

Rejected as a non-goal: persistence needs its own contract and adapter (ADR 0001, ADR 0018). The artifact
is deliberately small and self-contained so such an adapter can store it later.

### Extend `AdaptationCognitiveStage` instead of adding a stage

Rejected: it fabricates a score of 1.0 by default (ADR 0012) and its round-robin Signal mapping is a
different contract. A separate stage keeps the zero-feedback path byte-for-byte the existing behavior.

### Add a trace event for derivation inside the producing cycle

Rejected: derivation needs the finished cycle result, which only exists once the context has completed.
Deriving inside a stage would have required either an extra post-`ACTION` stage position or reading the
outcome before it exists.

### Reference hypotheses by `Hypothesis.sequence` or retain the `HypothesisSet`

Rejected: the sequence is meaningless across cycles (ADR 0019) and retaining the set would keep the full
reasoning object graph alive for an artifact that may live as long as the caller chooses.

### Keep unbounded Node history and only measure it

Rejected: each consumed cycle can add one state per adapted Node, so memory would grow with the number of
cycles. The ring buffer bounds it at the cost of dropping the oldest states.

## Consequences

- A caller can close the loop deterministically: equal initial state and an equal feedback sequence give
  equal states, results, and artifacts (`FeedbackLoopCycleTest`, `FeedbackLoopEvaluation`).
- State diverges from the control only when feedback is both derived and consumed. Deriving without
  consuming leaves every state fingerprint bit-identical to the control.
- Adaptation saturates at the `AdaptationConfig` bounds. Once an amplitude is at its maximum, the
  baseline policy no longer transitions, and Node history stops growing; a constant reward reached 75
  history states in 299 cycles before the evaluation alternated outcomes. Callers should not read
  history length as a count of cycles.
- Cost is small relative to propagation. Modeled retained size of an artifact is about 64 B plus 36 B per
  entry (1,216 B for 32 entries; a model of a 64-bit HotSpot with compressed references, not a measurement).
  With 32 targets, history is at most `256 * 32 * 44 B`, about 352 KB in the model.
- The ring buffer adds three `int` fields to every `Node`, even one that never adapts, because the history array
  stays unallocated until the first transition. Measured retained heap per node (no edges, 400,000 nodes) grew
  by 8.1 B under classic 12-byte object headers (218.8 to 226.9 B) and by 16.5 B under JDK 27's default compact
  headers (186.8 to 203.3 B). `DeterministicWorkloadGenerator.estimateRetainedHeapBytes` now models 232 B per node
  (classic headers), up from 224 B.
- `Node.getHistory()` is a snapshot, and callers that held the live view no longer observe later
  transitions through it.
- `CognitiveTraceEvent` gains a permitted variant; exhaustive `switch` statements over it need a case.
- The derivation scores are a research baseline: they treat every considered target equally and do not
  attribute credit by contribution.

## Empirical Evidence

Linux x86_64, Intel Core i7-6500U (4 CPUs), Zulu 27, G1. Workload: 512 Nodes with average degree 3, 32
adaptation targets, a 300-cycle sequence of `PERCEPTION (Aeon, threshold routing) -> ADAPTATION -> ACTION`
with a deterministic fixture that alternates `SUCCEEDED` and `FAILED`, three warm-up and ten measured
iterations, topology regenerated outside the timed window. One op is one cognitive cycle. Command:

```bash
./gradlew :monada-neuron-evaluation:runFeedbackLoop
```

| Arm | Mean per 300 cycles | Alloc / cycle | State digest |
| --- | ---: | ---: | --- |
| control (no feedback) | 12.8 ms | 14.5 KB | `7a0224c382d96a28` |
| feedback derived, not consumed | 14.2 ms | 17.6 KB | `7a0224c382d96a28` |
| feedback consumed by baseline adaptation | 21.9 ms | 18.9 KB | `b7b52519380cbd8c` |

All eight semantic checks passed: cycles complete through `ACTION`, derived-not-consumed matches the
control, consumed diverges from cycle 2 onward, the consumed arm replays bit-identically, `TIMED_OUT`
outcomes never move a Node, reward raises and penalty lowers mean amplitude, artifacts stay within their
bounds, and Node history reaches exactly its limit (256 after 299 consumed cycles) while unconsumed arms
retain none.

The table is one exploratory run on a laptop CPU, not a gate. Four runs of the same command are not
consistent on latency: the consumed arm measured between 8.8 and 21.9 ms and the control between 12.8 and
19.4 ms, and in the last run the consumed arm was faster than the control. No latency difference between
arms is claimed. Allocation was steadier, but the size of the difference varied: deriving feedback cost
about 1.1 to 3.1 KB per cycle over the control, and deriving plus consuming about 3.2 to 4.5 KB, with the
consumed arm always the largest. Resident-set deltas in the report are dominated by topology regeneration
and are not interpretable per arm.

## Follow-up Work

- An adapter that stores `OutcomeFeedback` in Monada Resonance Store, if cross-session learning is needed.
- A target-attribution policy that credits Nodes by contribution (for example the Nodes that processed
  the Signals behind the selected hypotheses) instead of uniform credit.
- An action-outcome evidence variant for hypotheses, now that an outcome reference lifecycle exists
  through the caller-owned artifact (ADR 0019 deferred it).
- Target-Signal feedback entries once a rule needs them; the record already carries the field.
- `ResonanceMemoryCognitiveStage` still turns a `COMPLETE` recall into `PARTIAL` when the cycle budget truncates
  it, the same conflation fixed here for actions; it is out of scope for this change.
