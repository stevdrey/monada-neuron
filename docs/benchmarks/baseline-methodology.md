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

## Reproducible Workloads

All synthetic workloads are generated deterministically from configurable seeds (default seed: `42L` via `DeterministicWorkloadGenerator`):

1. **Scalar Resonance Batches**:
   - Compares pairs of `FrequencyState` across multiple batch sizes (100, 1,000, 10,000, 100,000 pairs).
   - Evaluates amplitude, frequency, and phase similarity calculation costs.

2. **Sparse Directed Graph Propagation**:
   - Evaluates `DeterministicSignalPropagationEngine` on sparse topologies at multiple scales:
     - **Small**: 50 nodes, average degree 3, hop limit 5, step limit 200.
     - **Medium**: 500 nodes, average degree 5, hop limit 8, step limit 2,000.
     - **Large**: 2,000 nodes, average degree 8, hop limit 10, step limit 10,000.
   - Evaluates both unbounded routing (`RouteAll`) and gated routing (`ResonanceThresholdRoutingPolicy` at 0.5 threshold).

3. **Aeon Coordination**:
   - Evaluates `DeterministicAeonCoordinator` under direct mode vs. contextual mode (with `CognitiveContext` and budget bounds).

4. **Primary Monad Cognitive Cycles**:
   - Evaluates `DeterministicCognitiveCycle` over full 5-stage lifecycles (`PERCEPTION` -> `MEMORY_RECALL` -> `REASONING` -> `ADAPTATION` -> `ACTION`) using deterministic memory port and action capability fixtures.

5. **Adaptation Policy A/B Comparison**:
   - Measures cycle overhead with `NoOpAdaptationPolicy` (no-op baseline) versus `DeterministicBaselineAdaptationPolicy` (in-place bounded frequency state and energy updates).

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

Example with arguments:
```bash
./gradlew :monada-neuron-evaluation:runCognitiveBaseline -PbenchmarkArgs="--quick"
```

### Run JMH Microbenchmarks

Runs JMH microbenchmarks for hot primitives (`ScalarResonanceBenchmark`, `SignalPropagationBenchmark`, `AeonCoordinationBenchmark`, `CognitiveCycleBenchmark`, `AdaptationPolicyBenchmark`):

```bash
./gradlew :monada-neuron-evaluation:jmh
```

Pass custom JMH arguments:
```bash
./gradlew :monada-neuron-evaluation:jmh -PjmhArgs="-f 1 -wi 2 -i 3 ScalarResonanceBenchmark"
```

### Run Unit and Harness Tests

```bash
./gradlew test
```

## Baseline Results (Java 26 Reference)

The following baseline metrics were captured on Linux x86_64 with Java 26 (Eclipse Adoptium OpenJDK 64-Bit Server VM):

