# Monada Neuron Evaluation & Baseline Methodology

## Overview

Monada Neuron requires measurable, reproducible behavior prior to introducing hardware-specific, SIMD, off-heap, or accelerator-backed optimizations (as specified in [ADR 0003](../adr/0003-performance-first-java26-and-heterogeneous-compute.md) and [Issue #14](https://github.com/stevdrey/monada-neuron/issues/14)).

The `:monada-neuron-evaluation` subproject provides an isolated benchmarking and evaluation harness measuring both semantic correctness and resource characteristics (latency distributions, throughput, memory allocation rate, and GC activity).

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
- **Observation**: Phase-1 `Node` objects incur standard Java object header overhead (16–24 bytes per node/state reference plus GC pointer tracking).
- **Proposed Experiment**: Evaluate `java.lang.foreign.MemorySegment` contiguous off-heap layout for large state buffers ($> 100,000$ nodes).
- **Target Metric**: Zero GC overhead for node state storage and measurable cache locality improvements.

### 4. Bounded Parallelism for Multi-Input Aeon Dispatch
- **Observation**: Multi-input Aeon coordination is currently sequential. Contextual coordination on 10 inputs costs ~6.8 ms vs 2.9 ms direct due to single-threaded sequential signal tracking and trace entries.
- **Proposed Experiment**: Evaluate bounded parallel propagation for independent Aeon inputs when graph mutations are absent.
- **Target Metric**: Linear scaling across available CPU cores for multi-input workloads with batch sizes $\ge 10$ inputs.
