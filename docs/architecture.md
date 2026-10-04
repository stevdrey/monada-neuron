# Architecture

## Overview

Monada Neuron is an experimental Java 27 cognitive system whose architecture separates cognition from long-term memory and from optional external AI/tool integrations.

The current implementation is intentionally small. The repository presently contains the Phase-1
Node model, immutable Signal and processing contracts, deterministic graph propagation, scalar
resonance with an optional SIMD batch backend, native Aeon coordination, and a bounded ephemeral
cognitive context. The module structure below is therefore a **target architecture**, not a claim
that every module already exists.

## Core Cognitive Flow

```text
Input / Observation
  -> Signal
  -> Perception / Interpretation Nodes
  -> Aeon-level coordination
  -> Resonance request when memory is needed
  -> Hypothesis / Reasoning
  -> Evaluation
  -> Adaptation / Evolution
  -> Action
  -> Outcome / Feedback
  -> (caller-carried) Adaptation of a later cycle
```

The final arrow is the explicit cross-cycle feedback handoff described below: an outcome never flows
backward into the cycle that produced it.

The flow is intentionally native to Monada Neuron. External LLMs or agent frameworks may participate through adapters, but they do not define this lifecycle.

## Conceptual Layers

```text
Primary Monad
  -> coordinates identity and global cognitive state

Aeons
  -> organize coherent cognitive capabilities

Nodes
  -> execute focused operations

Signals
  -> carry explicit structured information between cognitive stages

Adapters
  -> connect memory, models, tools, devices, and accelerators
```

## Current Phase-1 Model

The current domain baseline is under `src/main/java/monada/neuron/model`.

`Node` currently provides:

- UUID-based identity;
- a mutable current `FrequencyState`;
- scalar energy;
- `NodeType` classification;
- directed node connections;
- lazily allocated, bounded state history (a ring buffer of the most recent states, default limit 256).

This representation prioritizes correctness and inspectability. It should remain the reference behavior until scale measurements justify a different internal representation.

In particular, a future compact graph representation must not silently alter node identity, connection semantics, state transition behavior, or deterministic iteration requirements.

## Current Signal and Processing Model

`Signal` is an immutable, cycle-ephemeral information carrier composed of a modality-neutral `SignalKind` and a `FrequencyState`. Its amplitude is the signal intensity; the signal does not duplicate energy, identity, or sequence metadata.

Signal has no durable or cycle-local identity. Correlation and tracing belong to a surrounding cognitive context when required, rather than to the signal value. Signal equality is structural across its kind and frequency state.

`NodeProcessor` receives a `NodeView` and one input signal. The view exposes node identity, type, energy, and frequency state for observation but excludes graph connections and mutation operations. Processing therefore emits its complete observable output through `NodeProcessingResult` rather than changing the node. State transition and adaptation remain separate explicit behaviors.

`NodeProcessingResult` owns an immutable snapshot of its emitted signals. The list order is the observable emission order, including duplicate signals. An empty list represents successful processing with no output; failures propagate to the caller rather than being hidden as empty output.

## Current Scalar Resonance Model

`ResonanceMetric` defines the in-process cognitive operation for scoring two `FrequencyState`
values. The portable reference implementation is `ScalarResonanceMetric`; it is intentionally
scalar, deterministic, and independent from persisted recall or ranking in Monada Resonance Store.

For states `(a1, f1, p1)` and `(a2, f2, p2)`, the normalized score is:

```text
resonance = amplitudeSimilarity * frequencySimilarity * phaseSimilarity
```

The component similarities are:

- `amplitudeSimilarity = min(a1, a2) / max(a1, a2)` when both amplitudes are positive;
- the complete resonance is `0` when either amplitude is zero, including two silent states;
- `frequencySimilarity = 1` for equal frequencies, including two zero frequencies;
- `frequencySimilarity = 0` when exactly one frequency is zero, otherwise it is
  `min(f1, f2) / max(f1, f2)`;
- `phaseSimilarity = (1 + cos(delta)) / 2`, where each phase is wrapped to one cycle before
  calculating the wrapped phase difference `delta`.

The result is finite, symmetric, and bounded by `[0, 1]`. Relative amplitude or frequency
divergence monotonically lowers its factor while the other components remain fixed. Equal scores
are ties; ranking and stable tie-breaking belong to a caller that has ordering context.

The metric consumes only `FrequencyState`. A `Signal` caller extracts `frequencyState()`
explicitly; `SignalKind` and Node energy do not influence this reference formula. The implementation
uses `StrictMath` for reproducible phase operations and performs no avoidable allocation or boxing
on the successful calculation path.

