# ADR 0017: Runtime Optimized Backend Selection and Fallback

## Status

Accepted

## Context

Issues #25 through #28 conducted empirical, benchmark-driven evaluations for candidate optimized execution paths in Monada Neuron:

1. **Issue #25 (ADR 0013)** evaluated the Java 26 Vector API for batch resonance scoring. The SIMD backend demonstrated 6.2x to 6.7x speedups on workloads with $\ge 4$ pairs and negligible allocation ($< 0.0001$ B/pair), establishing an empirical crossover threshold of 4 pairs.
2. **Issue #26 (ADR 0014)** evaluated compact CSR graph representation and propagation (`CompactSignalPropagationEngine`). Although RouteAll traversal improved throughput, it failed the pre-merge gate of 75% allocation reduction (achieving 65.4%–70.9%) and showed slower performance on threshold-routed workloads due to setup overhead. ADR 0014 therefore retained CSR as an explicit opt-in view rather than a default runtime backend.
3. **Issue #27 (ADR 0015)** evaluated Foreign Function & Memory (FFM) off-heap state layouts (`FfmNodeStateStore`). The evaluation showed that on-heap Structure-of-Arrays (`HeapNodeStateStore`) provides the primary locality benefits without thread-confinement friction. Consequently, FFM was retained as an experimental layout and **not promoted** to an automatic runtime backend.
4. **Issue #28 (ADR 0016)** evaluated bounded parallel multi-input Aeon coordination (`BoundedParallelAeonCoordinator`). The PR #38 validation gate revealed that contextual parallel coordination was 38.4% slower and incurred 39.4% higher allocation than the sequential oracle due to wave reconciliation and recording overhead. Thus, automatic contextual parallel selection was rejected, and the sequential oracle was preserved as the default.

Without a unified runtime selection policy, these capabilities would risk becoming ad-hoc configuration flags or causing incubator/native implementation details to leak into cognitive code. Monada Neuron requires a single, explicit, deterministic, and inspectable selection layer that preserves the portable reference oracle as the default safety baseline while selecting optimized backends only when supported, semantically eligible, and beneficial for the workload.

## Decision

1. **Explicit Backend Identifiers & Contracts**:
   - Define a sealed interface `BackendId` with component-specific enums:
     - `ResonanceBackendId`: `SCALAR` (reference), `VECTOR_API`
     - `GraphBackendId`: `DETERMINISTIC_OBJECT` (reference), `COMPACT_CSR`
     - `AeonBackendId`: `DETERMINISTIC_SEQUENTIAL` (reference), `BOUNDED_PARALLEL`
     - `StateBackendId`: `HEAP_OBJECT` (reference), `HEAP_SOA`, `FFM_OFF_HEAP` (experimental)
   - Cognitive domain contracts (`BatchResonanceEvaluator`, `CognitiveSignalPropagationEngine`, `CognitiveAeonCoordinator`) remain backend-neutral and clean of hardware or provider types.

2. **Immutable Selection Configuration (`RuntimeSelectionConfig`)**:
   - Master configuration record controlling execution preferences and fallback behavior:
     - `ExecutionPreference`: `REFERENCE`, `AUTO`, `EXPLICIT`.
     - `FallbackPolicy`: `FALLBACK_TO_REFERENCE` vs `FAIL_FAST`.
     - Component sub-configurations: `ResonanceSelectionConfig`, `GraphSelectionConfig`, `AeonSelectionConfig`.
   - `RuntimeSelectionConfig.referenceDefault()` provides a guaranteed safe reference baseline across all components.
   - `RuntimeSelectionConfig.forcedReference()` provides an explicit forced reference mode for testing and numerical/semantic equivalence verification.

3. **Deterministic, Benchmark-Derived `AUTO` Selection**:
   - **Resonance**: Workloads with $batchSize \ge 4$ select `VECTOR_API` if the Java 26 Vector API incubator module is present; smaller workloads or environments lacking incubator flags route to `SCALAR`. For `FrequencyState[]` object arrays, the threshold is bounded at 32 pairs to reflect `VectorBatchResonanceEvaluator`'s internal chunking floor, avoiding scalar tail overhead on 4–31 element object batches. For primitive SoA arrays, the threshold is bounded by the detected CPU vector lane width.
   - **Graph Propagation**: In `AUTO`, graph propagation always routes to `DETERMINISTIC_OBJECT` (reference BFS). `COMPACT_CSR` remains explicit and opt-in per ADR 0014, and validates that `startNode` is a canonical member of the compiled topology snapshot via `containsCanonical(Node)`.
   - **Aeon Coordination**: Contextual coordination in `AUTO` **always** selects `DETERMINISTIC_SEQUENTIAL`, strictly enforcing the negative empirical result from ADR 0016. Direct parallel coordination defaults to sequential unless the direct threshold is explicitly set below `Integer.MAX_VALUE`, $inputCount \ge threshold$, workload is independent read-only, and available processors $> 1$.
   - **Node State**: Remembers `HEAP_OBJECT` as reference default; FFM is experimental and excluded from `AUTO`.

