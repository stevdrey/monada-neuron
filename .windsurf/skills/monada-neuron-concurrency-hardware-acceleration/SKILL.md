---
name: monada-neuron-concurrency-hardware-acceleration
description: Use when adding concurrency, SIMD, Foreign Function & Memory API usage, shared-memory integration, GPU execution, or other hardware acceleration.
---

# Monada Neuron Concurrency and Hardware Acceleration

## Context

Read `AGENTS.md`, `docs/architecture.md`, ADR 0003, the Java 26 skill, the data-structures skill, and the performance skill before introducing specialized execution.

The portable Java CPU implementation is the reference path unless an accepted ADR says otherwise.

## Workload Classification

Classify the operation before selecting a mechanism.

### Blocking / I/O-bound

Prefer virtual threads when many independent blocking operations are expected and the underlying API behaves well with them.

### Coordinated concurrent tasks

Consider Structured Concurrency when JDK 26 preview usage is acceptable and task lifetime, cancellation, failure propagation, or observability benefits are material.

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

JDK 26 Structured Concurrency is preview.

If used:

- keep preview enablement scoped;
- structure child-task lifetime lexically;
- define join/failure policy explicitly;
- test cancellation and partial failure;
- document the preview dependency and expected migration path.

## CPU SIMD with Vector API

The Vector API is an incubating JDK API in Java 26.

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

Java SE 26 has no standard production GPU-offload API.

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

## Determinism and Precision

Parallel/SIMD/GPU floating-point reductions may reorder operations. Define allowed numerical tolerance explicitly.

If exact deterministic ordering is architecturally required, design reductions/ranking accordingly rather than assuming hardware execution order.

## Acceptance

Specialized execution is ready when the workload classification is correct, capability boundaries are isolated, reference/fallback behavior exists where practical, correctness is cross-checked, preview/native requirements are documented, and end-to-end measurements justify the added complexity.