This scalar implementation remains the semantic correctness oracle for future SIMD, native,
parallel, or accelerator backends. Alternative formulas require explicit evaluation and must not
silently replace it.

## Current Batch Resonance Backends

`BatchResonanceEvaluator` defines bulk scoring for independent `FrequencyState` pairs without
changing the scalar `ResonanceMetric` contract. `FrequencyStateBatch` is the immutable
Structure-of-Arrays representation for that workload: its amplitude, frequency, and phase values
are held in parallel primitive arrays, avoiding boxing and object-pointer traversal on the hot
path. The contract also supports direct parallel arrays and `FrequencyState[]` callers where an
array-of-objects representation is already available.

`ScalarBatchResonanceEvaluator` is the portable reference backend. It has no incubator dependency
and preserves `ScalarResonanceMetric` semantics, so it remains the correctness oracle and fallback.
`VectorBatchResonanceEvaluator` is an isolated Vector API implementation for sufficiently
large batches. `AdaptiveBatchResonanceEvaluator`, returned by
`BatchResonanceEvaluator.defaultEvaluator()`, uses that backend from 4 pairs onward only when the
runtime can resolve the incubating module and supports multi-lane vectors; otherwise it routes to
the scalar backend.

Vector API types remain outside the cognitive-domain contracts. A caller that does not resolve
`jdk.incubator.vector` continues through the scalar path, while the evaluation distribution adds
the module explicitly so its SIMD baseline can run. ADR 0013 records the capability boundary,
numerical-equivalence rules, and lifecycle of this experimental backend.

## Current Deterministic Graph Runtime

`SignalPropagationEngine` defines bounded Signal execution over directed Node connections. The
portable `DeterministicSignalPropagationEngine` is the reference path: it uses breadth-first
traversal, processes the start Node at hop zero, and counts each completed `NodeProcessor` call as
one step.

`Node` retains its Phase-1 `HashSet` adjacency. During one propagation, the runtime snapshots and
sorts each expanded Node's connections by ascending UUID once. Processor emissions remain in their
declared list order; each emitted Signal is then considered against targets in UUID order. Every
accepted delivery is independent, so Nodes and structurally equal Signals may be revisited without
deduplication. This preserves fan-in and meaningful duplicate emissions.

`PropagationConfig` requires explicit positive step and non-negative hop limits. A cycle therefore
terminates even when every arrival emits more work. `PropagationResult` snapshots all emissions in
global execution order and reports step-limit and hop-limit truncation independently. It is an
execution result, not the detailed cycle trace planned for the cognitive-context layer.

Routing is explicit through `SignalRoutingPolicy`. The named route-all configuration has no gating.
`ResonanceThresholdRoutingPolicy` optionally compares each emitted Signal state with the target
Node state through an injected `ResonanceMetric` and an inclusive normalized threshold. Resonance
is therefore selectable policy rather than global graph behavior.

The runtime is sequential, mutates no Nodes, and assumes callers do not modify Node state or graph
topology concurrently. It has no dependency on external orchestration frameworks or long-term
memory. ADR 0007 records the complete reference semantics that future compact or parallel backends
must preserve.

## Optional Compact Graph Runtime

`CompactGraphSnapshot` is an explicit compiled runtime view for a stable, closed collection of
canonical Nodes. Compilation sorts the Node UUIDs once, assigns dense internal indices, and stores
directed adjacency as CSR `offsets` and `targets` primitive arrays. It retains canonical Node
references so a `NodeProcessor` and `SignalRoutingPolicy` continue to observe the current domain
state rather than a copied physical state representation.

`Node` exposes a topology version that changes only after a successful connect or disconnect.
`CompactSignalPropagationEngine` validates that every compiled Node still has its captured version
before and after direct propagation; a stale snapshot fails clearly and requires explicit
recompilation. Frequency-state and energy transitions remain visible without invalidating the
snapshot. The compact engine uses a primitive parallel-array FIFO and does not sort adjacency or
look up UUIDs in its traversal hot path. Its direct behavior is checked against the deterministic
object engine; it is never selected implicitly.

The compact snapshot is not persistence and does not replace Node ownership. Because both the
object graph and snapshot coexist, retained-footprint evaluation reports the object topology, the
incremental CSR snapshot, and their combined structural estimate separately. Context-aware
propagation remains delegated to `DeterministicSignalPropagationEngine` so shared cognitive-budget
and trace semantics keep their established implementation. The compact path is sequential and
inherits the existing prohibition on concurrent topology mutation. ADR 0014 records this lifecycle
and fallback boundary.

