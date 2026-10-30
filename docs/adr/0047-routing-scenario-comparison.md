# ADR 0047: Routing Scenario Comparison Preview

## Status

Accepted (issue #99, and #170 for the stage delegation; adds `monada.neuron.routing.comparison` and the public
`TrustedRoutingAdvice` in `monada.neuron.routing`, and marks the scenario comparison entries of
`forge-integration-readiness/1` as implemented, an additive change under ADR 0033).

## Context

Forge needs to show a person what the routing advice would be if they adjusted an approved catalog, a requirement or a
preference. `RoutingPolicy.decide` is already pure, so a preview can decide twice, but a plain pair of decisions leaves
three questions open:

1. **Which pairs answer the same question.** A pair from two scopes, tasks, stage kinds, feature schemas, evaluation
   policies or cutoffs differs in more than the adjustment, so any change between them must not be read as the effect
   of the adjustment, let alone as an improvement.
2. **Who checks each side.** A preview that trusts each policy's own decision would show advice no trusted stage could
   produce. The mandatory `RoutingDecisionValidator` is package private to `monada.neuron.routing`, so a new package
   cannot reach it.
3. **What the delta may say.** A counterfactual selection establishes neither how a route would perform nor what it
   would spend. The delta must stay a description of advice, never an estimate.

Limits from the issue: at most two decisions, a bounded catalog diff in O(R log R) or better, no cache, no dependency,
no preview API, no mutation, no execution, no feedback and no billing.

## Decision

- **`TrustedRoutingAdvice`** (public, `monada.neuron.routing`) decides once, evaluates eligibility once, runs the
  mandatory validator against the profile's identity and then the profile's verifier, and returns
  `Verified(decision, report)`: the decision unchanged, with the eligibility report it was checked against. It throws
  instead of returning unverified advice. The trusted path of `RoutingReasoningStage` delegates to it, so the
  composition exists once; the legacy, self verified path is unchanged. Every trusted composition checks the policy
  identity through `TrustedRoutingProfile.requireHosts(policy)`.
- **`RoutingScenario(policy, profile, input)`** is one side. A `TrustedRoutingProfile` is required, and its policy
  identity must equal the policy's. There is no legacy, self verified side.
- **`ScenarioComparator.compare(before, after)`** decides each side through `TrustedRoutingAdvice` once. A side that
  fails verification fails the comparison; there is no partial preview.
- **`ScenarioDifference`** lists every differing input, in declaration order. `SCOPE`, `TASK`, `STAGE_KIND`,
  `FEATURE_SCHEMA`, `EVALUATION_POLICY` and `CUTOFF` break comparability. `CATALOG`, `REQUIREMENTS`,
  `OVERFLOW_ADMISSION`, `FEATURES`, `REQUEST_CONTEXT` (the three fingerprints), `REQUEST_IDENTITY` (execution,
  attempt, stage instance, ordinal), `PREFERENCE`, `POLICY_IDENTITY` and `POLICY_PARAMETERS` are reported but
  comparable. Fingerprints do not break comparability: a preview exists to show a changed constraint, and it is not a
  "directly comparable pair" of routing contract v1 Section 6, which is about judged outcomes.
- **`ScenarioComparison`** is sealed: `Compared(before, after, differences, delta)` or
  `Incomparable(before, after, differences)`. Both keep the two decisions unchanged, so abstention reasons, truncation
  and exclusion reasons read as Neuron produced them. Only `Compared` has a delta, and its constructor rejects a
  breaking difference, so an incomparable pair cannot be represented as a change of advice.
- **`ScenarioDelta`** holds a `RouteChange` (`SAME_ROUTE`, `DIFFERENT_ROUTE`, `NEWLY_SELECTED`, `NO_LONGER_SELECTED`,
  `NOT_SELECTED_EITHER`), whether the decisions, the ranking (ranked keys or truncation) and the cohort binding
  differ, and the `EligibilityChange`s: one per route whose `RouteStanding` (`Eligible(eligibleRoute)`,
  `Excluded(exclusion)`, `NotInCatalog`) differs, in canonical route order, at most 64. `Eligible` carries the
  filter's execution mode and overflow flag, so a route that stays eligible but becomes overflow only, or changes
  mode, is a change. Standings come from one merge pass over the two canonical catalog orders, each side's verified
  eligible routes and its decision's canonically ordered exclusions: O(R), no sort, no lookup table, no ranking.
- **`PreviewNotice`**: every comparison lists `HYPOTHETICAL` and `RECHECK_BEFORE_EXECUTION`. No public name in the
  package mentions cost, billing, quality, improvement, permission or feedback, and no source reaches the adaptation,
  feedback, outcome, receipt, disposition or resonance packages (`ComparisonBoundaryTest`).

## Alternatives considered

- **Compare two decisions the host already holds.** Rejected: the host could pair a decision with inputs it was not
  made from, and nothing would verify either side.
- **Put the comparator in `monada.neuron.routing`** to reach the package private validator. Rejected: the issue asks
  for an opt in subpackage, and a small public composition keeps the validator itself private and unchangeable.
- **Accept a legacy, self verified side.** Rejected: the preview would then show advice the policy vouches for itself.
- **Treat any fingerprint change as incomparable.** Rejected: a changed requirement changes the constraints
  fingerprint, so the main use of a preview would always be incomparable.
- **Return a single type with a comparability flag.** Rejected: a flag can be ignored, while a missing delta cannot.

## Consequences

- A host can preview an adjustment with the same trust as a live stage, at the cost of one extra eligibility pass and
  one verifier ranking per side.
- `RoutingReasoningStage`'s trusted path now delegates to `TrustedRoutingAdvice` (issue #170), so a later hardening of
  trusted verification reaches live stages and previews alike.
- A preview decision is a verified decision, not a receipt source. With an unchanged request identity it shares the
  live decision's `DecisionRef`, so a host that receipted it would bind two decisions to one reference; a host that
  applies an adjustment runs the trusted stage again and receipts that decision.
- `ScenarioComparison.Compared` derives the delta again from its decisions to validate it. That is the record's
  invariant check, O(R) over at most 32 routes per side, and a record cannot skip it for the comparator's own delta.
- The feature schema break is exercised by the record guards only, because `TaskFeatures` has a single schema version
  today.

## Follow up

- Unrelated to this decision but noticed while writing it: two ADRs share the number 0040 (issue #171).
