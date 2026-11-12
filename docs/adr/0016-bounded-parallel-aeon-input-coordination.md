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
   (defaulting to available CPU processors) and a direct-mode `parallelismThreshold`. The default threshold is
   `Integer.MAX_VALUE`, intentionally disabling automatic direct parallel selection until a measured runtime policy
   exists. A caller must configure a threshold below `DEFAULT_PARALLELISM_THRESHOLD` to opt into direct parallel
   coordination. The coordinator submits at most `maxParallelism` propagation tasks at once, including when a caller
   supplies a larger executor; supplied executors remain caller-owned.
2. **Separate Eligibility by Mode**: An explicit `AeonParallelEligibility` strategy evaluates whether a direct
   coordination request is eligible for concurrent execution. Workloads with input count below the configured
   threshold, single-worker configurations, or policies declaring non-independence (e.g. `SEQUENTIAL_ONLY` or active
   node-mutating adaptation) automatically delegate to `DeterministicAeonCoordinator`. Contextual coordination has a
   separate `ContextualParallelism` gate and defaults to `SEQUENTIAL_ORACLE` regardless of the direct configuration.
3. **Direct Parallel Coordination**: For non-contextual coordination, inputs are propagated concurrently across
   the worker pool. Results are collected in exact input order ($0 \dots N-1$).
4. **Experimental Contextual Reconciliation**: `ContextualParallelism.EXPERIMENTAL_PARALLEL` retains the contextual
   parallel implementation for controlled experiments. The default `SEQUENTIAL_ORACLE` mode delegates contextual
   coordination to `DeterministicAeonCoordinator`. When the experiment is explicitly selected, `CognitiveContext` is
   never mutated concurrently by worker threads. Instead:
   - Worker threads execute propagation concurrently into isolated, thread-local recording structures (`InputExecutionLog`).
   - The coordinator thread reconciles the recorded logs sequentially into the parent `CognitiveContext` in strict
     input index order ($0 \dots N-1$), applying step/signal budget limits, event sequences, and trace entries
     deterministically.
   - Any input that exceeds remaining cycle capacity is cleanly truncated to match the exact sequential oracle state.
     A speculative worker failure is retained with its execution position and is discarded when that invocation is
     outside the admitted sequential prefix.
5. **Deterministic Multi-Failure Semantics**: If any input task encounters an exception, sibling tasks are cancelled.
   The coordinator rethrows the exception belonging to the earliest input index in original order, attaching any
   subsequent completed concurrent exceptions as suppressed (`addSuppressed`). Cancellation interrupts cooperative
   processors but cannot forcibly stop a processor that ignores interruption.
