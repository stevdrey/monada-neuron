# ADR 0007: Deterministic Bounded Signal Propagation

## Status

Accepted

## Context

`Node` provides directed object connections through a `HashSet<Node>`, while ADR 0004 and ADR 0005
define immutable signals, read-only node processing, ordered emissions, and identifier-free Signal
values. The graph previously had no executable traversal semantics, and hash iteration order cannot
define reproducible cognitive behavior.

The first runtime must remain a simple correctness reference for future compact or parallel graph
implementations. It must preserve every meaningful signal arrival, terminate cyclic propagation,
and allow resonance routing without making one metric or threshold global behavior.

## Decision

The core runtime defines a `SignalPropagationEngine` contract and a sequential
`DeterministicSignalPropagationEngine` reference implementation.

Propagation uses breadth-first traversal with an `ArrayDeque`. The start node is processed at hop
zero, and one completed `NodeProcessor` invocation is one processing step. Outgoing connections are
copied and sorted by ascending Node UUID once per expanded node during a propagation. For each
processing result, emitted signals are handled in their declared list order; for each signal,
eligible targets are considered in sorted UUID order.

Every accepted emitted-signal/target pair creates an independent work item. Nodes may therefore be
revisited, and structurally equal or duplicate emitted signals are not deduplicated. This preserves
fan-in, cycles, and ADR 0004's duplicate-emission semantics. Duplicate structural connections remain
suppressed by `Node` membership semantics.

Every propagation requires positive `maxSteps` and non-negative `maxHops`. Work is not processed
after `maxSteps`, and routes beyond `maxHops` are not enqueued. The result reports step and hop
truncation independently, so both may be observable. Processor and routing failures propagate to
the caller; they are not converted to empty output or a partial successful result.

Routing is an explicit `SignalRoutingPolicy`. The default named configuration uses a shared
route-all policy. `ResonanceThresholdRoutingPolicy` is an optional policy that delegates scoring to
an injected `ResonanceMetric` and accepts scores greater than or equal to a finite threshold in
`[0, 1]`. It compares the emitted Signal frequency state with the target Node frequency state;
Signal kind, source state, and Node energy do not affect that policy.

`PropagationResult` snapshots every signal emitted by completed steps in global execution order,
plus the completed step count and independent limit flags. It does not provide a per-step trace;
cycle-scoped tracing belongs to the later cognitive-context boundary.

Node state and topology must not be mutated concurrently with propagation. The Phase-1 Node remains
non-thread-safe, and the runtime neither synchronizes nor mutates it.

## Alternatives Considered

### Process each Node only once

Rejected because it would discard later arrivals at fan-in nodes and make the first BFS path win
semantically.

### Deduplicate equal Node/Signal pairs

Rejected because Signal equality is structural and equal duplicate emissions may be meaningful.
Termination is instead enforced by explicit step and hop limits.

### Use depth-first or recursive traversal

Rejected because breadth-first order is easier to inspect across branches, while recursion adds
stack risk and allocation/lifecycle complexity for cyclic graphs.

### Change Node connections to an ordered or compact representation

Rejected for the reference phase. `HashSet` remains appropriate for mutable membership, and the
runtime isolates deterministic ordering through per-call snapshots. Compact adjacency needs a
representative evaluation workload.

### Hard-code scalar resonance gating

Rejected because routing thresholds and metric selection are cognitive policy. Route-all and
resonance gating remain explicit interchangeable policies.

## Consequences

- Identical graph state, input, processor, policy, and limits produce identical observable results.
- Repeated arrivals and cycles can grow work rapidly, but configured limits make execution finite.
- Sorting costs `sum(d * log(d))` for expanded node out-degrees and is paid once per expanded Node
  during a propagation.
- Queue, adjacency snapshots, and emitted results allocate in proportion to bounded execution work.
- A later optimized backend must preserve BFS, UUID tie-breaking, emission order, revisit behavior,
  routing decisions, and limit reporting when claiming semantic equivalence.
- Detailed cycle trace metadata remains outside the identifier-free Signal value.

## Follow-Up

Aeon coordination should reuse `SignalPropagationEngine` rather than duplicate traversal. The
evaluation phase should measure sparse graph sizes, branching, revisits, allocation, and sorting
cost before selecting compact adjacency, parallel execution, or specialized layouts.
