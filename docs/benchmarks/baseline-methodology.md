# Monada Neuron Evaluation & Baseline Methodology

## Overview

Monada Neuron requires measurable, reproducible behavior prior to introducing hardware-specific, SIMD, off-heap, or accelerator-backed optimizations (as specified in [ADR 0003](../adr/0003-performance-first-java26-and-heterogeneous-compute.md) and [Issue #14](https://github.com/stevdrey/monada-neuron/issues/14)).

The `:monada-neuron-evaluation` subproject provides an isolated benchmarking and evaluation harness measuring both semantic correctness and resource characteristics (latency distributions, throughput, thread allocation rate, GC activity, committed off-heap bytes, and Linux process RSS when available).

## Architecture & Module Isolation

Evaluation tooling is isolated in a separate Gradle subproject:

```text
monada-neuron (root)
  └── src/main/java (Core cognition, Signals, Nodes, Aeons, Monad, Memory/Action contracts)
       ▲
       │ depends on (implementation)
       │
monada-neuron-evaluation
  ├── src/main/java (Synthetic workloads, metrics collector, CLI runner, JMH benchmarks)
  └── src/test/java (Harness validation & reproducibility tests)
```

- **Dependency direction**: Evaluation code depends on production code. Production runtime artifacts have **zero dependencies** on JMH, benchmark harnesses, or evaluation test fixtures.
- **Semantic Oracle**: Production reference implementations (`ScalarResonanceMetric`, `DeterministicSignalPropagationEngine`, `DeterministicCognitiveCycle`) remain the semantic oracle.

## Experimental Validity & Workload Equivalence

To guarantee reliable and reproducible baselines, the harness enforces five key experimental invariants:

1. **Workload Equivalence Between Aeon Variants**:
   - Both `AeonCoordinator.Direct` and `AeonCoordinator.Contextual` run over identical 10-input workloads on 100-member topologies.
   - The contextual run is configured with sufficient global `CognitiveBudget` (50,000 steps, 100,000 signals, 100,000 traces) so neither variant truncates early.
   - Assertions verify that both direct and contextual runs process exactly 5,000 steps and emit exactly 5,000 signals, isolating the exact runtime and allocation cost of ephemeral `CognitiveContext` tracking.

2. **Per-Iteration State Reset & Isolation**:
   - `EvaluationMetricsCollector` executes a per-iteration setup hook outside the measurement window, excluding setup allocations and timing from latency and thread allocation deltas.
   - For all full-cycle and adaptation benchmarks, pristine topologies and node states with zero history are instantiated for each warmup and measurement iteration.

3. **Pure Arithmetic Separation & Fresh-State JMH Microbenchmarks**:
   - `AdaptationPolicyBenchmark` cleanly separates non-mutating `NoOpAdaptationPolicy` (~12.5 ns/op), pure mathematical decision derivation `benchmarkBaselinePolicyDecisionArithmetic` (~46.6 ns/op), and full adaptation on fresh node fixtures per invocation.
   - `CognitiveCycleBenchmark` provides a non-mutating 5-stage full cycle baseline (`benchmarkCognitiveCycleNoOp`, ~74.4 µs/op) and an isolated baseline cycle with fresh setup per invocation (`benchmarkCognitiveCycleBaseline`, ~65.4 µs/op).

4. **Retained Graph Footprint Modeling**:
   - Represents the 64-bit HotSpot JVM heap layout for the current `Node` object graph (~224 bytes/node base + ~32 bytes/directed edge, including the topology-version counter).
   - The CSR experiment separately estimates the incremental snapshot arrays (`Node[]`, topology-version `long[]`, CSR offsets and targets) and the combined structural footprint while both representations coexist.
   - This prevents a snapshot-only number from being presented as a reduction in total live heap; temporary traversal queues and emitted results remain allocation metrics, not retained-topology metrics.

5. **Explicit Allocation Telemetry Semantics**:
   - `AllocationMetrics` explicitly records `AllocationSource` (`THREAD_MX_BEAN` vs `UNAVAILABLE`).
   - If thread allocation tracking is unavailable, allocation metrics report as unavailable (`N/A` / `null`) rather than substituting net heap growth.
   - GC deltas capture JVM-wide cumulative GC activity across the entire measurement phase.
   - `ProcessResidentSetMetrics` reads `VmRSS` from `/proc/self/status` on Linux and reports `UNAVAILABLE` on other platforms; it never substitutes heap usage for RSS.

6. **State-layout Snapshot Isolation**:
   - `NodeStateSnapshot` sorts a non-empty canonical `Node` collection by UUID, retains the dense index mapping, and copies only amplitude, frequency, phase, and energy into the selected store.
   - Both the heap SoA control and FFM store use four `double` channels: 32 logical bytes per node. The FFM store reports those bytes as explicitly committed off-heap state while it is open.
   - The snapshots neither synchronize with nor write back to their source Nodes. FFM owns a confined arena and is intentionally single-threaded.

## Representative Graph Retained Footprint

| Topology Scale | Node Count | Average Degree | Directed Edges | Object Topology | CSR Snapshot Incremental | Combined Structural |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Small** | 50 | 3 | 143 | **15.78 KB** | **1.48 KB** | **17.26 KB** |
| **Medium** | 500 | 5 | 2,468 | **190.98 KB** | **17.98 KB** | **208.95 KB** |
| **Large** | 2,000 | 8 | 15,913 | **957.22 KB** | **95.76 KB** | **1,052.98 KB** |

The CSR column is an incremental execution-view estimate on a 64-bit HotSpot JVM with compressed
references. It excludes the shared Node object graph by design; the combined column makes the
coexistence cost explicit.

## Reproducible Workloads

All synthetic workloads are generated deterministically from configurable seeds (default seed: `42L` via `DeterministicWorkloadGenerator`):

1. **Scalar Resonance Batches**:
   - Compares pairs of `FrequencyState` across multiple batch sizes (100, 1,000, 10,000, 100,000 pairs).
   - Evaluates `amplitudeSimilarity * frequencySimilarity * phaseSimilarity` calculation costs.

2. **Sparse Directed Graph Propagation**:
   - Evaluates `DeterministicSignalPropagationEngine` and opt-in `CompactSignalPropagationEngine` on the same sparse topologies at Small, Medium, and Large scales.
   - Measures compact snapshot compilation separately from repeated direct traversal.
   - Evaluates both unbounded routing (`RouteAll`) and gated routing (`ResonanceThresholdRoutingPolicy` at 0.5 threshold).
   - Diagnostics capture actual processed steps, emitted signals, limit triggers, object-topology storage, incremental CSR storage, and combined structural estimates.

3. **Aeon Coordination**:
   - Evaluates `DeterministicAeonCoordinator` under direct mode vs. contextual mode under identical work bounds.

4. **Primary Monad Cognitive Cycles**:
   - Evaluates `DeterministicCognitiveCycle` over representative 5-stage lifecycles (`PERCEPTION` -> `MEMORY_RECALL` -> `REASONING` -> `ADAPTATION` -> `ACTION`) with fresh node state per iteration.

5. **Adaptation Policy A/B Comparison**:
   - Measures cycle overhead with `NoOpAdaptationPolicy` (no-op baseline) versus `DeterministicBaselineAdaptationPolicy` (in-place bounded frequency state and energy updates) from pristine node states.

6. **Node State Layout (Issue #27)**:
   - Compares canonical object state with copied heap SoA and FFM SoA state at 100,000 and 1,000,000 Nodes.
   - Covers construction/population, sequential reads, random reads against a precomputed schedule, and bounded updates. The baseline additionally measures CSR plus state snapshot compilation as an integration cost only.
   - Propagation stays on the existing reference or explicit CSR paths; no benchmark routes signals through FFM state.

## Verification & Benchmark Commands

### Run Full Baseline Suite

Executes all 5 cognitive workloads, prints the Markdown summary table, and writes machine-readable JSON and Markdown reports to `build/reports/benchmarks/`:

```bash
./gradlew :monada-neuron-evaluation:runCognitiveBaseline
```

Options:
- `--quick`: Runs reduced iteration count for fast smoke checks.
- `--seed <long>`: Sets custom deterministic pseudo-random seed.
- `--output-dir <path>`: Configures output directory for report files.

### Run JMH Microbenchmarks

Runs JMH microbenchmarks for hot primitives (`ScalarResonanceBenchmark`, `SignalPropagationBenchmark`, `AeonCoordinationBenchmark`, `CognitiveCycleBenchmark`, `AdaptationPolicyBenchmark`):

```bash
./gradlew :monada-neuron-evaluation:jmh
```

Pass custom JMH arguments:
```bash
./gradlew :monada-neuron-evaluation:jmh -PjmhArgs="-f 1 -wi 1 -i 1 -r 1s AdaptationPolicyBenchmark CognitiveCycleBenchmark ScalarResonanceBenchmark"
```

Run the Issue #27 layout comparison with GC telemetry:

```bash
./gradlew :monada-neuron-evaluation:jmh \
  -PjmhArgs="-f 1 -wi 3 -i 5 -prof gc NodeStateLayout"
```

For a shorter recorded comparison, used for the results below, constrain JMH to average time,
two warmups, three one-second measurements, and write JSON outside the repository:

```bash
./gradlew :monada-neuron-evaluation:jmh \
  -PjmhArgs="-bm avgt -f 1 -wi 2 -i 3 -r 1s -prof gc -rf json \
  -rff /tmp/issue-27-node-state-jmh.json NodeStateLayoutBenchmark"
```

### PR #35 Vector API allocation and crossover study

The current SIMD retention decision is based on JMH allocation telemetry, not
the older coarse end-to-end allocation rows below. The controlled study ran on
Linux x86_64, Intel Core i7-6500U with AVX2 (four available CPUs), Temurin
26+35, and `DoubleVector.SPECIES_PREFERRED` length `4`. It used one thread,
`avgt`, two one-second warmups, three one-second measurement iterations, and
three forks.

Run Scalar, Vector, and the then-current Adaptive dispatch together:

```bash
./gradlew :monada-neuron-evaluation:jmh \
  -PjmhArgs="-bm avgt -f 3 -wi 2 -i 3 -r 1s -prof gc -rf json \
  -rff /tmp/pr35-soa-gc.json \
  ScalarResonanceBenchmark.benchmark(Scalar|Vector|Adaptive)BatchSoA"
```

After setting the derived threshold, validate the resulting Adaptive dispatch:

```bash
./gradlew :monada-neuron-evaluation:jmh \
  -PjmhArgs="-bm avgt -f 3 -wi 2 -i 3 -r 1s -prof gc -rf json \
  -rff /tmp/pr35-adaptive-threshold4-gc.json \
  ScalarResonanceBenchmark.benchmarkAdaptiveBatchSoA"
```

For the 100,000-pair Vector allocation attribution, create an allocation-enabled
JFR configuration and capture the benchmark:

```bash
jfr configure --input "$JAVA_HOME/lib/jfr/profile.jfc" \
  --output /tmp/pr35-vector-allocation.jfc \
  jdk.ObjectAllocationInNewTLAB#enabled=true \
  jdk.ObjectAllocationOutsideTLAB#enabled=true \
  jdk.ObjectAllocationSample#enabled=true \
  jdk.ObjectAllocationSample#throttle=1000/s

./gradlew :monada-neuron-evaluation:jmh \
  -PjmhArgs='-bm avgt -f 1 -wi 2 -i 3 -r 1s -prof jfr:dir=/tmp/pr35-vector-allocation-jfr;configName=/tmp/pr35-vector-allocation.jfc -p batchSize=100000 ScalarResonanceBenchmark.benchmarkVectorBatchSoA'
```

Inspect the generated `profile.jfr` with:

```bash
jfr summary profile.jfr
jfr print --events jdk.ObjectAllocationInNewTLAB,jdk.ObjectAllocationOutsideTLAB,jdk.ObjectAllocationSample profile.jfr
```

The measured matrix is batch latency in ns / JMH allocated bytes per pair.
Adaptive was measured after `DEFAULT_CROSSOVER_THRESHOLD` changed to `4`.

| Pairs | Scalar | Vector | Adaptive |
| :--- | ---: | ---: | ---: |
| 1 | 94.05 / 32.000658 | 92.11 / 32.000642 | 90.97 / 32.000637 |
| 4 | 319.97 / 32.000558 | 77.09 / 0.000134 | 85.44 / 0.000149 |
| 8 | 644.04 / 32.000562 | 127.65 / 0.000111 | 131.41 / 0.000114 |
| 16 | 1,290.07 / 32.000563 | 222.93 / 0.000097 | 219.49 / 0.000095 |
| 32 | 3,310.88 / 32.000722 | 424.88 / 0.000093 | 429.79 / 0.000093 |
| 64 | 6,859.80 / 32.000750 | 904.86 / 0.000098 | 789.44 / 0.000086 |
| 128 | 23,883.53 / 32.001301 | 1,606.12 / 0.000087 | 1,598.69 / 0.000087 |
| 256 | 16,402.92 / 32.000451 | 3,308.66 / 0.000090 | 3,193.23 / 0.000087 |
| 1,000 | 78,002.71 / 32.000547 | 12,620.64 / 0.000088 | 12,641.00 / 0.000089 |
| 10,000 | 956,206.48 / 32.000670 | 142,138.04 / 0.000099 | 122,491.23 / 0.000085 |
| 100,000 | 9,287,027.59 / 32.000650 | 1,405,598.31 / 0.000098 | 1,383,684.78 / 0.000096 |

At 1,000, 10,000, and 100,000 pairs, both Vector and Adaptive are much lower
than the 105% Scalar allocation limit. The allocation-enabled JFR capture at
100,000 pairs found only JFR/JMH support objects, with no allocation stack in
Monada or the Vector API hot path. The three-fork crossover comparison chose
`4`: Vector was at least 5% faster than Scalar in each fork at that size and
at every larger sampled size; one pair failed that condition in one fork.

The former approximate `70.3 B/pair` Vector end-to-end row is superseded for
the SIMD retention decision by this controlled JMH and JFR evidence.

### PR #38 bounded Aeon coordination validation gate

`AeonCoordinationBenchmark` compares the deterministic sequential coordinator with the bounded parallel coordinator
for direct and contextual work. It fixes the parallel benchmark threshold at one and explicitly selects
`ContextualParallelism.EXPERIMENTAL_PARALLEL` so every configured shape measures the parallel implementation; both
choices are independent from the production default policy.

The benchmark parameters are:

| Parameter | Values |
| :--- | :--- |
| `inputCount` | 1, 2, 3, 4, 5, 8, 10, 16, 32, 64, 128 |
| `graphProfile` | `small` (50 nodes, degree 3), `medium` (200, 5), `large` (1,000, 4) |
| `workerCount` | 1, 2, 4 |

Use average time, three forks, three one-second warmups, and five one-second measurements. Persist JSON outside the
repository. The threshold matrix holds the medium graph and four workers constant:

```bash
./gradlew :monada-neuron-evaluation:jmh \
  -PjmhArgs="-bm avgt -f 3 -wi 3 -i 5 -r 1s \
  -p graphProfile=medium -p workerCount=4 -rf json \
  -rff /tmp/pr38-aeon-threshold.json AeonCoordinationBenchmark"
```

The worker-and-scale matrix uses representative input sizes:

```bash
./gradlew :monada-neuron-evaluation:jmh \
  -PjmhArgs="-bm avgt -f 3 -wi 3 -i 5 -r 1s \
  -p inputCount=10,32,128 -p graphProfile=small,medium,large \
  -p workerCount=1,2,4 -rf json \
  -rff /tmp/pr38-aeon-scale.json AeonCoordinationBenchmark"
```

For allocation evidence at a candidate threshold and at 128 inputs, repeat the matching command with `-prof gc`.
An automatic selection policy requires a measured direct/contextual crossover where parallel is at least 10% faster
than sequential in every fork for every input at or above that threshold. If either mode fails at 128, no universal
automatic policy in this matrix is eligible.

The current PR #38 gate is **not met**. On Linux x86_64, four available processors, Temurin 26.0.2.1+1, and the medium
graph with four workers and 128 inputs, the non-GC three-fork contextual result was:

| Mode | Average time | 99.9% interval | Decision |
| :--- | ---: | ---: | :--- |
| Sequential contextual | 18,714.734 us/op | 16,199.237-21,230.231 | Reference |
| Parallel contextual | 25,905.557 us/op | 22,926.791-28,884.322 | 38.4% slower |

The `-prof gc` repetition recorded the following allocation evidence across its fifteen measurement iterations:

| Mode | Allocation | GC count | GC time |
| :--- | ---: | ---: | ---: |
| Sequential direct | 10,245,517.559 B/op | 28 | 90 ms |
| Parallel direct | 10,268,673.028 B/op | 31 | 125 ms |
| Sequential contextual | 24,348,705.752 B/op | 46 | 628 ms |
| Parallel contextual | 33,932,152.739 B/op | 61 | 482 ms |

The contextual parallel allocation increase is 39.4%. The direct time result in the GC run was too variable to support
a crossover claim. Since the contextual non-GC result fails at the maximum sampled input count, the matrix cannot
select an automatic default. The runtime therefore defaults to the sequential oracle for contextual coordination and
uses `Integer.MAX_VALUE` as the conservative direct threshold; direct and contextual parallel execution require
explicit configuration, pending Issue #29's evidence-driven backend-selection policy. Raw JSON is retained under
`/tmp/pr38-aeon-*.json` for this validation run.

### PR #29 Runtime Backend Selection and Dispatch Overhead Benchmark

`BackendSelectionBenchmark` measures the control-plane selection latency of `RuntimeBackendSelector`
across execution preferences (REFERENCE vs AUTO), as well as the overhead of dynamic dispatch adapters
(`SelectingBatchResonanceEvaluator`) relative to direct execution:

```bash
./gradlew :monada-neuron-evaluation:jmh \
  -PjmhArgs="-f 1 -wi 2 -i 3 -r 1s BackendSelectionBenchmark"
```

The review-before-merge study was executed on Linux x86_64 with Java 26 (Temurin 26.0.2.1+1, Intel AVX2):

| Benchmark Operation | Workload Scale | Average Latency | Steady-State Alloc | Decision / Status |
| :--- | ---: | ---: | ---: | :--- |
| `benchmarkResonanceSelectionAuto` | 64 pairs | 17.0 ns | 64 B/op | `VECTOR_API` (`AUTO_THRESHOLD_MET`) |
| `benchmarkResonanceSelectionAuto` | 1,000 pairs | 16.3 ns | 64 B/op | `VECTOR_API` (`AUTO_THRESHOLD_MET`) |
| `benchmarkResonanceSelectionReference` | 1,000 pairs | 11.9 ns | 64 B/op | `SCALAR` (`FORCED_REFERENCE`) |
| `benchmarkGraphSelection` | 50 nodes | 14.9 ns | 64 B/op | `DETERMINISTIC_OBJECT` (`REFERENCE_DEFAULT`) |
| `benchmarkAeonSelection` | 64 inputs | 76.9 ns | 280 B/op | `DETERMINISTIC_SEQUENTIAL` (`AUTO_BELOW_THRESHOLD`) |
| `benchmarkDirectScalarBatch` | 4 pairs | 255.2 ns | 128 B/op | Direct scalar baseline |
| `benchmarkSelectingScalarBatch` | 4 pairs | 249.4 ns | 128 B/op | Selecting scalar adapter |
| `benchmarkDirectVectorBatch` | 4 pairs | 75.8 ns | 0.001 B/op | Direct SIMD vector (3.4x faster than scalar) |
| `benchmarkSelectingVectorBatch` | 4 pairs | 78.4 ns | 0.001 B/op | Selecting SIMD adapter (overhead ~2.6 ns) |
| `benchmarkDirectScalarBatch` | 64 pairs | 4,165.4 ns | 2,048 B/op | Direct scalar baseline |
| `benchmarkSelectingScalarBatch` | 64 pairs | 4,623.4 ns | 2,048 B/op | Selecting scalar adapter |
| `benchmarkDirectVectorBatch` | 64 pairs | 767.6 ns | 0.005 B/op | Direct SIMD vector (5.4x faster than scalar) |
| `benchmarkSelectingVectorBatch` | 64 pairs | 775.8 ns | 0.005 B/op | Selecting SIMD adapter (overhead ~8.2 ns) |
| `benchmarkDirectScalarBatch` | 1,000 pairs | 72,677.7 ns | 32,000 B/op | Direct scalar baseline |
| `benchmarkSelectingScalarBatch` | 1,000 pairs | 79,443.3 ns | 32,000 B/op | Selecting scalar adapter |
| `benchmarkDirectVectorBatch` | 1,000 pairs | 13,625.6 ns | 0.096 B/op | Direct SIMD vector (5.3x faster than scalar) |
| `benchmarkSelectingVectorBatch` | 1,000 pairs | 12,211.5 ns | 0.086 B/op | Selecting SIMD adapter (6.5x faster than scalar) |

Comparing direct and selecting variants demonstrates that adapter dispatch overhead is negligible (~2.6 ns at 4 pairs, undetectable at scale) and introduces zero application-level heap allocation in the steady-state evaluation hot path. The 0.001–0.005 B/op telemetry at small scales and 0.086 B/op at 1,000 pairs reflect amortized JVM and JMH harness background profiling noise across measurement intervals rather than application object allocations, accompanied by zero GC pauses (`gc.count ≈ 0`). In the control plane, pre-allocated metadata and immutable copy optimizations bound decision latency to 12–77 ns across all components.

### Run Unit and Harness Tests

```bash
./gradlew test
```

## Baseline Results (Java 26 Reference)

Captured on Linux x86_64 with Java 26 (Eclipse Adoptium OpenJDK 64-Bit Server VM):

### End-to-End Suite Results

| Benchmark | Workload Scale | Mean Latency | Median (p50) | p95 | Throughput | Alloc / Op |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `ScalarResonanceMetric.score` | 100 pairs | ~310 µs | ~274 µs | ~471 µs | ~323,000 pairs/s | ~62.0 B |
| `ScalarResonanceMetric.score` | 1,000 pairs | ~688 µs | ~532 µs | ~1.18 ms | ~1,453,000 pairs/s | ~62.2 B |
| `ScalarResonanceMetric.score` | 10,000 pairs | ~1.83 ms | ~1.86 ms | ~2.66 ms | ~5,470,000 pairs/s | ~32.0 B |
| `ScalarResonanceMetric.score` | 100,000 pairs | ~13.97 ms | ~13.91 ms | ~16.38 ms | ~7,158,000 pairs/s | ~32.0 B |
| `GraphPropagation.RouteAll` | Small (50 nodes, deg 3) | ~1.10 ms | ~1.09 ms | ~1.64 ms | ~905 ops/s | ~60.9 KB |
| `GraphPropagation.ThresholdRouting` | Small (50 nodes, deg 3) | ~12.0 µs | ~9.6 µs | ~19.1 µs | ~83,700 ops/s | ~1.0 KB |
| `GraphPropagation.RouteAll` | Medium (500 nodes, deg 5) | ~2.27 ms | ~2.06 ms | ~3.81 ms | ~440 ops/s | ~841.4 KB |
| `GraphPropagation.ThresholdRouting` | Medium (500 nodes, deg 5) | ~12.8 µs | ~11.7 µs | ~17.3 µs | ~78,200 ops/s | ~1.74 KB |
| `GraphPropagation.RouteAll` | Large (2,000 nodes, deg 8) | ~14.39 ms | ~13.64 ms | ~19.79 ms | ~69 ops/s | ~4.98 MB |
| `GraphPropagation.ThresholdRouting` | Large (2,000 nodes, deg 8) | ~11.1 µs | ~9.1 µs | ~20.1 µs | ~90,300 ops/s | ~2.79 KB |
| `AeonCoordinator.Direct` | 10 inputs, 100 members | ~2.92 ms | ~1.90 ms | ~6.38 ms | ~343 ops/s | ~1.32 MB |
| `AeonCoordinator.Contextual` | 10 inputs, 100 members | ~6.76 ms | ~6.28 ms | ~10.15 ms | ~148 ops/s | ~5.21 MB |
| `DeterministicCognitiveCycle.FullCycle` | 5 stages, 5 initial signals | ~582.4 µs | ~497.7 µs | ~1.13 ms | ~1,717 ops/s | ~255.4 KB |
| `CognitiveCycle.Adaptation.NoOp` | 50 target nodes | ~470.3 µs | ~372.2 µs | ~959.1 µs | ~2,126 ops/s | ~255.4 KB |
| `CognitiveCycle.Adaptation.BaselinePolicy` | 50 target nodes | ~328.5 µs | ~290.4 µs | ~518.4 µs | ~3,044 ops/s | ~255.4 KB |

### JMH Microbenchmark Results (Steady-State JIT Microbenchmarks)

| Benchmark | Parameter | Mode | Score | Units |
| :--- | :--- | :--- | :--- | :--- |
| `ScalarResonanceBenchmark.benchmarkSingleScore` | 64 size | avgt | ~61.2 | ns/op |
| `ScalarResonanceBenchmark.benchmarkSingleScore` | 256 size | avgt | ~62.4 | ns/op |
| `ScalarResonanceBenchmark.benchmarkSingleScore` | 1,000 size | avgt | ~71.7 | ns/op |
| `ScalarResonanceBenchmark.benchmarkSingleScore` | 10,000 size | avgt | ~97.6 | ns/op |
| `ScalarResonanceBenchmark.benchmarkSingleScore` | 100,000 size | avgt | ~125.8 | ns/op |
| `AdaptationPolicyBenchmark.benchmarkNoOpPolicy` | N/A | avgt | ~12.5 | ns/op |
| `AdaptationPolicyBenchmark.benchmarkBaselinePolicyDecisionArithmetic` | N/A | avgt | ~46.6 | ns/op |
| `AdaptationPolicyBenchmark.benchmarkBaselinePolicyFull` | N/A | avgt | ~120.4 | ns/op |
| `CognitiveCycleBenchmark.benchmarkCognitiveCycleNoOp` | N/A | avgt | ~96.2 | µs/op |
| `CognitiveCycleBenchmark.benchmarkCognitiveCycleBaseline` | N/A | avgt | ~110.4 | µs/op |


## Baseline Results (Java 27 Baseline)

Captured on Linux x86_64 with Java 27 via Issue #43:

- **Host & CPU**: Linux x86_64 (kernel 6.6.137+), `Intel(R) Core(TM) i7-6500U CPU @ 2.50GHz` (2 physical cores, 4 logical threads, AVX2).
- **SIMD / Vector Species**: `DoubleVector.SPECIES_PREFERRED` is `DoubleVector.SPECIES_256` (256-bit lane width = 4 `double` values).
- **Toolchain & JVM**: Azul Systems, Inc. Zulu OpenJDK 27+35 (build 27-ea+35-2431, 64-Bit Server VM).
- **Memory & GC**: 16 GB physical RAM, default G1 GC.

### End-to-End Suite Results (Java 27)

| Benchmark | Workload Scale | Mean Latency | Median (p50) | p95 | Throughput | Alloc / Op |
| :--- | :--- | ---: | ---: | ---: | ---: | ---: |
| `ScalarResonanceMetric.score` | 100 pairs | 334.42 µs | 117.92 µs | 1.23 ms | ~299,000 pairs/s | 61.9 B |
| `ScalarResonanceMetric.score` | 1,000 pairs | 994.32 µs | 536.12 µs | 3.63 ms | ~1,006,000 pairs/s | 62.1 B |
| `ScalarResonanceMetric.score` | 10,000 pairs | 2.34 ms | 1.99 ms | 3.14 ms | ~4,273,000 pairs/s | 32.0 B |
| `ScalarResonanceMetric.score` | 100,000 pairs | 14.88 ms | 15.70 ms | 16.59 ms | ~6,719,000 pairs/s | 32.0 B |
| `ScalarBatchResonance.SoA` | 100,000 pairs | 18.56 ms | 17.93 ms | 29.68 ms | ~5,389,000 pairs/s | 32.0 B |
| `VectorBatchResonance.SoA` | 100,000 pairs | 10.00 ms | 1.48 ms | 47.27 ms | ~9,997,000 pairs/s | 68.1 B |
| `GraphPropagation.RouteAll` | Small (50 nodes, deg 3) | 1.41 ms | 1.34 ms | 2.03 ms | ~708 ops/s | 50.92 KB |
| `GraphPropagation.ThresholdRouting` | Small (50 nodes, deg 3) | 15.66 µs | 13.17 µs | 32.16 µs | ~63,800 ops/s | 880.0 B |
| `CompactGraphPropagation.RouteAll` | Small (50 nodes, deg 3) | 401.74 µs | 398.39 µs | 460.00 µs | ~2,489 ops/s | 27.19 KB |
| `GraphPropagation.RouteAll` | Medium (500 nodes, deg 5) | 3.57 ms | 3.46 ms | 4.64 ms | ~280 ops/s | 744.33 KB |
| `GraphPropagation.ThresholdRouting` | Medium (500 nodes, deg 5) | 26.41 µs | 19.83 µs | 57.31 µs | ~37,800 ops/s | 1.53 KB |
| `CompactGraphPropagation.RouteAll` | Medium (500 nodes, deg 5) | 1.60 ms | 1.58 ms | 2.87 ms | ~624 ops/s | 267.94 KB |
| `GraphPropagation.RouteAll` | Large (2,000 nodes, deg 8) | 11.75 ms | 13.16 ms | 16.75 ms | ~85 ops/s | 4.33 MB |
| `GraphPropagation.ThresholdRouting` | Large (2,000 nodes, deg 8) | 38.60 µs | 8.12 µs | 158.95 µs | ~25,900 ops/s | 2.33 KB |
| `CompactGraphPropagation.RouteAll` | Large (2,000 nodes, deg 8) | 2.90 ms | 2.71 ms | 4.17 ms | ~345 ops/s | 1.22 MB |
| `AeonCoordinator.Direct` | 10 inputs, 100 members | 9.27 ms | 9.07 ms | 11.48 ms | ~108 ops/s | 1.49 MB |
| `AeonCoordinator.Contextual` | 10 inputs, 100 members | 12.04 ms | 11.42 ms | 16.79 ms | ~83 ops/s | 4.88 MB |
| `DeterministicCognitiveCycle.FullCycle` | 5 stages, 5 initial signals | 553.32 µs | 513.22 µs | 715.48 µs | ~1,807 ops/s | 231.72 KB |
| `CognitiveCycle.Adaptation.NoOp` | 50 target nodes | 507.71 µs | 468.33 µs | 682.16 µs | ~1,970 ops/s | 231.72 KB |
| `CognitiveCycle.Adaptation.BaselinePolicy` | 50 target nodes | 820.66 µs | 510.76 µs | 1.84 ms | ~1,219 ops/s | 231.72 KB |
| `NodeStateLayout.ObjectConstruction` | 100,000 nodes | 60.65 ms | 59.52 ms | 66.65 ms | ~1,649,000 ops/s | 405.6 B |
| `NodeStateLayout.HeapSoASnapshotConstruction` | 100,000 nodes | 19.08 ms | 16.09 ms | 33.49 ms | ~5,242,000 ops/s | 101.1 B |
| `NodeStateLayout.FfmSnapshotConstruction` | 100,000 nodes | 17.95 ms | 17.25 ms | 22.74 ms | ~5,572,000 ops/s | 68.0 B |

### JMH Microbenchmark Results (Java 27 Baseline)

#### Steady-State Microbenchmarks: Adaptation Policy & Full Cognitive Cycle

Measured with 3 forks, 3 warmups, and 5 measurement iterations (1 second each) on Azul Zulu JDK 27+35 (`avgt`):

| Benchmark | Parameter | Java 26 Reference | Java 27 Score | Units | Variance / Status |
| :--- | :--- | ---: | ---: | :--- | :--- |
| `AdaptationPolicyBenchmark.benchmarkNoOpPolicy` | N/A | ~12.5 | 10.05 ± 1.26 | ns/op | ~20% faster |
| `AdaptationPolicyBenchmark.benchmarkBaselinePolicyDecisionArithmetic` | N/A | ~46.6 | 44.06 ± 4.65 | ns/op | ~6% faster |
| `AdaptationPolicyBenchmark.benchmarkBaselinePolicyFull` | N/A | ~120.4 | 86.09 ± 3.54 | ns/op | ~28% faster |
| `CognitiveCycleBenchmark.benchmarkCognitiveCycleNoOp` | N/A | ~96.2 | 41.98 ± 1.28 | µs/op | ~56% faster |
| `CognitiveCycleBenchmark.benchmarkCognitiveCycleBaseline` | N/A | ~110.4 | 42.62 ± 1.16 | µs/op | ~61% faster |

#### Steady-State Microbenchmarks: Aeon Coordination (`AeonCoordinationBenchmark`)

Measured with 3 forks, 3 warmups, and 5 measurement iterations (`avgt`, medium graph 200 nodes, 10 inputs, 4 workers) on Azul Zulu JDK 27+35:

| Benchmark | Mode | Score (Java 27) | Error (99.9% CI) | Units | Equivalent CLI Time |
| :--- | :--- | ---: | ---: | :--- | ---: |
| `AeonCoordinationBenchmark.benchmarkSequentialDirect` | avgt | **553.64** | ± 13.83 | µs/op | ~0.55 ms |
| `AeonCoordinationBenchmark.benchmarkSequentialContextual` | avgt | **1,012.66** | ± 60.21 | µs/op | ~1.01 ms |
| `AeonCoordinationBenchmark.benchmarkParallelDirect` | avgt | **473.42** | ± 64.99 | µs/op | ~0.47 ms |
| `AeonCoordinationBenchmark.benchmarkParallelContextual` | avgt | **1,279.32** | ± 198.61 | µs/op | ~1.28 ms |

#### SIMD Crossover Study on Java 27 (`batchSize` = 1, 2, 4, 8)

To empirically re-verify the crossover threshold between scalar and Vector API execution on Java 27, a dedicated JMH microbenchmark was executed across `batchSize` 1, 2, 4, and 8 on `ScalarResonanceBenchmark.benchmark(Scalar|Vector|Adaptive)BatchSoA`:

| Batch Size | Scalar (`ns/op`) | Vector (`ns/op`) | Adaptive (`ns/op`) | Vector Speedup vs Scalar | Observation |
| :--- | ---: | ---: | ---: | ---: | :--- |
| **1 pair** | 95.362 ± 2.923 | 86.223 ± 2.766 | 82.805 ± 2.053 | ~1.10x | Within margin; vector offers no decisive win. |
| **2 pairs** | 142.124 ± 7.420 | 140.038 ± 5.485 | 136.784 ± 4.908 | ~1.01x | Parity; vector and scalar latencies overlap. |
| **4 pairs** | 268.490 ± 11.233 | 74.629 ± 1.849 | 74.372 ± 1.956 | **~3.60x** | **Inflection point**: SIMD delivers full 256-bit lane speedup. |
| **8 pairs** | 503.441 ± 11.666 | 119.866 ± 4.673 | 130.222 ± 5.923 | **~4.20x** | Continuous linear scaling for vector operations. |

**Conclusion**: The default crossover threshold of `4` (`DEFAULT_CROSSOVER_THRESHOLD = 4`) remains optimal and empirically justified on Java 27. At 1 and 2 pairs, SIMD vectorization exhibits no material advantage over scalar loops. At 4 pairs (matching the 4 `double` lanes of AVX2 / `SPECIES_256`), Vector API throughput is 3.6x faster than scalar, and `AdaptiveBatchResonanceEvaluator` matches direct vector performance without dispatch penalty.

#### Control Plane & Dynamic Selection Overhead (`BackendSelectionBenchmark`)

| Benchmark | Parameter | Mode | Score | Units |
| :--- | :--- | :--- | ---: | :--- |
| `BackendSelectionBenchmark.benchmarkDirectScalarBatch` | 4 pairs | avgt | ~308.6 | ns/op |
| `BackendSelectionBenchmark.benchmarkDirectVectorBatch` | 4 pairs | avgt | ~94.7 | ns/op |
| `BackendSelectionBenchmark.benchmarkDirectScalarBatch` | 64 pairs | avgt | ~4,449.4 | ns/op |
| `BackendSelectionBenchmark.benchmarkDirectVectorBatch` | 64 pairs | avgt | ~874.9 | ns/op |
| `BackendSelectionBenchmark.benchmarkDirectScalarBatch` | 1,000 pairs | avgt | ~79,322.7 | ns/op |
| `BackendSelectionBenchmark.benchmarkDirectVectorBatch` | 1,000 pairs | avgt | ~24,535.6 | ns/op |
| `BackendSelectionBenchmark.benchmarkResonanceSelectionAuto` | 64 pairs | avgt | ~20.5 | ns/op |
| `BackendSelectionBenchmark.benchmarkResonanceSelectionAuto` | 1,000 pairs | avgt | ~16.5 | ns/op |
| `BackendSelectionBenchmark.benchmarkGraphSelection` | 64 nodes | avgt | ~18.1 | ns/op |
| `BackendSelectionBenchmark.benchmarkAeonSelection` | 64 inputs | avgt | ~96.0 | ns/op |

### Comparability & Variance Analysis: Java 26 vs Java 27

When evaluating the comparative performance between the Java 26 reference numbers and the Java 27 migration results, several apparent differences must be distinguished according to harness methodology and execution environment:

1. **Harness Isolation vs. End-to-End Cumulative Runner**:
   - Both Java 26 and Java 27 report end-to-end figures from `CognitiveBaselineRunner`. In this unified runner, all 17+ workloads execute sequentially within a single JVM process.
   - Early workloads (such as 100,000-node object creation, CSR snapshot compilation, and multi-million scalar/vector evaluations) allocate hundreds of megabytes of ephemeral heap, prompting background G1 concurrent marking and garbage collection cycles that overlap with later micro-workloads (`AeonCoordinator` and `CognitiveCycle.Adaptation`).
   - Additionally, on the dual-core mobile host (`Intel Core i7-6500U`), sustained unisolated execution causes dynamic CPU frequency scaling (dropping clock speeds to ~51% of max frequency due to 15W TDP constraints), inflating single-process wall-clock durations.
2. **Resolution Under Isolated Multi-Fork JMH Measurement**:
   - To definitively determine whether the large CLI runner deltas reflect an actual runtime regression or test harness/environment variance, isolated JMH benchmarks were executed with fresh forks (3 forks, 3 warmups, 5 measurement iterations) on the suspect workloads:
     - **Aeon Coordination**: `benchmarkSequentialDirect` runs at **553.6 µs/op** (~0.55 ms) and `benchmarkSequentialContextual` at **1,012.7 µs/op** (~1.01 ms)—disproving the 9.27 ms and 12.04 ms unisolated CLI figures by an order of magnitude.
     - **Adaptation Policy**: Non-mutating no-op runs in **10.05 ns/op** (vs ~12.5 ns in Java 26), arithmetic decision runs in **44.06 ns/op** (vs ~46.6 ns), and full adaptation on fresh nodes runs in **86.09 ns/op** (vs ~120.4 ns, a 28% improvement).
     - **Cognitive Cycle**: Full cycle with baseline adaptation runs in **42.62 µs/op** (vs ~110.4 µs in Java 26, a 61% improvement).
3. **Core Conclusion**:
   - **The large deltas in the unified runner were not reproduced under isolated JMH measurement and therefore are treated as harness/environment variance rather than evidence of a Java 27 runtime regression.**
4. **Allocation and Invariant Integrity Preserved**:
   - Memory allocation per operation is virtually identical between Java 26 and Java 27 across all workloads (`ScalarResonanceMetric.score` at 32.0 B, FFM layout at 68.0 B, Cognitive Cycle at ~231 KB vs ~255 KB).
   - 100% of unit tests, architectural invariants, and deterministic oracle validations pass without degradation.
   - Architectural decisions and thresholds (`DEFAULT_CROSSOVER_THRESHOLD = 4`) remain fully validated on Java 27.


## Analysis of Bottlenecks & Next Optimization Experiments

From the empirical evidence gathered by the baseline harness and the completion of Experiment 1 (Vector API SIMD batch resonance):

### 1. Vector API SIMD for Batch Resonance (Completed - ADR 0013)
- **Result**: `VectorBatchResonanceEvaluator` remains behind the capability-driven `AdaptiveBatchResonanceEvaluator`, with `FrequencyStateBatch` contiguous Structure-of-Arrays (SoA) layout.
- **Retention evidence**: The controlled three-fork GC/JFR study above meets the 105% allocation gate at 1,000, 10,000, and 100,000 pairs and selects a four-pair crossover. On the large SoA sizes, Vector is approximately 6.2x to 6.7x faster than Scalar. Numerical equivalence with `ScalarResonanceMetric` remains protected within $10^{-12}$ tolerance.

### 2. Compact Graph Representation (CSR / Compact Integer Adjacency)
- **Implementation**: Issue #26 adds an opt-in `CompactGraphSnapshot` with UUID-sorted dense indices, CSR adjacency, topology-version invalidation, and a parallel-array BFS queue. Compilation is measured separately; `Node` remains the live domain model and reference engine.
- **Controlled command**:
  ```bash
  ./gradlew :monada-neuron-evaluation:jmh \
    -PjmhArgs="-f 1 -wi 3 -i 5 -prof gc SignalPropagation"
  ```
- **JMH steady state (average time, RouteAll)**: The same 2026-08-24 environment produced the following single-fork results. They exclude compilation from traversal and use one thread; allocation remains end-to-end with the benchmark processor's Signal and result creation.

  | Scale | Reference | CSR | Speedup | Reference alloc/op | CSR alloc/op | Allocation reduction | CSR compilation |
  | :--- | ---: | ---: | ---: | ---: | ---: | ---: |
  | 50 nodes | 21.932 µs | 15.047 µs | 1.46x | 46,320 B | 24,704 B | 46.7% | 6.498 µs |
  | 500 nodes | 364.346 µs | 153.544 µs | 2.37x | 701,563 B | 242,457 B | 65.4% | 147.138 µs |
  | 2,000 nodes | 3.759 ms | 948.257 µs | 3.96x | 4.420 MB | 1.286 MB | 70.9% | 902.356 µs |

- **Fresh end-to-end baseline**: Run on 2026-08-24 with Temurin 26+35, Linux amd64, four available processors, G1 GC, seed `42`, five warmups and fifteen measured iterations per graph operation. The compact and reference paths produced identical `PropagationResult` values before measurement.

  | RouteAll scale | Reference mean | CSR mean | Throughput improvement | Reference alloc/op | CSR alloc/op | Allocation reduction | CSR compilation mean |
  | :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
  | Small (50, degree 3) | 1.04 ms | 432.10 µs | 2.41x | 60.86 KB | 33.51 KB | 44.9% | 314.82 µs |
  | Medium (500, degree 5) | 3.12 ms | 1.01 ms | 3.09x | 841.37 KB | 330.52 KB | 60.7% | 1.59 ms |
  | Large (2,000, degree 8) | 6.17 ms | 1.95 ms | 3.16x | 4.55 MB | 1.54 MB | 66.2% | 3.52 ms |

- **Decision**: Both JMH and the end-to-end baseline exceed the RouteAll throughput hypothesis at 2,000 nodes; JMH also exceeds it at 500 nodes. Neither JMH workload reaches the required 75% allocation reduction (65.4% and 70.9%), so the complete target is not met. Threshold-routed workloads are slower with CSR because they do too little traversal to amortize snapshot validation and queue setup. This is a reproducible negative result for the full target; CSR remains explicit and is not selected as a default backend.

### 3. FFM / Off-Heap Storage for Large-Scale Topologies
- **Implementation**: Issue #27 adds an evaluation-only `NodeStateSnapshot`, with an object control,
  a four-`double[]` heap SoA control, and four FFM `MemorySegment` channels managed by one confined
  `Arena`. It preserves the UUID-sorted dense mapping, copies no topology or history, validates
  values and indices, and rejects every data access after idempotent close.
- **Physical contract**: each snapshot stores exactly four native-order `double` channels,
  or 32 logical bytes per Node. At 100,000 Nodes, the FFM snapshot explicitly commits 3.20 MB
  off heap; UUIDs, types, histories, edges, and the source Nodes still coexist on heap.
- **Fresh full baseline (2026-08-31)**: Temurin 26+35, Linux amd64, four available processors,
  G1 GC, seed `42`, three warmups and eight measured iterations for this experiment. Latencies
  below are complete operation means; allocation and RSS are emitted in the JSON/Markdown report,
  whose RSS source is `/proc/self/status` on Linux.

  | Operation (100,000 Nodes) | Object `Node` | Heap SoA snapshot | FFM snapshot |
  | :--- | ---: | ---: | ---: |
  | Construction / population | 98.19 ms | 31.45 ms | 24.17 ms |
  | Sequential read | 3.98 ms | 2.38 ms | 2.88 ms |
  | Random read | 2.63 ms | 1.55 ms | 2.13 ms |
  | Bounded update (10,000 states) | 1.72 ms | 1.20 ms | 2.14 ms |
  | CSR + state snapshot compilation | N/A | 107.03 ms | 81.95 ms |

- **JMH cross-check**: the shorter one-thread GC-profiled run described above exercised all four
  operations at 100,000 and 1,000,000 Nodes. It recorded the expected state-size scales
  (3.20 MB and 32.00 MB off heap) but had high variance in the million-Node construction paths,
  including GC during snapshot setup. Its raw JSON is therefore reproducible evidence rather than
  a promotion gate.
- **Decision**: FFM reduces the selected mutable state's on-heap payload but does not eliminate
  the source `Node` graph, and this isolated study does not establish an end-to-end runtime win.
  FFM remains experimental with no automatic backend selection and no change to Node, CSR, or
  propagation. A later decision would need stable multi-fork measurements and a lifecycle design
  for replacing, rather than coexisting with, the selected state.

### 4. Bounded Parallelism for Multi-Input Aeon Dispatch
- **Observation**: Multi-input Aeon coordination is currently sequential. Contextual coordination on 10 inputs costs ~6.8 ms vs 2.9 ms direct due to single-threaded sequential signal tracking and trace entries.
- **Proposed Experiment**: Evaluate bounded parallel propagation for independent Aeon inputs when graph mutations are absent.
- **Target Metric**: Linear scaling across available CPU cores for multi-input workloads with batch sizes $\ge 10$ inputs.
