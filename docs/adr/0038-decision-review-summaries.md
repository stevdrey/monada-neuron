# ADR 0038: Decision Review Summaries

## Status

Accepted (issue #96; adds Section 5.5 to `forge-integration-readiness/1` under ADR 0033 as a minor, additive bump)

## Context

A `RoutingDecision` already records everything a reviewer could need: provenance, the ranked candidates with their
placement, the truncation flag, the rules applied and skipped, and one structured `EligibilityReport.Exclusion` per
excluded route. It is shaped for verification and replay, not for a person. A host that wants to show "why this route,
and what was ruled out" has to walk the sealed variants itself, group exclusions itself and decide on its own which facts
deserve a warning. Each host would do that differently, and the easy shortcuts (show only primary reasons, drop the
truncation flag, show an abstention as "no advice") erase exactly the safety facts the decision carries.

The forces:

1. **The decision types are frozen.** No broad constructor change to `RoutingDecision`, no new eligibility or evidence
   logic, no second ranking.
2. **A decision is bounded.** At most 32 catalog routes, 8 ranked candidates and 8 additional reasons per exclusion, so
   any projection is linear in already small inputs.
3. **A decision does not record everything a reviewer might ask.** It carries no resource estimates and no task text, so
   a projection cannot say why the resource objective did not apply, only that it did not.
4. **Host facing reports already have a pattern.** Section 5.4 (ADR 0037) uses stored inputs only, derived rows, enum
   codes instead of English and a fixed sentence saying the report is information only.

## Decision

- **`DecisionReview.from(RoutingDecision)`** returns a sealed `DecisionReview` in the new package
  `monada.neuron.routing.review`: `Selected`, `Abstain` or `NoEligibleRoute`, matching the decision variant. `from` is
  `static` because it is a pure function of one value, like `DecisionRef.of`; there is no policy or schema version a
  later implementation would swap.
- **Each review holds only its decision**, the same instance. Exclusion groups, counts and notices are derived from it on
  every call, and the ranking is the decision's own list. A review therefore cannot disagree with its decision, and
  review equality is decision equality.
- **Shared accessors** echo the provenance's decision reference, catalog version, policy id and policy version.
  `contractVersion()` is the library constant `RoutingDecision.CONTRACT_VERSION`. State binding, policy parameters and
  cohort binding stay reachable only through `decision()`.
- **`Selected` adds** route, execution mode, basis, overflow flag, ranking, truncation flag and `decisiveRule()`, the
  winner's placement rule. **`Abstain` adds** reason, eligible routes (unranked), ranking, truncation flag and state
  mismatches, and has no decisive rule because an abstention decided nothing.
- **`ExclusionGroup`** groups the decision's own `EligibilityReport.Exclusion` records by primary reason, in precedence
  order, with additional reasons intact. Its constructor rejects an empty group, a member with another primary reason, a
  duplicate route and more than 32 members.
- **`ReviewNotice`** has ten constants, each present only when one recorded field says so (the table in Section 5.5).
  `RECHECK_BEFORE_EXECUTION` is on every review, including `NoEligibleRoute`, so its absence can never read as
  permission. Learned preference and resource notices appear only on `Selected`, the only variant that records
  `rulesSkipped`.
- **Information only.** Each of the six types carries the sentence: A review is information only: it is never
  eligibility, readiness, authentication or authorization, and the selected route stays advice that the host must
  recheck before execution. No public name reads as a grant, and the only argument free `boolean` methods echo decision
  flags.
- **The feature assessment stays separate.** This closes the ADR 0037 follow up: the review does not rebuild field status.
  A host shows the `FeatureReadinessAssessment` it built for the request beside the review, and neither reads the other.

## Alternatives

- **Materialized review records.** `from` would compute every field once and store it. Accessors are cheap and values
  serialize easily, but a public record constructor would let anyone build a review that matches no decision, unless the
  constructor rechecked every field against a decision it does not hold. That is two copies of each derivation rule.
- **One flat record with an outcome enum.** A single type with `Optional` fields for route, mode and reason is easy to put
  in a table, but `Optional.empty()` on a selection field is a quiet way to miss an abstention, and the compiler no longer
  makes the host handle each outcome.
- **English explanation strings.** Easy to show, but wording becomes a de facto API that breaks on rewording, and
  localization moves into Neuron.
- **Infer learned preference or resource notices for an abstention from its placements.** Rejected: an abstention does
  not record which rules ran, so such a notice would invent a fact.

## Consequences

- Forge gets one deterministic, coded review for every decision variant, and the summary cannot drop an exclusion reason
  or reorder the ranking.
- Three public types, three nested records and ten notice constants join the API covered by the contract versioning rule.
  Renaming or removing one is breaking, and adding a notice changes what existing decisions report.
- Derived lists are rebuilt on each call (at most 32 exclusions and 8 candidates); a host that renders often keeps what it
  got.
- The review cannot explain why the resource objective did not apply, because the decision does not record it; Forge says
  "not applied" without a cause. A tied group the objective left unrefined while it refined another is also invisible.
- `LEARNED_PREFERENCE_NOT_APPLIED` appears on almost every cold start decision, so it is routine, not alarming.
- `ExclusionGroup` keeps a public constructor, because Java requires a public record's canonical constructor to be
  public, so a host can build a group no decision produced. Its constructor checks every rule a group must keep, a group
  is never a review, and nothing in Neuron reads one back as evidence, so the exposure stays harmless.
- `RoutingDecision`, eligibility, ranking and verification are unchanged; no new dependency and no preview API.

## Follow-Up

- If Forge needs the cause behind `RESOURCE_OBJECTIVE_NOT_APPLIED`, that is a decision contract change (a recorded reason
  per skipped rule) and a successor to `routing-decision/1`, not a review change.
- Receipts (#97) and dispositions (#98) should reference the decision, not the review, since the review holds nothing more.
