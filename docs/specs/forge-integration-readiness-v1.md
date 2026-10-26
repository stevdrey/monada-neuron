# Forge Integration Readiness Contract v1

**Contract identifier**: `forge-integration-readiness/1`
**Status**: Accepted (issue #94; documentation only; normative for host adoption, decided in ADR 0033). Section 5.4
documents the Neuron types added by issue #95 (ADR 0037).
**Composes**: [Forge Routing Contract v1](forge-routing-contract-v1.md) (`forge-routing/1`), unchanged
**Decision record**: [ADR 0033](../adr/0033-phase-gated-host-adoption-boundary.md); Section 5.4 by
[ADR 0037](../adr/0037-feature-readiness-diagnostics.md)
**Forge baseline**: `stevdrey/monada-forge` at commit [`56c35e05bb94d4d97e49b59c138fbaed66036ecd`](https://github.com/stevdrey/monada-forge/commit/56c35e05bb94d4d97e49b59c138fbaed66036ecd) (2026-10-21)

This document tells a host application, Monada Forge in particular, how to opt into Neuron's routing advice one phase at a
time. It names the first useful slice, the gate for each later phase, who owns every input, output and authority, how a
reviewed task draft is turned into the existing `TaskFeatures` and `RoutingRequest`, what "ready" means before a catalog or
a Store exists, and how stale metadata is handled. It composes the routing contract v1; it introduces no type of its own,
no value of an existing type, no numeric limit and no new version of `forge-routing/1`. Section 5.4 describes the Neuron
types added by issue #95 (feature readiness diagnostics), which this contract documents but does not define.

Every Forge phase beyond Phase 1 is written in the conditional. Forge `main` at the pinned commit has Phases 0 and 1 only,
and its roadmap says the Phase 1 manual desktop verification is still partly pending. Nothing here changes Forge's roadmap
or claims that any later Forge phase exists. A gate says: when Forge reaches this evidence, these inputs exist and this
Neuron call is well formed.

## 1. Scope and Relationship to the Routing Contract

### 1.1 What this contract covers

- The **transformation boundary**: which Forge values become which Neuron values, by host code, and which Forge values
  never cross.
- The **ownership** of every input, output and authority among Forge, Neuron and the Resonance Store, with one owner each.
- The **phase gates** 2 to 5: the Forge exit evidence each gate waits for, the Neuron inputs that become available at it,
  and the Neuron calls made.
- The **host readiness outcomes** a host reports before, instead of, or after a Neuron call.
- **Staleness**, re check and re request rules, owned by the host.
- **Executed route evidence**, correction, replay and checkpoint ownership per phase.
- **Dependencies**, the core only mode, and a versioning and migration rule for this contract itself.

### 1.2 What it does not cover

- No new SDK API, no Forge adapter inside Neuron, no model discovery, no stage planning, no automated execution, no
  persistence, no `forge-routing/2`.
- No change to any Forge repository. Forge side prerequisites are described, not scheduled.
- No numeric limit beyond those v1 already guarantees (Section 9).

### 1.3 Relationship to `forge-routing/1`

The routing contract v1 defines the types, the ownership table (its Section 1), identity and versioning (Section 2), the
request, catalog and policy inputs (Section 3.1), the decision variants (Section 3.2), the bounds (Section 4), outcomes and
delayed feedback (Section 5), credit assignment (Section 6), the trust model (Section 18.1), feedback (Section 21),
checkpoints (Section 23) and the published coordinates and embedded host loop (Sections 24.1 and 24.2). This document only
says how a host that holds a reviewed task draft gets from there to a well formed request, which of its values are
trustworthy, when each call becomes well formed, and what the host must do when its own inputs change. Where the two documents
seem to differ, v1 governs the types and Neuron behaviour; this document governs host adoption order and host side state.

### 1.4 Forge baseline at the pinned commit

| Forge type (at `56c35e05`) | What it holds | Source |
| --- | --- | --- |
| `TaskDraft` | status `INCOMPLETE`, `REVIEWABLE`, `READY`; `missing()` lists `WORKSPACE`, `SPECIFICATION`, `SCOPE`; a confirmation is withdrawn by any later change to a component | [TaskDraft.java](https://github.com/stevdrey/monada-forge/blob/56c35e05bb94d4d97e49b59c138fbaed66036ecd/core/src/main/java/io/github/stevdrey/monadaforge/core/task/TaskDraft.java) |
| `TaskDraftService` | holds one draft in memory; a private `epoch` that increments when the workspace is replaced or the draft is cleared; `confirm()` returns `CONFIRMED` or `INCOMPLETE` | [TaskDraftService.java](https://github.com/stevdrey/monada-forge/blob/56c35e05bb94d4d97e49b59c138fbaed66036ecd/core/src/main/java/io/github/stevdrey/monadaforge/core/task/TaskDraftService.java) |
| `TaskSpecification` | free text: title, description, acceptance criteria, constraints, non goals; a `TaskSource` | [TaskSpecification.java](https://github.com/stevdrey/monada-forge/blob/56c35e05bb94d4d97e49b59c138fbaed66036ecd/core/src/main/java/io/github/stevdrey/monadaforge/core/task/TaskSpecification.java) |
| `TaskScope` | `Unset` (permits nothing), `EntireWorkspace`, `Paths`; path authorization data only, never routing permission and never an input to Neuron | [TaskScope.java](https://github.com/stevdrey/monada-forge/blob/56c35e05bb94d4d97e49b59c138fbaed66036ecd/core/src/main/java/io/github/stevdrey/monadaforge/core/task/TaskScope.java) |
| `WorkspaceRoot` | one validated local directory path | [WorkspaceRoot.java](https://github.com/stevdrey/monada-forge/blob/56c35e05bb94d4d97e49b59c138fbaed66036ecd/core/src/main/java/io/github/stevdrey/monadaforge/core/workspace/WorkspaceRoot.java) |
| Roadmap | Phases 0 and 1 implemented; Phases 2 to 5 not; Neuron and Resonance Store integration is optional and placed in Phase 5 | [Roadmap.md](https://github.com/stevdrey/monada-forge/blob/56c35e05bb94d4d97e49b59c138fbaed66036ecd/docs/wiki/Roadmap.md) |
| Security invariants | task text is untrusted data; confirmation is an in memory change withdrawn by any later edit and is not execution authorization; an unset scope permits nothing | [security-invariants.md](https://github.com/stevdrey/monada-forge/blob/56c35e05bb94d4d97e49b59c138fbaed66036ecd/docs/security/security-invariants.md) |

Forge has no task ids, no stages, no plan, no workers, no persistence and no fingerprints at this commit. Every one of those
is a Forge side prerequisite named at the gate that first needs it (Section 3).

## 2. Ownership

### 2.1 Ownership table

Each row has exactly one owner. The owner decides the value or holds the authority; the other parties consume it or never
see it. The table extends v1 Section 1 and [ADR 0026](../adr/0026-forge-routing-ownership.md) to the host side values this
document introduces into the discussion; it changes no v1 row.

| Input, output or authority | Owner | Notes |
| --- | --- | --- |
| Task text (title, description, acceptance criteria, constraints, non goals) | Forge | Never enters Neuron (Section 4.3). |
| Workspace path and `TaskScope` entries | Forge | Path authorization only; never enters Neuron. |
| Review state (`INCOMPLETE`, `REVIEWABLE`, `READY`) | Forge | A host readiness input; not permission (Section 2.2). |
| Confirmation of a draft and its withdrawal | Forge | In memory, withdrawn by any later edit. |
| Draft epoch and the three fingerprints | Forge | Minted and compared by the host; Neuron compares fingerprints by equality only. |
| `scopeId`, `taskId`, `executionId`, `attemptId`, `stageId`, `requestOrdinal` | Forge | Opaque tokens per v1 Section 2 and [ADR 0028](../adr/0028-shared-opaque-token-rules.md); Neuron never mints, parses or hashes them. |
| `stageKind` and `category` picks | Forge (a human, from fixed host lists) | `Unknown` until picked; never parsed from text. |
| Workspace observations (`languages`, `domains`, `changeSize`, `contextSize`) | Forge | Untrusted observation; may be wrong; shape encoding and preference only. |
| `tests` and `security` toggles | Forge (a human) | Explicit toggles; default `Unknown`. |
| `HardRequirements`, `overflowPermitted` | Forge (host execution policy) | Eligibility input; never widened by Neuron. |
| `RouteCatalog` and its `catalogVersion` | Forge | Descriptors are observations, not proof; availability is a snapshot value. |
| `evaluationPolicyId`, `evaluationPolicyVersion` | Forge | A partition key chosen once per host. |
| `RoutingPolicy`, `RoutingStateDefinition`, `TrustedRoutingProfile` selection | Forge (configuration) | Neuron supplies the reference implementations; the host selects them. |
| Eligibility filtering, ordering, the `RoutingDecision` | Neuron | Deterministic, fail closed, pure; advice only. |
| Decision verification semantics in `RoutingReasoningStage` (the mandatory validator and the verifier checks) | Neuron | What is checked and what a failed check means (v1 Sections 17.5, 18). |
| Selection of the `TrustedRoutingProfile` the stage verifies against | Forge | The host selected profile is trusted configuration (v1 Section 18.1). |
| Semantics of `RoutingPreference`, `RoutingFeedback`, `RoutingStateStore`, `RoutingStateCheckpoint` | Neuron | The host holds every instance; Neuron defines what they mean. |
| Authorization to run anything, quota, spend | Forge | Neuron never infers it (v1 Section 1). |
| Execution of a worker, cancellation | Forge | Neuron has no worker and no cancellation hook; `decide` is synchronous and pure. |
| Evidence judgment, acceptance | Forge | Neuron never inspects code or calls a judge. |
| Local outcome entries before Phase 5 | Forge | Host storage, host format (Section 7). |
| Ledger, projections, recall, ranking, export | Resonance Store | Appears at the Phase 5 gate only. |
| Checkpoint persistence, receipt persistence | Forge | Forge may delegate the storage to the Store; the choice and the responsibility stay with Forge. Neuron ships values and codecs only (`proposed, owned by issue #100` for the codec). |
| Readiness outcomes `NOT_READY`, `UNAVAILABLE`, `ADVISED`, `STALE` | Forge | Host vocabulary, not a Neuron type (Section 5). |

### 2.2 Never permission

The following are never permission to run, spend, access a path or accept anything. Each is information; authorization is a
separate, explicit host action at the side effect boundary (Forge security invariant 2), and Neuron never grants or infers
it.

- `TaskDraft.READY`: a confirmed, unchanged draft. It is a readiness input. It does not authorize execution (Forge security
  invariant 3).
- A `RoutingDecision` of any variant: advice. It reserves nothing and grants no authorization (v1 Section 3.1); Forge
  revalidates authorization, availability and quota immediately before executing anything.
- The retained decision used as a receipt (`proposed, owned by issue #97`): a canonical copy of what was advised. It is not
  proof that anything ran.
- A disposition (`proposed, owned by issue #98`): what a human did with advice. It is not execution, not authorization and
  not quality evidence.
- Catalog availability (`AVAILABLE`, `UNAVAILABLE`, `UNKNOWN`) and `fallbackPriority`: snapshot values that `decide` uses
  for eligibility and ordering. They do not authorize a route; Forge revalidates availability before executing.
- Resource estimates: observations with provenance. They do not authorize spend; hypothetical cost is evaluation tooling
  only.
- `TaskScope`: Forge path authorization only. It is never routing permission, it never enters Neuron, and Neuron never
  widens it. Widening a scope needs a separate explicit user action (Forge security invariant 3).
- `overflowPermitted`: admits overflow routes to eligibility for one request. It is not spending authorization; Forge
  revalidates quota before executing.
- A `FeatureReadinessAssessment` and its severities (`INFORMATION`, `MISSING_FOR_HOST_PREVIEW`,
  `INCONSISTENT_WITH_REQUEST`), Section 5.4: a report of which feature fields are known. A report with nothing missing
  and nothing inconsistent is not `READY`, not eligibility and not authorization.

## 3. Phase Gates

### 3.1 The first useful slice: Phase 2 advisory preview

The first independently useful slice is an **advisory preview** shown beside a reviewed plan, when Forge reaches its
Phase 2 exit evidence. It is one pure call, `RoutingPolicy.decide`, on reviewed metadata, with cold state and nothing
executed. It touches every layer a later phase thickens (ids, features, catalog, policy, decision, decision reference)
while needing only the core artifact.

- Policy: `LexicographicRoutingPolicy.reference()` (`route-lex` / `1`) over `RoutingStateDefinition.reference()`
  (`routing-state/1`).
- State: `RoutingPreference.empty(request, policy.definition(), policy.policyId(), policy.policyVersion())`, built fresh
  for every request from runtime values of the policy instance (never from literals), so the state is built from the request
  itself and `cutoff` is 0.
- Verification: a plain `decide` never consults a `TrustedRoutingProfile`, so Phase 2 advice is **unverified Level A output**
  and the host labels it so. Trusted verification through `RoutingReasoningStage` (Level B, v1 Section 24.2) is optional at
  Phase 2 and required from Phase 3, when the host may act on advice after its own, separate authorization.
- Basis: with the empty preference no decision carries a `LEARNED_PREFERENCE` basis; the basis is `COLD_START`,
  `HOST_PRIORITY` or `RESOURCE_OBJECTIVE`. Early advice is tier and fallback priority ordering, which is thin by design.
- Prerequisites that make the preview useful: a static host catalog of at least one route, and a fixed preview
  `HardRequirements` constant derived from that catalog's configuration and labelled preview only. Without them every
  preview would be `UNAVAILABLE(no-catalog)` or `NoEligibleRoute`. Two couplings are required, because an absent value
  fails closed (v1 Section 3.1): every route's `stages` list contains every `stageKind` on the host's fixed pick list,
  and every route declares a `Known` `locality` that the preview constant's `allowedLocalities` contains. A catalog that
  omits either makes every preview `NoEligibleRoute` (`STAGE_INCOMPATIBLE` or `LOCALITY_NOT_PERMITTED`).
- Observations are validated before construction: a collected `languages` or `domains` list that exceeds 8 tags, repeats
  a tag, or holds a tag that breaks the opaque token rules, and any `changeSize` or `contextSize` the host could not
  measure or measured as a negative value, is reported as `Unknown`, never truncated, never substituted and never allowed to throw from the `TaskFeatures`
  constructor. A constructor exception from any other cause is a host defect, surfaced as a host error and never as
  advice; it is not a readiness outcome.

### 3.2 Gate table

Each gate names the Forge roadmap exit evidence it waits for (the Forge roadmap's own words, at the pinned commit), the
Neuron inputs that become available when that evidence exists, the Neuron call, and what stays `Unknown`. A gate that has
not been reached makes no claim about Forge.

| Gate | Forge exit evidence waited on | Neuron inputs that become available | Neuron call | Stays `Unknown` or absent |
| --- | --- | --- | --- | --- |
| **Phase 2 preview** (conditional on Forge Phase 2) | source provenance, scope limits, acceptance criteria | host minted ids, a reviewed `stageKind` from a fixed list, a public draft epoch accessor, the three fingerprints, the `evaluationPolicy` constant, a preview `HardRequirements` constant, a static catalog of at least one route, workspace observations | `TaskFeatures`, `RoutingRequest`, `RouteCatalog`, `LexicographicRoutingPolicy.reference()`, `RoutingStateDefinition.reference()`, `RoutingPreference.empty(...)`, `RoutingPolicy.decide` (unverified advice) | `category` until picked; observations when not collected; no `TrustedRoutingProfile` required; no execution, no outcome |
| **Phase 3 first adapter** (conditional on Forge Phase 3) | contract tests, secret handling, execution policy tests | a real catalog from authorized adapters, per request `HardRequirements` from the host execution policy, the overflow switch, a host selected `TrustedRoutingProfile` | the Phase 2 calls, plus `RoutingReasoningStage` with `TrustedRoutingProfile.lexicographic(parameters, definition)`, built from the `PolicyParameters` and `RoutingStateDefinition` the host holds as its own configuration (v1 Section 18.1), not from the policy instance, for verified advice (the stage is a `CognitiveStage`, so it runs inside a `NeuronRuntime` cycle with a host `RoutingInputResolver` and a `Proposition` domain, v1 Section 24.2, not as a bare call); the host keeps local outcome entries keyed by `DecisionRef` (no Neuron call) | `RoutingPreference` still empty; `cutoff` still 0; no Store |
| **Phase 4 delivery loop** (conditional on Forge Phase 4) | end to end evidence, deterministic stop conditions | judged results and corrections in the local outcome entries; receipt correlation (`proposed, owned by issue #97`); dispositions (`proposed, owned by issue #98`) | the Phase 3 calls; still no feedback into Neuron | `RoutingPreference` still empty; advice still earns zero credit |
| **Phase 5 learning** (conditional on Forge Phase 5) | reproducible comparisons, provenance, measured value | the Resonance Store, the adapter artifact, one `RoutingStateStore` per `StatePartition`, Store exports with ledger sequences, checkpoint persistence (`proposed, owned by issues #100, #101`) | adapter `StoreExperienceTranslator`, `RoutingFeedbackPolicy.derive`, `RoutingStateStore`, `RoutingStateCheckpoint`, `rebuildFrom`; `decide` now receives a Store built `RoutingPreference` with a real `cutoff` | nothing by design; `Abstain(STATE_INCOMPATIBLE)` becomes possible for the first time |

### 3.3 Rules that hold at every gate

- A later gate adds inputs; it never changes the meaning of an earlier one.
- The same `RoutingRequest`, `TaskFeatures` and `RouteCatalog` types serve every gate. Nothing is replaced on the way to
  Phase 5.
- Decision identity is real from Phase 2: the preview mints real `taskId`, `executionId`, `attemptId`, `stageId` and
  `requestOrdinal`, with `attemptId` identifying the preview attempt, because v1 requires them and a later receipt must
  correlate with the executed attempt. Placeholder ids are not used.
- `evaluationPolicyId` and `evaluationPolicyVersion` are supplied from the first request, because they are a partition key
  in v1 Section 2; the example value is `forge-eval-preview` / `1` and the value is Forge's to choose. Changing it later
  starts a separate partition, so it is chosen once.

## 4. Transformation Boundary and Field Table

### 4.1 The boundary

Host code, in a Forge owned adapter module (Section 8.3), reads the reviewed draft and the host configuration and builds
`TaskFeatures`, `HardRequirements`, `RoutingRequest`, `RouteCatalog` and `RoutingPreference`. Only metadata crosses. Forge
keeps the original text, the paths and every authority. Neuron reads host supplied values and returns advice. The adapter
is the only place where a Forge type and a Neuron type meet.

Classification values used below are exactly three:

- **trusted configuration**: a human or host policy chose the value from fixed options, or the host minted it. It may feed
  eligibility.
- **untrusted observation**: Forge measured it and it may be wrong. It may feed encoding and learned preference only, never
  eligibility.
- **caller owned state**: a value the host holds between calls and hands to `decide` as an immutable snapshot.

Neither trusted configuration nor untrusted observation is ever permission. A host asserted catalog snapshot is trusted
configuration (v1 Section 18.1 calls it an authoritative input) even though its descriptors are not proof of capability:
"trusted" says who chose the value, not that the route will work; Forge revalidates before executing.

### 4.2 Field table

| Neuron field | Source owner (Forge side) | Classification | Intended routing use | Never read as permission |
| --- | --- | --- | --- | --- |
| `RoutingRequest.contractVersion` | adapter literal `forge-routing/1` (writing `RoutingRequest.CONTRACT_VERSION` in adapter code inlines to the same literal); checked by the runtime version probe (Section 5.2) against the loaded field | trusted configuration | admission; the constructor rejects any other value with `IllegalArgumentException` (backstop) | n/a |
| `RoutingRequest.scopeId` | host opaque id minted per workspace registration; never the path and never a digest of it | trusted configuration | partition key, state admission | names a boundary only; not path access |
| `RoutingRequest.taskId` | host id per confirmed draft (Phase 2) | trusted configuration | decision identity | not approval of the task |
| `RoutingRequest.executionId`, `attemptId` | host ids per plan execution and attempt; preview attempts get real ids; a re request keeps `taskId` and `executionId` and mints a new `attemptId` | trusted configuration | identity, later receipt correlation | not an execution grant |
| `RoutingRequest.stageId`, `stageKind` | reviewed plan stage chosen by a human from a fixed host list (Phase 2); `stageKind` is a required `String`, so before a stage is picked the host state is `NOT_READY(missing: stage)` | trusted configuration | identity; must equal `TaskFeatures.stageKind`, which is `Known` once picked | not stage authorization |
| `RoutingRequest.requestOrdinal` | host counter per `scopeId`, strictly increasing; a re request takes the next ordinal | trusted configuration | ordering, replay | n/a |
| `RoutingRequest.sourceFingerprint` | workspace source revision (for example a Git SHA combined with a digest of the uncommitted working tree changes, so that an edit after the last commit changes the value), v1 Section 2 meaning, at most 128 code points | trusted configuration | provenance, comparable pairs | n/a |
| `RoutingRequest.contextFingerprint` | host digest of the gathered context, at most 128 code points | trusted configuration | provenance; staleness by string equality | n/a |
| `RoutingRequest.constraintsFingerprint` | host digest of the confirmed specification, scope and policy in force, at most 128 code points | trusted configuration | provenance; staleness by string equality | n/a |
| `RoutingRequest.evaluationPolicyId`, `evaluationPolicyVersion` | host constant chosen by Forge once; example `forge-eval-preview` / `1` | trusted configuration | partition key | n/a |
| `RoutingRequest.features` (`TaskFeatures`) | built by the host adapter from the `TaskFeatures` rows below; `schemaVersion` set explicitly from the adapter literal; the container takes the most conservative classification, each component's own row governs that component, and the `schemaVersion` admission check is not eligibility | untrusted observation | encoding and learned preference only; never eligibility | not a description of the task text; carries no permission |
| `RoutingRequest.requirements` (`HardRequirements`) | Phase 2: a fixed preview constant from the static catalog configuration, labelled preview only; Phase 3 and later: host execution policy per request | trusted configuration | eligibility, evaluated before state, cannot be bypassed by preference | an empty `permittedModes` permits nothing |
| `HardRequirements.requiredCapabilities`, `requiredTools` | host execution policy | trusted configuration | eligibility (exact token match, absent fails closed) | n/a |
| `HardRequirements.allowedLocalities`, `permittedModes` | host execution policy | trusted configuration | eligibility (exact token match) | an empty set permits nothing |
| `HardRequirements.requiredContextSize` | host execution policy, host units; 0 means no requirement | trusted configuration | eligibility | never copied from the `contextSize` observation |
| `RoutingPolicy`, `RoutingStateDefinition`, policy id and version | `LexicographicRoutingPolicy.reference()`, `RoutingStateDefinition.reference()` (`routing-state/1`), `route-lex` / `1`; a host may configure a tier cap on the same `route-lex/1` ordering, always over `RoutingStateDefinition.reference()` through Phase 4 (Section 5.2); `POLICY_ID` and `POLICY_VERSION` are probed fields (Section 5.2), `routing-state/1` is a runtime value and needs no probe | trusted configuration | decision; cold state construction via `RoutingPreference.empty(request, policy.definition(), policy.policyId(), policy.policyVersion())`, runtime values from the policy instance, never literals; the host keys staleness and ledgers on `definition.mappingVersion()`, never on a hard coded string | n/a |
| `RoutingRequest.overflowPermitted` | explicit per request user action; default `false` | trusted configuration | admits overflow routes to eligibility | not spending authorization; Forge revalidates quota before executing |
| `RoutingRequest.cutoff` | Store export watermark; 0 in Phases 2 to 4 | trusted configuration | state admission, replay horizon | n/a |
| `TaskFeatures.schemaVersion` | adapter literal `task-features/1`, set explicitly on the builder (never the builder default, which is the core's own value and would make the backstop unreachable); checked by the runtime version probe (Section 5.2) against the loaded field | trusted configuration | admission; the constructor accepts any token, so without the probe a mismatch surfaces as `Abstain(STATE_INCOMPATIBLE)` with `FEATURE_SCHEMA_MISMATCH` (backstop, only when set explicitly) or as `EncodingResult.SchemaMismatch` from the encoder, never guessed | n/a |
| `TaskFeatures.stageKind` | same source as `RoutingRequest.stageKind` | trusted configuration | encoding | n/a |
| `TaskFeatures.category` | human pick from a fixed host list; `Unknown` otherwise | trusted configuration | encoding | n/a |
| `TaskFeatures.languages`, `domains` | Phase 2 context collection; `Unknown` allowed; at most 8 tags each (`TaskFeatures.MAX_TAGS`) | untrusted observation | encoding and learned preference only | never eligibility |
| `TaskFeatures.changeSize`, `contextSize` | Phase 2 context collection, host units; `Unknown` allowed | untrusted observation | encoding and learned preference only | never eligibility; never copied into `requiredContextSize` |
| `TaskFeatures.tests`, `security` | explicit human toggles (`REQUIRED`, `NOT_REQUIRED`); default `Unknown`; never derived from the constraints list | trusted configuration | encoding | not a quality guarantee |
| `RouteCatalog` (`catalogVersion`, entries, estimates) | Phase 2: a static host catalog of at least one route, required for a call (absent or empty is `UNAVAILABLE(no-catalog)`, Section 5.1); Phase 3 and later: host configuration of authorized adapters; `catalogVersion` changes with any content change | trusted configuration | eligibility and ranking | availability and `fallbackPriority` are not authorization; descriptors are not proof of capability, and availability is a point in time snapshot that Forge revalidates before executing |
| `RoutingPreference` | `RoutingPreference.empty(...)` in Phases 2 to 4; a Store built snapshot in Phase 5 | caller owned state | ranking | n/a |
| `TrustedRoutingProfile` | host selected verifier, `TrustedRoutingProfile.lexicographic(parameters, definition)` built from the `PolicyParameters` and `RoutingStateDefinition` the host holds separately as its configuration; the convenience form `lexicographic(policy)` reads both from the policy instance and so verifies a decision against that instance, not against the host's intended configuration; used only through `RoutingReasoningStage`; optional at Phase 2 (plain `decide` is unverified advice), required from Phase 3 | trusted configuration | decision verification | n/a |

### 4.3 Never enters Neuron

These Forge values never appear in any Neuron input, in any form, including digests Neuron would have to name:

- `TaskSpecification.title`, `description`, `acceptanceCriteria`, `constraints`, `nonGoals`
- `TaskSource`
- `WorkspaceRoot.path`
- `TaskScope` entries (`allowed`, `excluded`, the root)
- file contents
- model output

The fingerprints are host digests compared by equality; Neuron never computes, parses or resolves them. The `scopeId` is a
host minted opaque id, not a path and not a path digest.

### 4.4 Invariants of the transformation

- Every `Unknown` stays `Unknown`. The host never substitutes a default for a value it did not observe or a human did not
  pick.
- Eligibility inputs come only from trusted configuration. Observations shape encoding and preference only.
- `requiredContextSize` is never copied from `contextSize`.
- `TaskFeatures.stageKind` equals `RoutingRequest.stageKind`.
- No Forge free text, path or file content appears in any Neuron input.
- The adapter runs the runtime version probe of Section 5.2 once at start, before building any Neuron object. It never
  relies on a plain comparison with `RoutingRequest.CONTRACT_VERSION`, `TaskFeatures.SCHEMA_VERSION`,
  `RoutingDecision.CONTRACT_VERSION`, `LexicographicRoutingPolicy.POLICY_ID` or `POLICY_VERSION` written in adapter code,
  because the compiler copies those constants into the adapter and such a comparison can never fire.

## 5. Host Readiness Outcomes

### 5.1 The four outcomes

A host reports one of four readiness outcomes for each attempt to obtain advice. All four are **host vocabulary**. None is a
Neuron type, and this contract asks for no new Neuron type to carry them.

| Outcome | Meaning | Trigger |
| --- | --- | --- |
| `NOT_READY(missing…)` | The host cannot build a request yet. | Any prerequisite is missing. `missing` is one or more of the `TaskDraft.Missing` values (`WORKSPACE`, `SPECIFICATION`, `SCOPE`), or the draft is not `READY`, plus the host side values `stage`, `ids`, `policy`. A catalog is not a request input (it is a separate argument to `decide`), so its absence is never `NOT_READY`; it is `UNAVAILABLE(no-catalog)`. |
| `UNAVAILABLE(reason)` | The host could build a request but will not call Neuron. | `reason` is one of `integration-off`, `no-catalog`, `version-mismatch`, `cancelled`. `no-catalog` covers a catalog that is not configured and a catalog that is empty alike (example 10.5); the host checks its catalog configuration for zero routes before constructing a `RouteCatalog`, because an empty `RouteCatalog` is constructible and `decide` over it returns `NoEligibleRoute` with an empty exclusion list, which never stands in for `no-catalog`. `integration-off`, `no-catalog` and `version-mismatch` are detected before any Neuron object is built; `cancelled` can happen at any point before `decide` is called, and a request already built is simply dropped. In every case `decide` is not called. |
| `ADVISED(decision)` | `decide` was called and returned. | `decision` is one of the three unchanged v1 variants: `Selected`, `Abstain`, `NoEligibleRoute`. `NoEligibleRoute` keeps its v1 meaning and is not a readiness failure. |
| `STALE` | Advice exists but its inputs changed before use. | Section 6. |

Two names collide with Neuron values and mean different things:

- Readiness `UNAVAILABLE` is a host decision not to call. `InvocationStatus.UNAVAILABLE` is a worker invocation result after
  execution. Catalog `Availability.UNAVAILABLE` is a route snapshot value.
- Readiness `cancelled` is the person withdrawing the request before `decide` is called. `InvocationStatus.CANCELLED` and
  `OutcomeStatus.CANCELLED` describe an execution that was cancelled.

Cancellation is a host side state because `decide` is synchronous and pure: it has no cancellation hook, it runs to
completion, and a host that no longer wants the result discards it.

### 5.2 Version mismatch

The host runs a **runtime version probe** once at adapter start, before building any Neuron object. It is the host side of
v1's rule that an unknown major version is rejected and never reinterpreted.

**Why a plain comparison cannot work.** `RoutingRequest.CONTRACT_VERSION` (`forge-routing/1`),
`TaskFeatures.SCHEMA_VERSION` (`task-features/1`), `RoutingDecision.CONTRACT_VERSION` (`routing-decision/1`),
`LexicographicRoutingPolicy.POLICY_ID` (`route-lex`) and `LexicographicRoutingPolicy.POLICY_VERSION` (`1`) are
`public static final String` fields initialised with string literals. They are compile time constants, and the Java
compiler copies such a constant into every class that uses it. A comparison written in the adapter therefore compares the
adapter's own copy with itself and never fires against a newer core jar. Writing `RoutingRequest.CONTRACT_VERSION` in
adapter code is the same as writing the literal; both forms are acceptable for the adapter's *expected* value.

**The probe.** For each of the five fields the adapter reads the loaded class's field through reflection
(`Class.getField(name)`, check `Modifier.isStatic`, `get(null)`, check `instanceof String`) and compares the value with
its expected literal by `String.equals`. Any difference, and any failure of the read (`ReflectiveOperationException`, a
`LinkageError` such as `NoClassDefFoundError`, or any `RuntimeException`, including a non static or non String field), is
`UNAVAILABLE(version-mismatch)`. The readiness reason stays the single value `version-mismatch` (the reason list of
Section 5.1 is closed, and a split would be a classification change under Section 11.3); the field name, the expected
value and the found value or failure class go to the host log only. The fields exist at runtime with their real values
(only use sites are inlined); the core is an automatic module, which exports and opens every package, the classpath is
unrestricted, and `getField` works on the constant declared on `RoutingDecision`. `routing-state/1` needs no probe: it is a
literal inside `RoutingStateDefinition.reference()`, a runtime value, and the host keys staleness and ledgers on
`definition.mappingVersion()`, never on a hard coded string. The probe needs no new Neuron API. Runtime accessors already
exist for four of the five values (`policy.definition().cohortMapping().featureSchemaVersion()`,
`TrustedRoutingProfile.lexicographic(policy).decisionContractVersion()`, `policy.policyId()`, `policy.policyVersion()`);
reflection is used because `forge-routing/1` has none and one uniform read is simpler than four mechanisms.

**Runtime values everywhere else.** The adapter sets `TaskFeatures.schemaVersion` explicitly from its literal and never
relies on the builder default, which is the core's own value and would make the schema backstop below unreachable. The
cold state is built fresh for every request as
`RoutingPreference.empty(request, policy.definition(), policy.policyId(), policy.policyVersion())`, all runtime values
from the policy instance, never literals and never cached across requests.

**Backstops, at their true strength.** The core's own runtime checks remain the fail closed backstop, with these limits:

| Version | Backstop | Phase | Condition |
| --- | --- | --- | --- |
| `forge-routing` | the `RoutingRequest` constructor throws `IllegalArgumentException` ("unsupported contractVersion: X"; a null throws `NullPointerException`) | every phase | none |
| `task-features` | `decide` returns `Abstain(STATE_INCOMPATIBLE)` with `FEATURE_SCHEMA_MISMATCH` when `request.features().schemaVersion()` differs from `policy.definition().cohortMapping().featureSchemaVersion()` (`RoutingPreference.mismatches`) | every phase | only if the adapter set `schemaVersion` explicitly and the definition's `CohortMapping` is core provided (see Phases 2 to 4 below) |
| `routing-decision` | the `TrustedRoutingProfile` canonical constructor throws `IllegalArgumentException` ("unsupported routing decision contract X") | Phase 3 and later | only when the adapter passes its own literal; `TrustedRoutingProfile.lexicographic(...)` passes the core's constant and never rejects; at Phase 2 (Level A, no profile) the probe is the only detector, and the mismatch still matters because the adapter interprets the decision variants (a new sealed variant would break an exhaustive switch) |
| `route-lex` | none on the paths this document prescribes (`producedByPolicyId` and `producedByPolicyVersion` are not checked on collection; a profile built through the canonical constructor with the adapter's `route-lex` / `1` literals is rejected only when the verifier reports another identity) | n/a | the probe is the only detector |

So a skipped probe can never yield wrong advice for `forge-routing`, nor for `task-features` when `schemaVersion` is set
explicitly over a core provided `CohortMapping`; for `routing-decision` at Phase 2 and for `route-lex` the probe is the
sole guard.

**Phases 2 to 4.** Once the probe has passed, and provided `empty(...)` received `policy.definition()`, the features
carry the probed literal, and that definition is `RoutingStateDefinition.reference()` or another definition over a core
provided `CohortMapping`, the empty preference is built from the request (scope, feature schema and evaluation policy
copied from it, mapping version from the definition, `processedCutoff` 0, never above the non negative request `cutoff`),
so `decide` cannot report a state mismatch in Phases 2 to 4. The third condition matters because `CohortMapping` is a
public type a host may implement and `mismatches` compares the request schema with `definition.cohortMapping().featureSchemaVersion()`:
a host written mapping that returns `TaskFeatures.SCHEMA_VERSION` from adapter code returns the inlined adapter literal,
which makes the `task-features` backstop dead, and a host mapping that returns any other value makes
`FEATURE_SCHEMA_MISMATCH` appear in Phases 2 to 4. Through Phase 4 every host built `LexicographicRoutingPolicy`
therefore uses `RoutingStateDefinition.reference()`. `Abstain(STATE_INCOMPATIBLE)` with `PROCESSED_CUTOFF_NEWER`
from a Store built snapshot appears only at Phase 5, and is an ordinary `ADVISED` result (Section 10.8).

### 5.3 State transitions

Host side; Neuron has none. The rows are evaluated top to bottom, so `NOT_READY` takes precedence over `UNAVAILABLE`
and `UNAVAILABLE` over a call. The version probe runs once at adapter start and its result is cached; its row sits where
the host would otherwise build the request, but the value it reports was decided at start.

```text
draft INCOMPLETE or REVIEWABLE                      -> NOT_READY(missing: WORKSPACE | SPECIFICATION | SCOPE)
draft READY, no stage, ids or policy                -> NOT_READY(missing: stage | ids | policy)
prerequisites met, integration switched off         -> UNAVAILABLE(integration-off)
prerequisites met, catalog not configured or empty  -> UNAVAILABLE(no-catalog)
prerequisites met, runtime version probe fails      -> UNAVAILABLE(version-mismatch)  (a probed field differs, or the read fails)
prerequisites met, person withdrew before the call  -> UNAVAILABLE(cancelled)  (any built request is dropped)
prerequisites met, decide called                    -> ADVISED(Selected | Abstain | NoEligibleRoute)
decide returned, person withdrew before it was shown -> decision discarded unseen; not STALE, no outcome (example 10.9)
ADVISED, then any draft change before use           -> STALE  (re request: next requestOrdinal, new attemptId)
ADVISED, then host execution (Phase 3 and later)    -> local outcome entry, PENDING until judged; never fed back before Phase 5
```

### 5.4 Feature readiness diagnostics

Before it builds a `RoutingRequest`, a host may ask Neuron which `TaskFeatures` fields it supplied, so a preview can say
"category unknown" instead of silently showing weaker advice. The call is
`new TaskFeatureReadiness().assess(features, expectedStageKind, required)` in `monada.neuron.routing.features`
(issue #95, [ADR 0037](../adr/0037-feature-readiness-diagnostics.md)). It is pure: it reads only its three arguments,
does no I/O and keeps no state.

Diagnostics are information only: they are never eligibility, readiness, authentication or authorization, and no routing decision reads them.

- **Inputs.** `features` is the snapshot the host is about to send; `expectedStageKind` is the stage kind token of the
  request it will build (an opaque token, compared by `String.equals`); `required` is the host's own set of dimensions
  that its preview needs, possibly empty. `required` is a display preference, not a policy Neuron enforces.
- **Result.** A sealed `FeatureReadinessAssessment`: `Assessed` or `SchemaMismatch(expected, actual)`. Null arguments and
  an invalid token throw; a different schema is returned, never thrown.
- **Rows.** `Assessed.diagnostics()` returns exactly one `FieldDiagnostic` per `FeatureDimension`, eight in declaration
  order. A row holds the dimension, a `FieldStatus` (`KNOWN`, `UNKNOWN`, or `MISMATCH`, which only `STAGE_KIND` can
  have, when the known stage kind differs from `expectedStageKind`), whether the dimension was required, and a severity.
  Rows carry no feature values; the host reads those from its own `TaskFeatures`. Rows are derived from the inputs on
  every call and never stored.
- **Severity table** (fixed in Neuron, not configurable; declaration order is not a ranking):

| Status | Required | Severity |
| --- | --- | --- |
| `KNOWN` | either | `INFORMATION` |
| `UNKNOWN` | no | `INFORMATION` |
| `UNKNOWN` | yes | `MISSING_FOR_HOST_PREVIEW` |
| `MISMATCH` | either | `INCONSISTENT_WITH_REQUEST` |

- **Not a readiness outcome.** The host may use `missingForHostPreview()` to fill its own `NOT_READY(missing…)` list and
  `inconsistentWithRequest()` to explain why it did not build a request (a mismatched stage kind would make the
  `RoutingRequest` constructor throw). `NOT_READY`, `UNAVAILABLE`, `ADVISED` and `STALE` stay host vocabulary (Section
  5.1). A report with no missing and no inconsistent rows is not `READY` and is not permission (Section 2.2).
- **Schema mismatch carries no stage diagnostic.** A `SchemaMismatch` has no field rows, so a host that gets one learns
  nothing about the stage kind or any other field. It treats the result like the Section 5.2 version mismatch backstop.
- **Independent of advice.** `decide`, `RouteEligibilityFilter`, `RoutingPreference` and ranking never read an assessment,
  and an assessment never changes what `decide` returns. Unknown values stay unknown: nothing is defaulted or inferred.

## 6. Staleness, Re Check and Re Request

### 6.1 The staleness rule

Advice is computed from a snapshot of host inputs. The host, not Neuron, owns the check that the snapshot still holds. The
rule is placed in the **host guard**, the code that runs immediately before advice is shown as actionable or acted on after
the host's own authorization. It is never placed inside Neuron validation, and the `#102` fixture (`proposed, owned by issue #102`) enforces it
in code.

Before using advice the host checks all of the following:

1. The draft is still `READY` (confirmation has not been withdrawn; Forge security invariant 3).
2. The host draft epoch equals the epoch captured when the request was built.
3. The host's current, freshly recomputed `sourceFingerprint`, `contextFingerprint` and `constraintsFingerprint` equal the
   values captured in the request, by string equality.
4. The current catalog snapshot's `catalogVersion` equals the decision's `catalogVersion`. The decision was computed over
   that snapshot, and v1 Section 2 requires a new `catalogVersion` for any snapshot change: a route added, removed or re
   versioned, an availability change, a `fallbackPriority` change or a changed estimate. Any of them makes the advice
   stale. Check 5 is the separate revalidation against live state immediately before executing; a snapshot comparison
   never replaces it, and at Phase 2 there is no check 5 because nothing executes.
5. v1 Section 3.1 revalidation of authorization, availability and quota (Phase 3 and later, before executing).

Any failure of checks 1 to 4 makes the advice `STALE`.

The epoch is not folded into the fingerprints. Forge's epoch bumps when the workspace is replaced or the draft is cleared,
not when the specification or the scope is edited; the `constraintsFingerprint` catches those edits. Keeping the epoch
outside the fingerprints also keeps v1's directly comparable pairs intact across epochs.

### 6.2 Re request after `STALE`

Stale advice is discarded and re requested:

- keep `taskId` and `executionId`;
- mint a new `attemptId`;
- take the next `requestOrdinal` for the scope;
- rebuild `TaskFeatures` and the fingerprints from the current draft.

The old decision (the receipt, until `proposed, owned by issue #97` exists) remains history. It may never receive a
disposition (`proposed, owned by issue #98`) and may never receive credit. Nothing in Neuron is told about the staleness,
because Neuron holds no state between calls before Phase 5 and, at Phase 5, the Store ledger simply never sees an outcome
for the stale attempt.

### 6.3 Cross scope metadata

A request carries one `scopeId`. A host must never reuse a `scopeId` across workspaces, and must never build a request from
a draft whose workspace differs from the one the `scopeId` was minted for. The v1 admission rules then keep state and
observations from crossing scopes (`SCOPE_MISMATCH`). The host guard treats a workspace replacement as an epoch change,
which makes any outstanding advice `STALE`.

## 7. Executed Route Evidence, Correction, Replay and Checkpoints

### 7.1 Before Phase 5: local outcome entries (conditional on Forge Phase 3)

From Phase 3, when execution exists, the host keeps a **local outcome entry** per executed decision, keyed by
`DecisionRef` (scope, task, execution, attempt, stage, ordinal). It holds at least:

- the executed `RouteKey`;
- the three fingerprints as captured in the request;
- the `InvocationStatus` of the worker invocation;
- `OutcomeStatus.PENDING` until the host judges the result, then the judged status;
- no ledger sequence (there is no ledger until Store export).

The entry is host storage in a host format. It is shaped after `RoutingOutcome` so that a Phase 5 export can be built
from it, but it is not a `RoutingOutcome` and nothing in Neuron reads it. Advice earns **zero credit** until Phase 5.

### 7.2 Phase 4: judged results and corrections (conditional on Forge Phase 4)

The host updates local entries with judged results and corrections. A correction replaces an earlier judgment in host
storage; there is no ledger to supersede yet. Receipts (`proposed, owned by issue #97`) and dispositions
(`proposed, owned by issue #98`) correlate with these entries by `DecisionRef`; they add no credit.

### 7.3 Phase 5: Store export, feedback, state and checkpoints (conditional on Forge Phase 5)

At the Phase 5 gate the lifecycle of v1 Section 5 applies unchanged (decide, execute, judge, export, derive feedback, apply,
later decide):

- Evidence reaches Neuron only through the Store export and the existing translation
  (`StoreExperienceTranslator`, issue #64) into `RoutingObservation`, and `RoutingFeedbackPolicy.derive` (issue #65,
  [ADR 0029](../adr/0029-routing-feedback-credit-assignment.md)).
- The host holds one `RoutingStateStore` per `StatePartition` (`scopeId`, `evaluationPolicyId`,
  `evaluationPolicyVersion`), as v1 Section 24.2 describes ([ADR 0030](../adr/0030-caller-owned-adaptive-routing-state.md)).
- A correction that supersedes an applied reward or penalty is handled by reset and ordered replay, from scratch or from a
  `RoutingStateCheckpoint` with its `LedgerDigest` (issue #89,
  [ADR 0031](../adr/0031-routing-state-checkpoint-and-ledger-compaction.md)).
- Checkpoint bytes and their persistence are the host's or the Store's; the codec and the recovery preflight are
  `proposed, owned by issues #100, #101`.

| Phase | Outcome evidence | Correction | Replay | Checkpoint |
| --- | --- | --- | --- | --- |
| 2 | none (nothing executes) | none | none | none |
| 3 | local entry, `PENDING` | none | none | none |
| 4 | local entry, judged | host storage replace | none | none |
| 5 | Store ledger via export | superseding revision, `supersedesSequence` | `rebuildFrom` as of `cutoff` | host or Store persisted; codec `proposed, owned by issue #100` |

## 8. Dependencies and Core Only Mode

### 8.1 Core only mode (Phases 2 to 4)

| Artifact | Coordinates | JPMS name | Runtime dependencies |
| --- | --- | --- | --- |
| Core | `monada.neuron:monada-neuron:0.1.0-SNAPSHOT` | `monada.neuron` (automatic module) | none |

- No incubator flag: as an automatic module the core declares no `requires`, and the Vector API loader falls back to scalar.
- No Store, no evaluation module, no provider dependency.
- Consumed as a **locally built artifact**: nothing is published remotely (v1 Section 24.1, [ADR 0032](../adr/0032-published-module-names-and-adapter-publication.md)).
  The consumer builds under [`consumer-fixture/`](../../consumer-fixture/) show the classpath and module path forms.

### 8.2 Phase 5 additions

| Artifact | Coordinates | JPMS name |
| --- | --- | --- |
| Adapter | `monada.neuron:monada-neuron-resonance-adapter:0.1.0-SNAPSHOT` | `monada.neuron.resonance.adapter` (automatic module) |
| Resonance Store | pinned at `0.1.0-SNAPSHOT` | per the Store |

The adapter and the Store appear only at the Phase 5 gate. Phases 2 to 4 compile against the core alone.

### 8.3 Host adapter module (a host recommendation)

As a host recommendation, not a Neuron requirement: keep a separate host adapter module that `requires monada.neuron`, so
that Forge core (`io.github.stevdrey.monadaforge.core` at the pinned commit) stays Neuron free. The adapter module owns the
transformation of Section 4, the readiness outcomes of Section 5 and the host guard of Section 6. Forge core continues to
export only its `task` and `workspace` packages (its [module-info.java](https://github.com/stevdrey/monada-forge/blob/56c35e05bb94d4d97e49b59c138fbaed66036ecd/core/src/main/java/module-info.java) at the pinned commit), does not use JavaFX, and never depends on Neuron, the Store, an LLM or a provider.

## 9. Bounds and Proposals

### 9.1 Bounds already guaranteed by v1 (Section 4, with the tag and estimate bounds of Sections 15 and 16)

This contract introduces no numeric limit. The bounds a host meets at every gate are v1's (Section 4; the tags per list
feature bound is v1 Section 15, `TaskFeatures.MAX_TAGS`, and the estimates per route bound is v1 Section 16,
`RouteCatalog.MAX_ESTIMATES_PER_ROUTE`):

| Bound | Value |
| --- | --- |
| opaque token length (ids, stage kinds, fingerprints, version strings; `RouteVersion` is a positive `long`, not a token) | 1 to 128 code points |
| resource estimates per route; estimates per route and dimension | 4; 1 |
| routes per catalog | 32 |
| capability, tool, stage and execution mode tokens per route, each set | 16 |
| hard requirement tokens per request, each set | 16 |
| typed features per `TaskFeatures`; tags per list feature | 32; 8 |
| ranked candidates per decision | 8 |
| exclusion reasons | one primary per excluded route, up to 8 additional per route |
| feedback entries per `RoutingFeedback` | 64 |
| preference cohorts per state | 256 (`CAPACITY_EXHAUSTED`, never evicted silently) |
| applied revision entries per state | 4,096 |
| observations replayed per rebuild call | 4,096 (export page size 1 to 500) |

### 9.2 Proposals owned by later issues

New limits are proposals only, each owned by its issue, and none is part of `forge-integration-readiness/1`:

- maximum encoded receipt size and the receipt version rule, `proposed, owned by issue #97`;
- disposition shape and its fail closed combinations, `proposed, owned by issue #98`;
- comparison delta bounds, `proposed, owned by issue #99`;
- checkpoint byte format `routing-checkpoint/1`, oversize and corrupt input rules, `proposed, owned by issue #100`;
- recovery preflight blockers and precedence, `proposed, owned by issue #101`;
- host fixture bounds, `proposed, owned by issue #102`;
- evaluation suite fixture sizes, `proposed, owned by issue #103`.

## 10. Examples

Every example uses the Phase 2 defaults of Section 3.1 unless it says otherwise, and ends with three explicit lines: what was
advice, what was execution, what was acceptance.

### 10.1 Selected advice (Phase 2 preview, conditional on Forge Phase 2)

A `READY` draft; a human picked `stageKind = "implement"` and `category = Unknown`; `languages = Known(["java"])`;
`tests = Unknown`; the static preview catalog holds two routes, `local-small` (tier 1, `fallbackPriority` 1) and
`api-large` (tier 2, `fallbackPriority` 2), both `AVAILABLE`, both declaring `stages = ["plan", "implement", "review"]`
(the host's whole stage pick list), with `locality = Known("local")` and `Known("remote")` respectively; the preview
`HardRequirements` constant permits execution mode `"review"` for both and allows localities `["local", "remote"]`. `RoutingPreference.empty(...)`, `cutoff = 0`, `overflowPermitted = false`.

`decide` returns `Selected(route = local-small/1, executionMode = "review", basis = HOST_PRIORITY, overflowUsed = false,
candidates = [local-small/1, api-large/1])`. The host shows it beside the plan, labelled "unverified preview, recheck before
execution".

- **Advice**: `Selected(local-small/1)`, cold state, tier and fallback priority ordering.
- **Execution**: none. Phase 2 executes nothing.
- **Acceptance**: none. No outcome exists.

### 10.2 Abstention (Phase 3 and later, conditional)

If Forge reaches Phase 3: the same draft and catalog as 10.1, but the host's execution policy now configures a tier cap on
the same `route-lex/1` ordering (`maxAutoSelectTier = 1`, a `LexicographicRoutingPolicy` built with that parameter instead
of `reference()`), and the per request `HardRequirements` require capability `"long-context"`, which only `api-large`
(tier 2) declares. The only eligible route sits above the cap.

`decide` returns `Abstain(reason = POLICY_TRADEOFF_UNRESOLVED, eligible = [api-large/1])`. This abstention needs a
configured `maxAutoSelectTier` in every phase; `LexicographicRoutingPolicy.reference()` has no cap, so the Phase 2 preview
of Section 3.1 never produces it.

- **Advice**: `Abstain(POLICY_TRADEOFF_UNRESOLVED)`; the eligible list is information for a human, not a selection.
- **Execution**: none.
- **Acceptance**: none.

### 10.3 Stale scope (Phase 2 and later, conditional)

Advice `Selected(local-small/1)` was computed for `attemptId = "a-1"`, `requestOrdinal = 7`, with
`constraintsFingerprint = "c-9f3"` and draft epoch 4 captured. The person then narrows the `TaskScope` to one directory.
Forge withdraws the confirmation (the draft is `REVIEWABLE` again) and the host's `constraintsFingerprint` becomes `"c-a17"`.

The host guard runs before the advice is shown as actionable: check 1 fails (not `READY`) and check 3 fails (fingerprint
differs). The outcome is `STALE`. After the person confirms again, the host re requests with `attemptId = "a-2"`,
`requestOrdinal = 8`, the same `taskId` and `executionId`, and the current fingerprints. The decision for `a-1` stays in
history and may never receive a disposition or credit.

- **Advice**: the `a-1` decision was advice and is now stale history; the `a-2` decision is the live advice.
- **Execution**: none in either attempt.
- **Acceptance**: none.

### 10.4 Unknown feature (Phase 2 and later, conditional)

The context collection did not run, so `languages`, `domains`, `changeSize` and `contextSize` are all `Unknown`; the human
did not toggle `tests` or `security`, so both are `Unknown`; `category` is `Unknown`. The host does not substitute defaults.

`decide` still returns `Selected(local-small/1, basis = HOST_PRIORITY)`: eligibility depends only on `HardRequirements`
and the catalog, and the cold state has no learned preference to consult. Unknown features change the encoding, not the
eligibility.

- **Advice**: `Selected(local-small/1)` on configuration alone.
- **Execution**: none.
- **Acceptance**: none.

### 10.5 Missing catalog (Phase 2 and later, conditional)

The integration is on and the draft is `READY`, but the host has no catalog (the static preview catalog is not configured,
or it is empty). The host reports `UNAVAILABLE(no-catalog)` and does not call `decide`. This is distinct from
`ADVISED(NoEligibleRoute)`, which Neuron returns when a non empty catalog has no route that meets the hard requirements.

- **Advice**: none; Neuron was not called.
- **Execution**: none.
- **Acceptance**: none.

### 10.6 Delayed outcome (Phase 3 and later, conditional)

If Forge reaches Phase 3: advice `Selected(api-large/1)` for `DecisionRef(scope-w1, task-3, exec-1, a-1, stage-2, 12)` is
verified through `RoutingReasoningStage` with `TrustedRoutingProfile.lexicographic(parameters, definition)` built from the
host's own configuration. The host guard passes, the
person authorizes the run as a separate action, and the worker runs with `InvocationStatus.COMPLETED`. The host writes a
local outcome entry: `executedRoute = api-large/1`, the three fingerprints, `COMPLETED`, `OutcomeStatus.PENDING`.

Two days later the host judges the result `ACCEPTED`. The entry is updated. No `RoutingFeedback` exists, no
`RoutingPreference` changes, and the next `decide` for this scope still receives `RoutingPreference.empty(...)`. Only at
Phase 5, after Store export and translation, could this entry become an observation and earn credit.

- **Advice**: `Selected(api-large/1)`, verified.
- **Execution**: the worker invocation, `COMPLETED`, under a separate explicit host authorization that the advice never
  granted and Neuron never inferred.
- **Acceptance**: `ACCEPTED` two days later, held locally; zero credit before Phase 5.

### 10.7 Version mismatch (Phase 2 and later, conditional)

The adapter expects `forge-routing/1`, `task-features/1`, `routing-decision/1` and `route-lex` / `1`, but the core jar on
the classpath is a hypothetical future core whose loaded `TaskFeatures.SCHEMA_VERSION` field holds `"task-features/2"`.
At adapter start the runtime version probe (Section 5.2) reads the five fields through reflection; four match and
`TaskFeatures.SCHEMA_VERSION` differs. The host reports `UNAVAILABLE(version-mismatch)` and logs
`TaskFeatures.SCHEMA_VERSION expected task-features/1, found task-features/2`. No `TaskFeatures`, no `RoutingRequest` and
no call.

Had the probe been skipped, the adapter would have built `TaskFeatures` with `schemaVersion = "task-features/1"` set
explicitly, the request would have been accepted, and `decide` would have returned `Abstain(STATE_INCOMPATIBLE)` with
`FEATURE_SCHEMA_MISMATCH`, because `RoutingPreference.mismatches` compares the request's schema string with the loaded
definition's `task-features/2`. That backstop fires only because `schemaVersion` was set explicitly; with the builder
default the core's own `task-features/2` would have been stamped and no mismatch would ever appear.

- **Advice**: none.
- **Execution**: none.
- **Acceptance**: none.

### 10.8 State mismatch at Phase 5 only (conditional)

If Forge reaches Phase 5: the host hands `decide` a `RoutingPreference` rebuilt from a Store export with
`processedCutoff = 58`, but the request carries `cutoff = 50` (a historical replay). `decide` returns
`Abstain(STATE_INCOMPATIBLE)` with `PROCESSED_CUTOFF_NEWER` as the primary mismatch. The readiness outcome is
`ADVISED(Abstain)`, not `UNAVAILABLE`: Neuron was called and answered. The host rebuilds the state as of cutoff 50 and
re requests with the next ordinal.

- **Advice**: `Abstain(STATE_INCOMPATIBLE)`.
- **Execution**: none.
- **Acceptance**: none.

### 10.9 Cancellation (Phase 2 and later, conditional)

The draft is `READY`, the catalog is configured, and the host is about to build the request when the person closes the plan
view. The host reports `UNAVAILABLE(cancelled)` and does not call `decide`. Had the call already returned, the host would
discard the decision, because `decide` is synchronous and cannot be interrupted; the discarded decision is not `STALE`
(no input changed) and is simply never shown.

- **Advice**: none, or discarded unseen.
- **Execution**: none.
- **Acceptance**: none.

## 11. Compatibility, Versioning and Migration

### 11.1 Reuse

This contract reuses the work of issues #59 to #68 and #89 by type name and v1 section, and proposes no replacement:

| Issue | Reused as | v1 section |
| --- | --- | --- |
| #59 | the contract, ownership table, identity and bounds | 1, 2, 4 |
| #60 | `TaskFeatures`, `Feature.Known` / `Feature.Unknown`, `Requirement`, the signal encoder | 15 |
| #61 | `RouteCatalog`, `CatalogEntry`, `RouteDescriptor`, `HardRequirements`, fail closed eligibility | 16 |
| #62 | `RoutingPolicy.decide`, `LexicographicRoutingPolicy`, `RoutingDecision` and its three variants, `RoutingReasoningStage`, `RoutingPreference` (including the cold start form `empty(...)`), `RoutingStateDefinition` | 17 |
| #76, #78 | validator and verifier split, `TrustedRoutingProfile` | 17.5, 18 |
| #63 | `RoutingOutcome`, `RoutingObservation`, `InvocationStatus`, `OutcomeStatus` | 19 |
| #64 | `StoreExperienceTranslator` (adapter) | 20 |
| #65 | `RoutingFeedbackPolicy.derive`, credit assignment | 21 |
| #66 | `RoutingStateStore` and non empty `RoutingPreference` snapshots published from it | 7, 22 |
| #67 | the offline replay evaluation ([forge-routing-evaluation.md](../benchmarks/forge-routing-evaluation.md)) | evaluation module only |
| #68 | published coordinates, module names, embedded host loop | 24 |
| #89 | `RoutingStateCheckpoint`, `LedgerDigest`, compaction, `rebuildFrom` | 23 |

### 11.2 Forward references

Every reference to a later issue in this document is marked `proposed, owned by issue #N` and is not part of
`forge-integration-readiness/1` until it is marked `implemented by issue #N`. The numbers are Neuron issues in `stevdrey/monada-neuron`; the Resonance Store has issues
with the same numbers (v1 Section 1 cites Store issues 99 and 100), which are never meant here:

- `implemented by issue #95, Section 5.4`: feature readiness diagnostics over `TaskFeatures` before advice is shown.
- `proposed, owned by issue #96`: human review summaries of a `RoutingDecision`.
- `proposed, owned by issue #97`: versioned advice receipts.
- `proposed, owned by issue #98`: host disposition of advice.
- `proposed, owned by issue #99`: scenario comparison preview.
- `proposed, owned by issue #100`: checkpoint codec.
- `proposed, owned by issue #101`: checkpoint recovery diagnostics.
- `proposed, owned by issue #102`: the Forge like external host fixture that enforces the host guard in code.
- `proposed, owned by issue #103`: the host scenario evaluation suite.

When receipts (`proposed, owned by issue #97`) and dispositions (`proposed, owned by issue #98`) land, their names join
the never permission list of Section 2.2 and this contract takes a minor bump.

### 11.3 Versioning and migration rule

- The contract identifier is `forge-integration-readiness/1`. It is independent of `forge-routing/1`: a change here never
  bumps the routing contract, and a routing contract change needs a readiness review here.
- The identifier is carried by the host, not by Neuron: the adapter declares the `forge-integration-readiness` major
  version it was written against as an adapter constant, and the host's configuration names the major version it is
  deployed for. An **unknown major version** of `forge-integration-readiness` in that configuration makes the host report
  `UNAVAILABLE(version-mismatch)` at configuration time, before any Neuron object is built. It is never reinterpreted. No
  Neuron value carries this identifier, so this rule is checked by the host alone.
- **Minor versions are additive**: a new row, a new example, a new forward reference, a new gate note.
- **Any change to a field table classification** (Section 4.2, the trusted configuration, untrusted observation or caller
  owned state column) is a **major bump**, because it changes what may feed eligibility.
- **No new numeric limit** is introduced by this contract; v1 Section 4 bounds are cited (Section 9.1) and later limits are
  proposals owned by their issue (Section 9.2).
- The Forge baseline pin (`56c35e05`) is refreshed by a minor bump when Forge Phase 2 ships, with the link check rerun.

## 12. Verification and Documentation Updates

### 12.1 Verification for this document

- `./gradlew test` passes; the issue #94 revision touched no file under `src/`.
- Every relative link in this document resolves in this repository; every Forge link resolves at commit
  `56c35e05bb94d4d97e49b59c138fbaed66036ecd`.
- No sentence states or implies that a Forge phase beyond 1 is implemented.
- In the issue #94 revision, every mention of issues #95 to #103 carried `proposed, owned by issue`; issues #96 to #103
  still carry `proposed`, and #95 is `implemented by issue #95, Section 5.4`.
- The issue #94 revision introduced no new type, value or numeric limit; the issue #95 revision adds the Section 5.4
  types in Neuron, without changing any field table classification or adding a numeric limit.

### 12.2 Documentation updates

- `README.md` and `docs/architecture.md` link to this document from their Forge routing sections.
- [ADR 0033](../adr/0033-phase-gated-host-adoption-boundary.md) records the durable decisions: the metadata only boundary,
  host owned staleness, cold state before the Store, and the readiness contract versioning.
- Forge side prerequisites (opaque workspace id, public draft epoch accessor, three fingerprints, stage and category
  pickers, tests and security toggles, static preview catalog with its preview `HardRequirements` constant, a Neuron free
  core module) belong to issues in `stevdrey/monada-forge`, not here.
