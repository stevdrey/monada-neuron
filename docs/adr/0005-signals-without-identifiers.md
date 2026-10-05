# ADR 0005: Keep Signals Free of Identifiers

## Status

Accepted

## Context

ADR 0004 introduced caller-assigned UUIDs on `Signal` for correlation and tracing. The Phase-1 signal value is created frequently and no current processing contract consumes that identity. Keeping a UUID in the core value therefore adds allocation and API surface without supporting an implemented behavior.

Nodes retain their durable UUID identity. This decision concerns only the ephemeral `Signal` value.

## Decision

`Signal` is an immutable record containing only `SignalKind` and `FrequencyState`. It has no UUID, sequence, energy field, implicit identifier generation, or uniqueness policy.

Signal equality and hashing are structural across the kind and frequency state. Two independently emitted signals with equal components are equal values.

Correlation, tracing, or traversal metadata required by a future cognitive runtime belongs to an external cycle context or envelope, not to `Signal` itself. This supersedes the Signal identity portion of ADR 0004; its read-only Node processing and ordered output decisions remain in force.

## Alternatives Considered

### Retain a caller-assigned UUID

Rejected because the current Signal contract has no consumer for signal-level correlation and should not impose identifier allocation or producer responsibility prematurely.

### Generate UUIDs inside Signal

Rejected because it would add hidden allocation and nondeterminism while still making identity part of every signal value.

### Use a numeric sequence in Signal

Rejected because ordering belongs to `NodeProcessingResult` and a future propagation context, not to the signal payload.

## Consequences

- Signal construction requires only a non-null kind and frequency state.
- Signal values are smaller and do not require producers to create identifiers.
- A flow that needs correlation must own the metadata explicitly outside the Signal value.
- Signal intensity remains `FrequencyState.amplitude`; Node energy remains node activation state.
- `NodeProcessor`, `NodeView`, and `NodeProcessingResult` retain their existing processing, immutability, and ordered-output contracts.

## Follow-Up

If a future runtime needs correlation or tracing, define a separate cycle-context or envelope contract with explicit lifetime and ordering semantics. Do not restore signal identity without an implemented consumer and a measured justification for its cost.

Implemented for host integrations by ADR 0023, which adds a request-scoped `HostExecutionContext` carried outside `Signal`.
