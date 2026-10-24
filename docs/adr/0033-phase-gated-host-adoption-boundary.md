# ADR 0033: Phase Gated Host Adoption Boundary

## Status

Accepted (documentation only; issue #94; composes [ADR 0026](0026-forge-routing-ownership.md) without amending it)

## Context

The routing contract v1 ([forge-routing-contract-v1.md](../specs/forge-routing-contract-v1.md), issues #59 to #68 and
#89) defines the types, the ownership table, fail closed versioning and the rule that a decision is advice. It does not say
how a host that holds only a reviewed task draft gets from there to a well formed `RoutingRequest`, which of its values are
trustworthy, what "ready" means before a catalog or a Store exists, or how stale host metadata is handled.

Monada Forge, the first intended host, has Phases 0 and 1 only at the pinned commit `56c35e05` (2026-10-21): an in memory
`TaskDraft` with free text, a validated workspace path and a `TaskScope`, no ids, no stages, no workers and no persistence.
Its roadmap places optional Neuron and Resonance Store integration in its Phase 5, and its security invariants say task text
is untrusted data, confirmation is withdrawn by any later edit, and an unset scope permits nothing.

The proposed issues #95 to #103 (readiness diagnostics, review summaries, receipts, dispositions, comparison, checkpoint
codec, recovery diagnostics, the external host fixture and the evaluation suite; each is `proposed, owned by issue #N` in
the document and none is part of this decision) each need the same host assumptions. Without one place
that states them, each would invent its own. `AGENTS.md` asks for an ADR when a change establishes a durable decision on an
interoperability contract.

## Decision

Adopt [Forge Integration Readiness Contract v1](../specs/forge-integration-readiness-v1.md), contract identifier
`forge-integration-readiness/1`, as the normative statement of how a host adopts Neuron routing advice one phase at a time.
The durable decisions it fixes:

- **Metadata only boundary.** Host code, in a host owned adapter module, turns reviewed task metadata into the existing
  `TaskFeatures`, `HardRequirements`, `RoutingRequest`, `RouteCatalog` and `RoutingPreference`. Task text, paths, scope
  entries, file contents and model output never enter Neuron, in any form. Every host supplied value is classified exactly
  once as trusted configuration, untrusted observation or caller owned state; only trusted configuration may feed
  eligibility; observations shape encoding and learned preference only; `requiredContextSize` is never copied from the
  `contextSize` observation. Every `Unknown` stays `Unknown`.
- **Readiness is host vocabulary.** `NOT_READY(missing…)`, `UNAVAILABLE(reason)`, `ADVISED(decision)` and `STALE` are host
  states, not Neuron types. `UNAVAILABLE(version-mismatch)` comes from a version probe the adapter runs once at start: it
  reads the five version constants (`RoutingRequest.CONTRACT_VERSION`, `TaskFeatures.SCHEMA_VERSION`,
  `RoutingDecision.CONTRACT_VERSION`, `LexicographicRoutingPolicy.POLICY_ID` and `POLICY_VERSION`) reflectively from the
  loaded core classes, because they are compile time constants that the Java compiler inlines into every use site, and a
  literal comparison in the adapter would never fire. Any difference or reflective failure is `UNAVAILABLE(version-mismatch)`.
  The core's own runtime rejections remain as the fail closed backstop at their true strength (constructor rejection of a
  wrong `contractVersion` in every phase, `Abstain(STATE_INCOMPATIBLE)` on a wrong explicitly set `schemaVersion`,
  canonical constructor rejection of a wrong decision contract from Phase 3, none for `route-lex`). `cancelled` is detected
  by the host at any point before the `decide` call; `decide` itself stays synchronous and pure with no cancellation hook.
  The three v1 decision variants are unchanged inside `ADVISED`.
- **Host owned staleness.** The host guard, not Neuron validation, checks before using advice that the draft is still
  `READY`, that the draft epoch and the three fingerprints equal those captured in the request, that the current catalog
  snapshot's `catalogVersion` equals the decision's, and that v1's revalidation of authorization, availability and quota
  holds before executing. Stale advice is discarded and re requested with the next
  `requestOrdinal` and a new `attemptId`, keeping `taskId` and `executionId`; the old decision is history and never receives
  a disposition or credit. The epoch stays outside the fingerprints so v1's comparable pairs survive across epochs.
