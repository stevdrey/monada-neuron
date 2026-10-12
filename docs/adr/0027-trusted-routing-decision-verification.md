# ADR 0027: Trusted, Extensible Routing Decision Verification

## Status

Accepted (implemented by issue #78)

## Context

Issue #76 split `RoutingReasoningStage` verification into the policy-independent `RoutingDecisionValidator` and the
`route-lex/1` `LexicographicDecisionVerifier`. The stage still applied the latter to every policy, so the public
`RoutingPolicy` port advertised more extensibility than Level B offers. The verifier also read the `PolicyParameters` and the
cohort bucket the decision itself records, because the port exposes neither the policy's effective configuration nor its
`CohortMapping`: a policy chose the configuration it was checked against.

## Decision

Add an explicit, host-chosen verifier boundary (Forge Routing Contract v1, Section 18):

- `RoutingDecisionVerifier`, a public interface for policy-specific verification;
- `TrustedRoutingProfile`, an immutable record binding policy id/version, the decision contract version and a verifier, which
  rejects null, mismatched or unsupported (`!= routing-decision/1`) combinations at construction;
- an additive `RoutingReasoningStage` constructor taking the profile; the three-argument constructor keeps the legacy
  self-reported `route-lex/1` verification and is documented as not independent;
- `LexicographicDecisionVerifier.trusted(PolicyParameters, RoutingStateDefinition)`, which compares recorded identity and
  parameters with the host's, derives the expected `StateMismatch` list and the cohort bucket from the trusted definition, and
  recomputes the ranking from trusted values only.

`RoutingDecisionValidator` stays package-private, stage-owned and always first; no constructor can replace or skip it.

## Alternatives

- **A, widen `RoutingPolicy`** with configuration accessors: forces every policy to publish trust data and lets it describe
  itself. Rejected.
- **B, policy- or decision-selected verifier, registry or `ServiceLoader`:** self-attestation or hidden global state. Rejected.
- **C, host profile (chosen):** smallest explicit contract; additive.

## Consequences

- A faulty or untrusted policy can no longer choose the parameters, mapping versions or cohort bucket it is checked against, on
  the profile path. A malicious host that installs a permissive verifier is out of scope; the shared validator still blocks
  ineligible routes, wrong mode/overflow and foreign catalog/request provenance.
- The reference ranking is verified against the same `LexicographicRanking` implementation: agreement, not independent
  correctness.
- v1 decision types encode the reference ordering; other ordering families need a versioned successor of those types and fail
  closed here.
- Measured cost on the maximum-size fixture is a few hundred bytes per execution (contract Section 18.7); verification still
  repeats ranking.

## Follow-Up Work

- Versioned decision types for non-reference ordering families.
- #63 to #68 build on this boundary and stay separate.