## Current Aeon Domain and Coordination

`Aeon` owns the identity, cognitive-purpose classification, and deterministic membership of one
coherent cognitive capability. Its UUID and `AeonPurpose` are immutable. Members are canonical
`Node` references indexed by UUID in insertion order; duplicate UUIDs do not replace the original
member, and removing then re-adding a member appends it to the membership order. The exposed member
collection is a live unmodifiable view rather than a per-access snapshot.

`AeonInput` explicitly pairs an initial Signal with the UUID of its starting member. This avoids an
implicit root or broadcast rule and lets the coordinator resolve the canonical Node instance owned
by the Aeon. `AeonCoordinator` accepts an ordered input list, a `NodeProcessor`, and the bounded
`PropagationConfig`. `DeterministicAeonCoordinator` validates every starting membership before
processing and then executes inputs sequentially in declared order. Member UUIDs resolve to the
canonical Node objects stored by the Aeon; another Node object with the same UUID is not accepted
as a substitute.

Each input delegates traversal to `SignalPropagationEngine`; Aeon coordination does not copy or
reimplement the Node graph. A composed routing policy rejects connections whose targets are not
canonical Aeon member instances before applying the caller's policy. Each `AeonInputResult` retains
its input and complete `PropagationResult`, and `AeonCoordinationResult` snapshots those
associations in input order.

An empty input list succeeds with an empty result, including for an empty Aeon. A non-member start
fails before any propagation. Zero Node energy is not an inactivity flag: reached members are still
processed, while `NodeProcessingResult.noOutput()` ends only that propagation branch. Processing
and routing failures propagate without a partial result. Membership, Node state, and topology must
not change during coordination; the Phase-1 Aeon and graph runtime are sequential and not
thread-safe. ADR 0008 records these ownership and execution semantics.

## Runtime Backend Selection and Fallback

Monada Neuron provides an explicit, deterministic, and inspectable runtime selection layer
under `monada.neuron.runtime.selection`. The layer decouples high-level cognitive contracts from
hardware-specific and experimental implementations, ensuring that portable reference implementations
remain the first-class safety baseline while selecting optimized backends when beneficial.

The layer defines:

- `BackendId`: sealed hierarchy of strongly typed backend identifiers across runtime cognitive
  components:
  - `ResonanceBackendId`: `SCALAR` (reference oracle), `VECTOR_API` (Vector API SIMD).
  - `GraphBackendId`: `DETERMINISTIC_OBJECT` (reference BFS), `COMPACT_CSR` (CSR adjacency view).
  - `AeonBackendId`: `DETERMINISTIC_SEQUENTIAL` (reference sequential oracle), `BOUNDED_PARALLEL`.
  - `StateBackendId`: `HEAP_OBJECT` (reference), `HEAP_SOA`, `FFM_OFF_HEAP` (experimental).
- `RuntimeSelectionConfig`: immutable configuration record with safe reference defaults
  (`referenceDefault()`), benchmark-driven automatic selection (`autoDefault()`), and explicit
  forced-reference mode (`forcedReference()`) for testing.
- `FallbackPolicy`: explicit failure or fallback behavior (`FALLBACK_TO_REFERENCE` vs `FAIL_FAST`).
  When an explicitly requested backend is unavailable or semantically ineligible, the runtime never
  silently guesses: it either fails immediately or records structured diagnostic fallback rationale.
- `SelectionDiagnostic`: inspectable record detailing the selected backend identifier, selection reason
  (`SelectionReason`), relevant workload scale, fallback indicator, and diagnostic metadata.
- `RuntimeBackendSelector`: control-plane selector caching static JVM/hardware capabilities
  (`BackendCapabilities`) to ensure selection decisions execute in $O(1)$ with negligible overhead
  (18–97 ns in JMH microbenchmarks) and zero hot-loop allocation.

The supported backend matrix in `AUTO` mode is derived strictly from empirical benchmark evidence:

| Component | Default `AUTO` Backend | Crossover / Gate Constraint | Empirical Rationale |
| :--- | :--- | :--- | :--- |
| **Resonance** | `VECTOR_API` | $batchSize \ge 4$ (primitive SoA, bounded by vector lane width) / $\ge 32$ (`FrequencyState[]` object arrays) | ADR 0013: 6.2x–6.7x speedup, $< 0.0001$ B/pair allocation; object arrays $< 32$ delegate to scalar oracle |
| **Graph Propagation** | `DETERMINISTIC_OBJECT` | Opt-in snapshot only (`containsCanonical(startNode)`) | ADR 0014: CSR failed 75% allocation gate; slower on threshold routing; requires canonical start node |
| **Aeon Coordination** | `DETERMINISTIC_SEQUENTIAL` | Contextual always sequential | ADR 0016: Parallel contextual was 38.4% slower and 39.4% more allocation |
| **Node State Layout** | `HEAP_OBJECT` | Heap object reference | ADR 0015: FFM retained as experimental layout; not promoted |

