# ADR 0009: Bounded Ephemeral Cognitive Context and Deterministic Cycle Trace

## Status

Accepted

## Context

ADR 0001 assigns active-cycle state to Monada Neuron while reserving persisted and reusable
experience for Monada Resonance Store. ADR 0005 deliberately keeps Signal free of correlation
metadata, and ADR 0007 leaves detailed execution tracing to a later cycle context.

Aeon coordination can now execute multiple bounded propagations, but it has no explicit owner for
cycle-wide resource limits, temporary results, or deterministic diagnostics. Leaving those concerns
to callers would make temporary state unbounded, encourage hidden maps or logging, and risk
creating an accidental persistence subsystem.

## Decision

The core provides a mutable `CognitiveContext` for one sequential cognitive cycle. It owns a
validated `CognitiveBudget` with global maximums for completed processing steps, accepted signal
occurrences, and retained trace entries.

The context is active only for its owner-controlled cycle. It can complete once with a success or
failure outcome to produce an immutable `CognitiveCycleSnapshot`, or it can be discarded through
`close()`. Completion and discard clear the context's mutable buffers; it has no reset operation,
persistence API, durable identifier, timestamps, exception retention, or concurrency guarantee.

Every accepted input, processor emission, enqueued delivery, and non-Aeon stage input/output
receives a cycle-local sequence and consumes signal capacity. Completed `NodeProcessor` calls
consume step capacity. The cycle, rather than an extension stage, admits non-Aeon candidates from
left to right and retains only the admitted prefix. When either shared execution budget prevents
new work, the deterministic runtime suppresses additional work without throwing a budget exception
and reports the condition through the context snapshot and trace. Existing queued work remains
bounded by the accepted prefix and existing per-propagation limits still apply.

The trace has a closed vocabulary: Aeon-input start/completion, successful Node processing, accepted
routing, and cognitive-stage lifecycle events. It records no rejected routing decisions, timestamps,
threads, or external provider data. Trace events retain only identifiers, counters, and
accepted-occurrence sequences; accepted `Signal` values live only in bounded
`CognitiveSignalOccurrence` records, so rejected signals are not retained by the snapshot. A
bounded ordered `ArrayList` retains the first entries; later events increment an omission counter but
never affect cognition. The terminal outcome and counters remain in the snapshot even when the trace
is full.

`CognitiveSignalPropagationEngine` and `CognitiveAeonCoordinator` extend the existing contracts
without changing them. The deterministic propagation engine and Aeon coordinator implement the
contextual path. Legacy propagation and coordination retain their existing behavior exactly.

## Alternatives Considered

### Add identity or trace metadata to Signal

Rejected because it reverses ADR 0005 and would impose cycle-only allocation and responsibility on
every Signal producer.

### Use unbounded lists or generic context maps

Rejected because allocation and state ownership would grow with long-running processes while hiding
the typed cognitive semantics required for deterministic diagnostics.

### Make trace saturation stop cognition

Rejected because diagnostics must not determine production cognition. Trace retention and execution
budgets remain separate limits.

### Replace the existing runtime contracts

Rejected because current callers retain a valid deterministic reference path. Context-aware behavior
is an explicit optional extension.

## Consequences

- Temporary cycle state, correlation, and diagnostics have an explicit non-persistent owner.
- Identical inputs, graph state, limits, and processor behavior produce identical snapshots.
- Signal and step budgets constrain the reference runtime before unbounded queue or result growth.
- Non-Aeon stage boundaries, including optional memory recall, cannot bypass the global signal
  budget or retain rejected candidate Signals.
- Context snapshots can support future Monad orchestration without making Monad, memory access, or
  distributed tracing part of this decision.
- The Phase-1 contextual runtime is sequential and not thread-safe; concurrent orchestration needs
  a separate ownership and equivalence decision.

## Follow-Up

Future Monad orchestration may own context creation, completion, and failure policy. Any parallel,
distributed, persisted, or provider-facing trace must remain outside this in-process context or be
introduced through a new documented boundary.