- **Cold state before the Store.** The first useful slice is a Phase 2 advisory preview: one pure `decide` on
  `LexicographicRoutingPolicy.reference()` with `RoutingPreference.empty(...)`, `cutoff` 0, no execution, labelled
  unverified. Verified advice through `RoutingReasoningStage` with a host selected `TrustedRoutingProfile` is required from
  Phase 3, when the host may act on advice after its own, separate authorization. Executed route evidence is kept by the host in local outcome entries keyed by
  `DecisionRef` from Phase 3 and reaches Neuron only through the Phase 5 Store export and the existing #64 and #65
  translation. Advice earns zero credit until then. Once the version probe has passed, `empty(...)` received
  `policy.definition()`, and that definition is `RoutingStateDefinition.reference()` or another definition over a core
  provided `CohortMapping`, `Abstain(STATE_INCOMPATIBLE)` can appear only at Phase 5, when a Store loaded snapshot can
  carry a different feature schema. The schema mismatch backstop (`FEATURE_SCHEMA_MISMATCH`) exists only when the adapter
  sets `schemaVersion` explicitly; with the builder default the core stamps its own value and no mismatch can appear.
- **Phase gates in the conditional.** Each gate names the Forge roadmap exit evidence it waits for and the Neuron inputs that
  become available at it. No gate claims a Forge phase beyond 1 exists. Forge references are pinned to a commit.
- **Readiness contract versioning.** `forge-integration-readiness/1` has its own identifier, independent of
  `forge-routing/1`. An unknown major version makes the host report `UNAVAILABLE(version-mismatch)`; minor versions are
  additive; any change to a field table classification is a major bump; no numeric limit is introduced, v1 Section 4 bounds
  are cited, and later limits are proposals owned by their issue.
- **Core only mode.** Phases 2 to 4 compile against `monada.neuron:monada-neuron:0.1.0-SNAPSHOT` (automatic module
  `monada.neuron`, no runtime dependencies, no incubator flag, consumed as a locally built artifact). The adapter and the
  Store appear only at the Phase 5 gate. A separate host adapter module that `requires monada.neuron` is recommended to the
  host so its core stays Neuron free; it is not a Neuron requirement.

The document introduces no type, no value of an existing type, no numeric limit and no new version of `forge-routing/1`.
Every reference to issues #95 to #103 is marked `proposed, owned by issue #N`.

## Alternatives

### Append the adoption rules to the v1 routing contract

Rejected. The issue asks for a separate file, v1 is already over two thousand lines, and adoption wording would inherit
`forge-routing/1` versioning, so a reworded gate could force a routing contract bump the issue forbids.

### One document per phase gate

Rejected. The ladder is the deliverable; splitting it hides the order and scatters the field classifications.

### Amend ADR 0026 instead of a new ADR

Rejected. ADR 0026 fixes the routing contract's ownership and shape; this decision adds a host adoption boundary on top of
it without changing a row. A separate ADR leaves ADR 0026 untouched.

### Staleness checks inside Neuron validation

Rejected. Neuron holds no drafts, epochs or fingerprint sources; it compares fingerprints by equality only. The check can
live only in the host guard without crossing the ownership line.

### Placeholder ids for the preview phase

Rejected. v1 requires real ids, and a later receipt could not then be correlated with the executed attempt.

## Consequences

- The proposed issues #95 to #103 (`proposed, owned by issue #N`) share one set of host assumptions and a stable
  identifier to cite.
- A host can plan an adapter module from the readiness document without reading the full v1 contract.
- A second normative document must be kept consistent with v1; any v1 change needs a readiness review and may bump
  `forge-integration-readiness`.
- The Forge commit pin goes stale on purpose when Forge moves; refreshing it is a manual minor bump with a link check.
- Phases 2 to 4 run cold start by design, so early advice is only tier and fallback priority ordering.
- No production code changes; `./gradlew test` is unaffected.

## Follow-Up

- Open Forge side issues (in `stevdrey/monada-forge`) for the Phase 2 prerequisites the document names: an opaque workspace
  id, a public draft epoch accessor on `TaskDraftService`, the three fingerprints, review pickers for stage and category,
  toggles for tests and security, a static preview catalog with its preview `HardRequirements` constant, and a Neuron free
  core module layout.
- When the proposed #97 (receipts) and #98 (dispositions) land, add their names to the never permission list and bump the minor version.
- Refresh the Forge commit pin when Forge Phase 2 ships and rerun the link check.