ADR 0017 records the selection semantics, capability detection rules, and fallback lifecycle.

## Current Cognitive Context and Cycle Trace

`CognitiveContext` owns mutable working state for exactly one active, sequential cognitive cycle.
It is created with an explicit `CognitiveBudget` for global completed processing steps, accepted
Signal occurrences, and retained trace entries. It is neither thread-safe nor reusable: its owner
completes it once with a success or failure outcome to obtain a `CognitiveCycleSnapshot`, or closes
it to discard the cycle. Both terminal paths clear the context's mutable retained references.

The context assigns ordered, cycle-local sequences to accepted propagation inputs, processor
emissions, enqueued deliveries, and inputs/outputs admitted for non-Aeon cognitive stages. This
keeps correlation outside the identifier-free `Signal` value. Completed Aeon input results are also
retained in their completed order. No context API persists data, exposes a durable cycle identity,
records timestamps, or retains exception objects.

The contextual deterministic runtime is available through `CognitiveSignalPropagationEngine` and
`CognitiveAeonCoordinator`; the existing propagation and Aeon contracts retain their exact legacy
behavior. The contextual route shares one budget across all Aeon calls using the same context.
Successful `NodeProcessor` calls consume steps. Inputs, accepted emissions, enqueued deliveries,
and non-Aeon stage inputs/outputs consume signal capacity. The cycle admits non-Aeon candidates
left-to-right and retains only the admitted prefix. When either budget would admit no more work,
the runtime reports truncation in the context rather than throwing a budget exception.

The diagnostic trace uses stable event types for Aeon-input start/completion, completed Node
processing, and accepted routes. It deliberately excludes rejected routes, clocks, threads, and
provider-specific values. Its events retain only identifiers, counters, and accepted-occurrence
sequences; accepted `Signal` values exist only in bounded signal occurrences, so the trace does not
retain a signal rejected by the shared signal budget. Trace storage retains the first configured
entries in append order; later events increase an omission count but do not alter cognition.
Outcome and resource counters remain available in the immutable snapshot even when trace retention
is exhausted. ADR 0009 records these lifetime, resource, and diagnostic semantics.

## Current Primary Monad and Reference Cognitive Cycle

`PrimaryMonad` is the stable cognitive identity above Aeons. It owns an insertion-ordered
`LinkedHashMap<UUID, Aeon>` of canonical Aeon references: the first registration for a UUID remains
canonical, and remove/re-register appends the Aeon to the ownership order. The Monad is deliberately
not a global mutable context, history store, framework controller, or exclusive owner of an Aeon.
As with Aeon membership, registration is sequential and must not change while a cycle executes.

`DeterministicCognitiveCycle` is the control-plane reference path. Its optional stages always run
in the canonical order below, never in caller-provided order:

```text
PERCEPTION -> MEMORY_RECALL -> REASONING -> EVALUATION -> ADAPTATION -> ACTION
```

`MEMORY_RECALL` can use `ResonanceMemoryCognitiveStage`, backed by the Neuron-owned
`ResonanceMemoryPort`. It submits one ordered Signal batch with an explicit result limit, then
forwards the input Signals followed by the adapter-ordered recalled prefix. `COMPLETE` with no
matches, `UNAVAILABLE`, `TIMED_OUT`, and expected `FAILED` responses all preserve the input flow;
`PARTIAL` forwards the available prefix. The typed response remains visible in
`ResonanceMemoryStageResult`, while unexpected adapter failures follow normal cycle-failure
semantics.

`EVALUATION` can use `HypothesisEvaluationCognitiveStage`, backed by a Neuron-owned
`HypothesisEvaluationPolicy`. It scores the hypotheses of the preceding `REASONING` result and
selects a bounded, ranked subset; the reference policy is deterministic, side-effect free, and
documented in ADR 0020. The typed `EvaluationCognitiveStageResult` keeps the ranked `HypothesisEvaluation`,
which carries the set it scored, with per-candidate score breakdowns, and passes input signals through
so later stages still run. Evaluation observes hypotheses only; Node mutation remains the
responsibility of `ADAPTATION`, and memory ranking remains with the Resonance Store.

