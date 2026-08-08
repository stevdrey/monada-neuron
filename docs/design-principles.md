# Design Principles

## Purpose

These principles guide implementation and review decisions across Monada Neuron. They complement ADRs: principles are broad defaults, while ADRs capture specific durable decisions.

## 1. Cognition Is the Core Product

Monada Neuron owns cognitive processing: interpreting signals, coordinating specialized capabilities, reasoning, evaluating outcomes, adapting, and acting.

Do not let a storage engine, orchestration framework, LLM SDK, or accelerator API become the system's identity.

## 2. Memory Is External and Explicit

Long-term associative memory belongs to Monada Resonance Store. Memory access should cross an explicit adapter/protocol boundary.

Temporary working state inside an active cognitive cycle is allowed, but it must not silently evolve into duplicated persistent memory.

## 3. Measure Before Optimizing, but Design for Efficiency

The project is performance-conscious from the beginning without requiring premature micro-optimization.

Design APIs and representations so that future efficient implementations are possible, then use measurements to decide when complexity is justified.

## 4. Data Structures Are Architectural Choices

For non-trivial workloads, choose structures from the access pattern rather than habit.

Consider:

- asymptotic cost;
- cardinality and growth;
- update-to-read ratio;
- memory density;
- CPU cache locality;
- boxing and object headers;
- iteration determinism;
- concurrency;
- compatibility with SIMD or accelerator layouts.

A theoretically faster Big-O structure can still lose because of locality, allocation, indirection, or small problem sizes. Benchmark representative workloads.

## 5. Prefer Compact Numeric Paths

High-volume signal processing should avoid unnecessary object graphs and boxing.

Primitive arrays, memory segments, compact ids, structure-of-arrays layouts, bitsets, and other contiguous representations should be considered when they fit the operation.

Do not force data-oriented layouts onto low-volume domain orchestration where clarity is more valuable.

## 6. Preserve a Reference Implementation

Optimized implementations should be checked against a simple, deterministic reference path.

This is especially important for SIMD, parallel, native, and GPU implementations where implementation complexity can hide semantic drift.

## 7. Hardware Acceleration Is Capability-Driven

CPU SIMD, native code, integrated GPUs, dedicated GPUs, and other accelerators are implementation capabilities.

The cognitive model must not require a specific device vendor.

Select an accelerated backend only when:

- the operation is sufficiently parallel or vectorizable;
- the data volume amortizes setup and transfer costs;
- the hardware is available;
- measured end-to-end behavior improves the target metric.

Otherwise use the portable CPU path.

## 8. Java 26 Is a Performance Platform, Not Only a Language Version

Use Java 26 and modern JDK facilities deliberately.

Potential tools include:

- records and sealed types for precise domain models;
- pattern matching for clearer dispatch;
- virtual threads for blocking orchestration;
- scoped values for immutable contextual propagation;
- Structured Concurrency when preview usage is justified;
- Foreign Function & Memory API for explicit native/off-heap layouts and interop;
- Vector API for CPU SIMD;
- JFR/JMC and profilers for evidence-based optimization.

Preview/incubator APIs are acceptable in experimental or isolated production paths when status, flags, fallback, and migration risk are explicit.

## 9. Allocation Is Not Free

Avoid accidental allocation in hot loops, including:

- boxed primitive streams;
- temporary collections;
- repeated defensive copies inside internal hot paths;
- ephemeral wrapper objects;
- unnecessary string creation;
- per-element lambdas when profiling shows material overhead.

Do not sacrifice correctness or API safety without evidence. Internal trusted paths may use more specialized representations than public boundaries.

## 10. Concurrency Must Match the Workload

Use virtual threads for blocking operations, not to multiply CPU-bound work beyond available compute capacity.

For CPU-bound algorithms, control parallelism and consider work size, cache behavior, memory bandwidth, false sharing, and synchronization cost.

Prefer ownership/partitioning and immutable state over fine-grained shared mutation when possible.

## 11. Determinism Matters

Deterministic behavior improves testing, evaluation, reproducibility, and diagnosis.

When multiple results are otherwise equivalent, define stable tie-breaking rules. Do not depend accidentally on hash iteration order.

## 12. External Integrations Stay Behind Adapters

LLM providers, agent frameworks, databases, native libraries, and hardware APIs must remain replaceable.

Provider-specific types should not leak into core cognitive contracts.

## 13. Experimental Does Not Mean Unstructured

Experiments may use preview JDK features, incubating APIs, native code, or emerging OpenJDK projects. They still require:

- explicit hypothesis;
- reference behavior;
- reproducible setup;
- measurable result;
- known failure/fallback path;
- documentation of portability and maintenance risks.

## 14. Architecture Documentation Evolves with the Code

When implementation introduces a durable architectural choice, update the relevant documentation and ADR in the same change.

Silent divergence between code and architecture documentation is a defect in the project context supplied to future agents.
