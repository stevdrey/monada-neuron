# ADR 0028: Shared Opaque-Token Rules

## Status

Accepted (implemented by issue #81)

## Context

Forge Routing Contract v1 section 2 defines opaque tokens: 1 to 128 code points, no control characters, no
leading or trailing whitespace, Unicode space separator or format character, ordered by code point. Because each
routing package could not edit the package-private helper of another, the rules were copied into
`routing.features.FeatureTokens`, `routing.catalog.RouteTokens`, `routing.RoutingTokens` and
`routing.outcome.OutcomeTokens` (the last with a 512-code-point text variant). Issue #61 recorded consolidation as
follow-up. A rule change would have to be applied four times, and the copies could drift silently.

## Decision

- One `public final class OpaqueTokens` in the new package `monada.neuron.routing.token` owns `require`,
  `requireText` (512 code points, a Neuron outcome extension aligned with the Store's artifact references and not
  part of contract section 2), `CODE_POINT_ORDER` and `compareCodePoints`. The code moved verbatim, so
  messages, exception types and the allocation behavior of the validation path are unchanged.
- It is public only because the artifact has no JPMS boundary (ADR 0025) and the callers live in four packages.
  `package-info` documents it as shared infrastructure and not a supported extension point; hosts should not depend
  on it.
- `RouteTokens` stays package-private and keeps the catalog-specific token-set helpers (`canonicalSet`,
  `canonicalOrder`, merge-based `containsAll` and `firstCommon`), delegating to `OpaqueTokens`.
- Tests share one `monada.neuron.testsupport.AllocationProbe`, which guards the `com.sun.management.ThreadMXBean`
  cast with `instanceof` and skips the test when allocation tracking is unavailable.

## Alternatives

- Keep four copies: no new public type, but unchanged drift risk.
- Make one existing package-private helper public (for example in `routing`): `features` and `catalog` would then
  depend on the parent package, inverting the current direction.
- Reflection or a generated copy: more machinery than a 30-line class warrants.
- Add JPMS to hide the package: rejected in ADR 0025.

## Consequences

- One place to change or test the section 2 rules (`OpaqueTokensTest`).
- One additional public type in the published jar. No compatibility is promised for it: a host that imports it does
  so at its own risk, and it may change in any release.
- Two allocation tests that asserted tracking support now skip instead of failing when it is unsupported.
- Issue #65 added a second shared helper to the same package, `CanonicalLists.canonical` (sorted, bounded, duplicate-free
  immutable copies), moved verbatim from `routing.outcome.Ordering`, which now delegates to it; the same no-compatibility
  caveat applies, and it removed the fifth copy the new `routing.feedback` package would have needed.

## Follow-Up

- Move the remaining ad-hoc `ThreadMXBean` checks (`reasoning`, `evaluation`) onto the shared probe.
- Revisit hiding the package if a module boundary is ever introduced.