`ADAPTATION` can use `AdaptationCognitiveStage`, backed by an `AdaptationPolicy`. It evaluates
incoming feedback against eligible target Nodes and applies bounded in-place state and energy
transitions. `NoOpAdaptationPolicy` provides an immutable reference baseline where `adapted = false`,
while `DeterministicBaselineAdaptationPolicy` calculates bounded, deterministic updates: when a target
signal is provided, positive feedback shifts amplitude, frequency, and phase towards the target, while
negative feedback attenuates amplitude/energy and diverges from error-causing frequency; when scalar
feedback is provided without a target signal, amplitude and energy scale proportionally while frequency
and phase remain unchanged. Each adaptation decision is recorded as a `NodeAdapted` event in
`CognitiveContext` without persisting feedback state to disk or duplicating Monada Resonance Store.

`ACTION` can instead use `ActionCognitiveStage`, backed by a Neuron-owned `ActionCapability`.
It submits one ordered, bounded Signal batch and exposes an `ActionOutcome` containing the admitted
request and typed result. `SUCCEEDED` and `PARTIALLY_COMPLETED` may retain observations in
capability order; `REJECTED`, `UNAVAILABLE`, `TIMED_OUT`, and expected `FAILED` outcomes retain no
provider-specific payload and do not fail the cognitive cycle. Action observations are the stage's
only outputs: input Signals are not implicitly forwarded. An `ActionCognitiveStage` and an
`AeonCognitiveStage` are alternatives for the single `ACTION` position, so the deterministic cycle
rejects a plan that configures both. The other positions can use `AeonCognitiveStage`, which
validates that the referenced Aeon is the Monad's canonical instance, that its immutable purpose
matches the position, and that its entry Node is a current member. Constraint and self-monitoring
Aeons remain valid cognitive capabilities, but do not receive independent positions in this initial
reference cycle.

The cycle creates and completes exactly one `CognitiveContext`. Initial signals enter the first
configured stage; each later stage receives all emissions from its predecessor in existing
input-result and global emission order. Missing stages are skipped without fabricating behavior.
An empty stage plan is a successful pass-through. If no signals remain before a pending stage, the
cycle ends with `NO_SIGNALS`; completing all configured stages ends with `COMPLETED`.

A context step/signal budget exhaustion ends the cycle with `CONTEXT_BUDGET_EXHAUSTED`. Non-Aeon
stage results are normalized to their admitted output prefix; a memory result whose complete recall
is cut by that prefix becomes `PARTIAL`, while an action result keeps the status the capability reported and
`ActionCognitiveStageResult` records the produced observation count and a `TRUNCATED` admission; neither
retains a rejected Signal. Aeon result accounting is unchanged,
avoiding double-counting. A local
propagation step/hop limit ends it with `STAGE_LIMIT_REACHED`; when both are observed, context-budget
exhaustion wins and the snapshot retains both underlying indicators. Successful results retain the
executed stage prefix, final signals, and context snapshot. Operational stage failures record a
typed stage-failure trace event, complete a `FAILURE` snapshot, and propagate a
`CognitiveCycleException` containing the failed stage, original cause, snapshot, and completed
prefix. The trace records stage start/completion/failure without retaining exceptions, timestamps,
or external payloads.

### Hypothesis and Evidence Boundary