6. **Reference Oracle**: `DeterministicAeonCoordinator` remains the semantic oracle and fallback.
7. **Abnormal-Exit Cancellation** (added by Issue #208): Whenever `coordinate` ends with an exception, for any reason,
   every task it has already submitted is done or cancelled with `cancel(true)` before the exception leaves the call.
   This covers the input failures of decision 5 and also failures of the coordinator itself: a caller interrupt
   while it waits, an executor that rejects a submission (the rejected task is cancelled too, so it can never run
   later), and any other exception raised while it submits, awaits, or reconciles. The coordinator rethrows the
   original exception unchanged; when the caller was interrupted it throws `IllegalStateException` with the
   `InterruptedException` as cause and leaves the caller's interrupt status set. On an input failure the siblings
   are still cancelled before the suppressed failures are attached, as decision 5 requires; one handler around the
   direct submission loop, and one per contextual wave, then covers every other exit by cancelling the unreconciled
   window (direct) or the current wave (contextual; earlier waves are already reconciled). A failure raised while
   cancelling is attached to the original exception as suppressed and never replaces it. As in decision 5,
   cancellation is cooperative: a processor that is already running and ignores interruption can still run briefly
   after `coordinate` has thrown.

   *Cost.* The normal path allocates nothing new and does no extra work. The contextual handler cancels one wave of
   at most `maxParallelism` tasks. The direct handler scans the created tasks from the first unreconciled input on,
   which is **`O(inputCount)` in the worst case**, not `O(maxParallelism)`: at most `maxParallelism` of them are
   still in flight, but when an early input stalls while later inputs complete, each completion refills the window
   without advancing the first unreconciled index. Every other task in the scan is already done, and `cancel` on a
   done `FutureTask` is a constant-time state check. The decision-5 sibling cancellation already had the same bound.
   Bounding the scan to the in-flight tasks would need an index of in-flight tasks maintained on every submission and
   completion of the normal path; that cost is not justified for a path taken only once per failed call, so the
   linear worst case is accepted. Issue #208's `O(maxParallelism)` expectation is revised accordingly.

   *Context after an abnormal contextual exit.* The coordinator never adds a synthetic completion event: it would
   break decision 6, because the sequential oracle adds none when an input's propagation throws. What the
   `CognitiveContext` holds depends on where the exit happened:
   - **Before the wave's reconciliation starts** (an executor rejection during the wave's submission): the context
     holds exactly the events of the earlier, fully reconciled waves, with no `AeonInputStarted` for the current
     wave. It stays active.
   - **During reconciliation, with the context still active** (a caller interrupt while awaiting an input, an input
     failure, or a reconciliation failure): the context holds the events recorded up to the input in progress,
     including that input's `AeonInputStarted` with no matching completion, and possibly its input signal. It stays
     active.
   - **The context was closed or completed by someone else** (its owner, or a processor or callback): the coordinator
     does not reactivate or modify it; the context's own `IllegalStateException` propagates and the context remains
     in the state that party left it in.

   In the first two cases the owner must complete the context with `CognitiveCycleOutcome.FAILURE` or close it, and
   must not report it as a success. Callers must not rely on an `AeonInputStarted` existing for the failing input,
   or on the context being active, without knowing which case applies.

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

- Direct and contextual bounded coordination preserve observable cognitive semantics when explicitly enabled.
  Automatic backend selection remains disabled until end-to-end measurements justify a selection policy.
- `CognitiveContext` retains its sequential, single-threaded invariants and deterministic diagnostic traces.
- Workloads that cannot prove independence safely fall back to the sequential oracle without runtime failures.
- Multi-failure exception handling is strictly deterministic and matches the failure point of the sequential oracle.
- No submitted input task outlives an abnormal `coordinate` call in a runnable state, whether the call ended because
  of an input failure, a caller interrupt, or an executor rejection. Callers that interrupt a coordinating thread can
  rely on its interrupt status surviving the wrapped exception. `BoundedParallelAeonCoordinatorAbnormalExitTest`
  covers both paths with deterministic recording and rejecting executors.

## Follow-Up

Measure direct and contextual coordination across the complete input boundary matrix, worker counts, and graph scales.
Select a universal production threshold only when the parallel path is at least 10% faster in every fork for both
modes, including the largest sampled input count. The threshold must be the more conservative crossover of the two
modes.

### PR #38 validation status (2026-09-01)

The first three-fork validation on the representative medium graph (200 nodes, degree 5), four workers, and 128
inputs did not satisfy the contextual gate: sequential coordination measured `18,714.734 +/- 2,515.497 us/op`, while
parallel coordination measured `25,905.557 +/- 2,978.765 us/op`. The 99.9% intervals do not overlap and the parallel
path was 38.4% slower. GC profiling of the same shape also increased contextual allocation from `24,348,705.752 B/op`
to `33,932,152.739 B/op` (39.4%).

Because a universal threshold at or below 128 would select this non-beneficial contextual path, no threshold in the
tested range is eligible for automatic production selection. `DEFAULT_PARALLELISM_THRESHOLD` is therefore
`Integer.MAX_VALUE`, and automatic backend selection is deferred to the evidence-driven policy planned for Issue #29.
Contextual parallelism remains available only through the explicit `EXPERIMENTAL_PARALLEL` mode. The executable matrix
and raw-result conventions are documented in the benchmark methodology.
