# ADR 0001: Separate Cognition from Long-Term Memory

## Status

Accepted

## Context

Monada Neuron and Monada Resonance Store are complementary systems with different responsibilities.

Without an explicit boundary, cognitive code can gradually absorb persistence, recall ranking, vector compatibility, or feedback storage simply because those capabilities are convenient to access locally. That would create two competing memory implementations and make both systems harder to evolve.

## Decision

Monada Neuron owns cognition, active-cycle state, reasoning, adaptation, and action coordination.

Monada Resonance Store owns long-term associative memory, persisted resonance representations, recall/ranking, memory storage formats, and memory compatibility diagnostics.

Neuron accesses long-term memory only through an explicit resonance adapter/protocol.

Ephemeral working memory that exists only for an active cognitive operation may live in Neuron. Persisted or reusable experience intended for later recall belongs to Resonance Store.

## Alternatives Considered

### Keep all memory inside Monada Neuron

Rejected because it couples cognition to persistence and duplicates the specialized memory project.

### Let both repositories persist memory independently

Rejected because ownership and compatibility semantics become ambiguous.

### Treat Resonance Store as an implementation detail imported directly into core classes

Rejected because storage implementation details would leak into cognitive APIs and make replacement/testing harder.

## Consequences

- Core cognitive APIs should depend on a memory capability/port rather than storage internals.
- Memory failures and latency become explicit adapter concerns.
- Neuron can be tested with in-memory/fake resonance adapters.
- Resonance Store can evolve persistence/indexing independently.
- Cross-project protocol compatibility becomes an explicit integration concern.

## Follow-Up

When the resonance adapter is implemented, define request/response contracts, failure semantics, compatibility/version negotiation if required, and integration tests without moving storage ownership back into Neuron.
