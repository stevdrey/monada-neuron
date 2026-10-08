# ADR 0026: Forge Routing Ownership and Contract

## Status

Accepted (contract only; nothing described here is implemented)

## Context

Monada Forge needs vendor-neutral, stage-level recommendations of which authorized worker/model route to use, informed by
validated outcomes of earlier work. Three repositories are involved and their responsibilities must not blur:

- ADR 0001 keeps cognition in Neuron and long-term memory in Monada Resonance Store. The Store's accepted
  execution-memory v1 contract (not yet implemented; its issues #94 to #102 are pending) extends that ownership to execution
  history, outcomes, quality evidence, usage and `execution-sample/1` exports, and leaves routing and adaptation to Neuron
  and evidence collection and judgment to Forge.
- ADR 0005 keeps `Signal` free of identity. ADR 0023 added an opaque `HostExecutionContext`; ADR 0024 added a host
  `PerceptionCapability`; ADR 0011 gave `ACTION` a typed outcome whose `SUCCEEDED` status means the capability finished, not
  that the work was accepted.
- ADR 0021 closes the learning loop across cycles through a caller-held `OutcomeFeedback`, with no queue, store or session in
  Neuron. ADR 0017 selects internal computational backends and never external workers.

No typed route, catalog, routing decision or routing outcome exists, and `ActionRequest` carries Signals and an opaque host
context, not a route. Without a decision, a host would have to invent route identity inside Signals, infer acceptance from an
action status, or hide learned state in a global. The track of issues #59 to #68 implements the routing extension; this ADR
fixes the ownership and shape of it first.

## Decision

Adopt [Forge Routing Contract v1](../specs/forge-routing-contract-v1.md) as the normative contract for a small, opt-in,
experimental routing extension in a new `monada.neuron.routing` package. Type names in the contract are proposals.

- **Ownership.** Neuron owns the route *recommendation*, deterministic eligibility filtering of a caller-supplied catalog, and
  the semantics of routing adaptation. Forge owns authorization, quota and spending permission, context gathering, execution,
  evidence collection and judgment, and the issue of identifiers, ordinals, cutoffs and timestamps. The Store owns
  persistence, projections, recall and exports. The workflow-stage choice, the worker/model route choice, and the hardware
  backend choice are three separate decisions; v1 defines only the second.
- **A decision is advice.** `RoutingDecision` is `Selected`, `Abstain` or `NoEligibleRoute`, each carrying the same provenance (decision reference, catalog and policy versions, cutoff, state binding), and eligibility is evaluated before state, so no eligible route is always `NoEligibleRoute`. It reserves nothing, grants no
  authorization, is never an `ActionStatus`, and Forge revalidates before executing. Hard constraints are evaluated first and
  cannot be bypassed by learned preference. Authorization to spend is never inferred.
- **Versioning.** `RouteVersion` is a caller-assigned positive number ordered numerically; transient availability and the host `fallbackPriority` live in the catalog snapshot, outside the versioned descriptor, so changing them never discards learned history; `tier` and `overflowClass` stay versioned. Catalog identity `(RouteId, RouteVersion)` must be unique, and a preference snapshot is bound to the policy that built it, so changing policy requires an explicit full rebuild. State is admitted by scope, feature schema, route and policy versions, not by `catalogVersion`. Overflow is a dedicated host-set descriptor field, independent of billing mode and tier.
- **Route-side constraints.** Tools, locality and execution modes are typed, versioned descriptor values matched exactly against the request; an absent value fails closed.
- **Snapshot identity and state admission.** `catalogVersion` is an immutable snapshot identity that changes with any content change, there is at most one resource estimate per route and dimension, and a state is also admitted only when its cohort `mappingVersion` matches. An incompatible state always yields an abstention and never silently falls back to the baseline. Stage compatibility is a typed route value matched exactly.
- **Cohort binding.** The cohort of a decision is recorded in the decision, copied to the outcome and stored with the stage, so delayed observations are replayed into the right cohort without the original request.
- **Identity in a typed envelope.** Scope, task, execution, attempt, stage and route identity, version and fingerprints
  travel in typed routing records. `Signal` is unchanged. A selected route maps to a `Proposition` whose `code` is the route's
  index in the catalog snapshot that produced the decision; it is an in-cycle vehicle and never an identity.
- **Composition seam.** The routing policy is a pure function the host calls (Level A). An optional thin
  `RoutingReasoningStage` (Level B) occupies the existing `REASONING` position and obtains its per-execution inputs from a
  host-implemented resolver keyed by `HostExecutionContext`, as `PerceptionCapability` does. Because `ReasoningCognitiveStageResult` is a final record that cannot carry the decision, Level B returns a new `RoutingCognitiveStageResult` that retains the full `RoutingDecision` and the hypothesis hand-off, and must override the cycle's output-signal admission normalization so neither is dropped. No new stage kind, no change to
  `CycleInput`, `NeuronRuntime` or the canonical order, and no orchestrator.
- **Reference policy.** A fixed lexicographic list (host tier, host fallback priority, learned preference when compatible and
  sufficiently supported, known comparable resource estimate, canonical route id) instead of an unexplained blended scalar.
  Unknown resources are never zero, cheapest or fastest. Quality gates precede resource optimization.
- **Delayed, evidence-gated feedback.** A worker invocation result is not validated acceptance. Positive credit requires
  `VALIDATED_ACCEPTED` with stage-local attribution; unknown, pending, cancelled and environmental outcomes are neutral;
  absent attribution means no update. `RoutingFeedback` is a separate artifact; `OutcomeFeedback` is unchanged.
- **Caller-owned bounded state.** Preference is bound to caller-owned `Node` instances and updated through the existing
  `AdaptationPolicy`, not a new learning engine. The caller holds a mutable state store with applied-revision bookkeeping and hands `decide` only an immutable `RoutingPreference` value snapshot, which carries its scope, feature schema, policy and a `processedCutoff` watermark covering every ledger sequence examined, including neutral and superseded revisions; a snapshot newer than the request cutoff makes the policy abstain. Cohort Nodes start from a nonzero baseline amplitude and the mapping requires a positive `minAmplitude`, since the score-only adaptation path is multiplicative and a zero amplitude would never recover. A correction that supersedes any applied reward or penalty is handled by an explicit reset and an ordered replay (optionally from a checkpoint, with the ledger bound equal to the rebuild bound) from the Store export up to a cutoff, replacing each Node with one built from a complete versioned initial state (history disabled, so checkpoints and replays are identical), requiring a checkpoint cutoff strictly below the earliest superseded revision and keeping dormant cohorts of absent routes, because Node
  adaptation is not exactly invertible. Capacity exhaustion is reported and never evicts silently or crosses scopes.
- **Cost semantics.** Usage, hypothetical API cost, actual billing and subscription activity are different quantities.
  Hypothetical cost and pairwise comparison come only from Store evaluation tooling or host annotations; Neuron's production
  code does not depend on `monada-evaluation` and does no billing arithmetic.
- **No new platform requirement.** Stable Java 27 only; no preview, incubator, native or provider dependency; the Vector API
  backend and scalar fallback are unchanged.

## Alternatives Considered

### Route identity on `Signal` or in `FrequencyState`

Rejected by ADR 0005 and the domain-independence rule: it would add identity to the hot value and make it part of equality
and resonance semantics.

### A routing field on `CycleInput`, or a new canonical stage kind

Rejected: the first couples the host runtime facade to one extension; the second changes the lifecycle every stage and ADR
depends on (ADR 0002, ADR 0011, ADR 0019). A pure host-called policy plus an optional resolver-based stage needs neither.

### Routing inside `ActionCapability`

Rejected: it conflates advice with execution and would let an `ActionStatus` stand in for acceptance.

### Routing state in `PrimaryMonad`, a singleton, or a session

Rejected for the reasons of ADR 0021: hidden global state, no replay from explicit inputs, and a second long-term memory.

### A new count-based or probabilistic learner

Rejected: it would add a second generic learning engine beside `AdaptationPolicy`, invite uncalibrated probability claims,
and duplicate experience that belongs to the Store. The existing Node-bound path is reused and its values are preference
ordinals.

### A blended scalar score of quality, similarity, price and unknowns

Rejected: it hides policy tradeoffs, treats unknowns as numbers, and lets cost offset failed gates. A fixed lexicographic list
is explainable and testable, and unresolved tradeoffs become `Abstain`.

### Subtract a superseded reward from state

Rejected: adaptation saturates at configured bounds, so exact inversion is not possible. Deterministic reset and ordered
replay is the only correct rebuild.

### Persist routing state or outcomes from Neuron

Rejected as a non-goal: persistence belongs to the Store (ADR 0001, ADR 0018).

## Consequences

- Hosts get an explicit, versioned, bounded contract for routing advice and feedback before any code is written, and each
  later issue in the track has fixed semantics, bounds and a dependency order to implement against.
- All behavior is additive and opt-in; existing public signatures, defaults, `OutcomeFeedback` behavior and the canonical
  stage order are unchanged.
- The Store's execution-memory contract is accepted but not implemented, so end-to-end behavior with real exports is
  unverified until #64 and #68. The contract names those dependencies and does not claim the types or Store capabilities exist.
- Routing quality, cost and adaptation effect are unmeasured. Footprint numbers are a model. No improvement claim is made until
  the replay evaluation (#67) provides a reproducible workload and controls, and replay on observed exports cannot prove
  counterfactual or online improvement.
- Rebuilding after a correction costs a replay bounded by 4,096 observations per call; callers must page the Store export.
- A wrong or stale catalog yields wrong advice; Forge's revalidation before execution is the safety boundary.

## Follow-Up Work

- #60 to #68 implement the contract in dependency order (the shared value types `RoutingPreference`, `CohortMapping` and `RoutingObservation` are introduced early, by #62 and #63, so no issue depends on a later one); each must re-inspect `main`, the merged prerequisites, and the exact
  Store public API before use, and may refine signatures but not semantics without updating the contract and this ADR.
- Decide in a later ADR, if a concrete need appears, whether a resource-aware or exploration policy is added, and how routing
  chains into a PERCEPTION Aeon (ADR 0024 deferred this).