4. **Predictable Fallback Semantics**:
   - If an explicitly requested backend is unavailable or semantically ineligible:
     - Under `FallbackPolicy.FAIL_FAST`, throws `BackendUnavailableException` or `BackendIneligibleException`. Initialization failures (e.g. reflection or class loading defects) are preserved with their root causes rather than masked as absent capabilities.
     - Under `FallbackPolicy.FALLBACK_TO_REFERENCE`, safely falls back to the reference oracle while capturing the exact diagnostic reason in `SelectionDiagnostic`.
   - Never silently guesses or swallows configuration defects: `EXPLICIT` preference strictly requires a non-empty `explicitBackend`.

5. **Inspectable Diagnostics (`SelectionDiagnostic`)**:
   - Every selection emits an immutable `SelectionDiagnostic` detailing `selectedBackendId`, `reason` (`SelectionReason`), `workloadScale`, `isFallback`, `fallbackReason`, and defensively copied component metadata (`Map.copyOf`).
   - Paired with operational instances via `BackendSelection<T, B>`.
   - In data-plane adapters (`SelectingBatchResonanceEvaluator`), selection decisions and diagnostics are cached by threshold regime to eliminate allocation and volatile writes in hot loops.

6. **Static Capability Caching and Negligible Control-Plane Overhead**:
   - `BackendCapabilities` caches JVM and hardware detection once at startup.
   - JMH benchmarks (`BackendSelectionBenchmark`) confirm control-plane selection executes in **18–97 nanoseconds** with zero per-element allocation.

## Empirical Evidence

Microbenchmark results from `BackendSelectionBenchmark` executed on Java 26 (Temurin 26.0.2.1+1, Linux amd64, AVX2):

| Benchmark Operation | Workload Scale | Average Latency | Decision |
| :--- | ---: | ---: | :--- |
| `benchmarkResonanceSelectionAuto` | 4 pairs | 18.7 ns | `VECTOR_API` (`AUTO_THRESHOLD_MET`) |
| `benchmarkResonanceSelectionAuto` | 64 pairs | 22.7 ns | `VECTOR_API` (`AUTO_THRESHOLD_MET`) |
| `benchmarkResonanceSelectionAuto` | 1,000 pairs | 22.8 ns | `VECTOR_API` (`AUTO_THRESHOLD_MET`) |
| `benchmarkResonanceSelectionReference` | 1,000 pairs | 36.9 ns | `SCALAR` (`FORCED_REFERENCE`) |
| `benchmarkGraphSelection` | 50 nodes | 67.6 ns | `DETERMINISTIC_OBJECT` (`REFERENCE_DEFAULT`) |
| `benchmarkAeonSelection` | 64 inputs | 95.9 ns | `DETERMINISTIC_SEQUENTIAL` (`AUTO_BELOW_THRESHOLD`) |
| `benchmarkDirectScalarBatch` | 1,000 pairs | 73,382.1 ns | Raw scalar reference execution |
| `benchmarkSelectingBatch` | 1,000 pairs | 15,515.2 ns | Dynamically selected SIMD (4.7x speedup end-to-end) |

Selection control-plane latency is bounded under 100 ns, completely negligible relative to operational workloads.

## Consequences

- The system has a single, cohesive, inspectable control-plane policy governing optimized backends.
- Portable reference implementations remain first-class, always selectable, and easily tested against optimized paths.
- Unsupported or experimental backends (like FFM or contextual parallel coordination) are prevented from being mistakenly selected in production.
- Developers and diagnostic tools can inspect exactly why a backend was selected or why fallback occurred.

## Alternatives Considered

- **Global mutable singletons or static flags:** Rejected because global mutable state harms test isolation, prevents multi-tenant or contextual configuration, and complicates concurrency.
- **Reflection-heavy plugin discovery:** Rejected because classpath scanning adds startup latency, creates non-deterministic discovery order, and violates explicit architectural boundaries.
- **Machine learning auto-tuning / dynamic online re-benchmarking:** Rejected as out-of-scope non-goals for Phase 1. Static empirical thresholds derived from reproducible JMH benchmarks provide predictable and reproducible behavior.

## Follow-up Work

- Update documentation and benchmark methodologies with the supported backend matrix.
- Re-evaluate thresholds when upgrading the JDK or when incubating APIs (Vector API) or experimental backends transition to standard production features.
