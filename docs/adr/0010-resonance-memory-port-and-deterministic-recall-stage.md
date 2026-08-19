# ADR 0010: Resonance Memory Port and Deterministic Recall Stage

## Status

Accepted

## Context

ADR 0001 assigns persisted associative memory, encoding, retrieval ranking, storage compatibility,
and feedback logs to Monada Resonance Store. The reference cognitive cycle has a canonical
`MEMORY_RECALL` position, but cognition needs a typed, testable way to request remembered Signals
without importing storage implementation types or making external memory mandatory.

## Decision

Monada Neuron owns `ResonanceMemoryPort` and its immutable request, result, response, and status
contracts. A request carries one non-empty ordered batch of Neuron Signals and an explicit positive
result limit. A result carries only an opaque adapter reference, a Signal, and a finite score; result
order and tie semantics are supplied by the adapter and are never re-ranked by Neuron.

`ResonanceMemoryCognitiveStage` makes one batch request at `MEMORY_RECALL`, then emits its input
Signals followed by recalled Signals in adapter order. A complete empty result means no matches.
Partial recall forwards its available prefix. Unavailable, timed-out, and expected failed responses
also forward input Signals, so memory remains optional for cognition. A null, malformed, or
unexpectedly failing adapter response is an operational stage failure and follows
`CognitiveCycleException` semantics.

The cognitive cycle admits non-Aeon stage inputs and outputs through `CognitiveContext`. If the
global signal budget truncates a complete memory result, the observed stage result retains only the
admitted prefix and reports `PARTIAL`. No rejected result Signal is retained. Aeon accounting remains
unchanged to avoid double-counting its contextual propagation occurrences.

The first deterministic adapter is test-only. No production transport, dependency, persistence,
encoding, ranking, compatibility negotiation, or feedback behavior is introduced.

## Alternatives Considered

### Depend directly on Monada Resonance Store classes

Rejected because persistence and ranking details would enter cognitive APIs and make storage changes
couple directly to Neuron releases.

### Fail every cycle when memory is unavailable

Rejected because memory recall is optional and an expected capability outage must not prevent native
cognitive processing from continuing.

### Let extension stages account for their own Signals

Rejected because adapters could bypass the cycle-wide budget and retain unbounded outputs.

## Consequences

- Core integration tests use a lightweight deterministic fake without network or Store dependencies.
- Future production adapters translate between this contract and storage-specific APIs at the edge.
- Callers can distinguish complete, partial, unavailable, timeout, and expected failure outcomes.
- The contract deliberately does not define a storage compatibility version; add one only when a
  real cross-project transport requires negotiation.

## Follow-Up

Implement a production adapter only after selecting its concrete transport and proving which
compatibility metadata it requires. Keep any blocking or virtual-thread policy in that adapter rather
than in this core contract.
