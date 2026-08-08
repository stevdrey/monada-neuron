# AGENTS.md

## Purpose

This file is the primary repository guide for AI coding agents working on **Monada Neuron**.

Monada Neuron is an experimental cognitive system built around signals, Aeons, nodes, resonance, adaptation, and explicit action. It is intentionally not an LLM wrapper. Long-term associative memory belongs to **Monada Resonance Store**.

The project should remain measurable, modular, resource-efficient, hardware-aware, and explicit about experimental behavior.

## Required Context

Before changing code, architecture, or public behavior, inspect the current `main` branch and read:

1. `README.md`
2. `docs/architecture.md`
3. `docs/design-principles.md`
4. relevant records in `docs/adr/`
5. related source code and tests
6. the applicable skill under `.agents/skills/`

For new implementation work, use `docs/specs/spec-context-template.md` as the expected task shape. For reviews, use `docs/specs/pr-review-checklist.md`.

## Repository Context Layout

- `AGENTS.md` is the root operating guide.
- `.agents/skills/<skill-name>/SKILL.md` contains the canonical project-specific implementation skills.
- `.windsurf/skills/<skill-name>/SKILL.md` mirrors the canonical skills for tools that scan the Windsurf-compatible path.
- `docs/architecture.md` describes the current and target architecture.
- `docs/design-principles.md` contains durable design constraints.
- `docs/adr/*.md` records architecture decisions and their consequences.
- `docs/specs/*.md` contains task and review templates.

When updating a project skill, edit `.agents/skills/` first and mirror the same content under `.windsurf/skills/` so agent behavior does not drift between tools.

If a rule conflicts with an accepted ADR, the ADR wins for the scope it governs. If implementation reality diverges from documentation, do not silently normalize the mismatch: either update the documentation or record a new decision.

## Current Architecture Boundary

The core ownership rule is:

```text
Monada Neuron
  -> cognition
  -> signal processing
  -> reasoning
  -> adaptation/evolution
  -> action coordination

Monada Resonance Store
  -> long-term memory
  -> persisted resonance representations
  -> recall/ranking
  -> storage compatibility
  -> memory diagnostics
```

Do not duplicate long-term memory, persisted recall, or ranking logic inside Monada Neuron.

## Cognitive Design Rules

- A **Monad** coordinates system-level cognition; it is not a synonym for an LLM client or service class.
- An **Aeon** owns a coherent cognitive capability and coordinates focused nodes.
- A **Node** should remain focused, composable, measurable, and independently testable.
- A **Signal** is the internal information carrier and should remain explicit rather than hidden in arbitrary maps or opaque framework objects.
- External models, frameworks, tools, and memory systems belong behind adapters.
- Core cognition must remain usable without requiring LangChain, LangGraph, Spring AI, a remote LLM, or a specific vendor API.

## Java 26 Rules

- Java 26 is the project toolchain and should be treated as the implementation baseline.
- Use current Java language and JDK capabilities when they make the design simpler, safer, or measurably more efficient.
- Preview or incubating APIs are allowed when the benefit is concrete, isolated, documented, and covered by a fallback or migration strategy.
- Treat `static` as a semantic design choice, not a convenience modifier. Behavioral helpers remain instance methods unless the operation is genuinely class-level.
- Use normal imports and simple type names. Fully qualified names in signatures or local code require a real naming collision or external-contract reason.
- Prefer records for immutable value data, sealed hierarchies where they make exhaustive domain modeling clearer, pattern matching where it reduces branching boilerplate, and scoped values for immutable contextual data when appropriate.
- Structured Concurrency may be used when its preview status is acceptable for the affected module and it materially improves lifecycle, cancellation, or failure handling.

## Data Structure and Algorithm Rules

Choosing a data structure is part of the algorithm design, not an afterthought.

For every non-trivial collection, index, graph representation, cache, queue, history, or numeric buffer, consider:

1. access pattern;
2. asymptotic complexity;
3. expected cardinality;
4. mutation frequency;
5. iteration order and determinism;
6. memory footprint and object overhead;
7. cache locality;
8. allocation and GC pressure;
9. concurrency requirements;
10. whether primitive/contiguous representation is more appropriate than an object graph.

Do not default automatically to `ArrayList`, `HashMap`, `HashSet`, or linked structures without checking whether they fit the workload.

