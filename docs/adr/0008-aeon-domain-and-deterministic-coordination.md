# ADR 0008: Aeon Domain and Deterministic Coordination

## Status

Accepted

## Context

Monada Neuron defines an Aeon as a coherent cognitive capability that organizes focused Nodes.
The Node, Signal, processing, resonance, and deterministic graph contracts now exist, but no native
domain boundary identifies an Aeon, classifies its purpose, owns membership, or coordinates one or
more inputs. Without explicit semantics, Aeon behavior could depend on hash iteration, an implicit
root, duplicated graph state, or framework-owned agent abstractions.

Aeons are expected to contain a small-to-moderate number of Nodes and coordinate at a lower volume
than per-Signal numeric processing. Clarity, stable ordering, and safe reuse of the Phase-1 graph are
more important than a compact numeric layout at this stage.

## Decision

`Aeon` has a caller-supplied immutable UUID and one immutable `AeonPurpose`. The closed initial
classification contains perception, reasoning, evaluation, evolution, action, constraint, and
self-monitoring purposes. Equality and hashing use only the UUID.

Membership is mutable and backed by a `LinkedHashMap<UUID, Node>`. UUID lookup, addition, and
removal are O(1) on average, while iteration follows insertion order. The first Node added for a
UUID remains canonical; a duplicate addition returns false without replacement. Removing then
re-adding a member appends it to the order. Aeon exposes a live unmodifiable member view so
coordination does not require a member-collection copy on every execution.

`AeonInput` contains a member UUID and Signal. The UUID is resolved to the canonical member before
execution. `AeonCoordinator` receives an Aeon, an ordered list of inputs, a `NodeProcessor`, and a
`PropagationConfig`. `DeterministicAeonCoordinator` validates all inputs and starting memberships
before invoking any processing, then executes each input sequentially in list order. UUID equality
does not allow a different Node object to replace the canonical member during entry or traversal.

Each input is delegated to `SignalPropagationEngine`. The coordinator composes the configured
routing policy with an Aeon-membership gate: connections to non-canonical Node instances are
rejected before the caller policy runs, while canonical member connections keep ADR 0007 traversal,
emission, routing, and limit semantics. Aeon does not duplicate, own, or mutate Node adjacency.

The result retains one `AeonInputResult` per input, including the complete `PropagationResult`, in
input order. An empty input list returns a shared successful empty result. A non-member start is an
invalid request and fails before processing. Processing, routing, and invalid propagation-result
failures reach the caller; no partial `AeonCoordinationResult` is returned.

Node energy remains observable activation state, not an implicit enabled flag. A zero-energy member
is processed normally when reached. A successful `NodeProcessingResult.noOutput()` ends only that
branch according to ADR 0007.

The Phase-1 Aeon is not thread-safe. Membership, Node state, and graph topology must not change
during coordination. The reference coordinator is sequential and uses stable Java 26 APIs only.

## Alternatives Considered

### Give every Aeon an implicit root

Rejected because membership mutation could silently change the entry point and different cognitive
flows may need different starts.

### Broadcast every Signal to every member

Rejected because it duplicates work, can traverse overlapping topology repeatedly, and hides the
caller's intended entry boundary.

### Copy the complete graph into each Aeon

Rejected because Node already owns topology. A second graph would create synchronization,
identity, and memory-overhead problems.

### Use unordered membership

Rejected because observable membership must be reproducible and hash iteration cannot define
deterministic order.

### Return only aggregate signals and counters

Rejected because aggregation loses the association between each explicit input and its bounded
propagation result, including independent limit flags.

### Treat zero energy as inactive

Rejected because current graph processing does not assign gating semantics to energy. Cognitive
activation policy must remain explicit rather than silently changing the existing processing
contract.

## Consequences

- Aeon identity, purpose, membership, entry, and result semantics are explicit and testable.
- Identical membership, topology, inputs, processor, routing, and limits produce identical results.
- Membership uses object/hash overhead appropriate to low-volume orchestration; a compact layout
  would require a representative scale and semantic equivalence evidence.
- The live member view avoids repeated copies but requires callers to avoid mutation during
  coordination.
- A Node may participate in more than one Aeon, but each coordinator confines traversal to the
  membership of the Aeon being executed.
- Monad orchestration, memory access, learning, action execution, external providers, and parallel
  Aeon execution remain outside this decision.

## Follow-Up

Primary Monad orchestration may later coordinate multiple Aeons using their explicit input/result
contracts. Any parallel implementation must define membership snapshot or ownership semantics and
prove observable equivalence to this sequential reference path.
