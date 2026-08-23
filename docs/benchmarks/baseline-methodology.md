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
   - Represents the 64-bit HotSpot JVM heap layout for Phase-1 `Node` object graphs (~216 bytes/node base + ~32 bytes/directed edge).
   - Exported directly in benchmark diagnostics for future comparisons against CSR and FFM contiguous off-heap layouts.

5. **Explicit Allocation Telemetry Semantics**:
   - `AllocationMetrics` explicitly records `AllocationSource` (`THREAD_MX_BEAN` vs `UNAVAILABLE`).
   - If thread allocation tracking is unavailable, allocation metrics report as unavailable (`N/A` / `null`) rather than substituting net heap growth.
   - GC deltas capture JVM-wide cumulative GC activity across the entire measurement phase.

## Representative Graph Retained Footprint

| Topology Scale | Node Count | Average Degree | Total Directed Edges | Estimated Retained Heap |
| :--- | :--- | :--- | :--- | :--- |
| **Small** | 50 | 3 | 143 | **15.38 KB** |
| **Medium** | 500 | 5 | 2,468 | **186.98 KB** |
| **Large** | 2,000 | 8 | 15,913 | **941.22 KB** |

## Reproducible Workloads

All synthetic workloads are generated deterministically from configurable seeds (default seed: `42L` via `DeterministicWorkloadGenerator`):

1. **Scalar Resonance Batches**:
   - Compares pairs of `FrequencyState` across multiple batch sizes (100, 1,000, 10,000, 100,000 pairs).
   - Evaluates `amplitudeSimilarity * frequencySimilarity * phaseSimilarity` calculation costs.

2. **Sparse Directed Graph Propagation**:
   - Evaluates `DeterministicSignalPropagationEngine` on sparse topologies at multiple scales (Small, Medium, Large).
   - Evaluates both unbounded routing (`RouteAll`) and gated routing (`ResonanceThresholdRoutingPolicy` at 0.5 threshold).
   - Diagnostics capture actual processed steps, emitted signals, retained footprint, and limit triggers.

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
| `ScalarBatchResonance.SoA` | 100,000 pairs | ~13.40 ms | ~12.06 ms | ~20.22 ms | ~7,460,000 pairs/s | ~32.0 B |
| `VectorBatchResonance.SoA` | 100,000 pairs | ~6.16 ms | ~1.42 ms | ~26.06 ms | ~16,238,000 pairs/s | ~70.3 B |
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
| `ScalarResonanceBenchmark.benchmarkSingleScore` | 100 size | avgt | ~61.2 | ns/op |
| `ScalarResonanceBenchmark.benchmarkSingleScore` | 1,000 size | avgt | ~71.7 | ns/op |
| `ScalarResonanceBenchmark.benchmarkSingleScore` | 10,000 size | avgt | ~97.6 | ns/op |
| `ScalarResonanceBenchmark.benchmarkScalarBatchSoA` | 1,000 size | avgt | ~72.2 | µs/op |
| `ScalarResonanceBenchmark.benchmarkVectorBatchSoA` | 1,000 size | avgt | ~11.6 | µs/op (**6.23x speedup**) |
| `ScalarResonanceBenchmark.benchmarkScalarBatchSoA` | 10,000 size | avgt | ~905.9 | µs/op |
| `ScalarResonanceBenchmark.benchmarkVectorBatchSoA` | 10,000 size | avgt | ~119.2 | µs/op (**7.60x speedup**) |
| `ScalarResonanceBenchmark.benchmarkScalarBatchSoA` | 100,000 size | avgt | ~9.00 | ms/op |
| `ScalarResonanceBenchmark.benchmarkVectorBatchSoA` | 100,000 size | avgt | ~1.38 | ms/op (**6.50x speedup**) |
| `AdaptationPolicyBenchmark.benchmarkNoOpPolicy` | N/A | avgt | ~12.5 | ns/op |
| `AdaptationPolicyBenchmark.benchmarkBaselinePolicyDecisionArithmetic` | N/A | avgt | ~46.6 | ns/op |
| `AdaptationPolicyBenchmark.benchmarkBaselinePolicyFull` | N/A | avgt | ~120.4 | ns/op |
| `CognitiveCycleBenchmark.benchmarkCognitiveCycleNoOp` | N/A | avgt | ~96.2 | µs/op |
| `CognitiveCycleBenchmark.benchmarkCognitiveCycleBaseline` | N/A | avgt | ~110.4 | µs/op |


## Analysis of Bottlenecks & Next Optimization Experiments

From the empirical evidence gathered by the baseline harness and the completion of Experiment 1 (Vector API SIMD batch resonance):

### 1. Vector API SIMD for Batch Resonance (Completed - ADR 0013)
- **Result**: Implemented `VectorBatchResonanceEvaluator` with `FrequencyStateBatch` contiguous Structure-of-Arrays (SoA) layout.
- **Achieved Speedup**: **5.3x to 7.6x speedup** on AVX2 hardware (down to ~11.5 ns/pair in JMH and over 72M pairs/s throughput), exceeding the target 3x hypothesis. Numerical equivalence with `ScalarResonanceMetric` oracle is protected within $10^{-12}$ tolerance.

### 2. Compact Graph Representation (CSR / Compact Integer Adjacency)
- **Observation**: `GraphPropagation.RouteAll` on 2,000 nodes allocates ~4.98 MB per run and retains ~941 KB in heap due to `Node` UUID set iterations, sorting UUIDs, and allocating intermediate `Signal` and `NodeProcessingResult` lists.
- **Proposed Experiment**: Design a compact integer ID layout with Compressed Sparse Row (CSR) adjacency indexing and primitive state arrays.
- **Target Metric**: >75% allocation reduction and >2x traversal throughput improvement on graphs $\ge 500$ nodes.

### 3. FFM / Off-Heap Storage for Large-Scale Topologies
- **Observation**: Phase-1 `Node` objects incur standard Java object header overhead (16–24 bytes per node/state reference plus GC pointer tracking).
- **Proposed Experiment**: Evaluate `java.lang.foreign.MemorySegment` contiguous off-heap layout for large state buffers ($> 100,000$ nodes).
- **Target Metric**: Zero GC overhead for node state storage and measurable cache locality improvements.

### 4. Bounded Parallelism for Multi-Input Aeon Dispatch
- **Observation**: Multi-input Aeon coordination is currently sequential. Contextual coordination on 10 inputs costs ~6.8 ms vs 2.9 ms direct due to single-threaded sequential signal tracking and trace entries.
- **Proposed Experiment**: Evaluate bounded parallel propagation for independent Aeon inputs when graph mutations are absent.
- **Target Metric**: Linear scaling across available CPU cores for multi-input workloads with batch sizes $\ge 10$ inputs.
