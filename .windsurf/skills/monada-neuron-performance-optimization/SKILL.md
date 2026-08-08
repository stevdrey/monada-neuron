---
name: monada-neuron-performance-optimization
description: Use when optimizing latency, throughput, memory, allocation, locality, I/O, or hot loops from a measured baseline.
---

# Monada Neuron Performance Optimization

## Context

Read `AGENTS.md`, `docs/architecture.md`, `docs/design-principles.md`, affected code/tests, and the evaluation/benchmark skill.

Performance is a project requirement, but optimization must remain evidence-driven.

## Optimization Order

Prefer this order unless the workload clearly requires otherwise:

1. verify correctness and define the target metric;
2. establish a representative baseline;
3. improve algorithmic complexity;
4. choose a more appropriate data structure;
5. reduce unnecessary work and copying;
6. improve data layout/locality;
7. reduce material allocation/boxing/GC pressure;
8. improve batching and I/O behavior;
9. apply CPU SIMD where appropriate;
10. apply bounded CPU parallelism where appropriate;
11. consider native/off-heap or accelerator execution.

Do not jump directly to GPU/native code when a cheaper algorithmic change solves the problem.

## Baseline Required

Capture enough context to reproduce the result:

- JDK/JVM build;
- OS and CPU architecture;
- heap settings or relevant JVM flags;
- dataset/workload size;
- node/signal counts;
- data dimensions;
- operation count/batch size;
- warm-up strategy;
- measurement method;
- latency/throughput result;
- allocation or memory footprint where relevant.

For microbenchmarks, prefer JMH over ad-hoc `System.nanoTime()` loops.

## Profiling

Use evidence to identify hot paths. Depending on the problem, use JFR/JMC, async-profiler, allocation profiling, GC logs, hardware counters, or platform profilers.

Do not optimize methods merely because they look expensive in source.

## Memory Efficiency

Consider:

- number of allocated objects;
- object headers/reference width;
- boxing;
- retained graph size;
- collection capacity slack;
- history/cache growth;
- temporary buffers;
- duplicate representations;
- off-heap vs heap trade-offs.

Off-heap memory reduces GC ownership but adds explicit lifetime, safety, access, and copying concerns. It is not a default optimization.

## Locality and Layout

For large numeric/state workloads, benchmark whether primitive contiguous layouts outperform object graphs.

Potential transformations include:

- AoS -> SoA;
- UUID/object edge references -> compact internal ids;
- per-node sets -> compact adjacency arrays for stable sparse graphs;
- boxed numeric collections -> primitive arrays/memory segments;
- repeated materialization -> streaming/batched processing.

Preserve a domain abstraction when a physical optimized layout would otherwise leak implementation concerns.

## Allocation Rules

Avoid accidental high-frequency allocation such as:

- intermediate stream collections;
- boxed primitives;
- temporary tuples/wrappers;
- repeated immutable collection copying in internal hot paths;
- string formatting/log construction when disabled;
- unbounded history/cache accumulation.

Retain clear public immutability/safety boundaries. Optimize internal ownership deliberately.

## CPU Optimization

Before parallelizing, determine whether the workload is compute-, branch-, cache-, or memory-bandwidth-bound.

A single efficient sequential loop may outperform parallel work when tasks are small or bandwidth-bound.

Vectorize only operations that can actually benefit from SIMD and verify generated/perceived improvements with benchmarks.

## Parallelism

For CPU-bound work:

- bound active compute tasks;
- partition work to minimize synchronization;
- avoid false sharing where mutable counters/state are updated frequently;
- use coarse enough tasks to amortize scheduling;
- account for memory bandwidth saturation.

Virtual threads should not be used as a CPU throughput multiplier.

## GPU / Accelerator Performance

GPU acceleration is justified only by end-to-end improvement on representative workloads.

Measure:

- host preprocessing;
- layout conversion;
- host/device transfer;
- kernel/JIT compilation;
- warm-up;
- synchronization;
- kernel execution;
- result transfer/decoding;
- batch-size sensitivity.

Do not report kernel-only speedup as application speedup.

## Correctness Guard

Optimized paths must produce the same semantic result as a reference implementation, with an explicitly documented tolerance for floating-point differences.

Keep deterministic tie ordering and state transition behavior unless the specification explicitly changes them.

## Regression Policy

For material optimizations, add a benchmark or diagnostic that can detect future regressions without making CI fragile.

Use protected deterministic workloads for automated checks and larger exploratory workloads for local profiling when appropriate.

## Acceptance

A performance change is ready when:

- the target bottleneck is measured;
- before/after results are reproducible;
- the chosen optimization is lower-complexity than rejected alternatives where possible;
- semantic behavior remains protected;
- resource trade-offs are documented;
- specialized paths include fallback/reference behavior where practical.
