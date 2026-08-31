# ADR 0016: Bounded Parallel Aeon Input Coordination

## Status

Accepted

## Context

`DeterministicAeonCoordinator` coordinates ordered `AeonInput` entries sequentially within one Aeon's
membership boundary. Under the project's cognitive and execution model (ADR 0004, ADR 0007, and ADR 0008),
Node state and topology remain read-only during signal propagation. Propagating multiple independent inputs
across an Aeon's graph therefore constitutes independent CPU-bound work.

However, `CognitiveContext` (ADR 0009) is strictly sequential and not thread-safe. Furthermore, observable
deterministic semantics require:
1. Exact caller-visible result ordering matching the declared input list order.
2. Exact step, signal, and trace budget accounting identical to the sequential reference path.
3. Deterministic exception propagation and cancellation when one or more inputs fail.
4. Predictable fallback to the sequential path when workload independence cannot be guaranteed.

## Decision

1. **`BoundedParallelAeonCoordinator`**: Implement an optional bounded parallel coordinator implementing
   `CognitiveAeonCoordinator` and `AutoCloseable`. It bounds concurrency by a configurable `maxParallelism`
   (defaulting to available CPU processors) and a `parallelismThreshold` (defaulting to 2).
2. **Parallel Eligibility**: An explicit `AeonParallelEligibility` strategy evaluates whether a coordination
   request is eligible for concurrent execution. Workloads with input count below the threshold, single-worker
   configurations, or policies declaring non-independence (e.g. `SEQUENTIAL_ONLY` or active node-mutating
   adaptation) automatically delegate to `DeterministicAeonCoordinator`.
3. **Direct Parallel Coordination**: For non-contextual coordination, inputs are propagated concurrently across
   the worker pool. Results are collected in exact input order ($0 \dots N-1$).
4. **Deterministic Contextual Reconciliation**: For contextual coordination, `CognitiveContext` is never mutated
   concurrently by worker threads. Instead:
   - Worker threads execute propagation concurrently into isolated, thread-local recording structures (`InputExecutionLog`).
   - The coordinator thread reconciles the recorded logs sequentially into the parent `CognitiveContext` in strict
     input index order ($0 \dots N-1$), applying step/signal budget limits, event sequences, and trace entries
     deterministically.
   - Any input that exceeds remaining cycle capacity is cleanly truncated to match the exact sequential oracle state.
5. **Deterministic Multi-Failure Semantics**: If any input task encounters an exception, sibling tasks are cancelled.
   The coordinator rethrows the exception belonging to the earliest input index in original order, attaching any
   subsequent concurrent exceptions as suppressed (`addSuppressed`).
6. **Reference Oracle**: `DeterministicAeonCoordinator` remains the semantic oracle and fallback.

## Alternatives Considered

### Concurrently synchronize `CognitiveContext`

Rejected because fine-grained locking or atomic counters across threads would create cache contention, false
sharing, and non-deterministic event/trace sequencing that would violate ADR 0009.

### Spawn one Virtual Thread per Input unconditionally

Rejected because virtual threads are designed for blocking I/O and orchestration, not as a CPU-bound compute
multiplier. Unbounded task spawning introduces scheduling overhead for small workloads.

### Reorder output results based on task completion time

Rejected because observable Aeon results must remain deterministic and reproducible regardless of thread scheduling
or core execution speeds.

### Parallelize individual BFS edges within a single propagation

Deferred. Intra-propagation edge parallelization introduces significant fine-grained synchronization overhead for
sparse graphs with small branching factors. Partitioning work across independent inputs amortizes scheduling costs
coarsely and efficiently.

## Consequences

- Independent multi-input Aeon workloads can achieve linear CPU speedups across available cores without altering
  observable cognitive semantics.
- `CognitiveContext` retains its sequential, single-threaded invariants and deterministic diagnostic traces.
- Workloads that cannot prove independence safely fall back to the sequential oracle without runtime failures.
- Multi-failure exception handling is strictly deterministic and matches the failure point of the sequential oracle.

## Follow-Up

Measure multi-input benchmarks across 5, 10, 32, and 128 inputs to identify the exact crossover point where scheduling
overhead is amortized by parallel execution.
