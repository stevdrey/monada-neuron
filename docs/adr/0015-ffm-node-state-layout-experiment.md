# ADR 0015: Experimental FFM Off-Heap Node State Layout and Structure-of-Arrays Evaluation

## Status

Accepted (Experimental / Evaluation Only)

## Context

Monada Neuron's cognitive core represents nodes as rich domain objects (`Node`), each possessing an immutable UUID identity, role classification (`NodeType`), unmodifiable graph adjacency (`Set<Node>`), mutable `FrequencyState` (amplitude, frequency, phase), scalar `energy`, and lazily recorded transition history.

At massive topology scales (100,000 to 1,000,000 nodes), object-graph memory overhead, pointer indirection, and garbage collection tracing pressure become architectural concerns. The wave-state payload required for hot mathematical operations consists of four `double` values per node (amplitude, frequency, phase, energy), totaling 32 bytes of contiguous numeric data per node.

Issue #27 investigated whether off-heap native memory via Java 26's Foreign Function & Memory (FFM) API (`java.lang.foreign`) or contiguous on-heap Structure-of-Arrays (SoA) layouts improve cache locality, eliminate GC ownership of large state buffers, and provide measurable throughput benefits compared to the object-rich baseline.

## Decision

1. **Compiled Snapshot Model**: `NodeStateSnapshot` provides a compiled, closed, UUID-sorted dense index view of canonical `Node` instances. UUID sorting ensures deterministic index assignment across executions. The snapshot copies only the selected four state channels into an isolated `NodeStateStore`. Source `Node` instances, topology, history, and domain semantics remain unaltered on the heap.
2. **Four-Channel Structure-of-Arrays (SoA) Layout**: State is structured into four separate, contiguous channels (`amplitudes`, `frequencies`, `phases`, `energies`), each sized to the node count. This layout maximizes spatial locality and vectorization potential during sequential scans.
3. **Portable On-Heap Control (`HeapNodeStateStore`)**: An on-heap SoA implementation using four primitive `double[]` arrays serves as the deterministic reference oracle, portable fallback, and direct comparison control for FFM evaluation.
4. **FFM Native Memory Store (`FfmNodeStateStore`)**: An off-heap SoA implementation allocates four naturally aligned `MemorySegment` channels in native byte order within a single confined `Arena` (`Arena.ofConfined()`). Dense access uses `getAtIndex` / `setAtIndex` operations without allocating slice wrappers in hot paths.
5. **Memory Ownership, Confinement, and Lifecycle**:
   - `FfmNodeStateStore` is strictly confined to its creating thread.
   - Resource cleanup is explicit via `AutoCloseable.close()`.
   - To guarantee safety and prevent memory leaks, `arena.close()` is invoked before marking the store closed (`open = false`). If a non-owner thread attempts cleanup, `WrongThreadException` is propagated while preserving the open state, enabling the owner thread to subsequently perform cleanup.
   - All state access methods enforce `requireOpen` checks to prevent use-after-close bugs.
6. **Domain Boundary Preservation**: Internal dense integer coordinates and FFM types (`Arena`, `MemorySegment`, `ValueLayout`) do not escape the `monada.neuron.runtime.state` package. Cognitive identity remains rooted in `UUID` and domain `NodeView` abstractions.
7. **Experimental Retention and Non-Promotion Policy**:
   - FFM is retained as an experimental layout and benchmark harness under `monada.neuron.runtime.state` and `monada.neuron.evaluation`.
   - **FFM is NOT promoted to an automatic or default runtime backend.**
   - Empirical measurements demonstrate that the contiguous Structure-of-Arrays layout (`HeapNodeStateStore`) provides the primary cache locality and read/update throughput speedups over the object model. FFM successfully shifts the 32-byte state payload off-heap (committing 3.20 MB at 100k nodes and 32.00 MB at 1M nodes outside the GC heap), but introduces thread-confinement restrictions without yielding a throughput or latency advantage over on-heap primitive arrays.

## Alternatives Considered

### Replace Domain `Node` Fields Directly with Off-Heap Memory

Rejected. Replacing `Node`'s Java object fields with native memory pointers would destroy domain ergonomics, break immutable UUID identity semantics, complicate debugging, and introduce dangerous manual memory management into the cognitive core.

### Single-Segment per Node / Array-of-Structures (AoS)

Rejected. Allocating an individual off-heap `MemorySegment` per node introduces massive allocation overhead and eliminates the spatial locality benefits of contiguous layout. Interleaved AoS layouts are less cache-friendly for vectorized channel sweeps than SoA.

### Shared Off-Heap Arena (`Arena.ofShared()`)

Deferred. While a shared arena allows multi-threaded access and closing from any thread, it introduces atomic synchronization overhead on segment boundary checks. Single-threaded confined execution represents the optimal performance baseline for this evaluation.

### Promote FFM as Default Graph Propagation Storage

Rejected based on empirical evidence. In sequential and random reads, `HeapNodeStateStore` matches or outperforms `FfmNodeStateStore` while retaining full portability and avoiding thread-confinement friction.

## Consequences

- The project gains a clear, measurable off-heap state layout capability with zero memory leaks and deterministic lifecycle safety.
- The cognitive core remains 100% portable and independent of native platform flags or off-heap memory requirements.
- Future memory compaction efforts can adopt the on-heap SoA layout (`HeapNodeStateStore`) as a proven performance improvement for state storage.
- Benchmarks and cognitive baseline tools maintain reproducible, side-by-side comparison evidence across Object, Heap SoA, and FFM layouts.

## Follow-up Work

- Re-evaluate FFM if GC pressure or total heap occupancy becomes material at larger production scales or memory-constrained deployments.
- Compare `Arena.ofShared()` only when parallel state access and cross-thread mutation become necessary.
- Keep `HeapNodeStateStore` as the preferred compact state candidate until FFM shows a measurable end-to-end advantage.
- Re-benchmark on future JDK upgrades or material FFM implementation and compiler optimization changes.
