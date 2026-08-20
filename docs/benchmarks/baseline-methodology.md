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

To guarantee reliable and reproducible baselines, the harness enforces three key experimental invariants:

1. **Workload Equivalence Between Aeon Variants**:
   - Both `AeonCoordinator.Direct` and `AeonCoordinator.Contextual` run over identical 10-input workloads on 100-member topologies.
   - The contextual run is configured with sufficient global `CognitiveBudget` (50,000 steps, 100,000 signals, 100,000 traces) so neither variant truncates early.
   - Assertions verify that both direct and contextual runs process exactly 5,000 steps and emit exactly 5,000 signals, isolating the exact runtime and allocation cost of ephemeral `CognitiveContext` tracking.

2. **Per-Iteration State Reset & Isolation**:
   - `EvaluationMetricsCollector` executes a per-iteration setup hook outside the measurement window, excluding setup allocations and timing.
   - For all full-cycle and adaptation benchmarks, pristine topologies and node states with zero history are instantiated for each warmup and measurement iteration.
   - In JMH microbenchmarks, `@Setup(Level.Iteration)` refreshes node arrays to prevent mutation history from growing unbounded across iterations.

3. **Domain Throughput & Allocation Semantics**:
   - Batch scalar resonance calculations report true domain operations/sec (pairs scored per second) and memory allocation per individual pair.
   - Run configuration parameters (seed, quick mode, warmup/measurement counts, operation semantics) and JVM arguments/GCs are permanently recorded in generated reports.

## Reproducible Workloads

All synthetic workloads are generated deterministically from configurable seeds (default seed: `42L` via `DeterministicWorkloadGenerator`):

1. **Scalar Resonance Batches**:
   - Compares pairs of `FrequencyState` across multiple batch sizes (100, 1,000, 10,000, 100,000 pairs).
   - Evaluates `amplitudeSimilarity * frequencySimilarity * phaseSimilarity` calculation costs.