Prefer compact, contiguous, primitive-oriented layouts for hot numeric paths when they improve locality and reduce allocation. For large stable sparse graphs, evaluate compact adjacency layouts rather than millions of per-node collection objects. For bounded top-K work, evaluate bounded heaps/selection algorithms instead of sorting an entire candidate set. For membership-heavy workloads, evaluate sets, bitsets, indexed flags, or compact ids according to density and scale.

The current Phase-1 `Node` model is a correctness baseline. Do not rewrite it solely for theoretical efficiency; optimize when the target workload or benchmark demonstrates the need.

## Performance and Resource Efficiency

Resource efficiency is a first-class architectural requirement.

- Measure before and after meaningful optimization.
- Optimize the algorithm and data layout before adding complexity.
- Minimize unnecessary allocations in hot paths.
- Avoid boxing in high-volume numeric processing where practical.
- Consider data locality, branch behavior, memory bandwidth, and transfer costs, not only Big-O complexity.
- Preserve a clear correctness baseline when introducing optimized implementations.
- Do not trade deterministic behavior or debuggability for speculative micro-optimizations.

## SIMD, Native Memory, and Heterogeneous Compute

Java 26 capabilities and experimental OpenJDK work may be used when appropriate:

- Use the Vector API (`jdk.incubator.vector`) for CPU SIMD when profiling shows a vectorizable numeric hotspot and the resulting implementation is clearer or measurably faster.
- Use the Foreign Function & Memory API (`java.lang.foreign`) for controlled off-heap memory, memory-mapped regions, native interop, or layouts that benefit from explicit lifetime/alignment management.
- For native/shared-memory integration, isolate platform-specific behavior behind a narrow adapter and keep ownership/lifetime rules explicit.
- GPU or accelerator execution must be optional and capability-driven. Java SE 26 does not provide a standard production GPU-offload API.
- OpenJDK Project Babylon/HAT may be evaluated for experimental GPU acceleration, including GPU shared-memory techniques, only behind an experimental boundary with a CPU reference implementation.
- Native accelerator libraries may also be reached through FFM when justified, but vendor lock-in and deployment requirements must stay outside the cognitive core.
- Always benchmark end-to-end cost, including data conversion, host/device transfer, compilation/warm-up, synchronization, and fallback behavior. Kernel-only speedups are insufficient evidence.

## Concurrency Rules

- Use concurrency because the workload has independent work or latency to hide, not because parallelism is available.
- Virtual threads are preferred for high-concurrency blocking I/O and orchestration, not as a substitute for CPU parallelism.
- CPU-bound parallelism must respect available cores, work granularity, memory bandwidth, and contention.
- Avoid shared mutable state when immutable snapshots, partitioning, message passing, or ownership boundaries are simpler.
- Make cancellation and failure propagation explicit for coordinated tasks.

## Dependencies

- Keep dependencies minimal.
- Prefer JDK capabilities before introducing third-party libraries.
- A performance-oriented dependency must justify measurable benefit, maintenance cost, portability, and compatibility impact.
- Do not introduce a framework that becomes the architecture by convenience.

## Testing and Benchmarking

Behavior changes require tests. Performance-sensitive changes require reproducible measurements when practical.

Minimum verification:

```bash
./gradlew test
```

For performance work, record the workload, data size, JVM/JDK, relevant flags, warm-up strategy, measurement method, and before/after results. Use JMH or another controlled harness for microbenchmarks when wall-clock application timing is too noisy.

Optimized CPU/GPU/native paths must be checked against a reference implementation for numerical and semantic equivalence within an explicitly documented tolerance.

## Documentation and ADR Rules

Create or update an ADR when a change establishes a durable decision involving:

- cognitive boundaries;
- module ownership;
- signal representation;
- memory ownership;
- concurrency model;
- persistence or interoperability contract;
- hardware acceleration strategy;
- preview/incubator API dependency;
- a new performance-critical data layout.

An ADR should state context, decision, alternatives, consequences, and follow-up work.

## Issue Workflow

Implementation tasks should state:

- background;
- current state;
- goal;
- non-goals;
- affected areas;
- architectural boundaries;
- performance/resource expectations;
- acceptance criteria;
- verification commands;
- documentation or ADR updates.

## PR Review Workflow

PR review should verify goal alignment, scope control, cognitive boundaries, data-structure choice, algorithmic complexity, Java 26 usage, allocation/resource behavior, concurrency safety, deterministic behavior, test quality, benchmark evidence where relevant, documentation, and merge safety.
