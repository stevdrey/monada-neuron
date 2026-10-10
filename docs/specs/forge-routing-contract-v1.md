# Forge Routing Contract v1

Status: **proposed contract; implemented so far: `TaskFeatures` and its signal encoder (#60, Section 15), the route catalog and eligibility filter (#61, Section 16), and the routing policy, decisions and composition seam (#62, Section 17).** Decision record: [ADR 0026](../adr/0026-forge-routing-ownership.md).
Issue: [#59](https://github.com/stevdrey/monada-neuron/issues/59) (Forge Routing 1/10).

This document is the normative v1 contract that issues #60–#68 implement. Later issues may refine exact Java
signatures, but must not change the semantics defined here without updating this document and ADR 0026 first.

Normative words: **must**, **must not**, **should** and **may** carry their usual specification meaning.

Every type and package name below (`monada.neuron.routing`, `TaskFeatures`, `RouteCatalog`, `RoutingDecision`, ...) is a
**proposed name**. At the inspected baseline (`main` at `d1dd569`) none of them exists; since #60, `TaskFeatures` and the other types of `monada.neuron.routing.features` (Section 15), and since #61 the types of `monada.neuron.routing.catalog` (Section 16), and since #62 the policy, decision, preference and composition types of `monada.neuron.routing` (Section 17), are implemented. Types that do exist are named with
their current package and are described only as they exist today.

## Background

Monada Forge coordinates coding agents. For each workflow stage it needs a vendor-neutral recommendation of which
authorized worker/model route to use, informed by validated outcomes of earlier work. ADR 0001 keeps long-term memory in
Monada Resonance Store; the Store's accepted
[execution-memory contract v1](https://github.com/stevdrey/monada-resonance-store/blob/main/docs/specs/execution-memory-contract-v1.md)
defines the ledger, outcomes, quality evidence, usage and `execution-sample/1` exports. This contract defines what Neuron
adds on top: routing advice, outcome-driven adaptation, and the host-facing envelopes that carry identity and provenance.

## Current State

Inspected `main` at `d1dd569` (Java 27). Already implemented and **reused unchanged**:

- `monada.neuron.host.NeuronRuntime` and `CycleInput` (ADR 0022); `HostExecutionContext` / `HostReference`, opaque and
  request-scoped (ADR 0023); `PerceptionCapability` and `ActionCapability` boundaries (ADR 0024, ADR 0011).
- Typed `Hypothesis`, `Evidence`, `Proposition(int domain, long code)` and `HypothesisEvaluation` (ADR 0019, ADR 0020).
- Caller-carried `OutcomeFeedback` and `FeedbackAdaptationCognitiveStage` (ADR 0021), `AdaptationPolicy` and the bounded
  `Node` history.
- `RuntimeBackendSelector`, which selects *internal computational backends* (SCALAR/VECTOR_API, object/CSR graph, ...). It
  never selects external workers or models.

Not present: any typed route, worker catalog, routing decision, or routing outcome. `ActionRequest` carries Signals and an
opaque host context, not a route. `Signal` carries no identity (ADR 0005).

## Goal

Define a small, vendor-neutral, **experimental and opt-in** routing extension:

1. stage-level worker/model recommendations that are advice only;
2. evidence-driven learning from validated outcomes, with explicit credit assignment;
3. exact ownership, identity, provenance and version rules so replay is possible;
4. an explicit composition seam into the existing host and cognitive APIs.

## Non-Goals

No production code, build or dependency change, provider/network call, Forge or Store change, persistent learned state,
automatic execution, quota polling, exploration policy, background loop, or second orchestrator. This document does not
implement or promise any of #60–#68.

## 1. Ownership and Boundaries

| Concern | Neuron | Forge (host) | Resonance Store |
| --- | --- | --- | --- |
| Which workflow stage runs next | No | **Owns** | No |
| Which worker/model route to *recommend* for a stage | **Owns** (advice) | Consumes advice | No |
| Which internal hardware/algorithm backend runs | `RuntimeBackendSelector` (unchanged) | No | No |
| Route authorization, quota, spending permission | No: never infers it | **Owns** | No |
| Context gathering, task text, repository access | No | **Owns** | No |
| Executing the worker | No | **Owns** | No |
| Judging evidence (tests, security, review, ...) | No: never inspects code or calls a judge | **Owns** | No |
| Issuing IDs, ordinals, cutoffs, timestamps | No: never generates them | **Owns** | No: records what the caller supplies |
| Eligibility filtering of a caller-supplied catalog | **Owns** (deterministic, fail closed) | Supplies the catalog | No |
| Routing preference/adaptation state | **Owns the semantics**; state is caller-held | Holds the snapshot | No |
| Persisting experience, outcomes, usage | No | Records through the Store API | **Owns** (ledger, projections, recall, export) |
| Recall similarity and ranking of stored experience | No | Optional consumer | **Owns** |
| Hypothetical API cost and pairwise comparison | No arithmetic in Neuron's production code | Optional consumer | Evaluation tooling only (#99, #100) |

Three decisions are deliberately distinct and must never be conflated:

| Decision | Owner | Contract |
| --- | --- | --- |
| Workflow-stage choice (plan, implement, review, QA, ...) | Forge | Out of v1 scope. A stage is an opaque caller token. |
| Worker/model route choice for one stage | Neuron (advice) | This contract. |
| Hardware/algorithm backend choice | Neuron runtime | `RuntimeBackendSelector`; untouched. |

## 2. Identity, Provenance and Versioning

Identifier rules mirror the Store contract so that one value means the same thing in both repositories.

- `ScopeId`, `TaskId`, `ExecutionId`, `AttemptId`, `StageId`, `RouteId` and `StageKind` are **caller-issued opaque tokens**:
  1–128 Unicode code points, no control characters, no leading or trailing whitespace, compared with exact
  `String.equals`. Neuron never generates, parses, normalizes or hashes them.
- A `RouteId` is stable across versions of the same route. `RouteVersion` is a **positive `long` (at least 1) assigned by
  the caller** and changes when the route's *behavioral* descriptor changes. The fields are classified exactly once:

  | Class | Fields | Effect of a change |
  | --- | --- | --- |
  | Versioned descriptor | worker, provider, model, effort, capabilities, tools, locality, execution modes, limits, stage compatibility, `billingMode`, `overflowClass`, `tier` | New `RouteVersion`; the new version starts a new cohort and inherits nothing. |
  | Catalog-snapshot value | `availability`, `fallbackPriority`, resource estimates | No new `RouteVersion`; history is kept. The snapshot value in force at decision time is the one `decide` uses. |

  A route is identified by `(RouteId, RouteVersion)`.
- **Canonical route order**, used everywhere an order is needed (catalog order, `Proposition.code`, final tie-breaker):
  `RouteId` ascending by Unicode code point, then `RouteVersion` ascending numerically. `"2"` therefore precedes `"10"` only as
  a number, never lexically, because versions are numbers.
- The correlation envelope is typed and explicit. It never travels in `Signal` (ADR 0005), never in
  `HostExecutionContext` beyond the existing opaque references (ADR 0023), and never in `ResonanceMemoryRequest`.
- All timestamps are caller-supplied. No routing code calls a clock, a random generator, or reads the environment.

Provenance fields, all opaque strings of at most 128 code points compared by equality only:

| Field | Meaning |
| --- | --- |
| `sourceFingerprint` | Source revision of the work (e.g. a Git SHA); descriptive, never resolved by Neuron. |
| `contextFingerprint` | Fingerprint of the context Forge gathered. |
| `constraintsFingerprint` | Fingerprint of the host constraints in force. |
| `featureSchemaVersion` | Version of the `TaskFeatures` schema and encoder (#60). |
| `catalogVersion` | **Immutable identity of one `RouteCatalog` snapshot** (#61). It must change whenever any snapshot content changes: the route set, any descriptor, availability, `fallbackPriority` or a `ResourceEstimate`. The same `catalogVersion` always means identical content, and reusing a version for different content is a host contract violation. Neuron stores no catalogs, so a host that needs exact replay retains each snapshot by version. `Proposition.code` is meaningful only together with this version. It identifies the snapshot behind a decision (audit and `Proposition.code` mapping); it is **not** an admission key for state or observations. |
| `policyId` + `policyVersion` | The `RoutingPolicy` **ordering rules** that decided. The policy's configuration (`minSupportingObservations`, `maxAutoSelectTier`, the `ResourceObjective`, the state `mappingVersion`) is recorded separately as the decision's `parameters` (Section 3.2 and Section 17.1), because equal ordering rules can decide differently under different parameters. |
| `evaluationPolicy` (id, version) | The Forge evidence policy; matches the Store's `evaluationPolicy`. |

Ordinals and cutoff:

- `requestOrdinal` is a caller-issued, strictly increasing 64-bit integer per scope that numbers routing requests.
- Observations carry the Store `ExperienceRef` string, its `revision`, and its ledger sequence; the **applied order** of
  observations is `(ledgerSequence ascending, ExperienceRef ascending)`, the Store's total order.
- `cutoff` is a caller-supplied ledger sequence (inclusive). An observation with a greater sequence is excluded from any
  decision or rebuild "as of" that cutoff, so no future acceptance, correction or usage can leak backward.
- **State must not be newer than the request.** Every `RoutingPreference` carries `processedCutoff`, the **export/rebuild
  watermark**: the greatest ledger sequence the caller *examined* when it built the state, including neutral, rejected,
  superseded and correcting revisions that changed no Node. It is not the sequence of the last observation that updated a
  Node. A rebuild "as of cutoff X" sets `processedCutoff = X` (the export's high-watermark), so a correction at sequence 58
  that turns an earlier reward into a neutral observation still makes the state a sequence-58 state. If `processedCutoff` is
  greater than the request's `cutoff`, `decide` must return `Abstain(STATE_INCOMPATIBLE)`. It must not ignore the preference partially or silently, so a historical replay cannot let
  future feedback influence an earlier decision. A replay rebuilds the state as of each request's cutoff (Section 7).

Version compatibility:

- The contract identifier is `forge-routing/1`. Every envelope carries it. An unknown major version must be rejected, never
  reinterpreted. Minor additions must be additive.
- State, observations and feedback are admitted by these rules; otherwise they are reported as incompatible and not applied
  (Section 7):

| Key | Admission rule |
| --- | --- |
| `scopeId` | Must equal the request's scope. A different scope is never admitted or merged. The `RoutingPreference` and `RoutingStateCheckpoint` carry `scopeId` explicitly, so an empty snapshot is still checkable. |
| `featureSchemaVersion` | Must equal the version the state's cohort mapping was built for. The `RoutingPreference` and `RoutingStateCheckpoint` carry it explicitly, so a valid cold-start snapshot (same bindings, zero cohorts) is distinguishable from one reused from another scope or schema. |
| `(RouteId, RouteVersion)` | Must equal the route of the candidate. A different `RouteVersion` has its own cohort and inherits nothing. |
| `policyId` + `policyVersion` | **Provenance, not an admission key.** The routing policy only orders eligible routes at decision time; it never changes the values stored in the state, so changing it needs no rebuild and no cold start. Snapshots, checkpoints, ledger entries and `RoutingFeedback` record the policy that produced them for audit. Anything that changes **what is learned** (attribution and learning-eligibility rules, feedback scores) is part of `mappingVersion` below, which is the admission key. |
| `evaluationPolicy` (id, version) | The state is **partitioned by evaluation policy**: the `RoutingPreference`, checkpoint, ledger entries and observations carry it, and it must equal the request's. Evidence judged under different evaluation policies is non-comparable, so observations under another one are not applied (`EVALUATION_POLICY_MISMATCH`) and a changed policy starts a separate cold state; the host keeps one state per `(scopeId, evaluationPolicy)`. |
| `mappingVersion` | Must equal the `mappingVersion` of the `RoutingStateDefinition` the policy uses for the request. That version identifies the **complete state-transition definition**, which the `RoutingStateDefinition` groups (the `CohortMapping`, the `initialNodeState`, the full `AdaptationConfig` and the feedback scoring and eligibility rules) and which must change when **any** part changes; the `CohortMapping` alone has only a `bucketMappingVersion`. The definition covers: the feature-to-bucket assignment, the `initialNodeState` (including the Node type and identity rule), the **full `AdaptationConfig`** (`learningRate`, amplitude and energy bounds, `energyStep`, and every other field) the `RoutingFeedback` scoring configuration and the attribution and learning-eligibility rules that decide what is learned. It must change when any of them changes, so a snapshot built under another definition makes `decide` return `Abstain(STATE_INCOMPATIBLE)`. A new `mappingVersion` **starts a cold state**: stored observations keep only their old `cohortBinding`, which cannot be recomputed because the features are not retained (Section 3.2), so history under the old version is not migrated. The old state stays valid for the old version. A host that still owns the original features may re-export observations with bindings for the new version; the state is then an ordinary rebuild under that version. An observation whose `cohortBinding.mappingVersion` differs from the state's is not applied and is reported as `MAPPING_MISMATCH`. |
| `catalogVersion` | **Not an admission key.** A new catalog snapshot in which a route keeps its `(RouteId, RouteVersion)` reuses that route's history; a route that is added, removed or re-versioned follows the `(RouteId, RouteVersion)` rule above. |
| `processedCutoff` | Must not exceed the request cutoff (see above). |
- Store samples are consumed only as `execution-sample/1`. An unsupported schema is reported, never guessed.

## 3. Proposed Types

Immutable records and sealed variants with defensive copies and eager validation, in `monada.neuron.routing`. Bounds are
maxima fixed by this contract; an implementation may lower a bound but must not raise it without a contract revision.

| Type (proposed) | Introduced by | Role |
| --- | --- | --- |
| `TaskFeatures` | #60 (implemented, Section 15) | Caller-approved, bounded, typed task/stage characteristics with explicit unknown values, canonical order and schema version. |
| `RouteDescriptor`, `RouteCatalog` | #61 (implemented, Section 16) | `RouteDescriptor` is the versioned behavioral description of one route (worker/provider/model/effort, `billingMode`, `overflowClass`, `tier`, capabilities, stage compatibility, ceilings). `RouteCatalog` is an immutable snapshot of descriptors **plus a per-route availability value, which is outside the versioned descriptor**. |
| `RoutingRequest` | #61 (implemented, Section 16) | One stage request, defined with eligibility because every hard constraint is a request value: envelope, `TaskFeatures`, hard requirements, permitted execution modes, `overflowPermitted`. |
| `RoutingPolicy` | #62 (implemented, Section 17) | Versioned, explicit, rule-based policy; `decide(RoutingRequest, RouteCatalog, RoutingPreference) -> RoutingDecision`; the policy carries its `ResourceObjective`. |
| `ResourceEstimate` | #61 (implemented, Section 16) | Host-supplied, per-route annotation inside the `RouteCatalog` snapshot: `(RouteId, RouteVersion, dimension, unit, value, provenance)` with `value` Known, Unknown or NotMeasured (Section 5). At most one estimate per route and dimension (Section 3.1), and at most 4 dimensions per route. |
| `RoutingDecision` | #62 (implemented, Section 17) | Sealed: `Selected`, `Abstain`, `NoEligibleRoute`. |
| `RoutingOutcome` | #63 | Host-evaluated result of executing a decision, with evidence and provenance. |
| `RoutingObservation` | #63 | Neutral, Store-independent record of one effective execution sample, defined with `RoutingOutcome` so feedback derivation needs only #63. #64 only *produces* it from Store exports. |
| `RoutingFeedback` | #65 | Bounded artifact derived from an observation; separate from `OutcomeFeedback`. |
| `RoutingPreference`, `CohortMapping`, `RoutingStateDefinition` | #62 (implemented, Section 17) | `CohortMapping` is the pure, versioned `TaskFeatures -> cohortBucket` function with its `bucketMappingVersion`. `RoutingStateDefinition` groups the `CohortMapping`, `initialNodeState`, `AdaptationConfig` and feedback scoring and eligibility rules under one composite `mappingVersion` that changes when any part changes. `RoutingPreference` is the **immutable value snapshot** of `scopeId`, `featureSchemaVersion`, `evaluationPolicy`, `mappingVersion`, cohort preference values, supporting counts and `processedCutoff`, taken from the caller's mutable state and passed to `decide`. #62 defines the value type, its valid empty cold-start form (`EMPTY` for a scope, feature schema, evaluation policy and mapping version) and its admission validation, so `decide` needs no later type; #66 only builds non-empty snapshots from the caller's state. |
| `RoutingStateStore` (caller-owned, mutable) | #66 | The caller's `Node` bindings, applied-revision ledger and `RoutingStateCheckpoint`; it produces the `RoutingPreference` snapshots. It is never passed to `decide`. |
| `RoutingCognitiveStageResult` | #62 (implemented, Section 17) | Level B stage result that retains the full `RoutingDecision` together with the hypothesis hand-off (Section 3.5). |

### 3.1 Request, catalog and policy inputs

- `RoutingRequest` holds `contractVersion`, `scopeId`, `taskId`, `executionId`, `attemptId`, `stageId`, `stageKind`,
  `requestOrdinal`, the provenance fingerprints, the `evaluationPolicy` (id and version) the state is partitioned by, `TaskFeatures`, hard requirements (required capabilities, context size,
  `requiredTools`, `allowedLocalities`), `permittedModes`, `overflowPermitted` and the cutoff.
- Requirements, permitted modes and `overflowPermitted` **originate from host policy, never from task-generated text**.
  `overflowPermitted` defaults to `false`; only the host may set it, and Neuron must never infer authorization to spend.
- `RouteDescriptor` is a caller **observation**, not proof of capability or authorization: it names worker, provider, model,
  effort and `billingMode` (`API_METERED`, `SUBSCRIPTION`, `LOCAL`, `UNKNOWN`) as opaque tokens, plus a `tier` and an
  `overflowClass`, and the typed route-side values of the hard constraints below. The host `fallbackPriority` is **not** a descriptor field: it is a per-route annotation of the `RouteCatalog`
  snapshot, with availability and `ResourceEstimate` (Section 2). Neuron imports no provider SDK and performs no discovery or
  quota polling.
- **Route-side values for every hard constraint.** `RouteDescriptor` carries typed, versioned values that the request is
  matched against with exact `String.equals` on opaque host tokens (at most 16 tokens per set, 128 code points each):

  | Request value | Route value | Rule |
  | --- | --- | --- |
  | `stageKind` | `stages` (set of stage tokens) | Member, else `STAGE_INCOMPATIBLE`. There is no wildcard in v1: a route is compatible only with the stages it lists. |
  | `requiredCapabilities` | `capabilities` (set) | Subset, else `MISSING_CAPABILITY`. |
  | `requiredTools` | `tools` (set) | Subset, else `MISSING_CAPABILITY`. |
  | `allowedLocalities` (set) | `locality` (one token) | Member, else `LOCALITY_NOT_PERMITTED`. |
  | `permittedModes` (set) | `executionModes` (set) | Non-empty intersection, else `MODE_NOT_PERMITTED`. `Selected` records the mode as the intersection's first element in code point order. |
  | context size | context ceiling (known limit) | Request above a known ceiling is `REQUIRED_LIMIT_EXCEEDED`; an unknown required ceiling is `REQUIRED_LIMIT_UNKNOWN`. |

  An absent route value is the empty set (or no locality) and the rules above are applied literally. An **empty required set
  is always satisfied**, whatever the route declares, so `requiredCapabilities = {}` and `requiredTools = {}` match a route
  with absent `capabilities` or `tools`. A **non-empty** required set is not satisfied by an absent route value
  (`MISSING_CAPABILITY`). Fail-closed absence therefore decides only the fields that always need a positive match: `stages`
  (`STAGE_INCOMPATIBLE`), `locality` (`LOCALITY_NOT_PERMITTED`) and `executionModes` (`MODE_NOT_PERMITTED`). Neuron never infers
  stages, tools, locality or modes from provider, model or billing tokens.
- **Overflow is a dedicated host-set field**, `overflowClass` (`STANDARD` or `OVERFLOW`), independent of `billingMode` and
  `tier`. A route is `OVERFLOW_NOT_PERMITTED` exactly when `overflowClass = OVERFLOW` and `overflowPermitted = false`. Neuron
  never derives overflow from billing mode, price, or tier, so two implementations cannot disagree about the same catalog.
- **Availability is transient and is not part of the descriptor or of `RouteVersion`.** The catalog snapshot carries a per-route
  availability value (`AVAILABLE`, `UNAVAILABLE`, `UNKNOWN`). A route going unavailable and returning keeps its
  `(RouteId, RouteVersion)` and therefore its validated history.
- **Catalog identity is unique.** `RouteCatalog` must reject, at construction, two entries with the same
  `(RouteId, RouteVersion)` (`IllegalArgumentException`), whatever their other fields. The canonical order is therefore total,
  `Proposition.code` identifies exactly one entry, and decision, audit and credit assignment name the same route.
- **Resource estimates have a carrier.** Per-route `ResourceEstimate` values travel in the catalog snapshot and are host
  supplied, never computed by Neuron. The objective that selects among cost, latency or usage is a *policy* parameter,
  `ResourceObjective(dimension, direction)`, not a request field, so a request cannot steer it. Two estimates are comparable
  only if they share `dimension`, `unit` and, for money, currency and pricing assumptions, and both are Known. A catalog
  holds **at most one `ResourceEstimate` per `(RouteId, RouteVersion, dimension)`** and is rejected at construction otherwise,
  so the objective's `dimension` selects exactly one estimate per route and rule 4 has nothing to choose between.
- Selecting a route **reserves nothing**. Forge must revalidate authorization, availability and quota immediately before
  executing.

### 3.2 `RoutingDecision`

| Variant | Meaning | Mandatory content |
| --- | --- | --- |
| `Selected` | Exactly one recommended route. | Common provenance (below), route `(RouteId, RouteVersion)`, ranked candidates (at most 8) with the rule that placed each, `basis` (`COLD_START`, `HOST_PRIORITY`, `LEARNED_PREFERENCE`, ...), `overflowUsed`, the chosen `executionMode` (Section 3.1) and the `cohortBinding` (below). |
| `Abstain` | At least one route is eligible but the policy declines to advise. | Common provenance, typed reason (`POLICY_TRADEOFF_UNRESOLVED` or `STATE_INCOMPATIBLE`; these are the only v1 reasons, defined in Section 3.3 and Section 3.2), the eligible candidates, exclusion reasons for the excluded routes. |
| `NoEligibleRoute` | The hard constraints exclude every route. | Common provenance and one structured exclusion reason for **every catalog route**. |

**Common provenance, on every variant.** `decisionRef`, `catalogVersion`, `policyId` + `policyVersion`, the policy `parameters` (`minSupportingObservations`, `maxAutoSelectTier`, the `ResourceObjective` and the state `mappingVersion`; #62 records them because `policyId` + `policyVersion` identify the ordering rules only), the `cutoff`, and the
state binding: the `RoutingPreference`'s `scopeId`, `featureSchemaVersion`, `processedCutoff`, `mappingVersion`, `evaluationPolicy`, `policyId`/`policyVersion` (provenance), and the validation
result: `COMPATIBLE`, `NOT_EVALUATED`, or `INCOMPATIBLE(reasons)`. A snapshot can fail several checks at once, so
`INCOMPATIBLE` retains **every** mismatch as an ordered list in this fixed order (at most 5): `SCOPE_MISMATCH`,
`FEATURE_SCHEMA_MISMATCH`, `EVALUATION_POLICY_MISMATCH`, `MAPPING_VERSION_MISMATCH`, `PROCESSED_CUTOFF_NEWER`; the first is the
primary reason, so equal inputs give equal provenance. A retained Level B result can therefore identify and replay
the inputs of an abstention or of a no-route decision without host-side state.

**Cohort binding.** Whenever the policy computes a `cohortBucket` from the request's `TaskFeatures`, `Selected` records the
immutable `cohortBinding(cohortBucket, bucketMappingVersion, mappingVersion, featureSchemaVersion)`. `RoutingOutcome` copies it together with
`decisionRef`. A rebuild assigns a cohort **only** from this binding; features are never recomputed from a request object
that no longer exists.

**Routing envelope transport (v1 mechanism).** The Store baseline has no routing field: `RouteDescriptor` carries only worker,
provider, model, effort and billing mode, and `StageRecorded` carries the stage, route, timing, usage and `ArtifactRef`s. v1
therefore uses the Store's **existing opaque artifact reference plus a host-owned resolver**, and needs **no Store contract
extension**:

- **Payload.** A versioned `RoutingEnvelope` (`forge-routing/1`) with `decisionRef`, the route `(RouteId, RouteVersion)`,
  an **immutable snapshot of the selected route's descriptor** (`worker`, `provider`, `model`, `effort` and `billingMode`, with
  absent values explicit), `catalogVersion`, routing `policyId`/`policyVersion`, `evaluationPolicy`, the `cohortBinding`, and
  the chosen `executionMode`. Because the snapshot is inside the digest-protected payload, no catalog or descriptor resolution
  operation exists or is needed, and the resolver returns only the envelope. It is serialized canonically (fixed field order, UTF-8) and held by **Forge**, not Neuron and not the Store.
- **Binding in the Store.** On the `STAGE_RECORDED` event of the routed stage Forge adds one `ArtifactRef` with kind
  `neuron-routing-envelope/1`, `reference` = a host-issued opaque `envelopeRef` (at most 128 code points, within the Store's
  512-code-point opaque limit), and `digest` = the lower-case hexadecimal SHA-256 of the canonical payload. The reference
  therefore travels with that event and with every correction of it (a `CORRECTION` carries the full replacement payload), so
  identity and revision are bound by the stage event's own `ExperienceRef` and revision. A stage has at most one such reference.
- **Resolution ownership.** Neuron defines the host port `RoutingEnvelopeResolver` (in #64): `resolve(scopeId, envelopeRef) ->
  RoutingEnvelope | missing`. The host implements it over its own storage. Neuron never fetches by itself and never writes the
  Store.
- **Verification, in this order.** (1) *Integrity:* the translator recomputes the SHA-256 of the resolved canonical payload and
  compares it with the recorded digest; this only proves the bytes are the recorded ones. (2) *Identity:* the envelope's
  `decisionRef` scope, task, execution and attempt must equal the corresponding fields of the exported event's `ExperienceRef`
  (`scope`, `task`, `execution`, `attempt`; a stage event always has an attempt), and the envelope's `stageId` must equal the
  exported stage's `stage` token, so Forge must record the Store `stage` as the `stageId` (at most 128 code points). `stageKind`
  lives only in the envelope and the `TaskFeatures`; it is not compared with the Store. (3) *Descriptor:* the envelope's descriptor
  snapshot must equal the exported `RouteDescriptor` field by field (`worker`, `provider`, `model`, `effort` and `billingMode`;
  an absent value matches only an absent value). `decisionRef` is an identity, not a worker descriptor, and the opaque route
  identity `(RouteId, RouteVersion)` is never compared with worker, provider, model or effort.
- **Missing-data behavior.** An observation is not eligible for learning, never guessed or recomputed, and is reported with a
  typed reason: `MISSING_ENVELOPE_REFERENCE` (no such `ArtifactRef` on the stage), `ENVELOPE_UNRESOLVED` (the resolver returns
  missing), `ENVELOPE_DIGEST_MISMATCH` (payload does not match the recorded digest) `ENVELOPE_IDENTITY_MISMATCH` (an envelope for another scope, task, execution, attempt or stage, which must never be admitted as
  the current stage's observation) or `ENVELOPE_ROUTE_MISMATCH` (the descriptor differs from the exported one). A route mismatch
  also covers Forge deliberately executing a different route than the advised one: that is not learned, consistent with
  'a selected-but-unexecuted candidate receives no credit' (Section 6).
  `MISSING_COHORT_BINDING` remains for a resolved envelope that has no `cohortBinding`.
- **Alternative.** A native Store field for this envelope would be a Store contract extension and a prerequisite of its own;
  it is not assumed here and could replace the resolver later without changing Neuron's semantics.

- `decisionRef = (scopeId, taskId, executionId, attemptId, stageId, requestOrdinal)`. It is how a later outcome is matched
  to the exact decision that produced it.
- Every variant lists an exclusion reason **for each excluded catalog route only**, at most 32 (the catalog bound).
  Eligible routes are never given an exclusion reason; they appear separately as the ranked or eligible candidates. A
  `NoEligibleRoute` therefore has a reason for every route, and a `Selected` has reasons only for the routes it excluded.
  Missing mandatory capability, unavailability, unknown availability, a known limit that is too small, or an unknown
  *required* limit make a route ineligible (fail closed). An unknown *optional ranking* metric is not an eligibility failure.
- **Exclusion-reason precedence.** A route that violates several constraints gets exactly one *primary* reason, the first
  that applies in this fixed order, so equal inputs always give equal decisions:
  `STAGE_INCOMPATIBLE`, `ROUTE_UNAVAILABLE`, `AVAILABILITY_UNKNOWN`, `MODE_NOT_PERMITTED`, `LOCALITY_NOT_PERMITTED`,
  `OVERFLOW_NOT_PERMITTED`, `MISSING_CAPABILITY`, `REQUIRED_LIMIT_EXCEEDED` (a known limit is below what the request requires, for example a context
  ceiling), `REQUIRED_LIMIT_UNKNOWN` (the limit is required but unknown). Every other violated reason is listed in
  `additionalReasons` in the same order (at most 8).
- **Eligibility is evaluated before state.** If no route is eligible the result is `NoEligibleRoute` even when the supplied
  `RoutingPreference` is incompatible; the state is then not consulted and its provenance result is `NOT_EVALUATED`.
  `Abstain(STATE_INCOMPATIBLE)` is returned only when at least one route is eligible and the `RoutingPreference` is newer than
  the request cutoff or fails an admission rule of Section 2; the host then decides, for example by re-asking with an empty
  preference.
- **Learned preference never bypasses a hard constraint.** Eligibility is evaluated first and is final.
- `Abstain` is distinct from `NoEligibleRoute`: abstention is a policy statement and the host falls back to its own default;
  no-eligible-route is a constraint fact. The host decides what to do in both cases.
- A decision is **never** reinterpreted as `ActionStatus.SUCCEEDED` or any other `ActionStatus`.

### 3.3 Reference ordering (lexicographic, no blended scalar)

`RoutingPolicy` v1 orders **eligible** routes by this fixed lexicographic list; a policy that changes it is a new
`policyVersion`:

1. host `tier` ascending (subscription/local routes are tier 1, permitted API overflow tier 2 in the default host policy,
   i.e. subscription-first, as a *configurable host table*, not a Neuron constant);
2. host `fallbackPriority` ascending (a catalog-snapshot value, Section 2);
3. learned preference descending, **only when** the supplied `RoutingPreference` is compatible and has at least
   `minSupportingObservations` (policy parameter, default 3) for that candidate's cohort; otherwise this rule is skipped for
   every candidate, never partially applied;
4. the policy's `ResourceObjective` applied to the catalog's `ResourceEstimate` values, **only when every remaining
   candidate has a Known, comparable estimate** (same dimension, unit, currency and assumptions; Section 3.1). Unknown and
   NotMeasured are never cheapest or fastest, and never zero; otherwise the rule is skipped for every candidate;
5. canonical route order: `RouteId` by code point, then `RouteVersion` numerically (Section 2; total order, final tie-breaker).

Because rule 5 always breaks ties, a `Selected` decision exists whenever at least one route is eligible and no abstention
condition holds. v1 has exactly two abstention triggers, evaluated in this fixed order (state first, so the tier rule never runs on an incompatible state):

- `Abstain(STATE_INCOMPATIBLE)` when a route is eligible and the preference snapshot fails admission (Section 2).
- `Abstain(POLICY_TRADEOFF_UNRESOLVED)`, only when the state is compatible, when the policy parameter `maxAutoSelectTier` (a host-set integer, default
  unbounded) is set and the best eligible route has a `tier` above it, so the host must confirm a lower-priority tier such as
  paid overflow before it is advised. With the default, this never fires.

Insufficient learned evidence and unknown resource estimates are **not** abstention reasons: they only skip rules 3 and 4,
and rule 5 still yields a `Selected` decision (Section 7 fallback). A later contract version may add reasons with their exact
conditions.

### 3.4 Mapping a decision to cognitive artifacts

The route identity lives in the typed envelope. It is never encoded into `Signal`, `FrequencyState`, or `Proposition`
semantics.

| Neuron artifact | Mapping |
| --- | --- |
| `Signal` | Unchanged. Encoded features, if used, are `OBSERVATION` Signals produced by the #60 encoder inside a host `PerceptionCapability` (ADR 0024). The typed `TaskFeatures` are kept next to the Signals and are what hard constraints use, so Signal collisions or information loss can never substitute for an exact constraint. |
| `Proposition(domain, code)` | For `Selected` only: `domain` is the routing domain the host configures when wiring; `code` is the **zero-based index of the route in the canonical route order (Section 2) of the catalog snapshot that produced the decision**. It is an in-cycle vehicle, not an identity: it is only meaningful together with `catalogVersion`, and routing feedback never uses it to identify a route. |
| `Hypothesis` | One hypothesis for the selected route. Ranking among alternatives stays in `RoutingDecision`, which is authoritative; the existing `EVALUATION` stage's score is a generic evidence score (ADR 0020) and must not re-rank routes. `Abstain` and `NoEligibleRoute` produce **no** hypothesis. |
| `Evidence` | **No `SignalEvidence` in v1 on a Level B result**: the routing stage is a source with no admitted Signals when hypotheses are validated (`validateProvenance` precedes admission of its output), so a Signal occurrence would be rejected. Level A hypotheses built outside a cycle are not affected. Optional `MemoryReferenceEvidence` carrying opaque `ExperienceRef` strings (bounded by `ResonanceMemoryResult.MAX_REFERENCE_LENGTH`) for the experience behind a preference. Recall similarity is never evidence of quality. |
| `ActionRequest` | Unchanged. The host correlates through the opaque `HostExecutionContext` (ADR 0023). An `ActionResult` reports worker *invocation*, not task acceptance (Section 5). |
| `OutcomeFeedback` | Unchanged and untouched. Routing learning uses `RoutingFeedback` (Section 6). |

### 3.5 Composition seam

The seam is chosen once here; #62 implements it. Two levels, both additive and opt-in:

**Level A, the default: a pure function called by the host.**

```text
Forge
  -> builds RoutingRequest + RouteCatalog, takes a RoutingPreference snapshot from its RoutingStateStore
  -> RoutingPolicy.decide(request, catalog, preference) -> RoutingDecision      [pure, no cycle, no I/O]
  -> Forge authorizes, revalidates, executes, judges                            [Forge-owned]
```

**Level B, optional: a thin reasoning-stage adapter.** `RoutingReasoningStage` occupies the existing `REASONING` position and
calls the same pure policy. It gets its per-execution inputs through a host-implemented resolver keyed by the cycle's
`HostExecutionContext`, the same pattern as `PerceptionCapability`:

`RoutingReasoningStage` is a **source stage** (`CognitiveStage.isSource()` is true), exactly like `PerceptionCognitiveStage`
(ADR 0024), because it is driven by the host context and not by input Signals. Without that, the deterministic cycle ends
with `NO_SIGNALS` before calling a non-source stage that has no input, and the resolver would never run. The cycle's existing
source rules apply: the stage must be the first, runs with empty initial Signals, and a cycle with a source stage rejects
non-empty initial Signals. #62 must confirm that the cycle accepts a `REASONING` source and extend its source rule additively
if it does not. The composition restriction is explicit: a routing cycle cannot also have a `PerceptionCapability` source or
initial Signals; a host that needs both runs routing as its own cycle, or uses Level A.

```text
CycleInput.hostContext -> CognitiveContext.hostContext()
  -> RoutingReasoningStage -> RoutingInputResolver (host adapter) -> RoutingInput(request, catalog, preference)
  -> RoutingPolicy.decide(...) -> RoutingCognitiveStageResult(decision, HypothesisSet with the selected route; no output Signals)
```

`ReasoningCognitiveStageResult` is a final record that retains only status, output Signals and a `HypothesisSet`, so it cannot
carry the decision. `RoutingCognitiveStageResult` is a new `CognitiveStageResult` of kind `REASONING` that retains the **full**
`RoutingDecision` (ranked candidates, basis, exclusion reasons, policy and state binding, cutoff) together with the same
hypothesis hand-off. Forge reads the authoritative advice from `CognitiveCycleResult.stageResults()`; it never needs to call the
policy a second time or keep side state. The deterministic cycle normalizes every non-Aeon result through
`withAdmittedOutputSignals`, whose default returns a generic snapshot that would drop both the decision and the hypotheses.
`RoutingCognitiveStageResult` therefore **must**:

- override `withAdmittedOutputSignals` to return a `RoutingCognitiveStageResult` that keeps the decision and hypotheses
  unchanged and only replaces the (empty in v1) output Signals with the admitted prefix;
- report `retainsTypedHandOff()` when it holds hypotheses, and implement `validateProvenance(context)` like
  `ReasoningCognitiveStageResult`, so the cycle keeps running to `EVALUATION` and evidence is validated;
- be recognised by `ReasoningCognitiveStageResult.hypothesesOf`, extended **additively**; no existing signature or behavior
  changes.

The stage **emits no Signals**: its output list is empty, because route identity must not enter Signals. The typed hand-off
(`retainsTypedHandOff()`) keeps the cycle alive only for a following stage that opts in with `acceptsTypedOnlyHandOff()`, such
as `EVALUATION`; any other following stage ends the cycle with `NO_SIGNALS`, which is expected and not a failure. The host
reads the decision from `CognitiveCycleResult.stageResults()`. The output-Signal admission override is kept so the result stays
correct under the cycle's normalization; an empty output cannot be truncated, and `CognitiveBudget` rejects `maxSignals <= 0`,
so v1 has no truncation case.

#62 must test, with a **valid minimum positive budget** (`maxSteps >= 1`, `maxSignals >= 1`), empty initial and output Signals
and retained typed decision and hypotheses, that: normalization preserves the full `RoutingCognitiveStageResult`; a following
`EVALUATION` stage receives the typed hand-off; and a following stage without typed-only support ends the cycle with the
documented `NO_SIGNALS` termination. A future Signal-emitting variant defines its own truncation test.

Rules for both levels:

- No new `CognitiveStageKind`, no change to the canonical order, no change to `CycleInput`, `CognitiveStage` or
  `NeuronRuntime` signatures, and no stage that holds per-cycle data (ADR 0022, ADR 0023).
- No second orchestrator, scheduler, singleton or session. Neuron never starts a cycle and never executes the route.
- The runtime remains sequential and not thread-safe; a `NeuronRuntime` must not be reused concurrently.

Rejected seams (ADR 0026): a routing field on `CycleInput`; a new canonical stage; routing inside `ActionCapability`
(conflates advice with execution); state inside `PrimaryMonad`.

## 4. Bounds and Limits

| Limit | v1 maximum | Overflow behavior |
| --- | --- | --- |
| Identifier / token length | 128 code points | `IllegalArgumentException` at construction |
| Routes per catalog | 32 | Catalog rejected at construction |
| Capability, tool, stage and execution-mode tokens per route (each set) | 16 | Catalog rejected |
| Hard requirements per request (capabilities, tools, localities and modes, each set) | 16 | Request rejected |
| Typed features per `TaskFeatures` | 32 | Rejected; unknown values are explicit, never dropped |
| Ranked candidates in a decision | 8 | Truncation is explicit (`candidatesTruncated`) |
| Exclusion reasons in a decision | one primary reason per excluded route (at most 32) plus `additionalReasons` (at most 8 per route) | n/a |
| Feedback entries per `RoutingFeedback` | 64 | Rejected at construction (same bound as `OutcomeFeedback`) |
| Preference cohorts (Node-bound) per state, i.e. per `(scopeId, evaluationPolicy)` | 256 | **Report `CAPACITY_EXHAUSTED`, do not learn**; never evict silently, never spill into another scope |
| Applied-revision entries per state | 4,096 (equal to the rebuild bound) | `CAPACITY_EXHAUSTED`; nothing is evicted silently. The caller compacts through a `RoutingStateCheckpoint` (Section 7) or starts a new scope |
| Observations replayed per rebuild call | 4,096 | Caller pages through the Store export (page size 1–500) and may continue from a checkpoint |
| Node history per preference Node | 0 (`historyLimit = 0`) | History disabled for routing cohorts (Section 7) |

Validation failures of a caller-built value are contract violations and throw, like every Neuron record. Capacity and
compatibility conditions of *admitted, well-formed* data are expected results and are reported, never thrown.

## 5. Outcomes, Evidence and Delayed Feedback

Three things are kept strictly separate:

1. **Worker invocation result.** An `ActionResult` or a host-reported run status. `SUCCEEDED` means the worker finished. It
   is **not** validated task acceptance.
2. **Quality evidence.** Dimensions `CORRECTNESS`, `TESTS`, `SECURITY`, `ARCHITECTURE`, `MAINTAINABILITY`, `COMPLEXITY`, each
   `PASS`, `FAIL`, `UNKNOWN` or justified `NOT_APPLICABLE`, with evaluator and policy versions. Forge judges; Neuron does not.
3. **Derived acceptance.** Taken from the Store's definition, never recomputed differently: `VALIDATED_ACCEPTED`,
   `ACCEPTED_UNVALIDATED`, `NOT_ACCEPTED`, `PENDING`. An "accepted" flag alone is insufficient; effective-evidence
   precedence follows Store v1 Section 8 (a later applicable `FAIL`/`UNKNOWN` overrides an earlier `PASS`).

Lifecycle of delayed feedback, reusing the cross-cycle handoff pattern of ADR 0021 (no queue, store or session in Neuron):

```text
t0  Forge: RoutingPolicy.decide(...)            -> RoutingDecision (advice)
t1  Forge: authorize, revalidate, execute       -> worker invocation result (provisional)
t2  Forge: records stage + usage in the Store   -> outcome PENDING
t3  Forge: records evidence                     -> evidence rows; outcome may become ACCEPTED/REJECTED
t4  Forge: may record a CORRECTION              -> new revision; the earlier row stays history
t5  Host: exports samples (cutoff) and builds RoutingObservation   -> Neuron applies RoutingFeedback to the held state
t6  Later cycle / later decide(...) reads the updated snapshot
```

Learning eligibility (#63) must explicitly report: missing evidence, stale policy or artifact revision, contradictory
evidence, pending or cancelled work, provenance mismatch, and environmental failure. Environmental failures (unavailable,
timed out) stay distinguishable from invalid generated work and never penalize.

### Resource values

Resource data is carried, not computed, in Neuron's production code (no billing arithmetic in #63).

- A resource value is one of **Known** (a non-negative value with provenance `REPORTED` or `ESTIMATED`; a known `0` is a real
  zero), **Unknown** (reported but unmeasurable) or **NotMeasured** (absent). Unknown and not-measured are never zero, never
  cheapest, never fastest, and never proof of success.
- `RouteDescriptor.billingMode` and the cost kinds below are different things:

| Quantity | Meaning |
| --- | --- |
| Usage counters | Reported or estimated token/request counts with provenance, taken from the Store export. |
| Hypothetical API cost | Evaluation-time arithmetic over a caller-supplied, dated price snapshot (provider, model, currency, assumptions). Labelled hypothetical for **every** billing mode. Comes only from Store evaluation tooling (#99) or host-supplied annotations. |
| Actual billing | An amount the caller explicitly recorded. Reported separately, never inferred from usage. |
| Subscription activity | Never presented as API billing. |

- Currencies are never summed across; each is reported alone.
- Four quantities stay distinct and none substitutes for another: **recall similarity**, **hypothesis evaluation score**,
  **derived acceptance**, and **cost**. Quality gates precede resource optimization: cost may order only candidates that
  already satisfy every mandatory gate, and low cost never offsets a failed mandatory gate.

## 6. Credit Assignment and Feedback

`RoutingFeedback` is a separate typed artifact (#65). `OutcomeFeedback`, `NoOpOutcomeFeedbackPolicy`,
`DeterministicOutcomeFeedbackPolicy` and `FeedbackAdaptationCognitiveStage` keep their exact behavior; a cycle without routing
feedback is byte-for-byte unchanged.

Matching: feedback is derived only when an outcome matches its exact `decisionRef`, route `(RouteId, RouteVersion)`, scope,
stage, `featureSchemaVersion` and evaluation policy; the producing decision's routing policy is recorded as provenance. A selected-but-unexecuted candidate receives **no credit**.

| Observation | Disposition | Rule |
| --- | --- | --- |
| `VALIDATED_ACCEPTED` and sufficient stage-local attribution to the executed route | `REINFORCE` | Positive credit to that route's cohort only. |
| Proven mandatory quality failure attributable to the executed route's stage | `PENALIZE` | Bounded negative credit; a weak signal against the choice. |
| `ACCEPTED_UNVALIDATED` because mandatory evidence is `UNKNOWN`, missing, from a mismatched policy, or the outcome is an imported claim | `NEUTRAL` | No reward and no penalty; the missing evidence is reported. |
| `ACCEPTED_UNVALIDATED` because an effective mandatory observation of the accepted attempt is `FAIL` (for example after a correction) | `PENALIZE` | A proven mandatory failure is a failure of the gate regardless of the derived label; it is treated exactly like a `NOT_ACCEPTED` outcome with an attributable proven mandatory failure. |
| `NOT_ACCEPTED` (rejected, failed or cancelled work) with **no** attributable proven mandatory failure | `NEUTRAL` | Reason `NO_ATTRIBUTABLE_FAILURE`: a refusal, a rejection or an unjudged failure without gate evidence is not a proven failure of the route, so it is never penalized. |
| `PENDING`, `CANCELLED` | `NEUTRAL` | Says nothing about the route. |
| Environmental failure (unavailable, timed out) | `NEUTRAL` | Says nothing about the route. |
| Multi-stage chain accepted but no stage-local attribution | No update | Chain acceptance alone cannot identify which worker deserves credit; attribution evidence is retained, not invented. |
| Mixed-route repair chain | Stage-local only | Never full credit to every participant. |
| Invalid, unsupported or contradictory evidence | Rejected with a reason | Not applied. |

- Scores are bounded to `[-1, 1]`, are never zero for `REINFORCE`/`PENALIZE`, and their sign matches the disposition. The
  scoring configuration must keep the ordering invariants of ADR 0021 (a partial result never outscores a full one, and a
  refusal is never penalized harder than an execution failure).
- Cost may refine a preference only by comparing **validated, directly comparable, complete** measurements under declared
  objectives. Incomplete usage never produces a "cheap" reward.
- Direct comparable pairs and observational cohorts are kept apart. Two executions are *directly comparable* only when scope,
  task, `stageKind`, `sourceFingerprint`, `contextFingerprint`, `constraintsFingerprint`, and evaluation policy id/version all
  match. This is the Store v1 Section 10 key **plus `stageKind`**: plan, implement, review and QA executions of one task have
  different objectives, mandatory evidence and resource profiles, so executions of different stages are never a direct pair
  (a stage-specific feature or objective binding, if any, must match too). Only then may a pairwise statement be made. Cross-task cohort summaries are **observational evidence
  for that corpus**, with no causal or general model-superiority claim.

## 7. Bounded Caller-Owned Preference State

The adaptive state (#66) reuses the existing generic learning path; **no second learning engine is introduced**.

- **Mutable state and immutable snapshot are separate.** The caller owns a mutable `RoutingStateStore`: the `Node` bindings,
  the applied-revision ledger and the latest checkpoint. Before each `decide` the caller copies the relevant values into an
  immutable `RoutingPreference` (`scopeId`, `featureSchemaVersion`, `evaluationPolicy`, `cohortKey -> preferenceValue`, supporting-observation counts,
  `processedCutoff`, `mappingVersion`, and the producing `policyId` and `policyVersion` as provenance). `decide` sees only that snapshot, so it stays pure and deterministic, and later adaptation can never change a
  snapshot already published or race with a concurrent read. Neuron keeps no registry, session, history, timer or background
  task.
- Preference is bound to caller-owned `Node` instances by an explicit, versioned mapping
  `cohortKey -> Node`, where `cohortKey = (scopeId, stageKind, cohortBucket, RouteId, RouteVersion)`. `cohortBucket` comes from
  a versioned mapping of `TaskFeatures` (bounded; an unknown feature value maps to an explicit `UNKNOWN` bucket, never to a
  measured zero).
- Updates go through `AdaptationPolicy` / `DeterministicBaselineAdaptationPolicy` and the bounded Node history, applied only
  from `RoutingFeedback` artifacts after the producing execution. A Node amplitude is a **preference ordinal, not a
  calibrated success probability**, and must not be presented as one.
- **Nonzero baseline.** The score-only path of `DeterministicBaselineAdaptationPolicy` multiplies the previous amplitude by
  `1 + learningRate * score`, and a new `Node` starts at `FrequencyState.ZERO`, so a zero amplitude would never move. Every
  cohort `Node` must therefore start with a **nonzero `baselineAmplitude`** that is a versioned parameter of the mapping (it
  must lie inside the `AdaptationConfig` amplitude bounds). The effective preference value is `amplitude - baselineAmplitude`.
  The concrete number is fixed by #66 with evidence, not by this contract. `minSupportingObservations` counts applied
  observations, never amplitude.
- **Positive amplitude floor.** The same multiplicative rule can also drive an amplitude to zero: `AdaptationConfig` accepts
  `learningRate = 1`, `score = -1` and `minAmplitude = 0`, and a zero amplitude can never recover. The routing mapping therefore
  **requires `minAmplitude > 0` and `minAmplitude < baselineAmplitude <= maxAmplitude`** and rejects any `AdaptationConfig`
  with `minAmplitude = 0` as a configuration error. The policy clamps every result to `[minAmplitude, maxAmplitude]`, so a
  cohort amplitude stays strictly positive and a penalized route can still be reinforced later.
- **Complete initial Node state.** The score-only path also moves Node energy, and every transition appends to the Node's
  history, so restoring only the amplitude would leave superseded effects behind. The versioned mapping therefore defines a
  full `initialNodeState`: amplitude (`baselineAmplitude`), frequency, phase, energy and `historyLimit`, **with
  `historyLimit = 0`**. A zero limit disables the Node's history (`Node.Builder.historyLimit`, ADR 0021), `decide` never reads
  it, and a routing cohort's state is therefore exactly its four numeric channels. **The state also fixes the Node's two other
  required fields**: a stable `NodeType` (a versioned constant of the mapping) and a deterministic identity, the name-based UUID
  (`UUID.nameUUIDFromBytes`, set with `Node.Builder.id`) of a **collision-free byte string**: UTF-8 (never the default
  charset) of the fixed tag `forge-routing/1/node`, then in this order `scopeId`, `evaluationPolicy` id and version,
  `stageKind`, `cohortBucket`, `RouteId` and `mappingVersion`, each as a 4-byte big-endian length followed by its UTF-8 bytes,
  and finally `RouteVersion` as an 8-byte big-endian `long`. No delimiter is used, so distinct keys cannot collide on
  concatenation, and no randomness is used and a reset or restore recreates the same identity. **Reset replaces each cohort Node with a new
  Node built from that state**; it never patches fields in place. A checkpoint snapshot stores amplitude, frequency, phase and
  energy per cohort, and a Node restored from it is built the same way, so a checkpoint restore and a from-scratch replay are
  identical in every retained field, with no history left to differ.
- *Derivation without consumption leaves the cohort values and supporting counts bit-identical.* Consumed feedback changes only
  eligible bound targets in the same scope. Processing an observation, even a neutral, rejected or ineligible one that changes
  no Node, still advances `processedCutoff` to its sequence (or to the export's high-watermark), because the watermark records
  what was examined, not what changed; the snapshot is therefore not bit-identical in its watermark.
- **Applied-revision bookkeeping is caller-owned**: for each applied observation the caller retains
  `(ExperienceRef, revision, ledgerSequence, feedbackId, cohortKey)` so each effective revision is applied exactly once and a
  superseded one is detectable. The original `ledgerSequence` is kept because checkpoint validity needs the sequence of the
  revision being replaced, which the replacing revision (a later sequence) does not give. A `RoutingObservation` that replaces
  an earlier revision also carries `supersedesSequence`, the sequence of the earliest revision it replaces. The **translator
  (#64) obtains it once, at translation time**, from the export when `execution-sample/1` carries every revision with its ledger
  sequence, and otherwise from **bounded, read-only Store `history` reads** (pages of at most 500, snapshot cursor, caller-owned
  handle, never `feedback()`), which this contract explicitly permits for this purpose. It then stores the value in the
  `RoutingObservation`, so a later checkpoint check needs no read and works after compaction; only the translation step reads
  history. This is not a hidden history.
- **Reset and rebuild.** Node adaptation is not exactly invertible (bounds and saturation), so when **any already-applied
  non-neutral revision, reward or penalty, is superseded** by a different revision, the old effect cannot be subtracted and a
  replacement cannot undo saturation or ordering effects. The caller **resets** the affected scope's preference Nodes to the
  baseline and **replays the effective observations in the applied order** `(ledgerSequence, ExperienceRef)` up to the cutoff, in
  pages of at most 500 from a Store `ADAPTER_READY` export. A rebuilt state must equal the state built from scratch from the same
  corrected export. The rebuild runs on a **staged copy**: the live state is swapped in only after the whole replay succeeds. A
  failure, including `CAPACITY_EXHAUSTED` from a 257th cohort, leaves the previous state unchanged and no partial state is ever
  observable by `decide`; the caller knows the previous state predates the correction and decides whether to keep using it. Route changes never silently inherit another route's cohort.
- **Dormant cohorts.** A rebuild replays observations by their own `(RouteId, RouteVersion)`, **not** by the current catalog.
  A route that is absent from the current catalog keeps its replayed cohort, marked `DORMANT`: it is never consulted by
  `decide` and never counted toward `minSupportingObservations` for another route. When an identical `(RouteId, RouteVersion)`
  is reintroduced, its cohort becomes active with its history intact instead of returning cold. Dormant cohorts count toward
  the 256-cohort bound; when it is reached the state reports `CAPACITY_EXHAUSTED`, and only an explicit caller action removes
  a dormant cohort (after which a later rebuild would recreate it from the export unless the caller excludes that route).
- **Checkpoint and compaction.** The applied-revision ledger is bounded at 4,096 entries per state (one `(scopeId, evaluationPolicy)` partition), the same as one rebuild
  call, so a rebuild can never exhaust the ledger it is rebuilding. To go beyond it, the caller writes a
  `RoutingStateCheckpoint(scopeId, featureSchemaVersion, evaluationPolicy, mappingVersion, policyId, policyVersion, processedCutoff, preferenceSnapshot, ledgerDigest)` and compacts the entries it covers. A rebuild may
  start from the latest checkpoint whose `processedCutoff` is **strictly less than** the `supersedesSequence` of the earliest
  superseded revision (an inclusive checkpoint at that sequence already contains the obsolete effect) and replay only later
  observations. Starting from a valid checkpoint must give a state **identical** to a rebuild from scratch (#67 verifies
  this). A superseded revision at or before the checkpoint's `processedCutoff` invalidates it and forces a full rebuild.
- **Atomic application.** Applying a `RoutingFeedback` is all-or-nothing. The caller **preflights** the ledger capacity (the
  entries it would add against the 4,096 bound) and the cohort capacity before mutating anything. If either would be
  exceeded the result is `CAPACITY_EXHAUSTED` and the Nodes, the applied-revision ledger, the supporting-observation counts and
  `processedCutoff` are **all left unchanged**, so a retry after compaction applies the feedback exactly once and a later
  correction can still detect the original revision. `processedCutoff` advances only on success of the whole batch.
- **Fallback.** For a valid cold-start snapshot (right bindings, no cohorts), a candidate route without a cohort, or
  insufficient supporting evidence, rule 3 of the ordering is skipped and the decision is exactly the #62 baseline. An
  **incompatible** state never falls back: the abstention of Section 3.2 is authoritative, so it yields
  `Abstain(STATE_INCOMPATIBLE)` when a route is eligible, and the host decides, for example by re-asking with a valid empty
  snapshot. Preference can reorder eligible routes only; it never changes authorization,
  mandatory quality gates or API permissions. There is no exploration.

Retained-footprint model (**modeled, not measured**; a 64-bit HotSpot with compressed references, as in ADR 0021): a
`RoutingFeedback` is about 64 B plus 36 B per entry (at most about 2.4 KB at 64 entries); the applied-revision ledger at
about 64 B per entry is at most about 256 KiB at 4,096 entries; a checkpoint is the snapshot plus a digest (a few KB at 256
cohorts); preference Nodes have `historyLimit = 0`, so they retain no history and cost only the Node and its four numeric
channels, on the order of 100 B each, a few tens of KiB for 256 cohorts (a model, not a measurement). Measured allocation and latency are deferred to #67 and must not be inferred from this model.

## 8. Algorithm, Data Structures and Complexity

Let `R` be routes (at most 32), `Q` requirements (at most 16), `K` ranked candidates (at most 8) and `C` cohorts of one state (at most 256).

| Operation | Time | Extra space | Notes |
| --- | --- | --- | --- |
| Eligibility filter | `O(R·Q)` | `O(R)` | Linear scan over an immutable array sorted by `(RouteId, RouteVersion)`. |
| Order eligible routes | `O(R log R)` | `O(R)` | Fixed lexicographic comparator; at most 32 elements, so a plain sort is clearer than a heap. |
| Preference lookup | `O(1)` expected per candidate | none | Point lookup in a cohort-key map; **never iterated for ordering**, so hash iteration order cannot affect results. |
| `decide` overall | `O(R·Q + R log R)` | `O(R)` | No allocation beyond the decision; no cache, parallelism, SIMD or GPU. |
| Apply one feedback | `O(E)`, `E` at most 64 entries | `O(E)` | Existing `FeedbackAdaptationCognitiveStage` cost model. |
| Rebuild from `N` observations | `O(N·E)` | `O(N + C)` retained | Retained state is the cohort state plus the applied-revision ledger rebuilt for the `N` observations (at most 4,096 entries even with one cohort); transient working space is `O(E)` plus one export page of at most 500. Bounded by 4,096 observations per call. Starting from a valid checkpoint replays only later observations. |

Data-structure rationale (access pattern first): the catalog is read-mostly and tiny, so a sorted immutable array gives
deterministic iteration and good locality with no per-route collection objects; cohort lookup is a point query, so a map with
deterministic canonical keys is enough, and any snapshot export iterates keys sorted by canonical string. Collections are
copied defensively and immutable once published. No premature cache, parallelism or primitive layout is justified at
`R ≤ 32` and `C ≤ 256`; revisit only if a #67 measurement shows a hotspot.

Determinism: total orders everywhere (Section 3.3, Section 2), no wall-clock lookup, no randomness, no dependence on hash or
thread order. Equal inputs produce equal decisions and equal rebuilt states.

## 9. Examples

Catalog `cat-demo` version `7`; every identifier is fictional; no provider name or limit is encoded.

| Route | Billing | Tier | Overflow class | Capabilities | Availability (catalog, not versioned) |
| --- | --- | ---: | --- | --- | --- |
| `sub-a` v1 | `SUBSCRIPTION` | 1 | `STANDARD` | `java` | `AVAILABLE` |
| `sub-b` v1 | `SUBSCRIPTION` | 1 | `STANDARD` | `java` | `AVAILABLE` |
| `api-x` v1 | `API_METERED` | 2 | `OVERFLOW` | `java`, `long-context` | `AVAILABLE` |

Every example route also carries the mandatory values below, and every request has the matching ones, unless an example says
otherwise; without them a route would be ineligible (Section 3.1):

| Route value | All three routes | Request value (all requests) |
| --- | --- | --- |
| `stages` | `{plan, implement, review, qa}` | `stageKind` as stated per example |
| `executionModes` | `{sandboxed}` | `permittedModes = {sandboxed}` |
| `locality` | `hosted` | `allowedLocalities = {hosted}` |
| `tools` | `{edit, test}` | `requiredTools = {}` |

Scope `scope-demo`. Policy `route-lex` version `1` (Section 3.3), `minSupportingObservations = 3`, `maxAutoSelectTier`
unset.

### 9.1 Cold start

Request `r-101`, task `task-17`, stage `s-impl`, `stageKind = implement`, requires `{java}`, `overflowPermitted = false`, empty
preference. Eligible: `sub-a`, `sub-b` (`api-x` has `overflowClass = OVERFLOW`, so it is `OVERFLOW_NOT_PERMITTED`). Tiers and priorities tie, rule 3 is skipped (no
evidence), rule 4 is skipped (no resource data), rule 5 picks `sub-a`.
Result: `Selected(sub-a v1, basis=COLD_START, rulesApplied=[tier, fallbackPriority, routeOrder])` with exclusion reasons for `api-x` only. This is advice; Forge
revalidates and runs it.

### 9.2 No eligible route, then explicit overflow

Request `r-102` requires `{java, long-context}`, `overflowPermitted = false`. `sub-a` and `sub-b` are `MISSING_CAPABILITY`;
`api-x` is `OVERFLOW_NOT_PERMITTED`. Result: `NoEligibleRoute` with three reasons. Neuron does not escalate on its own. Only
after Forge's own quota check does it re-ask with `overflowPermitted = true` (`r-103`), which yields `Selected(api-x v1,
overflowUsed=true)`; the decision records that spending was permitted by the host, not inferred.

### 9.3 Missing evidence

The `sub-a` run for `task-17` completes (worker invocation succeeded) and the Store holds outcome `PENDING`. No feedback is
derived. Later the outcome becomes `ACCEPTED` with origin `IMPORTED_CLAIM`, or the mandatory `TESTS` observation is `UNKNOWN`:
derived status `ACCEPTED_UNVALIDATED`. Feedback is `NEUTRAL` with reason `MISSING_MANDATORY_EVIDENCE`; the preference
cohort values and counts are bit-identical before and after, and only `processedCutoff` advances to the examined sequence. The success of the invocation is never counted as reward.

### 9.4 Rejected work

`task-18`, stage `implement`, routed to `sub-b`. Stage-local evidence shows mandatory `TESTS` is `FAIL` under evaluation policy
`forge-gates` v`1`, the current outcome is `REJECTED`, derived status `NOT_ACCEPTED`. The failure is attributable to the
executed route's stage, so `RoutingFeedback` is `PENALIZE` for the cohort of `sub-b`. A `TIMED_OUT` run of `sub-b` would instead
yield `NEUTRAL`, because it says nothing about the generated work.

### 9.5 Corrected evidence

`task-17` on `sub-a` was `VALIDATED_ACCEPTED`: observation `X1` (revision 1, ledger sequence 41) produced `REINFORCE`, recorded
in the applied-revision ledger. Forge then records a correction: `X1` revision 2 (sequence 58) sets mandatory `TESTS` to `FAIL`.
The effective status is `ACCEPTED_UNVALIDATED` because of a proven mandatory failure, so the effective feedback is `PENALIZE`
(Section 6), not neutral. The caller detects that `X1` was applied at revision 1, resets the scope's preference Nodes to the
baseline, and replays the effective observations in `(sequence, ref)` order up to the cutoff. The rebuilt state equals one built
from scratch from the corrected export: the reward is gone and the penalty is applied once. Nothing is subtracted from the old
state. If instead the correction had set `TESTS` to `UNKNOWN`, the effective feedback would be `NEUTRAL` and the same reset and
replay would simply leave the cohort without that observation. The same procedure applies when a `PENALIZE` is superseded.

A replay for a request with `cutoff = 50` must use a state with `processedCutoff <= 50`. After the rebuild above the state has
`processedCutoff = 58` (the export's high-watermark), even in the `UNKNOWN` variant where the correction at 58 changes no Node,
so it is never accepted for a cutoff-50 request: it yields `Abstain(STATE_INCOMPATIBLE)` (when a route is eligible), never a
decision influenced by sequence 58. The caller rebuilds a separate state as of 50 for that request.

### 9.6 Later-cycle update

After three or more `VALIDATED_ACCEPTED` outcomes favor `sub-b` over `sub-a` in cohort `(implement, bucket-m)`, the preference
becomes active for that cohort. For request `r-140` (`task-31`) with requires `{java}`, tiers and priorities tie, rule 3 now
orders `sub-b` first: `Selected(sub-b v1, basis=LEARNED_PREFERENCE)`. If `sub-b` were ineligible, preference could not promote it;
if `api-x` is permitted it stays tier 2, so a learned preference cannot jump the tier order.

### 9.7 Plan / implement / review / QA

Each stage is a separate request with its own catalog compatibility and cohort; no stage's outcome credits another without
stage-local evidence.

| `stageKind` | Typical hard requirements (host-set) | Cohort | Credit rule |
| --- | --- | --- | --- |
| `plan` | `long-context`, no tool execution | `(plan, bucket)` | Reviewer acceptance of the plan, attributed to the planning route. |
| `implement` | `java`, tool use, locality | `(implement, bucket)` | Mandatory `TESTS` and `CORRECTNESS` on the attempt. |
| `review` | independent route from the implementing one | `(review, bucket)` | Findings confirmed by later evidence; a review route is not credited for the implementer's tests. |
| `qa` | `TESTS`, `SECURITY` observations available | `(qa, bucket)` | Mandatory gates of the QA policy. |

### 9.8 Subscription-first with explicit API overflow

Default host table: `SUBSCRIPTION`/`LOCAL` routes are tier 1 with `overflowClass = STANDARD`; `API_METERED` routes are tier 2 with
`overflowClass = OVERFLOW`, both set by the host. While `overflowPermitted = false`, `OVERFLOW` routes are `OVERFLOW_NOT_PERMITTED`, so no selection or preference can reach them. After Forge sets it to `true`, tier 1 routes
still rank first among eligible ones, so overflow is chosen only when no tier 1 route is eligible (for example 9.2) or the host
policy defines another table. The numeric quota, the provider, and the decision to spend are all outside Neuron.

## 10. Store Dependencies and Implementation Order

The Store's execution-memory v1 contract is accepted but **not implemented**; Store issues
[#94](https://github.com/stevdrey/monada-resonance-store/issues/94)-[#102](https://github.com/stevdrey/monada-resonance-store/issues/102)
are dependencies, not baseline capabilities. Each Neuron issue must re-check the exact public Store API before use.

| Store issue | Capability | Neuron consumer |
| --- | --- | --- |
| #94 | Consumable library publications | #68 |
| #95, #96, #97 | Records, ledger, `ExecutionMemory` facade (history, revisions, idempotency); `ArtifactRef` carries the routing envelope reference (Section 3.2); bounded `history` reads give correction ancestry when the export lacks it | #64, #68 |
| #98 | Scoped projections and bounded recall | Optional host experience recall; never ranking authority |
| #99 | Attempt-chain usage and hypothetical `CostToAcceptedOutcome` | #67 (evaluation-only) |
| #100 | Pairwise comparison and Pareto diagnostics | #67 (evaluation-only) |
| #101 | `exportSamples` (`execution-sample/1`) | #64 |
| #102 | End-to-end Store evaluation | Informational |

**Downstream integration verification (#64, #68).** With a real execution memory, the loop *record, close and reopen, export,
translate* must preserve the exact decision, `(RouteId, RouteVersion)` and cohort binding; a correction must recover the earliest
superseded sequence; and checkpoint validation after compaction must give the same result as a full rebuild. A matching stage and
envelope must translate successfully; an envelope whose descriptor differs from the recorded worker, provider, model, effort or
billing mode must be rejected with `ENVELOPE_ROUTE_MISMATCH` even when its digest is valid; and an envelope from another
task, execution, attempt or stage must be rejected with `ENVELOPE_IDENTITY_MISMATCH`. This contract
defines the mechanism and its dependencies and does not implement Store capabilities.

**API exports versus evaluation-only annotations.** The production `monada-api` export carries outcome, derived acceptance,
per-dimension evidence, usage counters with provenance, exact refs and revisions, and the ledger checkpoint. **Cost and
comparison annotations are produced only by `monada-evaluation` tooling.** Neuron's production code must not depend on
`monada-evaluation`; hypothetical cost reaches Neuron only through an explicit host-supplied annotation seam.

Neuron implementation order (a dependency graph, not a claim that any item exists). Every type an issue uses is introduced by that issue or by a declared prerequisite; where this contract moves a type earlier than the issue text says (`RoutingRequest`, `RoutingPreference`, `CohortMapping`, `RoutingStateDefinition`, `RoutingObservation`), this contract prevails and the issue bodies are to be aligned:

```text
#59 contract  (this document)
  -> #60 TaskFeatures + encoder
       -> #61 RoutingRequest + RouteCatalog (immutable snapshot identity) + ResourceEstimate + eligibility   (needs #60)
            -> #62 RoutingPolicy + RoutingDecision + CohortMapping + RoutingStateDefinition + RoutingPreference (value, EMPTY, admission)
                 + composition seam                               (needs #60, #61)
                 -> #63 RoutingOutcome + RoutingObservation + evidence + learning eligibility   (needs #62)
                      -> #64 Store export -> RoutingObservation   (needs #60, #63; Store #94 #95 #97 #101)
                      -> #65 RoutingFeedback + credit assignment  (needs #62, #63)
                           -> #66 RoutingStateStore, checkpoints, Node bindings, snapshot production
                                                                  (needs #60, #61, #62, #65)
                                -> #67 temporal replay evaluation (needs #62-#66, Store #99 #100)
                                     -> #68 published API + JPMS consumer loop
```

## 11. Compatibility

Everything in this contract is additive and opt-in.

| Surface | v1 routing extension |
| --- | --- |
| `Signal`, `FrequencyState`, `Node` | Unchanged; no identity or route on `Signal`. |
| `CycleInput`, `NeuronRuntime`, `CognitiveStage`, `CognitiveStageKind` | Unchanged signatures and defaults; no new stage kind. |
| `HostExecutionContext`, `ActionRequest`, `PerceptionRequest` | Unchanged; the context stays opaque. |
| `OutcomeFeedback`, `OutcomeFeedbackPolicy`, `FeedbackAdaptationCognitiveStage` | Unchanged behavior. |
| `RuntimeBackendSelector` and hardware backends | Unchanged; independent of route choice. |
| Vector API, scalar fallback | Unchanged; no new preview, incubator or native requirement, no JDK or Gradle change. |
| `ResonanceMemoryPort` | Receives no routing envelope, as with host context. |
| Dependencies | No new dependency; no provider SDK, no Store type in root contracts. |

## 12. Limits and Remaining Evidence Gaps

- **No effectiveness claim.** This document makes no claim that routing improves quality, acceptance or cost. #67 may report
  coverage and chosen-route outcomes on a reproducible workload with controls; replay on Store exports observes only the route
  that was actually chosen and cannot prove counterfactual or online improvement.
- Footprint figures in Section 7 are a model. Latency and allocation are unmeasured until #67.
- Unknown or incomplete evidence can bias routing; the contract's answer is to refuse unsupported inferences (neutral or no
  update), not to estimate them.
- Descriptors are caller observations. A wrong catalog yields wrong advice; Forge's revalidation before execution is the
  safety boundary.
- Stable provenance and schema/policy versions are required for replay; corrections and scope boundaries must not silently
  contaminate learned state, which is why mismatches are reported and rebuilds are explicit.
- `NeuronRuntime` is sequential and not thread-safe; the preference snapshot must be passed by the host, not shared mutably.
- The Store's contract is not yet implemented, so end-to-end behavior with real exports is unverified until #64 and #68.

## 13. Verification (issue #59, historical)

Issue #59 was documentation-only: no runtime change and no new tests. Issue #60 adds code and tests; its verification is Section 15.5. From the repository root, at the #59 baseline:

```bash
git diff --stat origin/main -- . ':!docs' ':!README.md'
./gradlew test
```

The first command must print nothing (no code, build or dependency change). The second is optional and shows the build is
untouched. Relative links and examples are validated by inspection and by resolving every `](...)` target to an existing
file.

## 14. Documentation Updates

- `docs/specs/forge-routing-contract-v1.md` (this file, new; Section 15 added by #60, Section 16 by #61, Section 17 by #62).
- `docs/adr/0026-forge-routing-ownership.md` (new).
- `docs/architecture.md`: "Forge Routing Extension (proposed)" section.
- `README.md`: pointer under the host-embedding section.

## 15. Implemented: `TaskFeatures` and the Signal Encoder (#60)

Package `monada.neuron.routing.features`. This section describes **implemented behavior** and refines the proposal in
Section 3; it does not change the semantics above. `Signal`, `FrequencyState`, the Store encoders and
`HostExecutionContext` are untouched, and nothing in the package is wired into a cycle (opt-in, called by a host-side
`PerceptionCapability` or by Level A code).

### 15.1 Typed features

`TaskFeatures(schemaVersion, stageKind, category, languages, domains, changeSize, contextSize, tests, security)`, schema
`task-features/1`. Every field except `schemaVersion` is a `Feature<T>`: `Known(value)` or `Unknown`; unknown is never a
measured zero, an empty set or a default. There is no task, project, execution, route or price field and no outcome, so
none of them can enter similarity; correlation stays in the routing envelope (#61).

| Rule | Behavior |
| --- | --- |
| Tokens (`stageKind`, `category`, tags, `schemaVersion`) | 1-128 code points, no control characters, no leading/trailing whitespace, Unicode space separator (including NBSP) or format character (U+200B, U+202E, ...); interior format characters such as emoji ZWJ stay valid; `IllegalArgumentException` otherwise. Compared by `String.equals`, never normalized. |
| Tag sets (`languages`, `domains`) | At most 8 tags each; duplicates rejected; stored in **code point order** (not UTF-16 unit order) as an immutable copy. Known-empty set differs from unknown set. |
| Numerics (`changeSize`, `contextSize`) | Non-negative `long` in host-defined units; negative rejected. Values above the policy ceiling are valid and saturate only in Signals. |
| `tests`, `security` | `Requirement.REQUIRED` / `NOT_REQUIRED` or unknown. |
| Capacity | 6 scalar features + 2 x 8 tags = 22 <= contract bound of 32. Overflow throws (a caller-built value). |
| Ownership | The caller's input is copied; later mutation cannot change a snapshot. Equivalent input in any order gives equal records. |

### 15.2 Encoding policy `task-encoding-default` v1

An `EncodingPolicy` is explicit and versioned: vocabularies (ordered, at most 32 tokens each; **order is part of the
policy**) for stage kind, category, language and domain, plus saturation ceilings. A ceiling must be of the form `2^k - 1`
(rejected otherwise), so "value above the ceiling" and "bit length above the ceiling's" are the same condition and the
saturation flag always equals the clamp. Any change to a vocabulary, ceiling or the arithmetic needs a new policy version
(v1 was finalized before the first merge, so there is no earlier published v1). The default vocabularies are illustrative
and host-replaceable. No hashing is used.

The layout is chosen for `ScalarResonanceMetric` (ADR 0006: `R = A*F*P`, `F = min/max` of frequencies). For dimension index `i`
(`STAGE_KIND`=0, `CATEGORY`=1, `LANGUAGES`=2, `DOMAINS`=3, `CHANGE_SIZE`=4, `CONTEXT_SIZE`=5, `TESTS`=6, `SECURITY`=7):
`frequency = 3^i * (1 + ratio)` with `ratio` in `[0, 1]`. Bands are the **closed** intervals `[3^i, 2*3^i]` (a measured zero sits
exactly at `3^i`) and are disjoint. Because the offset is multiplicative, every dimension has the same within-band range
`F` in `[0.5, 1]`: no dimension is implicitly weighted more than another (an additive offset would make high-index dimensions
nearly indistinguishable).

| Value | Signal `FrequencyState(amplitude, frequency, phase)` |
| --- | --- |
| Categorical token, vocabulary index `k` of `n` | `(1.0, 3^i * (1 + (k+1)/(n+1)), 0.0)` |
| Token outside the vocabulary (`OTHER`) | `(1.0, 2 * 3^i, 0.0)`; reported in `otherBucketDimensions` |
| Number `v`, ceiling bit length `L` | `(1.0, 3^i * (1 + min(bitLength(v), L)/L), 0.0)`; integer arithmetic only; `v > ceiling` reported in `saturatedDimensions` |
| `Requirement` | `NOT_REQUIRED` = `(1.0, 3^i * 4/3, 0.0)`, `REQUIRED` = `(1.0, 3^i * 5/3, 0.0)` |
| **Unknown** | `(1.0, 2.5 * 3^i, PI)`: non-silent, in the gap above its band |
| Tag set | one Signal per tag in canonical order; none for a known-empty set; one unknown marker for an unknown set |

Unknown semantics (a policy choice, documented rather than hidden): an unknown value has full amplitude and opposite phase,
so under the resonance metric, **when two Signals of the same dimension are compared**, it matches only an unknown (`R = 1`) and
never a known value (`P = 0`, so `R = 0`). Across dimensions the metric can still be positive (unknown stage kind against unknown
category scores `1/3`, since `F = 2.5/7.5`), so the guarantee requires pairing Signals by band first (Section 15.3). It is never silent (a silent state scores 0 even against itself), never a measured zero and never
`FrequencyState.ZERO`. Unknown therefore does not act as a wildcard.

Default ceilings are 65,535 (`L`=16) for change size and 1,048,575 (`L`=20) for context size. All Signals are
`SignalKind.OBSERVATION`.

Golden fixture (asserted in `TaskFeatureEncoderTest`): `implement`, `bugfix`, `{rust, java}`, `{backend}`, change size 100,
context size 1,048,575, tests `REQUIRED`, security `NOT_REQUIRED` encodes to frequencies
`1.4, 24/7, 10, 16, 216/7, 1863/16 (= 116.4375), 486, 1215, 2916`, all with amplitude 1 and phase 0 (`bitLength(100)`=7, so
`81 * (1 + 7/16) = 116.4375`; `java` is index 0 of 8, so `9 * (1 + 1/9) = 10`).

### 15.3 Result, collisions and schema mismatch

`TaskFeatureEncoder.encode(TaskFeatures)` returns a sealed `EncodingResult`:

- `Encoded(features, signals, policyId, policyVersion, saturatedDimensions, otherBucketDimensions)` retains the **typed
  features next to the Signals**. The Signals are a lossy ordinal layout: distinct out-of-vocabulary tokens share the `OTHER`
  position, values above a ceiling share the maximum, and a vocabulary of `n` tokens is spread over `n+1` positions. These are
  documented collisions, not semantic similarity. **Exact eligibility must use the typed fields**, never Signal equality.
- Every Signal's frequency identifies its dimension (bands above, unknown markers in the gaps). Tag sets have variable length,
  so **consumers must match Signals by band, never by list index**.
- `SchemaMismatch(expected, actual)` when `schemaVersion` is not `task-features/1`. It is a reported result, not an
  exception, and the encoder never guesses.

`Encoded` validates structural invariants in its public constructor (at most 22 `OBSERVATION` signals, valid policy token,
positive version, strictly ordered dimension lists limited to numeric or categorical dimensions); consistency with `features`
is guaranteed only for values the encoder produced.

The encoder reads only its argument: no repository, task text, secret, `HostExecutionContext` reference, clock or randomness.

### 15.4 Complexity and footprint

With `F` features and `T <= 16` tags: construction `O(F + T log T)`; `encode` `O(F)` time with point lookups in
vocabulary maps built once per encoder (never iterated, so hash order cannot affect output); extra space `O(F)`, at most
22 Signals plus their `FrequencyState` objects. **Modeled:** about 64 B per Signal and `FrequencyState` pair plus about 4 B per
list reference, roughly 1.5 KiB for a full encoding (22 x 64 B + 16 B + 88 B). **Measured once** (JDK 27,
`ThreadMXBean.getThreadAllocatedBytes`, after 20,000 warm-up calls, one full-capacity `encode`): 1,896 bytes, which includes the
two `EnumSet`s and the result record. The test asserts only an upper bound of 4 KiB; it is a regression guard, not a benchmark,
and no latency is measured. No cache, parallelism, SIMD or GPU is used.

### 15.5 Verification and evidence gaps

```bash
./gradlew test
./gradlew consumerSmokeTest
```

Evidence gaps: no claim that this layout improves routing or that Signal resonance reflects task similarity; the default
vocabularies, the band layout and the unknown semantics are unvalidated policy choices; latency is unmeasured and allocation is a single upper-bound check (#67); the
encoder is not yet consumed by a `PerceptionCapability`; `RoutingRequest` (#61, Section 16) carries the typed `TaskFeatures` record beside its hard requirements, but nothing feeds the encoded Signals into a request or a cycle yet.

## 16. Implemented: Route Catalog and Eligibility Filtering (#61)

Package `monada.neuron.routing.catalog`. This section describes **implemented behavior** and refines Sections 2, 3.1 and
3.2; it does not change their semantics. Everything is additive and opt-in; nothing is wired into a cycle and no existing
type changed. There is no ranking, no `RoutingDecision` (#62), no discovery, quota polling, credentials, process launch or
retained state.

### 16.1 Types and rules

| Type | Behavior |
| --- | --- |
| `RouteKey(routeId, routeVersion)` | Token plus version >= 1; natural order is the canonical route order (code point, then numeric). |
| `RouteDescriptor` | Versioned values only: worker/provider/model/effort (`Feature`, unknown allowed), `billingMode`, `overflowClass`, `tier` >= 1, `stages`, `capabilities`, `tools`, `executionModes` (sets of at most 16 tokens, code point order, duplicates rejected, absent = empty), `locality` (one token or unknown), `contextCeiling` (`Known` >= 0 or unknown; a known 0 is not unknown). |
| `CatalogEntry` | Descriptor plus snapshot-only `availability` (`AVAILABLE`, `UNAVAILABLE`, `UNKNOWN`) and `fallbackPriority` (consumed by ranking in #62, ignored by eligibility). |
| `ResourceValue` / `ResourceEstimate` | `Known(value >= 0, REPORTED or ESTIMATED)`, `Unknown`, `NotMeasured`; carried only, no arithmetic. |
| `RouteCatalog(catalogVersion, entries, estimates)` | At most 32 routes, 4 estimates per route (so at most 128 per catalog, checked before any copy or sort), one per (route, dimension), estimates only for catalog routes. Duplicate `RouteKey` is rejected whatever the other fields (`IllegalArgumentException`). Entries and estimates are stored in canonical order, so input permutations give equal records. Immutable, defensive copies. |
| `RoutingRequest` | Envelope, provenance fingerprints, evaluation policy, `TaskFeatures`, `HardRequirements`, `overflowPermitted`, `cutoff`. Only `forge-routing/1` is accepted. The record has no default: hosts pass `false` unless they authorize overflow (the contract's default of `false` is a host obligation here). `TaskFeatures` travel beside the constraints and are never used for eligibility; a known `features.stageKind` must equal the request's `stageKind` (construction fails otherwise), an unknown one is allowed. |
| `HardRequirements` | `requiredCapabilities`, `requiredTools` (empty = always satisfied), `allowedLocalities` and `permittedModes` (allow-lists: empty permits nothing), `requiredContextSize` (host units; **0 means no requirement**, a refinement of Section 3.1 so that a known zero requirement never needs a ceiling). |
| `RouteEligibilityFilter.evaluate(request, catalog)` | Stateless. Returns an `EligibilityReport` that places every catalog route in exactly one of `eligible` (with the chosen mode, first common mode in code point order, and an `overflow` flag) or `excluded` (primary reason plus `additionalReasons`, at most 8, unique and strictly later than the primary in precedence order, otherwise rejected at construction). A directly built report is also bounded to 32 routes, rejects a repeated key or a key in both lists, and stores both lists in canonical order. `noneEligible()` is the `NoEligibleRoute` condition. |

Reasons follow the precedence of Section 3.2 (`EligibilityReason` is declared in that order): `STAGE_INCOMPATIBLE`,
`ROUTE_UNAVAILABLE`, `AVAILABILITY_UNKNOWN`, `MODE_NOT_PERMITTED`, `LOCALITY_NOT_PERMITTED`, `OVERFLOW_NOT_PERMITTED`,
`MISSING_CAPABILITY` (capabilities and tools), `REQUIRED_LIMIT_EXCEEDED`, `REQUIRED_LIMIT_UNKNOWN`. Unknown mandatory data
fails closed: unknown availability, absent stages, absent modes, unknown locality and an unknown ceiling when a context size is
required all exclude the route. Unknown optional values (worker, provider, model, effort) never affect eligibility.

The report is not a `RoutingDecision`: it reserves nothing, grants no authorization and does not rank. Forge must
revalidate authorization, availability and quota immediately before executing any route.

### 16.2 Complexity and footprint

`R` <= 32 routes, `Q` <= 16 tokens per requirement set. Time `O(R*Q)` (all sets are sorted, so subset and intersection checks
are linear merges; no hash iteration, so order cannot affect results); extra space `O(R)` for the report; no retained state,
cache, parallelism, SIMD or GPU. **Measured** on the final implementation, including the validating and canonicalizing `EligibilityReport` constructor (JDK 27, `ThreadMXBean.getThreadAllocatedBytes`, after 20,000 warm-up calls, one evaluation at R=32, Q=16 with every route eligible, three separate runs): 7,696, 7,696 and 7,720 bytes. Earlier revisions measured 5,504 bytes before report validation and 11,736 bytes with a set-based validation; re-measure whenever report construction changes. The test publishes the figure through the JUnit report, asserts a positive, readable counter (it is skipped, not passed, when allocation tracking is unsupported) and an upper bound of 16 KiB, about twice the measurement; it is a regression guard, not a benchmark, and no latency is measured. The `O(R*Q)` bound follows from the algorithm (merge-based subset and intersection checks over sorted sets, one pass per route) and is not established by a work-counting test; the grid test only checks that every route is accounted for. `RouteTokens` intentionally duplicates the package-private
token rules of `features.FeatureTokens`, because #61 may not edit that package; consolidating them is follow-up work.

### 16.3 Verification and evidence gaps

```bash
./gradlew test
./gradlew consumerSmokeTest
```

The tests include a plan/implement/review/QA capability-matrix fixture and the Section 9.1 and 9.2 outcomes.

Evidence gaps: no latency measurement; eligibility is only as correct as the caller-supplied catalog (descriptors are
observations, not proof); ranking, `RoutingDecision` and state admission arrive with #62; the `EligibilityReport` is not yet
consumed by any policy.

## 17. Implemented: Routing Policy, Decisions and Composition Seam (#62)

Package `monada.neuron.routing`. This section describes **implemented behavior** and refines Sections 2, 3.2 to 3.5; it
does not change their semantics. Everything is additive and opt-in. `Signal`, `CycleInput`, `NeuronRuntime`,
`CognitiveStage`, `CognitiveStageKind`, `RuntimeBackendSelector` and `HypothesisEvaluationPolicy` are unchanged; the only
edit outside the routing packages is an additive branch in `ReasoningCognitiveStageResult.hypothesesOf` that also reads the
hypotheses of a `RoutingCognitiveStageResult` (a scope correction accepted for this issue, because the contract requires the
following `EVALUATION` stage to receive them and `hypothesesOf` recognized only one result type).

### 17.1 Types

| Type | Behavior |
| --- | --- |
| `RoutingPolicy` / `LexicographicRoutingPolicy` | `decide(RoutingRequest, RouteCatalog, RoutingPreference) -> RoutingDecision`, pure, stateless. The reference policy is `route-lex` v`1`; parameters: `RoutingStateDefinition`, `minSupportingObservations` (default 3, at least 1), `maxAutoSelectTier` (empty = unbounded), optional `ResourceObjective(dimension, MINIMIZE/MAXIMIZE)`. |
| `RoutingDecision` | Sealed `Selected` / `Abstain` / `NoEligibleRoute`, each with `Provenance(decisionRef, catalogVersion, policyId/Version, cutoff, StateBinding, StateValidation)` and its exclusion reasons (for excluded routes only, reusing `EligibilityReport.Exclusion`). `Selected` adds the route, the chosen `executionMode`, `Basis`, `overflowUsed`, at most 8 `RankedCandidate`s (`Placement` names the rule that separated each from its neighbour) with `candidatesTruncated`, `rulesApplied` / `rulesSkipped` and the `CohortBinding`. A decision is never an `ActionStatus`. |
| `RoutingPreference`, `CohortPreference` | Immutable snapshot (at most 256 cohorts, unique per stage kind, bucket and route, canonical order, finite values, `-0.0` normalized). `RoutingPreference.empty(...)` is the valid cold start; `mismatches(request, definition)` returns the admission failures in the fixed order of Section 3.2 (`StateMismatch`). The snapshot's own producing policy id/version is provenance, not an admission key. |
| `CohortMapping`, `ChangeSizeCohortMapping`, `RoutingStateDefinition` | Pure feature-to-bucket function with `bucketMappingVersion`; the reference mapping buckets change size (`S` up to 50, `M` up to 500, `L`, `UNKNOWN`) and its thresholds are an **unvalidated policy choice**. In #62 `RoutingStateDefinition(mappingVersion, cohortMapping)` carries only the mapping; the initial Node state, `AdaptationConfig` and feedback rules join the same composite `mappingVersion` in #65 and #66 (a refinement of Section 3, not a change of semantics). |
| `RoutingInput`, `RoutingInputResolver`, `RoutingCognitiveStageResult`, `RoutingReasoningStage` | Level B (Section 3.5). `RoutingReasoningStage` is a `REASONING` **source** stage: first in the cycle, empty initial Signals, no perception source in the same cycle. It resolves `RoutingInput` from the cycle's `HostExecutionContext` through the host port and throws `IllegalStateException` when there is no host context or the resolver returns empty. It also re-verifies every decision against `RouteEligibilityFilter` and throws `IllegalStateException` on any disagreement, so a custom policy cannot hand off a hypothesis for an ineligible route: a `Selected` route must be eligible with the same execution mode and overflow flag; `NoEligibleRoute` is rejected while a route is eligible; an `Abstain` must list exactly the eligible routes; and the exclusions must equal the filter's. It also rejects a decision whose `catalogVersion`, `decisionRef`, `cutoff`, state binding or `policyId`/`policyVersion` do not match the resolved catalog, request, preference and policy, because `Proposition.code` is meaningful only with its own catalog snapshot (the admission `validation` result is not recomputed, because the port does not expose the policy's state definition). The checks cost one extra `O(R*Q)` filter pass per cycle. The stage also checks that every ranked candidate is eligible and that the explanation is the bounded prefix the filter implies (`min(8, eligible)` candidates, `candidatesTruncated` iff more than 8 are eligible). The decision types reject a route that is both eligible/ranked and excluded, an `Abstain` candidate that is not in its eligible list, more eligible or ranked plus excluded routes than one catalog holds (32), a `Selected` whose `cohortBinding` schema or mapping version disagrees with the state provenance and `PolicyParameters`, and contradictory rule explanations (`rulesApplied` and `rulesSkipped` must be unique, in `RoutingRule` order, disjoint and together cover all five rules, with tier, priority and route order always applied). The stage also verifies the admission keys that the request alone decides (scope, feature schema, evaluation policy, `processedCutoff` against the cutoff): a decision recorded as `Compatible` is rejected when the preference conflicts with the request, and an `Incompatible` one must list every such mismatch. A `POLICY_TRADEOFF_UNRESOLVED` abstention must rank `min(8, eligible)` candidates and flag truncation exactly when more than 8 are eligible; a truncated `Selected` ranking lists exactly 8 candidates and counts one further, unlisted eligible route toward the 32-route bound. `Basis` and `Placement` must agree (`Placement.basis()`), every placing rule must be in `rulesApplied`, and `ONLY_ELIGIBLE` marks exactly a sole, untruncated candidate. The cohort bucket token itself is not re-derived, because the `RoutingPolicy` port does not expose the `CohortMapping`. A `Selected` decision yields one hypothesis `Proposition(domain, zero-based index of the route in the catalog's canonical order)` without `SignalEvidence`; `Abstain` and `NoEligibleRoute` yield none. Output Signals are always empty. |

### 17.2 Decision flow

1. `RouteEligibilityFilter.evaluate`. No eligible route: `NoEligibleRoute`, state `NOT_EVALUATED`, even for an incompatible snapshot.
2. State admission (Section 2). Any mismatch: `Abstain(STATE_INCOMPATIBLE)` with every mismatch, the eligible routes in canonical order and no ranking.
3. Ordering by the lexicographic rules of Section 3.3: `tier` ascending, `fallbackPriority` ascending, learned preference descending, resource objective, canonical route order. Precise reading of the scope of the optional rules:
   - **Learned preference** is applied only when the snapshot is compatible and *every* eligible candidate has a cohort (same stage kind, same bucket computed from the request, same `(RouteId, RouteVersion)`) with at least `minSupportingObservations`; otherwise it is skipped for all candidates, never partially applied. It reorders eligible routes only and never crosses a tier.
   - **Resource objective** is applied to each group still tied after the first three rules, and only if every route of that group has a `Known` estimate of the objective's dimension with an identical `unit`. `Unknown`, `NotMeasured` and a missing estimate are never cheapest or fastest and never zero. `ResourceEstimate` has no currency or pricing-assumption field (#61), so comparability is `dimension` plus `unit`: the host must put currency and pricing assumptions into the unit token.
4. `maxAutoSelectTier` set and the best route's `tier` above it: `Abstain(POLICY_TRADEOFF_UNRESOLVED)` with the ranked candidates. Insufficient evidence and unknown estimates only skip rules, they never abstain.
5. Otherwise `Selected`. `Basis` is `HOST_PRIORITY` (tier or priority separated the leaders), `LEARNED_PREFERENCE`, `RESOURCE_OBJECTIVE`, or `COLD_START` (canonical order or a sole route).

### 17.3 Limits, complexity and footprint

`R` <= 32 routes, `Q` <= 16 requirement tokens, 8 ranked candidates, 256 cohorts. Time `O(R*Q + R log R + R log C)` with `C` <= 256 cohorts (eligibility filter, one sort of at most 32 working objects, one merge pass to pair eligible routes with their entries, grouped resource refinement, one binary search per candidate in the snapshot's canonical cohort order); extra space `O(R)`. Dormant cohorts are never indexed: the cohort lookup is an allocation-free binary search over the sorted snapshot (a refinement of Section 8, which named a map), so its cost does not scale with the number of retained cohorts. Estimate lookups are `HashMap` point queries over at most `4R` estimates, built per call and never iterated, so hash order cannot affect results. No cache, parallelism, SIMD or GPU, no clock, no randomness, no retained state. **Measured** (JDK 27, `ThreadMXBean.getThreadAllocatedBytes`, after 20,000 warm-up calls, one `decide` at R=32 with an objective and 32 estimates, three runs, with a 256-cohort snapshot of which 32 cohorts are live and 224 dormant): 18,512, 18,488 and 18,464 bytes (an empty snapshot measured 16,760, 16,584 and 16,440 bytes earlier). The test publishes the figure and asserts only an upper bound of 32 KiB; it is a regression guard, not a benchmark, and no latency is measured. The asymptotic bound follows from the algorithm and is not established by a work-counting test.

### 17.4 Verification and evidence gaps

```bash
./gradlew test
./gradlew consumerSmokeTest
```

The tests cover the Section 9 fixtures (cold start, no eligible route then explicit overflow, subscription-first, learned promotion that cannot cross a tier), ties and numeric version order, unknown and incomparable resource metrics, both abstention reasons, every admission mismatch, input permutations (catalog entries, estimates and cohorts) giving equal decisions, injected cheap invalid candidates, bounds and truncation, and the cycle composition with a minimum positive budget (`maxSteps >= 1`, `maxSignals >= 1`): full result preserved by normalization, typed hand-off to `EVALUATION`, `NO_SIGNALS` for a following stage without typed-only support, rejection of initial Signals.

Evidence gaps: no claim that the ordering improves quality, acceptance or cost; the default cohort thresholds are unvalidated; learned preference values can only be produced by #63 to #66, so non-empty snapshots are test fixtures here; latency is unmeasured; `RoutingEnvelope` transport, outcomes and feedback are not implemented; the reasoning-stage adapter and `hypothesesOf` extension introduce a package dependency from `reasoning` to `routing` that a later refactor may invert with a small shared interface.