2. **Sparse Directed Graph Propagation**:
   - Evaluates `DeterministicSignalPropagationEngine` on sparse topologies at multiple scales:
     - **Small**: 50 nodes, average degree 3, hop limit 5, step limit 200.
     - **Medium**: 500 nodes, average degree 5, hop limit 8, step limit 2,000.
     - **Large**: 2,000 nodes, average degree 8, hop limit 10, step limit 10,000.
   - Evaluates both unbounded routing (`RouteAll`) and gated routing (`ResonanceThresholdRoutingPolicy` at 0.5 threshold).
   - Diagnostics capture actual processed steps, emitted signals, and limit triggers.

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
./gradlew :monada-neuron-evaluation:jmh -PjmhArgs="-f 1 -wi 1 -i 1 -r 1s ScalarResonanceBenchmark AdaptationPolicyBenchmark"
```

### Run Unit and Harness Tests

```bash
./gradlew test
```

## Baseline Results (Java 26 Reference)

Captured on Linux x86_64 with Java 26 (Eclipse Adoptium OpenJDK 64-Bit Server VM):

### End-to-End Suite Results

| Benchmark | Workload Scale | Mean Latency | Median (p50) | p95 | Throughput | Allocation / Op |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `ScalarResonanceMetric.score` | 100 pairs | ~125 µs | ~122 µs | ~156 µs | ~798,000 pairs/s | ~62.0 B |
| `ScalarResonanceMetric.score` | 1,000 pairs | ~303 µs | ~290 µs | ~367 µs | ~3,297,000 pairs/s | ~62.2 B |
| `ScalarResonanceMetric.score` | 10,000 pairs | ~1.51 ms | ~1.26 ms | ~3.59 ms | ~6,643,000 pairs/s | ~32.0 B |
| `ScalarResonanceMetric.score` | 100,000 pairs | ~12.02 ms | ~12.21 ms | ~13.35 ms | ~8,317,000 pairs/s | ~32.0 B |
| `GraphPropagation.RouteAll` | Small (50 nodes, deg 3) | ~1.18 ms | ~1.09 ms | ~1.78 ms | ~851 ops/s | ~60.9 KB |
| `GraphPropagation.ThresholdRouting` | Small (50 nodes, deg 3) | ~27.8 µs | ~11.5 µs | ~91.9 µs | ~36,000 ops/s | ~1.0 KB |
| `GraphPropagation.RouteAll` | Medium (500 nodes, deg 5) | ~1.84 ms | ~1.60 ms | ~2.96 ms | ~545 ops/s | ~841.4 KB |
| `GraphPropagation.ThresholdRouting` | Medium (500 nodes, deg 5) | ~12.4 µs | ~11.4 µs | ~16.8 µs | ~80,700 ops/s | ~1.74 KB |
| `GraphPropagation.RouteAll` | Large (2,000 nodes, deg 8) | ~9.36 ms | ~8.82 ms | ~15.72 ms | ~107 ops/s | ~4.84 MB |
| `GraphPropagation.ThresholdRouting` | Large (2,000 nodes, deg 8) | ~17.7 µs | ~16.4 µs | ~26.9 µs | ~56,500 ops/s | ~2.79 KB |
| `AeonCoordinator.Direct` | 10 inputs, 100 members | ~1.99 ms | ~1.80 ms | ~3.27 ms | ~503 ops/s | ~1.30 MB |
| `AeonCoordinator.Contextual` | 10 inputs, 100 members | ~6.74 ms | ~7.00 ms | ~11.98 ms | ~148 ops/s | ~5.21 MB |
| `DeterministicCognitiveCycle.FullCycle` | 5 stages, 5 initial signals | ~531.4 µs | ~508.4 µs | ~696.9 µs | ~1,882 ops/s | ~255.4 KB |
| `CognitiveCycle.Adaptation.NoOp` | 50 target nodes | ~648.7 µs | ~489.0 µs | ~1.34 ms | ~1,542 ops/s | ~255.4 KB |
| `CognitiveCycle.Adaptation.BaselinePolicy` | 50 target nodes | ~556.4 µs | ~442.1 µs | ~1.05 ms | ~1,797 ops/s | ~255.4 KB |

### JMH Microbenchmark Results (Single-Operation)

| Benchmark | Parameter | Mode | Score | Units |
| :--- | :--- | :--- | :--- | :--- |
| `ScalarResonanceBenchmark.benchmarkSingleScore` | 100 size | avgt | ~65.1 | ns/op |
| `ScalarResonanceBenchmark.benchmarkSingleScore` | 1,000 size | avgt | ~84.7 | ns/op |
| `ScalarResonanceBenchmark.benchmarkSingleScore` | 10,000 size | avgt | ~102.0 | ns/op |
| `AdaptationPolicyBenchmark.benchmarkNoOpPolicy` | N/A | avgt | ~12.3 | ns/op |
| `AdaptationPolicyBenchmark.benchmarkBaselinePolicy` | N/A | avgt | ~100.0 | ns/op |

## Analysis of Bottlenecks & Next Optimization Experiments

From the empirical evidence gathered by the baseline harness, four candidate optimization experiments are identified:

### 1. Vector API SIMD for `ScalarResonanceMetric`
- **Observation**: Batch scalar scoring costs ~65–102 ns per pair. The calculation involves floating-point ratios and trigonometric phase differences (`StrictMath.IEEEremainder` and `StrictMath.cos`).
- **Proposed Experiment**: Implement a SIMD vector batch evaluator using `jdk.incubator.vector.DoubleVector` / `FloatVector`.
- **Target Metric**: >3x throughput increase on batches $\ge 1,000$ pairs while maintaining strict equivalence with the scalar oracle within floating-point tolerance.

### 2. Compact Graph Representation (CSR / Compact Integer Adjacency)
- **Observation**: `GraphPropagation.RouteAll` on 2,000 nodes allocates ~4.84 MB per run and runs in ~9.4 ms due to `Node` UUID set iterations, sorting UUIDs, and allocating intermediate `Signal` and `NodeProcessingResult` lists.
- **Proposed Experiment**: Design a compact integer ID layout with Compressed Sparse Row (CSR) adjacency indexing and primitive state arrays.
- **Target Metric**: >75% allocation reduction and >2x traversal throughput improvement on graphs $\ge 500$ nodes.

### 3. FFM / Off-Heap Storage for Large-Scale Topologies
- **Observation**: Phase-1 `Node` objects incur standard Java object header overhead (16–24 bytes per node/state reference plus GC pointer tracking).
- **Proposed Experiment**: Evaluate `java.lang.foreign.MemorySegment` contiguous off-heap layout for large state buffers ($> 100,000$ nodes).
- **Target Metric**: Zero GC overhead for node state storage and measurable cache locality improvements.

### 4. Bounded Parallelism for Multi-Input Aeon Dispatch
- **Observation**: Multi-input Aeon coordination is currently sequential. Contextual coordination on 10 inputs costs ~6.7 ms vs 1.99 ms direct due to single-threaded sequential signal tracking and trace entries.
- **Proposed Experiment**: Evaluate bounded parallel propagation for independent Aeon inputs when graph mutations are absent.
- **Target Metric**: Linear scaling across available CPU cores for multi-input workloads with batch sizes $\ge 10$ inputs.
