---
name: monada-neuron-concurrency-hardware-acceleration
description: Use when adding concurrency, SIMD, Foreign Function & Memory API usage, shared-memory integration, GPU execution, or other hardware acceleration.
---

# Monada Neuron Concurrency and Hardware Acceleration

## Context

Read `AGENTS.md`, `docs/architecture.md`, ADR 0003, the Java 27 skill, the data-structures skill, and the performance skill before introducing specialized execution.

The portable Java CPU implementation is the reference path unless an accepted ADR says otherwise.

## Workload Classification

Classify the operation before selecting a mechanism.

### Blocking / I/O-bound

Prefer virtual threads when many independent blocking operations are expected and the underlying API behaves well with them.

### Coordinated concurrent tasks

Consider Structured Concurrency when JDK 27 preview usage is acceptable and task lifetime, cancellation, failure propagation, or observability benefits are material.

### CPU-bound scalar work

Start with efficient sequential code. Add bounded parallelism only when task size and available cores justify scheduling overhead.

### CPU data-parallel numeric work

Evaluate the Vector API before adding native/GPU complexity when contiguous data and SIMD-friendly operations exist.

### Massive regular parallel work

Evaluate an accelerator only when sufficient parallelism and batch size can amortize data movement and launch overhead.

## Virtual Threads

Use virtual threads for concurrency, not raw compute multiplication.

Good fits include:

- multiple independent memory/tool/model calls;
- network-bound adapters;
- blocking file or service orchestration.

Poor fits include launching one virtual thread per element in a CPU-heavy numeric loop.

## Structured Concurrency

JDK 27 Structured Concurrency is preview.

If used:

- keep preview enablement scoped;
- structure child-task lifetime lexically;
- define join/failure policy explicitly;
- test cancellation and partial failure;
- document the preview dependency and expected migration path.

## Bounded Parallel Aeon Coordination

This section is the one definition of when the schedule check runs; other documents point here. A change to
`BoundedParallelAeonCoordinator`, to ADR 0016's failure or abnormal-exit decisions, or to the schedule harness under
`src/test/java/monada/neuron/aeon/formal/` also runs `./gradlew formalPilotCheck` (about 7 s with a warm daemon). It checks
the direct path's failure semantics on every schedule up to four inputs against the sequential oracle. It is not part
of `./gradlew test` or CI, and it does not cover the contextual path, caller interruption, executor rejection, or cost
bounds: those stay with unit tests. When the coordinator's layout changes, rerun `scripts/formal-pilot-mutants.sh`. Regenerate a mutant patch
that no longer applies, and rewrite one that applies but is reported NOT KILLED by its target property: the new layout
may have made the break ineffective, as with `m2` in #208, where a second cancellation path still cancelled the
siblings. Model, bounds, and exclusions:
`docs/experiments/formal-pilot-aeon-failure-semantics-v1.md`; adoption decision:
`docs/experiments/pstack-adoption-evaluation-v1.md`.

## CPU SIMD with Vector API

The Vector API is an incubating JDK API in Java 27.

Use it for operations such as bulk normalization, dot products, element-wise transforms, signal energy updates, or other vectorizable kernels when benchmarks show value.

Requirements:

- scalar/reference implementation;
- preferred species chosen portably unless intentionally architecture-specific;
- correct loop-bound and tail handling;
- tests across zero/small/non-multiple lengths;
- floating-point tolerance documented;
- benchmark on relevant x64/AArch64 targets when portability matters.

## Foreign Function & Memory API

Use FFM for explicit memory/native requirements, including:

- native function calls;
- aligned off-heap buffers;
- memory-mapped regions;
- interoperability with C/C++/accelerator libraries;
- native shared-memory mechanisms exposed by the platform.

Rules:

- isolate platform/native code behind adapters;
- make `Arena` ownership explicit;
- validate size/offset arithmetic;
- document memory ordering and concurrency;
- do not retain a segment beyond its arena lifetime;
- keep raw addresses and `MemorySegment` details out of cognitive domain APIs where possible;
- document restricted native-access flags.

## Shared Memory

"Shared memory" can refer to different mechanisms and must be named precisely:

- shared mutable Java heap state between threads;
- mapped file regions shared through the OS;
- platform-native inter-process shared memory reached through FFM;
- GPU local/shared memory inside an accelerator kernel.

Do not treat these as interchangeable.

For CPU/process shared memory, define ownership, synchronization, visibility, alignment, lifetime, crash recovery, and platform portability.

For GPU shared memory, keep its use inside accelerator kernels/backends and do not expose the memory model to cognitive APIs.

## GPU and Other Accelerators

Java SE 27 has no standard production GPU-offload API.

Allowed approaches include controlled experiments with:

- OpenJDK Project Babylon / HAT;
- FFM bindings to OpenCL/CUDA/vendor libraries;
- another accelerator runtime when an explicit architecture decision accepts the dependency.

HAT is experimental and actively evolving. Treat it as an optional backend, not the default system architecture.

## Accelerator Backend Contract

An accelerator backend should expose capability-oriented behavior such as:

```text
isAvailable()
supports(operationShape)
execute(input)
```

rather than leaking CUDA/OpenCL/HAT classes through domain interfaces.

A selection layer may choose CPU scalar, CPU SIMD, or accelerator backend based on availability and workload size.

## Fallback

Optional acceleration must degrade predictably.

Prefer:

```text
accelerated backend available + worthwhile
  -> accelerated execution
otherwise
  -> portable CPU implementation
```

Do not silently fall back after a correctness/data-integrity failure. Fallback is for unsupported/unavailable capability, not for hiding defects.

## End-to-End Benchmarking

Measure at least:

- preprocessing/conversion;
- transfer;
- compilation/warm-up;
- synchronization;
- compute;
- result materialization;
- total latency/throughput;
- memory consumption where relevant.

Also vary batch/problem size to identify the crossover point where acceleration becomes beneficial.

A stated speedup needs a claim evidence report (`monada-neuron-evaluation-benchmarking`, section "Claim Evidence
Report", subsection "SIMD, native, and accelerated paths"). It records whether the accelerated path and the
reference use the same arithmetic, and checks the gain against the lane count or another arithmetic bound.

## Determinism and Precision

Parallel/SIMD/GPU floating-point reductions may reorder operations. Define allowed numerical tolerance explicitly.

If exact deterministic ordering is architecturally required, design reductions/ranking accordingly rather than assuming hardware execution order.

## Acceptance

Specialized execution is ready when the workload classification is correct, capability boundaries are isolated, reference/fallback behavior exists where practical, correctness is cross-checked, preview/native requirements are documented, and end-to-end measurements justify the added complexity.
