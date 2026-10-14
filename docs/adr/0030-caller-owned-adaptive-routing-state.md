# ADR 0030: Caller-Owned Adaptive Routing State

## Status

Accepted (implemented by issue #66; checkpoints and ledger compaction deferred to #67)

## Context

Issues #60 to #65 provide a pure routing policy that reads an immutable `RoutingPreference` snapshot, and pure feedback
derivation plus reconciliation advice for a caller-owned applied-revision ledger. Contract Section 7 requires the mutable
state behind those snapshots to be caller-owned, bounded, deterministic and updated only through the existing
`AdaptationPolicy`, with a nonzero baseline, a positive amplitude floor, deterministic Node identities, dormant cohorts,
atomic application and staged rebuilds. Until #66 that state existed only as a test prototype
(`LedgerRebuildEquivalenceTest.State`). Contract Section 3.1 also says the initial Node state, `AdaptationConfig` and feedback
scores join the cohort mapping under one composite `mappingVersion`.

## Decision

- Add the package `monada.neuron.routing.adaptation` with a caller-owned, mutable, not thread-safe `RoutingStateStore` for one
  `StatePartition` (scope and evaluation policy) under one `AdaptiveStateDefinition`.
- Model the composite definition as a **new additive record** `AdaptiveStateDefinition(RoutingStateDefinition,
  InitialNodeState, AdaptationConfig, RoutingFeedbackConfig)` identified by the wrapped `mappingVersion`, instead of widening
  `RoutingStateDefinition`, so no existing public signature changes and the routing policy keeps admitting snapshots against
  `RoutingStateDefinition`.
- Reuse `DeterministicBaselineAdaptationPolicy` for every Node update and `RevisionReconciler` / `RebuildOrder` for every
  ledger decision; the store only follows their advice. No second learning engine.
- Report every expected outcome as a value (`BindResult`, `StateUpdate`, `RebuildResult`); throw only for inconsistent caller
  data. Anything other than an application or a record changes nothing, including the watermark.
- Rebuild on a staged store bound to an explicit cohort set and return it; the live store is never modified, the caller
  swaps. The cohort set is how a cohort enters or leaves with its history consistent, so `bind` refuses a key the ledger
  still credits, and a rebuild never accepts an export cutoff below the live watermark.
- Cohorts and ledger are `TreeMap`s in canonical order: point lookups, deterministic iteration, no hash-order dependence;
  `C <= 256` and `L <= 4,096` do not justify a primitive layout.
- Provide `AdaptiveStateDefinition.reference()` (`adaptive-routing-state/1`) with documented, illustrative, untuned values.
- Defer `RoutingStateCheckpoint` and compaction to #67, which verifies checkpoint-versus-scratch equivalence.

## Alternatives

- **Widen `RoutingStateDefinition`** with the new parts and a legacy constructor: rejected, it changes a public record's
  components, `equals` and accessors and touches the verifier and policy contracts for no behavioral gain.
- **A second, routing-specific learner** (bandit, counts-based scorer): rejected by contract Section 7 and ADR 0026; it would
  duplicate `AdaptationPolicy` semantics and add exploration, which is a non-goal.
- **Patch Nodes in place on reset or subtract superseded effects**: rejected, adaptation is clamped and not invertible.
- **Hash maps keyed by cohort and sample**: rejected, iteration order would need a separate sort for every snapshot and
  diagnostics, with no measurable benefit at these bounds.
- **Implement checkpoints now**: deferred; they need a ledger digest format and equivalence evidence that #67 owns.

## Consequences

- Hosts can now learn route preferences from validated outcomes and route from the resulting snapshots, entirely opt-in.
- Equal definitions, partitions and ordered deliveries give equal states and decisions; scopes cannot contaminate each other.
- A full ledger blocks learning in that store until a rebuild into a new scope or the #67 compaction; this is explicit.
- `NodeType.MEMORY` is used for preference Nodes in the reference definition as Neuron-local adaptive state; long-term memory
  remains in the Resonance Store (ADR 0001).

## Follow-Up Work

- #67: `RoutingStateCheckpoint`, ledger digest and compaction, checkpoint-versus-scratch equivalence, replay evaluation and
  latency/retained-memory measurements; revisit the reference values only with that evidence.
- #68: published consumer loop over real Store exports.
