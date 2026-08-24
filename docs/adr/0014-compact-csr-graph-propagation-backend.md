# ADR 0014: Optional Compact CSR Graph Propagation Backend

## Status

Accepted

## Context

The Phase-1 `Node` graph intentionally uses UUID identity and mutable `HashSet<Node>` adjacency as
the clear reference model. Issue #14 measured the RouteAll traversal of its 2,000-node,
average-degree-8 workload at approximately 4.98 MB allocation per run and approximately 941 KB of
retained object-topology storage. `DeterministicSignalPropagationEngine` also sorts expanded
adjacency by UUID during each propagation.

The object model remains necessary for domain identity, mutation, inspectability, and semantic
reference behavior. A compact representation must therefore be a compiled runtime view rather than
a replacement for `Node`, public UUID identity, or the deterministic engine.

## Decision

1. `CompactGraphSnapshot.compile(Collection<Node>)` creates a closed, canonical view of an
   explicitly supplied Node collection. It sorts Nodes by UUID, assigns dense internal indices, and
   stores immutable CSR `int[] offsets` and `int[] targets` arrays.
2. The snapshot retains canonical Node references and their topology versions. It exposes only
   counts and current/stale state; CSR arrays and integer IDs stay internal to `runtime.graph`.
   Compilation rejects nulls, duplicate UUID instances, targets outside the canonical collection,
   and topology changes observed while compiling.
3. A Node topology version advances only after successful `connect` or `disconnect`. Direct compact
   propagation validates all captured versions before and after execution, failing clearly when a
   caller must recompile. State and energy changes do not invalidate topology and remain live to
   processors and routing policies.
4. `CompactSignalPropagationEngine` implements direct propagation with a primitive parallel-array
   FIFO. It preserves the reference engine's BFS order, UUID target order, output order, duplicates,
   revisits, routing decisions, and independent step/hop limit reporting. It remains opt-in.
5. Its `CognitiveContext` overload delegates to `DeterministicSignalPropagationEngine`. The context
   trace and shared-budget semantics retain the proven reference implementation while direct CSR
   traversal is evaluated independently.
6. Benchmarks report object topology, incremental snapshot, and combined structural estimates.
   They measure compilation separately from steady-state traversal. No backend becomes a default
   solely from one machine's results.

## Alternatives Considered

### Replace Node connections with CSR arrays

Rejected because it would change mutable domain topology, public identity handling, and Phase-1
correctness semantics.

### Cache sorted object adjacency inside the reference engine

Rejected because it retains object-heavy traversal work and requires an equivalent invalidation
policy without producing a compact execution layout.

### Compile Node state into independent primitive arrays

Deferred. Copying mutable state would require state-version lifecycle rules and risks diverging from
the live `NodeView` observed by processors and policies.

### Implement compact contextual propagation now

Deferred. It would duplicate bounded context tracing and resource-accounting behavior before direct
CSR traversal has independent performance evidence.

## Consequences

- Stable sparse graphs can avoid repeated adjacency sorting, UUID edge lookups, boxed queue entries,
  and per-delivery work records on the direct path.
- Callers explicitly own the compile/recompile lifecycle; structural mutation makes an existing
  snapshot unusable rather than silently rebuilding it during a timed traversal.
- The compact snapshot adds heap while it coexists with the domain graph. Its isolated compact
  storage must not be reported as total process-memory reduction.
- The runtime remains sequential and does not provide topology-mutation synchronization.
- Future state-array, FFM, concurrent, or accelerator work must preserve this direct semantic
  oracle and establish its own lifecycle decision.