| Benchmark | Workload Scale | Mean Latency | Median (p50) | p95 | Throughput | Allocation / Op |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `ScalarResonanceMetric.score` | 100 pairs | ~0.50 ms | ~0.30 ms | ~1.86 ms | ~2,000 ops/s | ~6.0 KB |
| `ScalarResonanceMetric.score` | 1,000 pairs | ~0.73 ms | ~0.66 ms | ~1.56 ms | ~1,380 ops/s | ~60.7 KB |
| `ScalarResonanceMetric.score` | 10,000 pairs | ~1.97 ms | ~2.08 ms | ~2.52 ms | ~500 ops/s | ~312.7 KB |
| `ScalarResonanceMetric.score` | 100,000 pairs | ~11.59 ms | ~11.79 ms | ~13.56 ms | ~86 ops/s | ~3.05 MB |
| `GraphPropagation.RouteAll` | Small (50 nodes, deg 3) | ~1.01 ms | ~1.08 ms | ~1.61 ms | ~980 ops/s | ~60.9 KB |
| `GraphPropagation.ThresholdRouting` | Small (50 nodes, deg 3) | ~6.25 µs | ~4.99 µs | ~12.64 µs | ~160,000 ops/s | ~1.0 KB |
| `GraphPropagation.RouteAll` | Medium (500 nodes, deg 5) | ~2.49 ms | ~2.74 ms | ~3.24 ms | ~400 ops/s | ~841.4 KB |
| `GraphPropagation.ThresholdRouting` | Medium (500 nodes, deg 5) | ~8.43 µs | ~6.16 µs | ~17.66 µs | ~118,000 ops/s | ~1.75 KB |
| `GraphPropagation.RouteAll` | Large (2,000 nodes, deg 8) | ~12.59 ms | ~12.05 ms | ~17.02 ms | ~80 ops/s | ~4.98 MB |
| `GraphPropagation.ThresholdRouting` | Large (2,000 nodes, deg 8) | ~16.35 µs | ~14.73 µs | ~25.98 µs | ~61,000 ops/s | ~2.80 KB |
| `AeonCoordinator.Direct` | 10 inputs, 100 members | ~4.47 ms | ~2.94 ms | ~9.56 ms | ~220 ops/s | ~1.44 MB |
| `AeonCoordinator.Contextual` | 10 inputs, 100 members | ~1.82 ms | ~1.58 ms | ~3.37 ms | ~550 ops/s | ~453.4 KB |
| `DeterministicCognitiveCycle.FullCycle` | 5 stages, 5 initial signals | ~561.4 µs | ~578.5 µs | ~771.4 µs | ~1,780 ops/s | ~255.4 KB |
| `CognitiveCycle.Adaptation.NoOp` | 50 target nodes | ~405.6 µs | ~313.2 µs | ~745.1 µs | ~2,460 ops/s | ~255.4 KB |
| `CognitiveCycle.Adaptation.BaselinePolicy` | 50 target nodes | ~376.8 µs | ~347.3 µs | ~504.7 µs | ~2,650 ops/s | ~255.4 KB |

## Analysis of Bottlenecks & Next Optimization Experiments

From the empirical evidence gathered by the baseline harness, four candidate optimization experiments are identified:

### 1. Vector API SIMD for `ScalarResonanceMetric`
- **Observation**: Batch scalar scoring scales linearly (~98–115 ns per pair in large batches). The calculation involves floating-point ratios and trigonometric phase differences (`StrictMath.IEEEremainder` and `StrictMath.cos`).
- **Proposed Experiment**: Implement a SIMD vector batch evaluator using `jdk.incubator.vector.DoubleVector` / `FloatVector`.
- **Target Metric**: >3x throughput increase on batches $\ge 1,000$ pairs while maintaining strict equivalence with the scalar oracle within floating-point tolerance.

### 2. Compact Graph Representation (CSR / Compact Integer Adjacency)
- **Observation**: `GraphPropagation.RouteAll` on 2,000 nodes allocates ~4.98 MB per run and runs in ~12.6 ms due to `Node` UUID set iterations, sorting UUIDs, and allocating intermediate `Signal` and `NodeProcessingResult` lists.
- **Proposed Experiment**: Design a compact integer ID layout with Compressed Sparse Row (CSR) adjacency indexing and primitive state arrays.
- **Target Metric**: >75% allocation reduction and >2x traversal throughput improvement on graphs $\ge 500$ nodes.

### 3. FFM / Off-Heap Storage for Large-Scale Topologies
- **Observation**: Phase-1 `Node` objects incur standard Java object header overhead (16–24 bytes per node/state reference plus GC pointer tracking).
- **Proposed Experiment**: Evaluate `java.lang.foreign.MemorySegment` contiguous off-heap layout for large state buffers ($> 100,000$ nodes).
- **Target Metric**: Zero GC overhead for node state storage and measurable cache locality improvements.

### 4. Bounded Parallelism for Multi-Input Aeon Dispatch
- **Observation**: Multi-input Aeon coordination is currently sequential.
- **Proposed Experiment**: Evaluate bounded parallel propagation for independent Aeon inputs when graph mutations are absent.
- **Target Metric**: Linear scaling across available CPU cores for multi-input workloads with batch sizes $\ge 10$ inputs.
