# ADR 0046: Host Disposition of Routing Advice

## Status

Accepted (issue #98; adds `monada.neuron.routing.disposition` under contract version `routing-advice-disposition/1`,
and amends `forge-integration-readiness/1` under ADR 0033 as a minor, additive bump)

## Context

A Forge review loop needs to record what a person did with a piece of routing advice before anything ran: followed it,
proposed a different route, deferred, or declined. The [readiness contract](../specs/forge-integration-readiness-v1.md)
reserved this as "a disposition" in its never permission list and in the Phase 4 gate, and ADR 0039 asked that a
disposition reference a receipt by its `sha256()` plus its `DecisionRef`, never by adding fields to
`routing-advice-receipt/1`.

The forces:

1. **Human intent must never become authorization or credit by accident.** The record is a host UI fact. Nothing in
   Neuron may read it as permission to run, and no feedback path may count it as an outcome.
2. **The variant rules are part of the meaning.** An abstention cannot be followed; a deferral or a decline carries no
   route; an alternative always diverges from the advice. If a public record can be built by hand, the rules must hold
   there too, not only in a factory.
3. **A proposed route needs a label the decision can vouch for.** A UI should be able to say "the policy excluded this"
   or "this is your own claim" without Neuron ranking again or seeing a catalog.
4. **Hard limits from the issue.** No bytes, no decision copy, no state, no ordinal, no actor, no time, no dependency,
   no I/O, and no change to any type outside the new package.

## Decision

- **`AdviceDisposition`** is a sealed interface permitting exactly four records: `Followed`, `AlternativeProposed`,
  `Deferred` and `Declined`. Each holds an `AdviceReference advice`; only `AlternativeProposed` adds a `RouteKey route`
  and an `AlternativeStanding standing`. `CONTRACT_VERSION` is `routing-advice-disposition/1`.
- **`AdviceReference`** copies the advice identity from an `AdviceReceipt`: the `DecisionRef`, the receipt digest
  (exactly 64 lowercase hexadecimal characters), the `AdviceKind` (`SELECTED`, `ABSTAINED`, `NO_ELIGIBLE_ROUTE`, one per
  `RoutingDecision` variant by exhaustive switch) and, for a selection only, the advised `RouteKey`. It holds neither the
  bytes nor the decision.
- **Four named factories, coded results.** `followed`, `alternative`, `deferred` and `declined` take the receipt and the
  `DecisionRef` the host believes it is answering and return a sealed `DispositionResult`: `Bound(disposition)` or
  `Rejected(reason)` with a closed `DispositionRejection`. The check order is fixed: `REFERENCE_MISMATCH` (any of the six
  `DecisionRef` fields differs), then `ADVICE_NOT_SELECTED` (followed only), then `ALTERNATIVE_EQUALS_ADVICE`
  (alternative only). A host mistake is a code; only a null argument throws.
- **The constructors enforce the structural rules as well.** `new Followed` rejects a reference whose kind is not
  `SELECTED`, and `new AlternativeProposed` rejects a route equal to the advised route, both with
  `IllegalArgumentException`, so a hand built record cannot say "followed an abstention" or "declined with a route".
- **Standing is derived, never supplied.** `ELIGIBLE_IN_DECISION` when the route is a ranked candidate of a `Selected`
  or a member of `Abstain.eligible()` (so `Abstain(STATE_INCOMPATIBLE)` still vouches for eligibility computed before
  state validation failed), `EXCLUDED_IN_DECISION` when it is one of the exclusions, `HOST_CLAIM` otherwise. The
  derivation is two indexed loops over lists the decision constructors already cap, at most `RouteCatalog.MAX_ROUTES`
  key compares and no allocation. A key with the advised route id under another version is a different `RouteKey`, so it
  is accepted as an alternative and labelled `HOST_CLAIM` unless the decision lists that exact key.
- **`refersTo(receipt)` reruns the matching factory and compares for equality.** That re derives the kind, the advised
  route and the standing from the receipt, so a hand built reference with a false kind or a hand built alternative with a
  false standing answers `false`, as does a receipt of a re request or of the same `DecisionRef` decided over another
  catalog version.
- **The package cannot reach state or credit.** It imports nothing from `routing.adaptation`, `routing.feedback`,
  `routing.outcome`, `routing.review` or `resonance`, and no public signature or record component mentions a type from
  those packages. A boundary test pins this, the forbidden name list, the Javadoc sentence, the public type count of 12
  and the `toString` forms, which print the kind, the request ordinal and the digest and never a token.

