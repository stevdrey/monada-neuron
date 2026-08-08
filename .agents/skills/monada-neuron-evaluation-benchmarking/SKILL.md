---
name: monada-neuron-evaluation-benchmarking
description: Use when designing correctness baselines, performance experiments, JMH benchmarks, regression checks, or comparisons between execution strategies.
---

# Monada Neuron Evaluation and Benchmarking

## Context

Read `AGENTS.md`, `docs/architecture.md`, `docs/design-principles.md`, and the performance-related skills before creating a benchmark or making a performance claim.

## Principle

A benchmark should answer a specific engineering question.

Examples:

- Does a compact adjacency layout reduce memory and traversal time at target node counts?
- Does Vector API SIMD improve a signal transform over the scalar baseline?
- At what batch size does GPU execution beat CPU SIMD end-to-end?
- Does off-heap state reduce GC pressure enough to justify explicit memory management?

Avoid benchmark suites that collect numbers without a decision they are meant to inform.

## Correctness Baseline First

Before comparing performance, establish a deterministic reference implementation and expected output.

For numeric implementations, define tolerances before comparing scalar, SIMD, parallel, native, or GPU results.

For graph/ranking/state operations, define ordering and identity semantics explicitly.

## Microbenchmarking

Use JMH when measuring operations small enough to be affected by:

- JIT compilation;
- dead-code elimination;
- constant folding;
- warm-up;
- loop optimizations;
- timer overhead.

A JMH benchmark should document:

- state scope;
- parameters/data sizes;
- forks;
- warm-up iterations;
- measurement iterations;
- benchmark mode;
- JVM flags;
- setup/teardown behavior.

Do not create hand-written repeated loops and treat them as reliable microbenchmarks unless JMH is genuinely impractical and the limitation is stated.

## System / End-to-End Benchmarks

For larger cognitive flows, record:

- node/signal counts;
- graph density;
- signal dimensions;
- history/cache settings;
- memory backend;
- concurrency level;
- batch size;
- external adapter behavior or stubs;
- elapsed latency and throughput;
- peak/retained memory when relevant;
- allocation/GC observations when relevant.

## Comparative Experiments

A comparison should keep semantics and workload constant.

Preferred report:

```text
Question
Environment
Workload
Reference implementation
Candidate implementation
Correctness check
Warm-up / measurement method
Results
Memory/allocation observations
Trade-offs
Decision / recommendation
```

## CPU SIMD Experiments

Compare at least:

- scalar reference;
- candidate Vector API implementation;
- multiple input lengths, including non-vector-multiple tails;
- small inputs where vector setup may not pay off;
- realistic target sizes.

When portability matters, test representative x64 and AArch64 hardware if available.

## CPU Parallel Experiments

Compare multiple task sizes and worker counts. Do not assume maximum thread count is optimal.

Observe:

- scaling by core count;
- contention;
- memory bandwidth saturation;
- task scheduling overhead;
- false sharing when applicable.

## FFM / Off-Heap Experiments

Include allocation/lifetime cost and any data copying required to move between heap and native memory.

Measure GC benefit separately from raw compute speed.

## GPU / Accelerator Experiments

Always include end-to-end time.

Break down when possible:

- host preparation;
- host-device transfer;
- kernel compilation/warm-up;
- kernel execution;
- synchronization;
- device-host transfer;
- result materialization.

Evaluate multiple batch/problem sizes and report the crossover point where the accelerator becomes beneficial.

A kernel-only speedup is diagnostic information, not the final application performance result.

## Memory Benchmarks

When comparing representations, include approximate or measured retained memory per:

- node;
- edge;
- signal element;
- history entry;
- cache entry.

Use heap histograms/profilers or controlled sizing where possible instead of relying only on theoretical field counts.

## Protected vs Exploratory Baselines

Use two classes of benchmarks when useful:

### Protected

Small, deterministic, stable, CI-friendly workloads that catch semantic/performance regressions without relying on specific high-end hardware.

### Exploratory

Large, hardware-specific, real-world workloads used for design decisions and optimization research.

Do not make GPU availability or a specific CPU model a requirement for the normal test suite unless the repository explicitly adopts that infrastructure.

## Regression Thresholds

Avoid fragile nanosecond-level CI gates.

Prefer:

- coarse regression thresholds;
- operation/allocation invariants;
- protected data sizes;
- trend reporting;
- local exploratory performance runs for hardware-sensitive optimization.

## Acceptance

A benchmark is useful when it is reproducible, protects semantic equivalence, isolates the engineering question, accounts for JVM/runtime effects, includes the relevant resource metric, and produces evidence that can support an architecture or implementation decision.
