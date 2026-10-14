# ADR 0029: Routing Feedback With Explicit Executed-Route Credit Assignment

## Status

Accepted (implemented by issue #65)

## Context

`OutcomeFeedback` (ADR 0021) derives a uniform reward from an `ActionStatus` and rewards every target it is given. A routing
outcome needs more: acceptance is validated quality evidence, not worker completion (Forge Routing Contract v1, Section 5);
only the route that actually ran may be credited; a mixed-route repair must not credit every participant; unknown or
environmental results must not bias routing; and a corrected sample must be able to supersede a reward that was already applied.
Fabricating an `ActionStatus` for these cases would corrupt the generic semantics, and a node-wide reward would credit routes that
produced nothing. #63 already decides learning eligibility in `OutcomeEvaluator`; #66 will own the mutable state.

## Decision

Add a separate, opt-in package `monada.neuron.routing.feedback` (Contract Section 21):

- `RoutingFeedback`, a typed artifact with an explicit `ScoreRule`, exact sample revision and a stable, hash-free
  `FeedbackIdentity`. `OutcomeFeedback` and the generic feedback path are unchanged; an explicit adapter,
  `toFeedbackInputs()`, produces `FeedbackInput`s only when the semantic invariants hold (bounded, non-zero, sign agrees with the
  disposition).
- `RoutingFeedbackPolicy`, a pure function that maps the `OutcomeEvaluator` assessment, the single owner of the verdict rule, to
  `REINFORCE`, `PENALIZE`, `NEUTRAL` or no update, and targets only the Node the caller bound to the executed route's `CohortKey`
  in the decision's scope and state partition. Bindings carry Node identifiers, never Nodes.
- An optional, reward-only cost refinement that compares one declared usage counter between directly comparable, validated
  executions of different routes and never reads cost for a penalty or a neutral result.
- Caller-owned applied-revision bookkeeping as records plus pure functions: `AppliedRevision`, `RevisionReconciler` and
  `RebuildOrder`. A superseded reward or penalty is never subtracted; the caller resets and replays in the Store's applied order.
  Ledger entries carry everything the contract validates about a claim (policy, ancestry, catalog, cutoff, rule and
  assessment, credited Node, cost peer), a revision that derived nothing (including a provenance mismatch, which a corrected
  pairing of the same revision can replace) is recorded as a marker, an observation of another state partition is not recorded,
  and an application that moves a Node out of applied order requires a rebuild because adaptation is clamped and order
  dependent. `ScoreRule.requireConsistent` is the single owner of the rule/assessment consistency that the feedback and the
  ledger entry both enforce.
- The invariant "incremental state equals a rebuild from the latest known revisions" is the acceptance test of that
  bookkeeping (`LedgerRebuildEquivalenceTest`, seeded delivery sequences), and a cost peer must be credited in the state and
  precede the reward in applied order, so a peer's arrival or correction is always a Node change the ledger sees.

## Alternatives

- **Reuse `OutcomeFeedback` with a synthetic `ActionStatus`:** conflates invocation, acceptance and quality; rejected.
- **Reward every bound target (node-wide uniform reward):** credits routes that did not run; rejected.
- **Subtract the old reward when a revision supersedes it:** Node adaptation saturates and is order dependent, so subtraction is not
  an inverse; rejected in favor of reset and replay.
- **A library-held ledger or history:** a hidden session and unbounded state; rejected, the caller owns it and Neuron only decides.
- **Cost as a blended scalar or a penalty term:** would let cheap failing work look good; rejected.
- **Trust a caller-supplied peer assessment for the cost comparison:** the policy re-assesses the peer from its decision and request.
- **Fix ledger gaps one review comment at a time:** five review rounds each found one more field or ordering rule that entry
  equality missed; the delivery-sequence property test replaced that with one oracle.

## Consequences

- Equal inputs give equal feedback; derivation has no side effects and mutates no Node.
- `IMPORTED_CLAIM` is neutral even with a failing observation, which is more conservative than the evaluator's own verdict.
- A scoring change needs a new `mappingVersion` chosen by the host until #66 folds the config into `RoutingStateDefinition`.
- The invariant assumes the caller derives from its current knowledge: a cost peer left out (not merely out of date) is
  not detectable, and a rebuild must pass the same cost-peer selection rule.
- The modeled ledger footprint (about 400 bytes per entry, 1.6 MiB at 4,096 entries) replaces the contract's earlier 256 KiB estimate.
- No effectiveness claim: this ADR makes none, and #67 must supply a reproducible workload with controls.

## Follow-Up Work

- #66: caller-owned state store, Node baseline and identity, checkpoints and snapshots, and the composite state definition.
- #67: temporal replay evaluation and measurements.
- Cost refinement over hypothetical API cost or actual billing once outcomes carry directly comparable amounts.