### Four terms that stay apart

| Term | What it is | Where it lives |
|---|---|---|
| **intent** | a disposition: what the host reports a person chose to do with the advice | `monada.neuron.routing.disposition`, this ADR |
| **authorization** | a host act at the side effect boundary; Forge revalidates scope, availability and quota there | outside Neuron; no disposition and no standing grants it |
| **invocation** | the worker run the host reports after it happened | `RoutingOutcome.invocation` |
| **acceptance** | the judged result of that run | `RoutingOutcome.outcomeStatus` of `ACCEPTED` |

Only the last two are evidence, and only through the feedback path. A disposition alone earns zero credit, and an
`AlternativeProposed` with any standing is a request to the host's own authorization, never a route grant.

## Alternatives

- **One record with a kind enum and an optional route.** Smaller, but "followed an abstention" and "declined with a
  route" become representable and need a runtime check instead of a type.
- **Factories only, no constructor guards.** Leaves `new Followed(abstainedReference)` valid; the issue says an abstention
  *cannot* be marked followed, not "is not, if you use the factory".
- **Variant check before the reference check.** Would report a variant fault about advice the host was not even looking
  at; identity first makes every later answer meaningful.
- **`refersTo` as a ref plus digest comparison.** The first draft did this; a hand built record with a false kind or
  standing defeats it. Rerunning the factory costs one reference copy plus up to 32 key compares.
- **A `HashSet` per standing derivation.** More allocation than the two capped lists it would index; the lists are
  disjoint by construction, so order only matters for reading.
- **Forcing `HOST_CLAIM` after `Abstain(STATE_INCOMPATIBLE)`.** Would discard eligibility the decision carries; its
  Javadoc says ordering never ran, not that eligibility is unknown.
- **An ordinal, reason, actor or timestamp on the record.** The issue allowed an ordinal; it was left out on purpose.
  Which disposition is current is a host storage fact, as it is for outcome entries before Phase 5.

## Consequences

- Forge can keep a small value beside its own task entry that says what a person did with a receipt, with the variant
  rules enforced by types and constructors, and later compare it with what actually ran.
- A host override is always labelled by what the decision proves. `HOST_CLAIM` is coarse: a route cut from a truncated
  candidate list and a route never in the catalog look the same, and a host that needs the difference keeps the catalog
  and checks it itself.
- Without bytes, a stored disposition is the host's own serialization problem. A byte format, if one is ever needed,
  would carry a tag of its own, distinct from `CONTRACT_VERSION` (`routing-advice-disposition/1` names the type contract,
  not an encoding), and would reuse the receipt primitives; no issue up to #103 needs one.
- A factory binds any receipt whose `DecisionRef` matches; it cannot know whether the advice is stale. Staleness stays the
  host's re check (readiness contract Section 6), and a host must not record a disposition for a decision it has already
  re requested. Sections 6.2 and 10.3 of the contract describe that host duty, not a Neuron check.
- Twelve public types (six top level) join the API covered by the contract versioning rule; renaming a standing or a
  rejection constant is a breaking change and needs a successor version.
- `refersTo` costs a factory rerun instead of two equality checks; cheap, but a host calling it in a tight loop should
  cache the answer.
- `RoutingDecision`, `AdviceReceipt` and every type outside `monada.neuron.routing.disposition` are unchanged. No
  dependency and no preview API is added.

## Follow-Up

- The Forge like host fixture (#102) builds all four dispositions from this package; no new API is expected there.
  Done: `consumer-fixture/forge-host` binds every variant over selected, abstained and no eligible route advice
  through the existing factories, with no new API ([forge-host-fixture-v1.md](../specs/forge-host-fixture-v1.md)).
- The host scenario suite (#103) should assert "disposition alone earns zero credit" by driving `RoutingFeedbackPolicy`
  with and without dispositions and seeing identical state.
- If a host ever needs "cut by truncation" apart from "never seen", add an `UNLISTED_IN_DECISION` standing in a
  `routing-advice-disposition/2`, never by reinterpreting `HOST_CLAIM`.
- When issue #165 is reopened, list `DispositionBoundaryTest` in it so the source path fix covers four boundary tests.