`REASONING -> Hypothesis/Evidence -> EVALUATION` is typed. A reasoning stage may return a
`ReasoningCognitiveStageResult` that retains an immutable `HypothesisSet` next to its normal output
signals. `Hypothesis` records carry a cycle-local `sequence` (no UUID), an opaque `Proposition`,
and ordered `Evidence`: a sealed hierarchy of signal-occurrence references (validated against the
active context) and opaque memory references (bounded by the port's shared `MAX_REFERENCE_LENGTH`) with a `SUPPORTS`/`CONTRADICTS`/`NEUTRAL` relation and a finite weight in `(0, 1]`.
`HypothesisLimits` bounds candidates and evidence per candidate; `HypothesisSetBuilder` reports
exhaustion without throwing and merges equivalent propositions deterministically.

The cycle passes each stage the cycle-normalized result of the preceding executed stage through a
`default` `CognitiveStage.execute` overload, so `EVALUATION` reads hypotheses via
`ReasoningCognitiveStageResult.hypothesesOf(previousResult)`. A result that retains hypotheses
keeps the cycle running for the next non-Aeon stage that opts in through
`acceptsTypedOnlyHandOff()` even with zero output signals; other stages still end the cycle with
`NO_SIGNALS`. `Signal` is
unchanged.

Hypotheses are ephemeral cognitive artifacts of one cycle, not long-term memory, and are never
persisted by Neuron. Evidence holds only compact ids and never retains signals, graphs, or
Resonance Store/provider objects. Scoring and selection are not part of this boundary; they live
in the evaluation policy below. See ADR 0019.

### Hypothesis Evaluation Policy

`HypothesisEvaluationPolicy` is the Neuron-owned contract that turns a `HypothesisSet` into a
ranked, bounded `HypothesisEvaluation`. `ReferenceHypothesisEvaluationPolicy` is the semantic oracle
for future learned or model-backed evaluators. With `S` and `C` the summed weights of supporting
and contradicting evidence and `R` an optional resonance contribution:

```text
score = (S + R) / (S + R + C + 1)            score in [0, 1)
```

Neutral evidence is reported but not scored. Ranking is score descending, then lower
`Hypothesis.sequence()`, defined once in `HypothesisRanking` and shared by selection and result
validation; NaN scores are rejected. Top-K selection uses a primitive bounded heap
(`BoundedHeapSelector`, O(N log K)); `FullSortSelector` remains as the oracle. Resonance is an optional, support-only
component supplied by an adapter, and cognitive evaluation of hypotheses is distinct from memory
retrieval ranking. See ADR 0020.

### Cross-Cycle Feedback Handoff

`ACTION` runs after `ADAPTATION`, so an action's outcome cannot adapt the cycle that produced it
without a backward edge that would break the canonical order. Closing the learning loop is therefore
an explicit hand-over between two cycles that the *caller* owns (ADR 0021):

```text
Cycle N
  -> ... EVALUATION -> ADAPTATION (consumes feedback given to cycle N, if any) -> ACTION
  -> CognitiveCycleResult (ActionOutcome inside)

Caller / explicit orchestrator
  -> OutcomeFeedbackPolicy.derive(result, targetNodeIds, ordinal)  -> Optional<OutcomeFeedback>
  -> chooses whether to carry the artifact forward, or drops it

Cycle N+1
  -> FeedbackAdaptationCognitiveStage(policy, targets, priorFeedback) occupies ADAPTATION
  -> ... -> ACTION -> next artifact
```

`OutcomeFeedback` is an immutable, bounded record (at most 64 entries and 16 attributions) that holds
only the source `ActionStatus`, counters, stable Node identifiers, finite scores, and the
`Proposition` plus score of hypotheses the evaluation selected. An entry may also carry one optional target
Signal for adaptation rules that need it; the artifact retains no Signals of the cycle that produced it, no
provider payloads, exceptions, or reasoning object graphs, and it refers to Nodes and hypotheses by stable
identifiers rather than by cycle-local sequences. `FeedbackAdaptationCognitiveStage` only accepts feedback
produced by the Monad that executes the cycle. Neuron keeps no queue, session, or history of feedback: dropping the artifact
discards it, and persisting reusable experience belongs to Monada Resonance Store behind an explicit adapter.

`DeterministicOutcomeFeedbackPolicy` maps each status without fabricating reward: `SUCCEEDED` and
`PARTIALLY_COMPLETED` reinforce, `FAILED` and `REJECTED` penalize, and the environmental `UNAVAILABLE` and
`TIMED_OUT` derive explicit `NEUTRAL` feedback with no entries. `NoOpOutcomeFeedbackPolicy` is the control
path, and the existing `AdaptationCognitiveStage` is unchanged, so a cycle without feedback behaves exactly as before.
The consuming stage skips targets it was not configured with, applies entries in artifact order, and records one
`FeedbackConsumed` trace event after its `NodeAdapted` events, carrying the origin cycle ordinal, which correlates
consumption with derivation without making the trace a store, and the number of entries adapted, left unchanged
(for example by the no-op policy), and ineligible. The stage runs only if the cycle reaches `ADAPTATION`; when it
ends earlier (for example with `NO_SIGNALS`) the artifact was not consumed and stays with the caller, visible as
the absence of an `AdaptationCognitiveStageResult` in the cycle result. Repeated adaptation grows a Node's history by one state per transition,
so history is bounded (see Current Phase-1 Model). See ADR 0021.

## Host Runtime Boundary

External Java hosts, such as Monada Forge, embed Neuron through `monada.neuron.host.NeuronRuntime`
(ADR 0022) instead of wiring `DeterministicCognitiveCycle` and its stages themselves:

```text
Host application
  -> prepares Signals from its own domain input        (host-owned)
  -> NeuronRuntime.execute(signals[, budget])
       -> DeterministicCognitiveCycle (built once)     (Neuron-owned semantics)
            -> stages in canonical order
            -> optional ResonanceMemoryPort / ActionCapability
  <- CognitiveCycleResult or CognitiveCycleException
  -> interprets outputs and action observations        (host-owned)
```

The ownership split is deliberate. Neuron owns cognitive execution semantics: stage order, termination,
budget handling, and failure behavior all remain those of the cycle, which the runtime only delegates to.
The host owns domain-specific input preparation and output interpretation, so no issue, task, tracker,
provider, or UI concept enters Neuron. Memory stays behind `ResonanceMemoryPort` and external actions stay
behind `ActionCapability`; neither is mandatory, and neither a Resonance Store nor an LLM is required.

A `NeuronRuntime` is configured once with a `PrimaryMonad`, its stages, and an optional default
`CognitiveBudget` (overridable per call). Its composition is immutable and reusable across executions,
and the budget, termination status, and failures stay visible because the runtime returns the cycle's
own `CognitiveCycleResult` and propagates its `CognitiveCycleException`. The runtime is sequential and
not thread-safe, like the Monad and context it wraps. Its stage instances are shared by every
execution, so a stage that captures one cycle's input, such as `FeedbackAdaptationCognitiveStage`,
reapplies it each time; the cross-cycle feedback handoff (ADR 0021) therefore builds a runtime per cycle
with the feedback derived from the previous one. The low-level cycle, stage, memory, and action APIs
remain public for experiments and focused tests, and no preview, incubator, or native type is part of the
host-facing contract.

## Target Module Boundaries

As the repository grows, prefer boundaries similar to:

| Area | Responsibility |
| --- | --- |
| `monada-neuron-core` | Monad, Aeon, Node contracts, shared cognitive primitives and invariants. |
| `monada-neuron-signal` | Signal types, transformations, routing metadata, encoding boundaries. |
| `monada-neuron-aeon` | Aeon coordination and cognitive capability composition. |
| `monada-neuron-evolution` | Feedback, adaptation policies, strategy evolution and outcome learning. |
| `monada-neuron-resonance-adapter` | Embedded `ResonanceMemoryPort` adapter over the Monada Resonance Store `monada-api` (ADR 0018). |
| `monada-neuron-action` | Tool/action contracts and execution boundaries. |
| `monada-neuron-evaluation` | Cognitive benchmarks, diagnostics, reproducible experiments and regression baselines. |
| optional adapters | LLM providers, LangChain/LangGraph, Spring AI, hardware accelerators, native libraries. |

Do not create modules merely to match this table. Split only when ownership, dependency direction, build isolation, or independent evolution justifies it.

## Memory Boundary

Long-term memory belongs outside this repository:

```text
Monada Neuron
  -> asks for memory through an explicit resonance interface

Monada Resonance Store
  -> persists knowledge
  -> encodes/indexes persisted memory
  -> performs associative recall and ranking
  -> owns compatibility of stored memory formats
```

Neuron may hold ephemeral working state needed for an active cognitive cycle. Ephemeral state must not become an accidental second long-term memory subsystem.

### Production Resonance Store adapter

`monada-neuron-resonance-adapter` is the only module that sees both the Neuron memory contracts and
Resonance Store API types; the core never depends on it (ADR 0018). It holds one long-lived
`MonadaMemory`, runs one bounded `topK` store query per query Signal, merges deterministically, and
translates store failures into `UNAVAILABLE`/`FAILED` responses. Signal-to-text encoding and
recalled-content decoding are injected codecs with deterministic placeholder defaults. Verify with
`./gradlew :monada-neuron-resonance-adapter:test`; it requires the sibling `monada-resonance-store`
checkout; without it the module is not part of the build, and core builds are unaffected.

`ResonanceMemoryPort` is a synchronous, transport-neutral capability contract. Its request, result,
and response records use only Neuron Signals, an opaque adapter reference, a finite score, explicit
result limits, and adapter-supplied ordering. The contract exposes no persisted file format, vector
or index type, compatibility metadata, feedback log, or ranking algorithm from Monada Resonance
Store. The production adapter above owns those translations without changing the cognitive core.

## External Model Boundary

External models are capabilities, not identity:

```text
Cognitive core
  -> ModelCapability interface / adapter
     -> local model
     -> OpenAI-compatible model
     -> Ollama
     -> Groq
     -> other provider
```

The core must remain testable without network calls or provider credentials.

## Data-Oriented Execution

Performance-critical cognitive workloads should be designed around their access patterns.

Potential representations include:

- object-rich domain models for low-volume orchestration and clarity;
- primitive arrays or memory segments for dense numeric state;
- structure-of-arrays layouts for repeated SIMD-friendly operations over many nodes/signals;
- compact integer ids and adjacency arrays for large stable sparse graphs;
- bitsets or indexed flags for dense membership/state masks;
- bounded heaps or selection algorithms for top-K operations.

No representation is universally preferred. The chosen representation must be justified by workload, memory footprint, locality, update pattern, and measured behavior.

## CPU Execution Strategy

The default portable execution path is the CPU/JVM implementation.

Optimization order should generally be:

```text
correct scalar implementation
  -> algorithm/data-structure improvement
  -> allocation/layout improvement
  -> JIT-friendly primitive loops
  -> Vector API SIMD where beneficial
  -> bounded CPU parallelism where beneficial
```

The scalar/reference path should remain available for correctness testing when an optimized implementation becomes difficult to inspect.

## Native Memory and FFM

The Foreign Function & Memory API may be used for:

- off-heap numeric buffers;
- explicit memory alignment;
- memory-mapped data;
- interoperability with native libraries;
- controlled native/shared-memory adapters;
- reducing object overhead where a contiguous layout is demonstrably beneficial.

Memory ownership, `Arena` lifetime, alignment, endianness, concurrency, and failure behavior must be explicit. Off-heap memory is not automatically faster than heap memory; its use requires evidence or a clear interoperability requirement.

The Issue #27 state-layout experiment provides `NodeStateSnapshot` as an explicit, copied view of
the four mutable numeric channels (amplitude, frequency, phase, and energy). Its heap SoA and FFM
variants each use 32 logical bytes per node; UUIDs, node type, history, and graph edges remain on
the canonical `Node`. The FFM variant owns four native-order, eight-byte-aligned segments through
one confined `Arena`, is single-thread owned, and has no implicit synchronization or write-back.
It is an evaluation-only API: runtime selection, CSR traversal, and the reference `Node` model are
unchanged until a later measured decision promotes a backend. ADR 0015 records the experimental
layout, ownership lifecycle, and empirical non-promotion rationale.

## SIMD

The Vector API (`jdk.incubator.vector`) is an incubating API in Java 27 and may be used for vectorizable numeric hotspots such as bulk signal transforms, similarity operations, normalization, or activation updates.

Every SIMD implementation should have:

1. a scalar/reference equivalent;
2. tests for tails and small inputs;
3. numerical tolerance defined where floating-point reordering matters;
4. benchmark evidence on relevant CPU architectures;
5. no assumption that one preferred vector width is optimal everywhere.

## Heterogeneous / GPU Execution

Java SE 26 does not define a standard production GPU-offload API. Accelerator support therefore belongs behind an explicit optional boundary.

Potential experimental paths include:

- OpenJDK Project Babylon / HAT for Java-authored accelerator kernels;
- FFM bindings to native compute libraries;
- vendor-neutral OpenCL-style backends where appropriate;
- vendor-specific CUDA or other accelerator libraries when a concrete experiment justifies them.

GPU execution must not contaminate domain APIs with vendor types.

A GPU path is considered beneficial only when end-to-end measurement includes:

- host-to-device and device-to-host transfer;
- data layout conversion;
- kernel compilation/warm-up;
- synchronization;
- batching requirements;
- actual kernel time;
- fallback behavior.

## Concurrency Architecture

Use different concurrency mechanisms for different workload classes:

- virtual threads: blocking I/O and high-concurrency orchestration;
- structured concurrency: coordinated task lifetimes and failure/cancellation propagation when preview usage is accepted;
- bounded CPU worker parallelism: CPU-bound independent computation;
- partitioned ownership: mutable hot state when avoiding synchronization is possible.

Avoid unbounded CPU fan-out and shared mutable global registries.

## Dependency Direction

Preferred direction as the architecture grows:

```text
external adapters
       |
       v
application / coordination
       |
       v
Aeons / evolution / action
       |
       v
signals + core cognitive contracts
```

The resonance adapter depends on a memory protocol/client, not on storage implementation internals.

Evaluation code may depend on production modules. Production modules must not depend on evaluation-only code.

## Architecture Change Rule

A change should receive an ADR when it establishes or materially changes:

- cognitive ownership;
- memory ownership;
- signal semantics;
- module/dependency boundaries;
- a persistent or interoperability contract;
- a concurrency model;
- a hardware acceleration contract;
- reliance on preview/incubator APIs;
- a performance-critical data layout.
